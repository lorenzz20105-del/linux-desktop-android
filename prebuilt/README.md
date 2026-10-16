# Android native libraries

The APK build currently packages only `arm64-v8a/libproot.so`, plus the libraries it needs (`libtalloc.so` and `libandroid-shmem.so`). The Debian guest and rootfs add-ons are arm64-only, so the checked-in x86_64 binaries are not packaged.

The arm64 proot comes from Termux proot 5.1.107.96 and includes SIGSYS handling needed under Android's seccomp policy. Rebuild it with `NDK=/path/to/android-ndk-r27c tools/build-proot.sh`; this recipe requests 16 KB ELF load alignment. The currently checked-in proot binary predates that alignment change and must be rebuilt before 16 KB page-size compatibility can be claimed.

`libbusybox.so` and `libsyscalltest.so` are retained as diagnostics/build artifacts, but neither is included in the APK: archive extraction is implemented in Java, and the syscall-test executable is only for device diagnostics.
