# Seccomp allowlist simulation (host kernel 6.1, glibc 2.41, same as the trixie rootfs)

`sim.c` installs a filter that kills the process (SIGSYS, exit status 159) when it makes a
named syscall. The phone's app process has a filter of this kind (`Seccomp: 2`, one filter).

Columns: `true` = /bin/true; `sh` = dash; `thread` = python3 thread start/join;
`spawn` = python3 subprocess. Values are the exit status (159 = killed by SIGSYS).
`tunable` = GLIBC_TUNABLES=glibc.pthread.rseq=0.

| blocked | tunable | true | sh | thread | spawn |
|---|---|---|---|---|---|
| none | no | 0 | ok | ok | ok |
| rseq | no | 159 | killed | 159 | 159 |
| rseq | yes | 0 | ok | ok | ok |
| set_robust_list | no | 159 | killed | 159 | 159 |
| set_robust_list | yes | 159 | killed | 159 | 159 |
| clone3 | no | 0 | ok | 159 | ok |
| clone3 | yes | 0 | ok | 159 | ok |
| openat2, faccessat2 | either | 0 | ok | ok | ok |
| epoll_pwait2 | either | 0 | ok | ok | ok |
| io_uring_setup | either | 0 | ok | ok | ok |
| mount | either | 0 | ok | ok | ok |

Conclusions:
- rseq is fixed by the tunable.
- clone3 blocked: no thread creation, so Xvfb, XFCE, and Node would fail.
- set_robust_list blocked: every glibc program dies at startup. The tunable does not help.
- The phone's zero-argument test blocked set_robust_list, but the guest's real call completed in
  the phone's trace. The argument-dependence test (APK build 018246c) is what settles it.
- Rebuilding on Debian bullseye (glibc 2.31) does not remove set_robust_list, which glibc has
  used since 2.6. Treat bullseye as not a fix unless the phone shows set_robust_list passes.

## Phone result, real arguments (build 018246c)

Phone (INFINIX X6528, Android 13): set_robust_list, rseq, clone3, openat2, faccessat2,
epoll_pwait2, and io_uring_setup all return SIGSYS with real arguments, the same as with zeros.
The block does not depend on arguments.

## musl vs glibc under the phone's block list (host kernel)

Block list: set_robust_list, rseq, clone3, openat2, faccessat2, epoll_pwait2, io_uring_setup, mount.
Test program: 4 pthreads plus a fork (thr.c).

| build | result |
|---|---|
| glibc 2.41, dynamic | killed (159) |
| glibc 2.41 + GLIBC_TUNABLES=glibc.pthread.rseq=0 | killed (159) |
| musl, static | ok |
| musl, only clone3 blocked | ok |
| musl, only set_robust_list blocked | ok |

Conclusion: every glibc program fails on this phone's policy. musl programs run.
