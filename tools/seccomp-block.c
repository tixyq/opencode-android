/*
 * seccomp-block — test stand-in for the Android app seccomp policy.
 *
 * Installs a BPF filter that answers close_range(2) with
 * SECCOMP_RET_TRAP, which is what Android's policy does to the app
 * process: the kernel then raises SIGSYS and the server dies at startup.
 * Everything else runs untouched, so the exec'd command sees an ordinary
 * process apart from that one blocked call.
 *
 * Usage: seccomp-block <command> [args...]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef __NR_close_range
#define __NR_close_range 436 /* aarch64 */
#endif

static struct sock_filter filter[] = {
    /* load the syscall number (offset 0 of seccomp_data) */
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
    /* if it is not close_range, skip the kill instruction */
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close_range, 0, 1),
    /* SIGSYS for this process, kernel-independent */
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    /* everything else: allowed */
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
};

int main(int argc, char **argv) {
    struct sock_fprog prog;
    pid_t pid;
    int status = 0;

    if (argc < 2) {
        fprintf(stderr, "usage: %s <command> [args...]\n", argv[0]);
        return 2;
    }
    prog.len = (unsigned short)(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        perror("PR_SET_NO_NEW_PRIVS");
        return 2;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
        perror("PR_SET_SECCOMP");
        return 2;
    }

    pid = fork();
    if (pid < 0) {
        perror("fork");
        return 2;
    }
    if (pid == 0) {
        execvp(argv[1], argv + 1);
        fprintf(stderr, "exec %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }
    if (waitpid(pid, &status, 0) < 0) {
        perror("waitpid");
        return 2;
    }
    if (WIFSIGNALED(status)) {
        fprintf(stderr, "child died with signal %d (%s)\n",
                WTERMSIG(status), strsignal(WTERMSIG(status)));
        return 128 + WTERMSIG(status);
    }
    return WEXITSTATUS(status);
}