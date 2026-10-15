/*
 * Tests which syscalls an app process may make on this phone. Each syscall runs in its own
 * child, so a seccomp kill (SIGSYS) is reported instead of killing the test.
 * Built static for aarch64 and packaged as libsyscalltest.so. It runs directly, without proot.
 * Each line: name, number, result. "ok ret=N errno=E" means the kernel answered. "SIGSYS" means
 * a seccomp filter killed the call.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

struct test { const char *name; long nr; };

static const struct test tests[] = {
    {"getpid", __NR_getpid}, {"futex", __NR_futex}, {"set_robust_list", 99},
    {"rseq", 293}, {"clone3", 435}, {"clone", __NR_clone}, {"openat2", 437},
    {"faccessat2", 439}, {"statx", 291}, {"close_range", 436}, {"getrandom", 278},
    {"epoll_pwait2", 441}, {"pidfd_open", 434}, {"memfd_create", 279},
    {"membarrier", 283}, {"prlimit64", 261}, {"prctl", __NR_prctl},
    {"sched_getaffinity", 123}, {"getcpu", 168}, {"ptrace", 117},
    {"seccomp", 277}, {"io_uring_setup", 425}, {"mount", 40},
};

int main(void) {
    size_t n = sizeof(tests) / sizeof(tests[0]);
    printf("syscalltest: %zu calls, pid %d\n", n, getpid());
    fflush(stdout);
    for (size_t i = 0; i < n; i++) {
        pid_t pid = fork();
        if (pid == 0) {
            /* Zero-argument probes. Calls that could change state are given invalid arguments. */
            long r = syscall(tests[i].nr, 0L, 0L, 0L, 0L, 0L, 0L);
            _exit(r < 0 ? (errno & 0x7f) : 0x40);
        }
        int status = 0;
        waitpid(pid, &status, 0);
        if (WIFSIGNALED(status)) {
            printf("%-18s %4ld  %s (signal %d)\n", tests[i].name, tests[i].nr,
                   WTERMSIG(status) == SIGSYS ? "SIGSYS" : "killed", WTERMSIG(status));
        } else {
            int code = WEXITSTATUS(status);
            if (code == 0x40) printf("%-18s %4ld  ok ret=0\n", tests[i].name, tests[i].nr);
            else printf("%-18s %4ld  ok errno=%d (%s)\n", tests[i].name, tests[i].nr, code, strerror(code));
        }
        fflush(stdout);
    }
    printf("syscalltest: done\n");
    return 0;
}
