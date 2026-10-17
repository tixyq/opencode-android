/*
 * oc_compat — LD_PRELOAD shim for the bundled OpenCode server.
 *
 * Why: the server (Bun runtime) hardens process startup by calling
 * close_range(4, ~0U, CLOSE_RANGE_UNSHARE) through the raw syscall(2)
 * wrapper. Android's seccomp policy for this app traps close_range, and a
 * trapped syscall raises SIGSYS, which kills the server before it ever
 * binds the port:
 *
 *   libc: Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)
 *         in tid ... (opencode-server)
 *
 * The trap comes from the kernel policy and is inherited across execve,
 * so the child cannot relax it. The only place we can act is before the
 * syscall reaches the kernel: intercept it and emulate it.
 *
 * Interposing syscall(2) rather than close_range(2) is deliberate: the
 * runtime calls the raw wrapper (call *syscall@plt in the binary), which
 * bypasses libc's close_range symbol entirely.
 *
 * Every other syscall number is forwarded verbatim, so this shim only
 * changes behaviour where the kernel would otherwise kill the process.
 * If close_range is permitted by the policy (or added by a future
 * Android release), we forward to the real syscall instead of emulating
 * it, keeping the atomic semantics the runtime asked for.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef CLOSE_RANGE_CLOEXEC
#define CLOSE_RANGE_CLOEXEC (1U << 2)
#endif
#ifndef CLOSE_RANGE_UNSHARE
#define CLOSE_RANGE_UNSHARE (1U << 1)
#endif

typedef long (*syscall_fn)(long, ...);

static syscall_fn real_syscall(void) {
    static syscall_fn cached;
    if (cached == NULL) {
        cached = (syscall_fn)dlsym(RTLD_NEXT, "syscall");
        if (cached == NULL) {
            /* Nothing sensible left to do; failing loudly beats looping. */
            abort();
        }
    }
    return cached;
}

/*
 * Whether we may hand the call to the kernel.
 *
 * We cannot probe: asking the kernel whether close_range works means
 * calling it, and a trapped probe kills the process exactly like the
 * original crash. So the answer has to come from configuration instead.
 * Emulation is equivalent for the runtime's use (drop every inherited
 * descriptor at startup); setting OC_COMPAT_KEEP_CLOSE_RANGE=1 restores
 * the kernel call and its atomicity on policies that permit it.
 */
static int kernel_close_range_ok(void) {
    static int cached = -1;
    if (cached < 0) {
        const char *flag = getenv("OC_COMPAT_KEEP_CLOSE_RANGE");
        cached = (flag != NULL && flag[0] == '1') ? 1 : 0;
    }
    return cached;
}

/*
 * close_range() replacement: same visible effect without entering the
 * kernel, so a SIGSYS policy cannot kill us. Not atomic — a concurrent
 * opener could slip between two close() calls — which is why we prefer
 * the real syscall whenever it works.
 */
static long emulate_close_range(unsigned int first, unsigned int last,
                                unsigned int flags) {
    DIR *dir;
    struct dirent *ent;
    int dir_fd;
    int cloexec = (flags & CLOSE_RANGE_CLOEXEC) != 0;

    dir = opendir("/proc/self/fd");
    if (dir == NULL) {
        /* Cannot enumerate: fall back to a plain range walk. */
        unsigned int fd;
        for (fd = first; fd <= last && fd < 4096; fd++) {
            if (cloexec) {
                fcntl(fd, F_SETFD, FD_CLOEXEC);
            } else {
                close(fd);
            }
        }
        return 0;
    }
    dir_fd = dirfd(dir);
    while ((ent = readdir(dir)) != NULL) {
        unsigned long value;
        char *end = NULL;
        unsigned int fd;

        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') {
            continue;
        }
        value = strtoul(ent->d_name, &end, 10);
        if (end == NULL || *end != '\0' || value > 0x7fffffffUL) {
            continue;
        }
        fd = (unsigned int)value;
        if (fd < first || (last != ~0U && fd > last) || fd == (unsigned int)dir_fd) {
            continue;
        }
        if (cloexec) {
            fcntl(fd, F_SETFD, FD_CLOEXEC);
        } else {
            close(fd);
        }
    }
    closedir(dir);
    return 0;
}

static long do_close_range(unsigned int first, unsigned int last,
                           unsigned int flags) {
    /* CLOSE_RANGE_UNSHARE only asks the kernel to skip a forked child we
     * do not have; ignoring it keeps our own table change equivalent. */
    if (kernel_close_range_ok()) {
        return real_syscall()(__NR_close_range, first, last, flags);
    }
    return emulate_close_range(first, last, flags);
}

long syscall(long number, ...) {
    va_list ap;
    long a0, a1, a2, a3, a4, a5;

    va_start(ap, number);
    a0 = va_arg(ap, long);
    a1 = va_arg(ap, long);
    a2 = va_arg(ap, long);
    a3 = va_arg(ap, long);
    a4 = va_arg(ap, long);
    a5 = va_arg(ap, long);
    va_end(ap);

#ifdef __NR_close_range
    if (number == __NR_close_range) {
        return do_close_range((unsigned int)a0, (unsigned int)a1,
                              (unsigned int)a2);
    }
#endif
    return real_syscall()(number, a0, a1, a2, a3, a4, a5);
}

/* Belt and braces: anything reaching libc's wrapper, not the raw one. */
int close_range(unsigned int first, unsigned int last, unsigned int flags) {
    if (kernel_close_range_ok()) {
        return real_syscall()(__NR_close_range, first, last, flags);
    }
    return (int)emulate_close_range(first, last, flags);
}