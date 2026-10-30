#include <jni.h>
#include <string.h>
#include <stdbool.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <inttypes.h>
#include <stdint.h>
#include "hook.h"

#define LOG_TAG "SpotXeroNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static HookFunType hook_func = NULL;
static bool s_native_hooked = false;

static bool ends_with(const char *str, const char *suffix) {
    if (!str || !suffix) return false;
    size_t len_str = strlen(str);
    size_t len_suf = strlen(suffix);
    if (len_suf > len_str) return false;
    return strncmp(str + len_str - len_suf, suffix, len_suf) == 0;
}

static bool patch_instruction(uintptr_t addr, uint32_t expected, uint32_t patch_value) {
    uint32_t *ptr = (uint32_t *)addr;
    if (*ptr != expected) {
        if (*ptr == patch_value) {
            return true;
        }
        return false;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = addr & ~(page_size - 1);
    if (mprotect((void *)page_start, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }
    *ptr = patch_value;
    __builtin___clear_cache((char *)addr, (char *)(addr + sizeof(uint32_t)));
    mprotect((void *)page_start, page_size, PROT_READ | PROT_EXEC);
    return true;
}

extern "C" void on_library_loaded(const char *name, void *handle) {
    if (!name || s_native_hooked) return;
    if (!ends_with(name, "liborbit-jni-spotify.so")) return;

    void *onload = dlsym(handle, "JNI_OnLoad");
    if (!onload) return;

    Dl_info info;
    if (dladdr(onload, &info) == 0 || !info.dli_fbase) return;

    uintptr_t base = (uintptr_t)info.dli_fbase;
    uintptr_t target_offset = 0x12895d4;
    uintptr_t target_addr = base + target_offset;

    uint32_t original_instr = 0xB9011FE0;
    uint32_t patch_instr = 0xB9011FFF;

    if (patch_instruction(target_addr, original_instr, patch_instr)) {
        s_native_hooked = true;
        LOGI("Successfully applied native patch to liborbit-jni-spotify.so");
    } else {
        LOGE("Failed to apply native patch at offset 0x%" PRIxPTR, target_offset);
    }
}

extern "C" __attribute__((visibility("default"))) __attribute__((used)) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (!entries) return NULL;
    hook_func = entries->hook_func;
    return on_library_loaded;
}
