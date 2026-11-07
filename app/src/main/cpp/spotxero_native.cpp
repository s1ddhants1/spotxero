#include <jni.h>
#include <string.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <elf.h>
#include "hook.h"

#define LOG_TAG "SpotXeroNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__LP64__)
typedef Elf64_Ehdr Elf_Ehdr;
typedef Elf64_Phdr Elf_Phdr;
#else
typedef Elf32_Ehdr Elf_Ehdr;
typedef Elf32_Phdr Elf_Phdr;
#endif

static bool s_native_hooked = false;

static bool is_target_library(const char *str) {
    if (!str) return false;
    return (strstr(str, "liborbit") != NULL ||
            strstr(str, "split_config.arm64_v8a.apk") != NULL ||
            strstr(str, "split_config.x86_64.apk") != NULL ||
            strstr(str, "split_config.armeabi_v7a.apk") != NULL ||
            strstr(str, "split_config.x86.apk") != NULL);
}

static bool patch_memory(uintptr_t addr, const void *bytes, size_t len) {
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = addr & ~(page_size - 1);
    uintptr_t page_end = (addr + len + page_size - 1) & ~(page_size - 1);
    size_t protect_len = page_end - page_start;

    if (mprotect((void *)page_start, protect_len, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }
    memcpy((void *)addr, bytes, len);
    __builtin___clear_cache((char *)addr, (char *)(addr + len));
    mprotect((void *)page_start, protect_len, PROT_READ | PROT_EXEC);
    return true;
}

static uintptr_t find_string_in_elf(uintptr_t base, const Elf_Phdr *phdr, int phnum, const char *str, size_t len) {
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_R) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            size_t seg_len = phdr[i].p_memsz;
            const void *found = memmem((const void *)seg_start, seg_len, str, len);
            if (found) {
                return (uintptr_t)found;
            }
        }
    }
    return 0;
}

#if defined(__aarch64__)
static bool patch_single_instruction(uintptr_t addr) {
    uint32_t *ptr = (uint32_t *)addr;
    uint32_t instr = *ptr;

    if ((instr & 0x1F) == 0x1F) {
        return true;
    }

    if ((instr & 0xFFC00000) != 0xB9000000) {
        return false;
    }

    uint32_t rn = (instr >> 5) & 0x1F;
    if (rn != 31 && rn != 29) {
        return false;
    }

    uint32_t rt = instr & 0x1F;
    if (rt != 0) {
        return false;
    }

    uint32_t patched_instr = (instr & ~0x1F) | 0x1F;
    return patch_memory(addr, &patched_instr, sizeof(uint32_t));
}

static bool resolve_and_patch_semantic(uintptr_t base, const Elf_Phdr *phdr, int phnum) {
    const char target_str[] = "ad_clips_per_free_tier_ad_break";
    uintptr_t str_addr = find_string_in_elf(base, phdr, phnum, target_str, sizeof(target_str));
    if (!str_addr) {
        return false;
    }

    uintptr_t str_ref_pc = 0;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (seg_end <= seg_start + 8) continue;

            for (uintptr_t p = seg_start; p <= seg_end - 8; p += 4) {
                uint32_t w1 = *(const uint32_t *)p;
                if ((w1 & 0x9F000000) != 0x90000000) continue;

                uint32_t w2 = *(const uint32_t *)(p + 4);
                if ((w2 & 0xFFC00000) != 0x91000000) continue;

                uint32_t adrp_rd = w1 & 0x1F;
                uint32_t add_rn = (w2 >> 5) & 0x1F;
                if (adrp_rd != add_rn) continue;

                uint32_t immlo = (w1 >> 29) & 0x3;
                uint32_t immhi = (w1 >> 5) & 0x7FFFF;
                int64_t imm = (int64_t)((immhi << 2) | immlo);
                if (imm & (1 << 20)) imm -= (1 << 21);

                uintptr_t target_page = (p & ~0xFFFULL) + (imm << 12);
                uint32_t imm12 = (w2 >> 10) & 0xFFF;
                if (target_page + imm12 == str_addr) {
                    str_ref_pc = p;
                    break;
                }
            }
            if (str_ref_pc) break;
        }
    }

    if (!str_ref_pc) {
        return false;
    }

    uintptr_t thunk_entry = str_ref_pc;
    for (uintptr_t back = str_ref_pc - 4; back >= str_ref_pc - 64; back -= 4) {
        uint32_t prev = *(const uint32_t *)back;
        if (prev == 0xD65F03C0 || (prev & 0xFC000000) == 0x14000000) {
            thunk_entry = back + 4;
            break;
        }
    }

    bool patched = false;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (seg_end <= seg_start + 4) continue;

            for (uintptr_t p = seg_start; p <= seg_end - 4; p += 4) {
                uint32_t instr = *(const uint32_t *)p;
                if ((instr & 0xFC000000) == 0x94000000) {
                    int32_t imm26 = (int32_t)(instr & 0x03FFFFFF);
                    if (imm26 & (1 << 25)) imm26 -= (1 << 26);
                    uintptr_t dest = p + (imm26 << 2);
                    if (dest == thunk_entry) {
                        uintptr_t window_end = (p + 48 < seg_end) ? p + 48 : seg_end;
                        for (uintptr_t fwd = p + 4; fwd <= window_end - 4; fwd += 4) {
                            uint32_t fwd_instr = *(const uint32_t *)fwd;
                            if ((fwd_instr & 0xFFC00000) == 0xB9000000) {
                                uint32_t rn = (fwd_instr >> 5) & 0x1F;
                                uint32_t rt = fwd_instr & 0x1F;
                                if ((rn == 31 || rn == 29) && (rt == 0 || rt == 31)) {
                                    if (patch_single_instruction(fwd)) {
                                        patched = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (!patched) {
        uintptr_t window_end = (str_ref_pc + 64);
        for (uintptr_t fwd = str_ref_pc + 8; fwd <= window_end - 4; fwd += 4) {
            uint32_t fwd_instr = *(const uint32_t *)fwd;
            if ((fwd_instr & 0xFFC00000) == 0xB9000000) {
                uint32_t rn = (fwd_instr >> 5) & 0x1F;
                uint32_t rt = fwd_instr & 0x1F;
                if ((rn == 31 || rn == 29) && (rt == 0 || rt == 31)) {
                    if (patch_single_instruction(fwd)) {
                        patched = true;
                    }
                }
            }
        }
    }

    return patched;
}
#elif defined(__x86_64__)
static bool resolve_and_patch_semantic(uintptr_t base, const Elf_Phdr *phdr, int phnum) {
    const char target_str[] = "ad_clips_per_free_tier_ad_break";
    uintptr_t str_addr = find_string_in_elf(base, phdr, phnum, target_str, sizeof(target_str));
    if (!str_addr) {
        return false;
    }

    bool patched = false;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (seg_end <= seg_start + 7) continue;

            for (uintptr_t p = seg_start; p <= seg_end - 7; p++) {
                const uint8_t *b = (const uint8_t *)p;
                if (b[0] == 0x48 && b[1] == 0x8D) {
                    int32_t disp = *(const int32_t *)(p + 3);
                    uintptr_t target = p + 7 + disp;
                    if (target == str_addr) {
                        uintptr_t window_end = (p + 40 < seg_end) ? p + 40 : seg_end;
                        for (uintptr_t q = p + 7; q <= window_end - 5; q++) {
                            const uint8_t *qb = (const uint8_t *)q;
                            if (qb[0] == 0xE8) {
                                uintptr_t after_call = q + 5;
                                if (after_call + 3 <= seg_end) {
                                    const uint8_t *ab = (const uint8_t *)after_call;
                                    if (ab[0] == 0x89 && ab[2] == 0x24 && (ab[1] == 0x84 || ab[1] == 0x44)) {
                                        const uint8_t x86_patch[] = {0x31, 0xC0, 0x90, 0x90, 0x90};
                                        if (patch_memory(q, x86_patch, sizeof(x86_patch))) {
                                            patched = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return patched;
}
#elif defined(__arm__)
static bool resolve_and_patch_semantic(uintptr_t base, const Elf_Phdr *phdr, int phnum) {
    const char target_str[] = "ad_clips_per_free_tier_ad_break";
    uintptr_t str_addr = find_string_in_elf(base, phdr, phnum, target_str, sizeof(target_str));
    if (!str_addr) {
        return false;
    }

    bool patched = false;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (seg_end <= seg_start + 4) continue;

            for (uintptr_t p = seg_start; p <= seg_end - 4; p += 2) {
                uint16_t instr = *(const uint16_t *)p;
                if ((instr & 0xFF78) == 0x4478) {
                    uint32_t rx = instr & 0x7;
                    uintptr_t back_limit = (p >= seg_start + 24) ? p - 24 : seg_start;
                    for (uintptr_t back = p - 2; back >= back_limit; back -= 2) {
                        uint16_t ldr = *(const uint16_t *)back;
                        if ((ldr & 0xF800) == 0x4800 && ((ldr >> 8) & 0x7) == rx) {
                            uint32_t imm8 = ldr & 0xFF;
                            uintptr_t lit_addr = ((back + 4) & ~((uintptr_t)3)) + (imm8 << 2);
                            if (lit_addr + 4 <= seg_end) {
                                int32_t rel = *(const int32_t *)lit_addr;
                                if ((p + 4) + rel == str_addr) {
                                    uintptr_t fwd_limit = (p + 36 < seg_end) ? p + 36 : seg_end;
                                    for (uintptr_t fwd = p + 2; fwd <= fwd_limit - 2; fwd += 2) {
                                        uint16_t fwd_instr = *(const uint16_t *)fwd;
                                        bool is_call = ((fwd_instr & 0xFF87) == 0x4780);
                                        size_t call_len = 2;
                                        if (!is_call && (fwd_instr & 0xF800) == 0xF000 && fwd + 4 <= fwd_limit) {
                                            uint16_t fwd2 = *(const uint16_t *)(fwd + 2);
                                            if ((fwd2 & 0xD000) == 0xD000) {
                                                is_call = true;
                                                call_len = 4;
                                            }
                                        }
                                        if (is_call) {
                                            uintptr_t st_limit = (fwd + call_len + 16 < seg_end) ? fwd + call_len + 16 : seg_end;
                                            for (uintptr_t st = fwd + call_len; st <= st_limit - 2; st += 2) {
                                                uint16_t st_instr = *(const uint16_t *)st;
                                                if ((st_instr & 0xF800) == 0x9000) {
                                                    if (call_len == 2) {
                                                        const uint16_t thumb_patch = 0x2000;
                                                        if (patch_memory(fwd, &thumb_patch, sizeof(thumb_patch))) {
                                                            patched = true;
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return patched;
}
#elif defined(__i386__)
static bool resolve_and_patch_semantic(uintptr_t base, const Elf_Phdr *phdr, int phnum) {
    const char target_str[] = "ad_clips_per_free_tier_ad_break";
    uintptr_t str_addr = find_string_in_elf(base, phdr, phnum, target_str, sizeof(target_str));
    if (!str_addr) {
        return false;
    }

    bool patched = false;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (seg_end <= seg_start + 6) continue;

            for (uintptr_t p = seg_start; p <= seg_end - 6; p++) {
                const uint8_t *b = (const uint8_t *)p;
                if (b[0] == 0x8D && (b[1] & 0xC0) == 0x80) {
                    int32_t disp = *(const int32_t *)(p + 2);
                    uintptr_t window_end = (p + 36 < seg_end) ? p + 36 : seg_end;
                    for (uintptr_t q = p + 6; q <= window_end - 6; q++) {
                        const uint8_t *qb = (const uint8_t *)q;
                        if (qb[0] == 0x8D && (qb[1] & 0xC0) == 0x80) {
                            int32_t disp2 = *(const int32_t *)(q + 2);
                            if (disp2 - disp == 17) {
                                uintptr_t call_limit = (q + 30 < seg_end) ? q + 30 : seg_end;
                                for (uintptr_t c = q + 6; c <= call_limit - 5; c++) {
                                    if (*(const uint8_t *)c == 0xE8) {
                                        uintptr_t after = c + 5;
                                        if (after + 3 <= seg_end) {
                                            const uint8_t *ab = (const uint8_t *)after;
                                            if (ab[0] == 0x86 && ab[1] == 0x47) {
                                                const uint8_t x86_patch[] = {0x31, 0xC0, 0x90, 0x90, 0x90};
                                                if (patch_memory(c, x86_patch, sizeof(x86_patch))) {
                                                    patched = true;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return patched;
}
#else
static bool resolve_and_patch_semantic(uintptr_t base, const Elf_Phdr *phdr, int phnum) {
    (void)base;
    (void)phdr;
    (void)phnum;
    return false;
}
#endif

static bool scan_elf_segments(uintptr_t base) {
    if (!base) return false;
    const Elf_Ehdr *ehdr = (const Elf_Ehdr *)base;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
        return false;
    }

    const Elf_Phdr *phdr = (const Elf_Phdr *)(base + ehdr->e_phoff);
    return resolve_and_patch_semantic(base, phdr, ehdr->e_phnum);
}

static bool scan_process_maps() {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;

    char line[512];
    bool success = false;

    while (fgets(line, sizeof(line), fp)) {
        if (is_target_library(line)) {
            uintptr_t start = 0, end = 0;
            if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) >= 2) {
                if (start != 0 && memcmp((const void *)start, ELFMAG, SELFMAG) == 0) {
                    if (scan_elf_segments(start)) {
                        success = true;
                        break;
                    }
                }
            }
        }
    }
    fclose(fp);
    return success;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_spotxero_SpotXeroModule_triggerNativePatch(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (s_native_hooked) return JNI_TRUE;
    if (scan_process_maps()) {
        s_native_hooked = true;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" void on_library_loaded(const char *name, void *handle) {
    if (s_native_hooked) return;
    if (!name || !is_target_library(name)) return;

    if (handle) {
        void *onload = dlsym(handle, "JNI_OnLoad");
        if (onload) {
            Dl_info info;
            if (dladdr(onload, &info) != 0 && info.dli_fbase && info.dli_fname) {
                if (is_target_library(info.dli_fname)) {
                    uintptr_t base = (uintptr_t)info.dli_fbase;
                    if (scan_elf_segments(base)) {
                        s_native_hooked = true;
                        LOGI("Successfully applied semantic native patch to liborbit-jni-spotify.so via ELF scan");
                        return;
                    }
                }
            }
        }
    }

    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied native patch to liborbit-jni-spotify.so via maps scan");
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied native patch on JNI_OnLoad scan");
    }
    return JNI_VERSION_1_6;
}

extern "C" __attribute__((visibility("default"))) __attribute__((used)) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    (void)entries;
    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied native patch on native_init scan");
    }
    return on_library_loaded;
}
