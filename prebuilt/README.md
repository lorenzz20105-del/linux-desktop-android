# Prebuilt proot 5.4.0 for Android (NDK r26d, API 26)

- arm64-v8a/libproot.so and x86_64/libproot.so are built from proot-me/proot v5.4.0 (GPLv2).
- talloc 2.4.2 is linked statically. Its build used a minimal replace.h shim.
- Dependencies: only Android's libc and libdl.
- The loader is built into the binary (no separate loader file).
- Build recipe: see ../build-proot.sh.
