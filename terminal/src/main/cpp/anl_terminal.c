/*
 * Pseudo-terminal helpers for tech.anl.terminal.Pty.
 *
 * Opens a PTY master, forks, and in the child makes the slave side the
 * controlling terminal of a new session before exec'ing the target program.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s", what, strerror(errno));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

/* Copies a Java String[] into a NULL-terminated, malloc'ed C array. */
static char **to_c_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = s ? (*env)->GetStringUTFChars(env, s, NULL) : "";
        out[i] = strdup(utf ? utf : "");
        if (s) {
            (*env)->ReleaseStringUTFChars(env, s, utf);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    return out;
}

static void free_c_array(char **array) {
    if (array == NULL) return;
    for (char **p = array; *p; p++) free(*p);
    free(array);
}

static void close_inherited_fds(int keep) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return;
    int dir_fd = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != dir_fd && fd != keep) close(fd);
    }
    closedir(dir);
}

JNIEXPORT jintArray JNICALL
Java_tech_anl_terminal_Pty_createSubprocess(JNIEnv *env, jclass clazz, jstring cmd, jstring cwd,
                                            jobjectArray argv, jobjectArray envp,
                                            jint rows, jint cols) {
    (void) clazz;
    int ptm = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (ptm < 0) {
        throw_io(env, "posix_openpt");
        return NULL;
    }
    char pts_name[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, pts_name, sizeof(pts_name))) {
        throw_io(env, "pty setup");
        close(ptm);
        return NULL;
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tios.c_cc[VERASE] = 0x7f;
        tcsetattr(ptm, TCSANOW, &tios);
    }
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(ptm, TIOCSWINSZ, &ws);

    const char *c_cmd = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char *c_cwd = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;
    char **c_argv = to_c_array(env, argv);
    char **c_envp = to_c_array(env, envp);
    char *empty_env[] = {NULL};

    pid_t pid = fork();
    if (pid < 0) {
        throw_io(env, "fork");
        close(ptm);
    } else if (pid == 0) {
        /* Child: new session with the PTY slave as controlling terminal. */
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);

        close(ptm);
        setsid();
        int pts = open(pts_name, O_RDWR);
        if (pts < 0) _exit(126);
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        if (pts > 2) close(pts);
        close_inherited_fds(-1);

        if (c_cwd && *c_cwd && chdir(c_cwd) != 0) {
            fprintf(stderr, "chdir(%s): %s\r\n", c_cwd, strerror(errno));
        }
        char *fallback_argv[] = {(char *) c_cmd, NULL};
        execvpe(c_cmd, c_argv ? c_argv : fallback_argv, c_envp ? c_envp : empty_env);
        fprintf(stderr, "exec(%s): %s\r\n", c_cmd, strerror(errno));
        fflush(stderr);
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, cmd, c_cmd);
    if (c_cwd) (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    free_c_array(c_argv);
    free_c_array(c_envp);
    if (pid < 0) return NULL;

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) return NULL;
    jint values[2] = {pid, ptm};
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_tech_anl_terminal_Pty_setPtyWindowSize(JNIEnv *env, jclass clazz, jint fd, jint rows,
                                            jint cols, jint width_px, jint height_px) {
    (void) env;
    (void) clazz;
    struct winsize ws = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) cols,
            .ws_xpixel = (unsigned short) width_px,
            .ws_ypixel = (unsigned short) height_px,
    };
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_tech_anl_terminal_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_tech_anl_terminal_Pty_close(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    close(fd);
}

JNIEXPORT void JNICALL
Java_tech_anl_terminal_Pty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env;
    (void) clazz;
    /* Signal the whole process group first so children of the shell see it too. */
    if (kill(-pid, sig) != 0) kill(pid, sig);
}
