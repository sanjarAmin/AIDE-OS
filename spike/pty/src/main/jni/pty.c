/*
 * Spike R7: a pseudoterminal, and a shell running in it.
 *
 * The smallest thing that can answer the question. No terminal emulation, no
 * escape parsing, no buffering strategy -- just: does the kernel give an
 * unprivileged Android app a PTY, will it exec a shell as a session leader on
 * it, and does job control work afterwards.
 *
 * Everything here is deliberately close to the syscalls. A terminal that is
 * subtly wrong about process groups looks fine until the first Ctrl-C, and a
 * layer of abstraction over that is a layer between the finding and the reader.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/*
 * Forks a child on a new PTY and execs a shell in it.
 *
 * Returns the child's pid, and writes the master fd through `out_fd`. A
 * negative return is `-errno` so the Kotlin side can report which call failed
 * rather than "it did not work".
 */
JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeOpen(
        JNIEnv *env, jclass clazz, jstring j_shell, jstring j_cwd,
        jint columns, jint rows, jintArray out_fd) {
    (void) clazz;

    const char *shell = (*env)->GetStringUTFChars(env, j_shell, NULL);
    const char *cwd = j_cwd ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = (unsigned short) columns;
    size.ws_row = (unsigned short) rows;

    int master = -1;
    /*
     * forkpty does openpty + fork + setsid + TIOCSCTTY in one call, which is
     * exactly the sequence that has to work for job control to work at all.
     * Doing it by hand would let a mistake in the sequence look like a platform
     * restriction, which is the confusion this spike exists to avoid.
     */
    pid_t pid = forkpty(&master, NULL, NULL, &size);
    if (pid < 0) {
        int saved = errno;
        (*env)->ReleaseStringUTFChars(env, j_shell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        return -saved;
    }

    if (pid == 0) {
        /*
         * Child. Nothing here may allocate through the JVM or touch a JNIEnv:
         * this is a forked copy of a multi-threaded process, so only
         * async-signal-safe work is legal until execve replaces the image.
         */
        if (cwd) {
            if (chdir(cwd) != 0) _exit(127);
        }
        /*
         * A terminal without TERM set is one where every full-screen program
         * refuses to start, and an app's inherited environment has none.
         * `dumb` deliberately: this spike has no emulator behind the fd, and
         * claiming xterm would invite escape sequences nothing here can read.
         */
        setenv("TERM", "dumb", 1);
        setenv("HOME", cwd ? cwd : "/", 1);

        /*
         * Signals the JVM has caught or blocked are inherited across fork and
         * survive exec if they are ignored. A shell that starts with SIGINT
         * ignored cannot be interrupted, which would look exactly like job
         * control not working on this platform.
         */
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);

        execl(shell, shell, "-l", (char *) NULL);
        /* Only reached when exec failed; the parent sees it as an exit code. */
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, j_shell, shell);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);

    jint fd = master;
    (*env)->SetIntArrayRegion(env, out_fd, 0, 1, &fd);
    return (jint) pid;
}

/** The size the child believes its terminal is. Drives SIGWINCH. */
JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeResize(
        JNIEnv *env, jclass clazz, jint fd, jint columns, jint rows) {
    (void) env;
    (void) clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = (unsigned short) columns;
    size.ws_row = (unsigned short) rows;
    return ioctl(fd, TIOCSWINSZ, &size) == 0 ? 0 : -errno;
}

/**
 * The foreground process group of the terminal.
 *
 * This is the job-control question asked directly: a shell that made itself a
 * session leader and took the terminal reports its own pgid here, and a
 * command running in the foreground reports that command's. If this fails, the
 * fd is a pipe wearing a terminal's clothes.
 */
JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeForegroundGroup(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    pid_t group = tcgetpgrp(fd);
    return group < 0 ? -errno : (jint) group;
}

/** Waits for the child, returning its exit status or -errno. */
JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeWait(JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t done = waitpid((pid_t) pid, &status, 0);
    if (done < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -EINVAL;
}

/** Non-blocking check: has the child exited yet? -1 means still running. */
JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativePoll(JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t done = waitpid((pid_t) pid, &status, WNOHANG);
    if (done == 0) return -1;
    if (done < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -EINVAL;
}

JNIEXPORT jint JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeKill(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env;
    (void) clazz;
    /* Negated: the whole process group, which is what a terminal signals. */
    return kill((pid_t) -pid, sig) == 0 ? 0 : -errno;
}

JNIEXPORT void JNICALL
Java_com_osamu_aide_spike_pty_Pty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    close(fd);
}
