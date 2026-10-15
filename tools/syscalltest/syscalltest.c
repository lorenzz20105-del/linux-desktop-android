/*
 * Syscall test for the phone. Built with no libc (raw aarch64 svc), so the test makes no
 * startup syscalls of its own. Each syscall runs in a forked child, so a seccomp kill (SIGSYS)
 * is reported as a result, not as the end of the test.
 * Result per line:
 *   "returned >=0"    the kernel accepted the call (returned a value, not an error)
 *   "errno=N"         the kernel returned an error (the call was not blocked)
 *   "SIGSYS"          the seccomp filter killed the call
 *   "signal N"        another signal killed the child
 * Built with: aarch64-linux-gnu-gcc -static -nostdlib -O2 -o syscalltest syscalltest.c
 */

static long sc(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return x0;
}

struct test { const char *name; long nr; };

/* aarch64 syscall numbers. Arguments are zero, so the calls only check, except the few that
 * act on the child (set_tid_address and friends are harmless). kill(0, 0) only checks. */
static const struct test tests[] = {
    {"read", 63}, {"write", 64}, {"close", 57}, {"openat", 56}, {"getpid", 172},
    {"getppid", 173}, {"getuid", 174}, {"uname", 160}, {"newfstatat", 79},
    {"readlinkat", 78}, {"getdents64", 61}, {"fcntl", 25}, {"dup", 23}, {"lseek", 62},
    {"mmap", 222}, {"mprotect", 226}, {"munmap", 215}, {"mremap", 216}, {"brk", 214},
    {"madvise", 233}, {"set_tid_address", 96}, {"set_robust_list", 99}, {"rseq", 293},
    {"futex", 98}, {"sched_yield", 124}, {"sched_getaffinity", 123}, {"getcpu", 168},
    {"clock_gettime", 113}, {"nanosleep", 101}, {"rt_sigaction", 134},
    {"rt_sigprocmask", 135}, {"sigaltstack", 132}, {"kill", 129}, {"prlimit64", 261},
    {"prctl", 167}, {"clone", 220}, {"clone3", 435}, {"execve", 221}, {"wait4", 260},
    {"openat2", 437}, {"faccessat2", 439}, {"faccessat", 48}, {"statx", 291},
    {"close_range", 436}, {"getrandom", 278}, {"epoll_create1", 20}, {"epoll_pwait", 22},
    {"epoll_pwait2", 441}, {"pidfd_open", 434}, {"memfd_create", 279}, {"membarrier", 283},
    {"ptrace", 117}, {"seccomp", 277}, {"io_uring_setup", 425}, {"mount", 40},
    {"unshare", 97}, {"setns", 268}, {"bpf", 280}, {"userfaultfd", 282},
};

static void put_n(const char *s, long n) { sc(64, 1, (long)s, n, 0, 0, 0); }

static void put(const char *s) {
    long n = 0;
    while (s[n]) n++;
    put_n(s, n);
}

static void put_num(long v) {
    char buf[24];
    int i = 23;
    int neg = v < 0;
    unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
    buf[i] = 0;
    do { buf[--i] = '0' + (u % 10); u /= 10; } while (u && i > 0);
    if (neg) buf[--i] = '-';
    put(&buf[i]);
}

static long slen(const char *s) {
    long n = 0;
    while (s[n]) n++;
    return n;
}


/* Realistic arguments for the calls the phone blocked with zero arguments. */
static char head_buf[64] __attribute__((aligned(64)));
static char rseq_buf[64] __attribute__((aligned(64)));
static char how_buf[24] __attribute__((aligned(8)));
static char ev_buf[64] __attribute__((aligned(8)));
static char params_buf[120] __attribute__((aligned(8)));
static char clone_buf[88] __attribute__((aligned(8)));
static char ts_buf[16] __attribute__((aligned(8)));
static const char status_path[] = "/proc/self/status";

static long real_call(long nr) {
    switch (nr) {
    case 99:  return sc(99, (long)head_buf, 24, 0, 0, 0, 0);
    case 293: return sc(293, (long)rseq_buf, 32, 0, 0x53053053, 0, 0);
    case 435: {
        /* clone_args: flags, pidfd, child_tid, parent_tid, exit_signal (SIGCHLD), stack, ... */
        long *a = (long *)clone_buf;
        for (int i = 0; i < 11; i++) a[i] = 0;
        a[4] = 17;
        return sc(435, (long)clone_buf, 88, 0, 0, 0, 0);
    }
    case 437: {
        /* open_how: flags (O_RDONLY), mode, resolve */
        long *h = (long *)how_buf;
        h[0] = 0; h[1] = 0; h[2] = 0;
        return sc(437, -100, (long)status_path, (long)how_buf, 24, 0, 0);
    }
    case 439: return sc(439, -100, (long)"/", 0, 0, 0, 0);
    case 441: {
        long epfd = sc(20, 0, 0, 0, 0, 0, 0);
        long *t = (long *)ts_buf;
        t[0] = 0; t[1] = 0;
        return sc(441, epfd, (long)ev_buf, 1, (long)ts_buf, 0, 0);
    }
    case 425: return sc(425, 8, (long)params_buf, 0, 0, 0, 0);
    default:  return sc(nr, 0, 0, 0, 0, 0, 0);
    }
}

static int has_real(long nr) {
    return nr == 99 || nr == 293 || nr == 435 || nr == 437 || nr == 439 || nr == 441 || nr == 425;
}

static void run_one(const char *name, long nr, int realistic) {
    long pid = sc(220, 17 /* SIGCHLD */, 0, 0, 0, 0, 0);
    if (pid == 0) {
        long r = realistic ? real_call(nr) : sc(nr, 0, 0, 0, 0, 0, 0);
        long code = r < 0 ? -r : 0;
        if (code > 127) code = 127;
        sc(94, code, 0, 0, 0, 0, 0);
    }
    int status = 0;
    sc(260, pid, (long)&status, 0, 0, 0, 0);
    put(name);
    if (realistic) put(" (real args)");
    long shown = slen(name) + (realistic ? 12 : 0);
    for (long pad = shown; pad < 30; pad++) put(" ");
    put_num(nr);
    put("  ");
    if ((status & 0x7f) == 0) {
        long code = (status >> 8) & 0xff;
        if (code == 0) put("returned >=0\n");
        else { put("errno="); put_num(code); put("\n"); }
    } else if ((status & 0x7f) == 31) {
        put("SIGSYS (killed by seccomp)\n");
    } else {
        put("signal "); put_num(status & 0x7f); put("\n");
    }
}

void test_main(void) {
    long n = sizeof(tests) / sizeof(tests[0]);
    put("syscalltest: ");
    put_num(n);
    put(" calls, zero arguments\n");
    for (long i = 0; i < n; i++) run_one(tests[i].name, tests[i].nr, 0);
    put("syscalltest: realistic arguments for the blocked calls\n");
    for (long i = 0; i < n; i++) if (has_real(tests[i].nr)) run_one(tests[i].name, tests[i].nr, 1);
    put("syscalltest: done\n");
    sc(94, 0, 0, 0, 0, 0, 0);
}

__asm__(".globl _start\n_start:\n bl test_main\n");
