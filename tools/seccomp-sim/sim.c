/*
 * Simulates the phone's seccomp allowlist on this host: installs a filter that kills the
 * process (SIGSYS) when it makes one of the named syscalls, then execs the command.
 * Usage: sim <comma-separated syscall names or "none"> <command> [args...]
 * Each process inherits the filter, as on the phone.
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
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

static const struct { const char *name; int nr; } known[] = {
    {"set_robust_list", __NR_set_robust_list}, {"rseq", 334}, {"clone3", __NR_clone3},
    {"openat2", __NR_openat2}, {"faccessat2", __NR_faccessat2},
    {"epoll_pwait2", __NR_epoll_pwait2}, {"io_uring_setup", __NR_io_uring_setup},
    {"mount", __NR_mount},
};

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: sim <names|none> <cmd> [args]\n"); return 2; }
    int blocked[16], nb = 0;
    if (strcmp(argv[1], "none") != 0) {
        char *list = strdup(argv[1]);
        for (char *tok = strtok(list, ","); tok; tok = strtok(NULL, ",")) {
            int found = 0;
            for (size_t i = 0; i < sizeof(known) / sizeof(known[0]); i++) {
                if (strcmp(tok, known[i].name) == 0) { blocked[nb++] = known[i].nr; found = 1; }
            }
            if (!found) { fprintf(stderr, "unknown syscall %s\n", tok); return 2; }
        }
    }
    /* BPF: load arch, require x86_64, load syscall number, kill on any blocked number. */
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
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) { perror("no_new_privs"); return 2; }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) { perror("seccomp"); return 2; }
    execvp(argv[2], argv + 2);
    perror("exec");
    return 127;
}
