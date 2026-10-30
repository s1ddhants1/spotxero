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
#include "hook.h"

#define LOG_TAG "SpotXeroNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static HookFunType hook_func = NULL;
static bool s_native_hooked = false;

static bool is_target_library(const char *str) {
    if (!str) return false;
    return (strstr(str, "liborbit") != NULL || strstr(str, "split_config") != NULL);
}

static bool patch_single_instruction(uintptr_t addr) {
    uint32_t *ptr = (uint32_t *)addr;
    uint32_t instr = *ptr;

    if ((instr & 0x1F) == 0x1F) {
        return true;
    }

    if ((instr & 0xFFC003FF) != 0xB90003E0) {
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

static bool scan_and_patch(uintptr_t start, uintptr_t end) {
    for (uintptr_t p = start; p < end - 16; p += 4) {
        uint32_t w0 = *(uint32_t *)p;
        uint32_t w1 = *(uint32_t *)(p + 4);
        uint32_t w2 = *(uint32_t *)(p + 8);

        if (w0 == 0x528000C5 && w1 == 0xF94003A8) {
            if ((w2 & 0xFFC003FF) == 0xB90003E0 || (w2 & 0xFFC003FF) == 0xB90003DF) {
                return patch_single_instruction(p + 8);
            }
        }
    }
    return false;
}

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
    if (name && !is_target_library(name)) return;

    if (handle) {
        void *onload = dlsym(handle, "JNI_OnLoad");
        if (onload) {
            Dl_info info;
            if (dladdr(onload, &info) != 0 && info.dli_fbase) {
                uintptr_t base = (uintptr_t)info.dli_fbase;
                uintptr_t fast_path_addr = base + 0x12895d4;

                if (patch_single_instruction(fast_path_addr)) {
                    s_native_hooked = true;
                    LOGI("Successfully applied fast-path native patch to liborbit-jni-spotify.so");
                    return;
                }
            }
        }
    }

    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied pattern-scanned native patch to liborbit-jni-spotify.so");
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
    if (!entries) return NULL;
    hook_func = entries->hook_func;
    if (scan_process_maps()) {
        s_native_hooked = true;
        LOGI("Successfully applied native patch on native_init scan");
    }
    return on_library_loaded;
}
