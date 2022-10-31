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
#include <memory>
#include <set>
#include "miniz.h"

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

    inline static int (*ashmem_create_region)(const char *name, std::size_t size) = nullptr;

    inline static int (*ashmem_set_prot_region)(int fd, int prot) = nullptr;

    std::mutex mt;

    struct MemFile {
        std::string displayName;
        std::string bakZip;
        struct stat st;
    };
    std::map<int, MemFile> fds;

    class FileContent {
    public:
        static std::unique_ptr<FileContent> MapReadWrite(int h, ssize_t length) {
            if (h == -1 || length < 0) {
                return nullptr;
            }

            void *base = mmap(nullptr, length, PROT_READ | PROT_WRITE, MAP_SHARED, h, 0);
            if (base == MAP_FAILED) {
                LOGE("fail to map fd {}, +{}, {}", h, length, strerror(errno));
                base = mmap(nullptr, length, PROT_WRITE, MAP_PRIVATE, h, 0);
                if (base == MAP_FAILED) {
                    LOGE("fail to map2 fd {}", h);
                    return nullptr;
                }
            }
            return std::make_unique<FileContent>(-1, static_cast<char *>(base), length);

        }

        static std::unique_ptr<FileContent> MapReadOnly(std::string filename) {
            int h = open(filename.c_str(), O_RDONLY);
            if (h == -1) {
                return nullptr;
            }
            struct stat st;
            if (0 != fstat(h, &st)) {
                close(h);
                return nullptr;
            }
            size_t length = st.st_size;

            void *base = mmap(nullptr, length, PROT_READ, MAP_SHARED, h, 0);
            if (base == MAP_FAILED) {
                close(h);
                return nullptr;
            }
            return std::make_unique<FileContent>(h, static_cast<char *>(base), length);

        }

        FileContent(int fd, char *base, size_t size) : fd_(fd), base_(base),
                                                       size_(size) {}

        int fd_;
        char *base_;
        size_t size_;

        bool Sync() {
            if (base_ != nullptr && size_ != 0) {
                if (msync(base_, size_, MS_SYNC) == 0) {
                    return true;
                }
                LOGE("fail to sync {} +{}", base_, size_);
            }
            return false;
        }

        ~FileContent() {
            if (base_ != nullptr && size_ != 0) {
                LOGI("munmap");
                if (0 == munmap(base_, size_)) {}
                else {
                    LOGE("fail to munmap {} +{}", base_, size_);
                }
            }
            if (fd_ != -1) {
                close(fd_);
            }
            base_ = nullptr;
            size_ = 0;
        }
    };


    class Zip {
    public:
        struct ZipEntry {
            int index;
            ssize_t size;
            ssize_t offset;
        };
        mz_zip_archive zip_archive_;
        std::unique_ptr<FileContent> fileContent_;
        bool init_;

        Zip(std::string name) : init_(false) {
            fileContent_ = FileContent::MapReadOnly(name);

            memset(&zip_archive_, 0, sizeof(mz_zip_archive));
            if (fileContent_.get()) {
                init_ = (mz_zip_reader_init_mem(&zip_archive_, fileContent_->base_,
                                                fileContent_->size_, 0));
            }
        }

        ZipEntry findEntry(std::string entry) {
            ssize_t size = -1;
            ssize_t offset = -1;
            int index = -1;
            if (!init_) {
                return {index, size, offset};
            }

            index = mz_zip_reader_locate_file(&zip_archive_, entry.c_str(), nullptr, 0);

            if (index >= 0) {
                mz_zip_archive_file_stat zip_entry_stat;
                if (mz_zip_reader_file_stat(&zip_archive_, index, &zip_entry_stat)) {
                    size = zip_entry_stat.m_uncomp_size;
                }
            }
            return {index, size, offset};
        }

        bool extractEntry(ZipEntry entry, int fd) {
            if (!init_ || entry.index < 0 || fd == -1) {
                return false;
            }
            int fd2 = dup(fd);
            if (fd2 == -1) {
                LOGE("fail fd2");
            }
            FILE *fp = fdopen(fd2, "rw");
            if (fp == NULL) {
                LOGE("fail fp");
            }
            bool result = mz_zip_reader_extract_to_cfile(&zip_archive_, entry.index, fp, 0);
            fclose(fp);
            if (!result) {
                LOGE("fail to extract entry");
            }
            return result;
        }

        bool extractEntry(ZipEntry entry, FileContent *apk) {
            if (!init_ || entry.index < 0 || !apk) {
                return false;
            }
            if (mz_zip_reader_extract_to_mem(&zip_archive_, entry.index,
                                             apk->base_,
                                             apk->size_,
                                             0)) {
                return true;
            } else {
                LOGE("fail to extract entry");
            }
            return false;
        }

        ~Zip() {
            if (init_) {
                mz_zip_reader_end(&zip_archive_);
            }
        }
    };

    bool endsWith(std::string const &str, std::string const &suffix) {
        if (str.length() < suffix.length()) {
            return false;
        }
        return str.compare(str.length() - suffix.length(), suffix.length(), suffix) == 0;
    }


    CREATE_HOOK_STUB_ENTRY(
            "fstat",
            int, h_fstat,
            (int __fd, struct stat *__buf), {
                int x = backup(__fd, __buf);
                bool find = false;
                MemFile m;
                mt.lock();
                auto it = fds.find(__fd);
                if (it != fds.end()) {
                    m = it->second;
                    find = true;
                }
                mt.unlock();
                if (find) {
                    LOGI("fstat: {} size to {}, result {}", m.displayName, m.st.st_size, x);
                    __buf->st_size = m.st.st_size;
                    __buf->st_mtim = m.st.st_mtim;
                    __buf->st_ctim = m.st.st_ctim;
                    __buf->st_atim = m.st.st_atim;
                }
                return x;
            });

    CREATE_HOOK_STUB_ENTRY(
            "fstatat",
            int, h_fstatat,
            (int __dir_fd, const char *__path, struct stat *__buf, int __flags), {

                int x = backup(__dir_fd, __path, __buf, __flags);
                if (x == 0) {
                    return x;
                }
                if (__path == nullptr) {
                    return x;
                }
                std::string sPathname(__path);
                size_t index = sPathname.find("!/");
                if (index == std::string::npos) {
                    return x;
                }
                LOGI("pathname is {}", sPathname);
                std::string zip = sPathname.substr(0, index);
                std::string entry = sPathname.substr(index + 2);
                LOGI("bak zip is {}", zip);
                LOGI("entry is {}", entry);

                x = backup(__dir_fd, zip.c_str(), __buf, __flags);
                if (x == 0) {
                    Zip bak(zip);
                    Zip::ZipEntry bakEntry = bak.findEntry(entry);
                    if (bakEntry.index < 0) { // no zip entry
                        return backup(__dir_fd, __path, __buf, __flags);
                    }
                    LOGI("fstatat: {} size to {}", sPathname, bakEntry.size);
                    __buf->st_size = bakEntry.size;
                }
                return x;


            });

    CREATE_HOOK_STUB_ENTRY(
            "close",
            int, h_close,
            (int __fd), {
                mt.lock();
                fds.erase(__fd);
                mt.unlock();
                return backup(__fd);
            });

    int create_memfd(const char *name, ssize_t size) {
        if (size < 0) {
            return -1;
        }
        int apk_fd = -1;


        if (apk_fd == -1) {
            apk_fd = syscall(__NR_memfd_create, name,
                             MFD_ALLOW_SEALING);
            LOGI("memfd_create({}) -> {}", name, apk_fd);
            if (0 != ftruncate(apk_fd, size)) {
                LOGI("fail ftruncate({}, {}):: {}", apk_fd, size,
                     strerror(errno));
            }
        }
        if (apk_fd == -1) {
            apk_fd = ashmem_create_region(name, size);
            LOGI("ashmem_create_region({}, {}) -> {}", name, size, apk_fd);
            if (0 != ashmem_set_prot_region(apk_fd, PROT_READ | PROT_WRITE)) {
                LOGI("ashmem_set_prot_region failed");
            }
        }
        return apk_fd;
    }

    CREATE_HOOK_STUB_ENTRY(
            "__openat",
            int, h__openat,
            (int fd, const char *pathname, int flag, int mode), {
                int x = backup(fd, pathname, O_RDONLY, mode);
                if (x != -1) {
                    return x;
                }
                if (pathname == NULL) {
                    return x;
                }
                std::string sPathname(pathname);
                size_t index = sPathname.find("!/");
                if (index == std::string::npos) {
                    return x;
                }
                LOGI("pathname is {}", sPathname);
                std::string zip = sPathname.substr(0, index);
                std::string entry = sPathname.substr(index + 2);
                LOGI("bak zip is {}", zip);
                LOGI("entry is {}", entry);

                Zip bak(zip);
                Zip::ZipEntry bakEntry = bak.findEntry(entry);
                if (bakEntry.index < 0) { // no zip entry
                    return backup(fd, pathname, O_RDONLY, mode);
                }
                int apk_fd = create_memfd(sPathname.c_str(), bakEntry.size);
                std::unique_ptr<FileContent> apk = FileContent::MapReadWrite(apk_fd, bakEntry.size);
                if (bak.extractEntry(bakEntry, apk.get()) && apk->Sync()) {
//                if (bak.extractEntry(bakEntry, apk_fd)) {


                    struct stat st;
                    stat(zip.c_str(), &st);
                    st.st_size = bakEntry.size;

                    MemFile f;
                    f.displayName = sPathname;
                    f.bakZip = zip;
                    f.st = st;
                    mt.lock();
                    fds.insert(std::make_pair<>(apk_fd, f));
                    mt.unlock();
                    LOGI("__openat({}) -> {}", pathname, apk_fd);
                    return apk_fd;
                }
                close(apk_fd);
                return x;
            });

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enhanceOpenat) {
        SandHook::ElfImg libc("libc.so");

        HookSymNoHandle(handler, libc.getSymbAddress(h__openat.sym), h__openat);
        HookSymNoHandle(handler, libc.getSymbAddress(h_fstatat.sym), h_fstatat);
        HookSymNoHandle(handler, libc.getSymbAddress(h_fstat.sym), h_fstat);
        HookSymNoHandle(handler, libc.getSymbAddress(h_close.sym), h_close);

        SandHook::ElfImg cutils("/system/lib" LP_SELECT("", "64") "/libcutils.so");
        ashmem_create_region = reinterpret_cast<decltype(ashmem_create_region)>(
                cutils.getSymbAddress("ashmem_create_region"));
        ashmem_set_prot_region = reinterpret_cast<decltype(ashmem_set_prot_region)>(
                cutils.getSymbAddress("ashmem_set_prot_region"));
    }
    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enhanceOpenat, "()V")
    };

    void RegisterBypass(JNIEnv *env) {
        REGISTER_LSP_NATIVE_METHODS(SigBypass);
    }

}
