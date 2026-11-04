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

static HookFunType hook_func = NULL;
static bool s_native_hooked = false;

static bool is_target_library(const char *str) {
    if (!str) return false;
    return (strstr(str, "liborbit") != NULL || strstr(str, "split_config.arm64_v8a.apk") != NULL);
}

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

    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = addr & ~(page_size - 1);

    if (mprotect((void *)page_start, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }

    *ptr = patched_instr;
    __builtin___clear_cache((char *)addr, (char *)(addr + sizeof(uint32_t)));
    mprotect((void *)page_start, page_size, PROT_READ | PROT_EXEC);
    return true;
}

#if defined(__aarch64__)
static bool scan_and_patch(uintptr_t start, uintptr_t end) {
    if (end <= start + 16) return false;

    for (uintptr_t p = start; p <= end - 16; p += 4) {
        uint32_t w0 = *(const uint32_t *)p;
        if ((w0 & 0xFFFFFFE0) != 0x528000C0) {
            continue;
        }

        uint32_t w1 = *(const uint32_t *)(p + 4);
        if ((w1 & 0xFC000000) != 0x94000000) {
            continue;
        }

        uint32_t w2 = *(const uint32_t *)(p + 8);
        if ((w2 & 0xFFC00000) != 0xF9400000) {
            continue;
        }

        uint32_t w3 = *(const uint32_t *)(p + 12);
        if ((w3 & 0xFFC00000) != 0xB9000000) {
            continue;
        }

        uint32_t rn = (w3 >> 5) & 0x1F;
        if (rn != 31 && rn != 29) {
            continue;
        }

        uint32_t rt = w3 & 0x1F;
        if (rt != 0 && rt != 31) {
            continue;
        }

        if (patch_single_instruction(p + 12)) {
            return true;
        }
    }
    return false;
}

static bool scan_elf_segments(uintptr_t base) {
    if (!base) return false;
    const Elf64_Ehdr *ehdr = (const Elf64_Ehdr *)base;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) {
        return false;
    }

    const Elf64_Phdr *phdr = (const Elf64_Phdr *)(base + ehdr->e_phoff);
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_LOAD && (phdr[i].p_flags & PF_X) != 0) {
            uintptr_t seg_start = base + phdr[i].p_vaddr;
            uintptr_t seg_end = seg_start + phdr[i].p_memsz;
            if (scan_and_patch(seg_start, seg_end)) {
                return true;
            }
        }
    }
    return false;
}
#else
static bool scan_and_patch(uintptr_t start, uintptr_t end) {
    (void)start;
    (void)end;
    return false;
}

static bool scan_elf_segments(uintptr_t base) {
    (void)base;
    return false;
}
#endif

static bool scan_process_maps() {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;

    char line[512];
    bool success = false;

    while (fgets(line, sizeof(line), fp)) {
        if (is_target_library(line) && strstr(line, "r-x")) {
            uintptr_t start = 0, end = 0;
            if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) == 2) {
                if (scan_and_patch(start, end)) {
                    success = true;
                    break;
                }
            }
        }
    }
    fclose(fp);
    return success;
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
        LOGI("Successfully applied semantic native patch to liborbit-jni-spotify.so via maps scan");
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied semantic native patch on JNI_OnLoad scan");
    }
    return JNI_VERSION_1_6;
}

extern "C" __attribute__((visibility("default"))) __attribute__((used)) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (!entries) return NULL;
    hook_func = entries->hook_func;
    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied semantic native patch on native_init scan");
    }
    return on_library_loaded;
}
