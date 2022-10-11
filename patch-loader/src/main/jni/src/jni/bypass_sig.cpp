//
// Created by VIP on 2021/4/25.
//

#include "bypass_sig.h"
#include "elf_util.h"
#include "logging.h"
#include "native_util.h"
#include "patch_loader.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"
#include "fcntl.h"

namespace lspd {
/*
 * Android currently has host prebuilts of glibc 2.15 and 2.17, but
 * memfd_create was only added in glibc 2.27. It was defined in Linux 3.17,
 * so we consider it safe to use the low-level arbitrary syscall wrapper.
 */
#ifndef __NR_memfd_create
#if __aarch64__
#	define __NR_memfd_create 279
#elif __arm__
#	define __NR_memfd_create 279
#elif __powerpc64__
#	define __NR_memfd_create 360
#elif __i386__
#	define __NR_memfd_create 356
#elif __x86_64__
#	define __NR_memfd_create 319
#endif
#endif

    std::string origPath;
    std::string bakPath;
    jlong bak_offset = -1;
    jlong bak_size = -1;

    CREATE_HOOK_STUB_ENTRY(
            "fstatat",
            int, fstatat,
            (int __dir_fd, const char *__path, struct stat *__buf, int __flags), {
                if (__path == origPath) {
                    int x = backup(__dir_fd, bakPath.c_str(), __buf, __flags);
                    if (x == 0 && __buf && __buf->st_size == 0) {
                        LOGI("fstatat: {} size to {}", origPath.c_str(), bak_size);
                        __buf->st_size = bak_size;
                    }
                    return x;
                }

                return backup(__dir_fd, __path, __buf, __flags);
            });

    CREATE_HOOK_STUB_ENTRY(
            "__openat",
            int, __openat,
            (int fd, const char *pathname, int flag, int mode), {
                if (pathname == origPath) {
                    if (bak_offset >= 0 && bak_size >= 0) {
                        LOGI("openat: {} to [@{}+{} {}]", origPath.c_str(), bak_offset, bak_size, bakPath.c_str());
                    } else {
                        LOGI("openat: {} to {}", origPath.c_str(), bakPath.c_str());
                    }
                    int bak_fd = -1;
                    int apk_fd = -1;
                    void *bak_addr = MAP_FAILED;
                    void *apk_addr = MAP_FAILED;
                    do {
                        if (bak_size < 0 || bak_offset < 0) {
                            break;
                        }
                        // FIXME is there any way to load zip entry directly ?
                        // FIXME return backup("/a.zip!entry.apk", flag, mode); ?
                        bak_fd = backup(fd, bakPath.c_str(), O_RDONLY, mode);
                        if (bak_fd <= 0) {
                            LOGE("fail to open({}, O_RDONLY, 0)", bakPath.c_str());
                            break;
                        }
                        bak_addr = mmap(nullptr, bak_size, PROT_READ, MAP_SHARED,
                                        bak_fd,
                                        bak_offset);

                        if (bak_addr == MAP_FAILED) {
                            break;
                        }

                        apk_fd = syscall(__NR_memfd_create, origPath.c_str(),
                                         MFD_ALLOW_SEALING);
                        LOGI("memfd_create({}) -> {}", origPath.c_str(), apk_fd);

                        if (apk_fd <= 0) {
                            break;
                        }
                        if (0 != ftruncate(apk_fd, bak_size)) {
                            LOGI("fail ftruncate({}, {}):: {}", apk_fd, bak_size, strerror(errno));
                        }

                        apk_addr = mmap(nullptr, bak_size, PROT_READ | PROT_WRITE,
                                        MAP_SHARED,
                                        apk_fd, 0);
                        if (apk_addr == MAP_FAILED) {
                            break;
                        }
                        memcpy(apk_addr, bak_addr, bak_size);
                        msync(apk_addr, bak_size, MS_SYNC);
                        munmap(apk_addr, bak_size);

                        munmap(bak_addr, bak_size);
                        close(bak_fd);
                        return apk_fd;
                    } while (0);

                    if (apk_addr != MAP_FAILED) {
                        munmap(apk_addr, bak_size);
                    }
                    if (bak_addr != MAP_FAILED) {
                        munmap(bak_addr, bak_size);
                    }
                    if (bak_fd > 0) {
                        close(bak_fd);
                    }
                    if (apk_fd > 0) {
                        close(apk_fd);
                    }
                    return backup(fd, bakPath.c_str(), flag, mode);
                }
                return backup(fd, pathname, flag, mode);
            });

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook, jstring jorig, jstring jbak,
                          jlong offset, jlong size) {
        SandHook::ElfImg libc("libc.so");
        auto sym_openat = libc.getSymbAddress<void *>("__openat");
        auto r = HookSymNoHandle(handler, sym_openat, __openat);
        if (!r) {
            LOGE("Hook __openat fail");
            return;
        }

        if (offset >= 0 && size >= 0) {
            auto sym_fstatat = libc.getSymbAddress<void *>("fstatat");
            r = HookSymNoHandle(handler, sym_fstatat, fstatat);
            if (!r) {
                LOGE("Hook sym_fstatat fail");
                return;
            }
        }

        lsplant::JUTFString cOrigApkPath(env, jorig);
        lsplant::JUTFString cCacheApkPath(env, jbak);
        origPath = cOrigApkPath.get();
        bakPath = cCacheApkPath.get();
        bak_offset = offset;
        bak_size = size;
    }
    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;JJ)V")
    };

    void RegisterBypass(JNIEnv *env) {
        REGISTER_LSP_NATIVE_METHODS(SigBypass);
    }
}
