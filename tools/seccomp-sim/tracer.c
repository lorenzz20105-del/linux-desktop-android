/*
 * Tracer test: runs a command under the same seccomp block list as sim.c, but rewrites each
 * blocked syscall at its entry stop to getpid (allowed), then makes it return -ENOSYS.
 * This is what proot can do in ptrace mode if the kernel runs ptrace before seccomp.
 * Usage: tracer <names|none> <cmd> [args]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

static const struct { const char *name; int nr; } known[] = {
    {"set_robust_list", __NR_set_robust_list}, {"rseq", 334}, {"clone3", __NR_clone3},
    {"openat2", __NR_openat2}, {"faccessat2", __NR_faccessat2},
    {"epoll_pwait2", __NR_epoll_pwait2}, {"io_uring_setup", __NR_io_uring_setup},
    {"mount", __NR_mount},
};

static int blocked[16], nb;
static int is_blocked(long nr) { for (int i = 0; i < nb; i++) if (blocked[i] == nr) return 1; return 0; }

#define MAXP 512
static pid_t pids[MAXP];
static int in_sc[MAXP], fake[MAXP];
static int nthreads;

static int find(pid_t p) { for (int i = 0; i < nthreads; i++) if (pids[i] == p) return i; return -1; }
static int add(pid_t p) { int i = nthreads++; pids[i] = p; in_sc[i] = 0; fake[i] = 0; return i; }

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: tracer <names|none> <cmd> [args]\n"); return 2; }
    if (strcmp(argv[1], "none") != 0) {
        char *list = strdup(argv[1]);
        for (char *tok = strtok(list, ","); tok; tok = strtok(NULL, ",")) {
            int ok = 0;
            for (size_t i = 0; i < sizeof(known) / sizeof(known[0]); i++)
                if (strcmp(tok, known[i].name) == 0) { blocked[nb++] = known[i].nr; ok = 1; }
            if (!ok) { fprintf(stderr, "unknown syscall %s\n", tok); return 2; }
        }
    }
    pid_t child = fork();
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        struct sock_filter f[64];
        int n = 0;
        f[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
        f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0);
        f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
        f[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
        for (int i = 0; i < nb; i++) {
            f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, blocked[i], 0, 1);
            f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
        }
        f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        struct sock_fprog prog = { .len = (unsigned short)n, .filter = f };
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        raise(SIGSTOP);
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
        execvp(argv[2], argv + 2);
        _exit(127);
    }
    int status;
    waitpid(child, &status, 0);
    ptrace(PTRACE_SETOPTIONS, child, 0,
           PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK |
           PTRACE_O_TRACEVFORK | PTRACE_O_EXITKILL);
    add(child);
    ptrace(PTRACE_SYSCALL, child, 0, 0);
    int rewrites = 0, sig_forward = 0;
    for (;;) {
        int st;
        pid_t p = waitpid(-1, &st, __WALL);
        if (p < 0) break;
        int i = find(p);
        if (i < 0) { i = add(p); }  /* new thread from clone: its first stop */
        if (WIFEXITED(st) || WIFSIGNALED(st)) {
            for (int k = 0; k < nthreads; k++) if (pids[k] == p) { pids[k] = pids[--nthreads]; in_sc[k] = in_sc[nthreads]; fake[k] = fake[nthreads]; break; }
            if (p == child) { printf("tracer: child exit, rewrites=%d forwarded_signals=%d\n", rewrites, sig_forward); return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st); }
            continue;
        }
        if (WIFSTOPPED(st)) {
            int sig = WSTOPSIG(st);
            if (sig == (SIGTRAP | 0x80)) {
                struct user_regs_struct r;
                ptrace(PTRACE_GETREGS, p, 0, &r);
                if (!in_sc[i]) {
                    /* Entry stop. Rewrite blocked calls to getpid. */
                    in_sc[i] = 1; fake[i] = 0;
                    if (is_blocked((long)r.orig_rax)) {
                        r.orig_rax = __NR_getpid;
                        ptrace(PTRACE_SETREGS, p, 0, &r);
                        fake[i] = 1; rewrites++;
                    }
                } else {
                    /* Exit stop. Blocked calls return -ENOSYS. */
                    if (fake[i]) { r.rax = (unsigned long)(-(long)ENOSYS); ptrace(PTRACE_SETREGS, p, 0, &r); fake[i] = 0; }
                    in_sc[i] = 0;
                }
                ptrace(PTRACE_SYSCALL, p, 0, 0);
            } else if (sig == SIGTRAP && (st >> 16) != 0) {
                /* ptrace event (clone/fork). The new child starts with its own stop. */
                ptrace(PTRACE_SYSCALL, p, 0, 0);
            } else {
                if (sig != SIGSTOP && sig != SIGTRAP) sig_forward++;
                ptrace(PTRACE_SYSCALL, p, 0, sig == SIGSTOP || sig == SIGTRAP ? 0 : sig);
            }
        }
    }
    return 0;
}
