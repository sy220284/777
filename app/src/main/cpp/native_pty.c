#define _XOPEN_SOURCE 700

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

typedef struct {
    int master_fd;
    pid_t pid;
    bool reaped;
} dsh_pty_handle;

static void throw_io(JNIEnv *env, const char *prefix) {
    char message[512];
    const char *reason = strerror(errno);
    snprintf(message, sizeof(message), "%s: %s", prefix, reason != NULL ? reason : "unknown");
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

static char *copy_utf(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) return NULL;
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

static void free_strings(char **values, jsize count) {
    if (values == NULL) return;
    for (jsize i = 0; i < count; ++i) free(values[i]);
    free(values);
}

static char **copy_string_array(JNIEnv *env, jobjectArray array, jsize *count_out) {
    const jsize count = array == NULL ? 0 : (*env)->GetArrayLength(env, array);
    char **values = calloc((size_t) count + 1U, sizeof(char *));
    if (values == NULL) {
        errno = ENOMEM;
        throw_io(env, "无法分配 PTY 参数");
        return NULL;
    }
    for (jsize i = 0; i < count; ++i) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        values[i] = copy_utf(env, item);
        if (item != NULL) (*env)->DeleteLocalRef(env, item);
        if (values[i] == NULL) {
            free_strings(values, count);
            if (!(*env)->ExceptionCheck(env)) {
                errno = ENOMEM;
                throw_io(env, "无法复制 PTY 参数");
            }
            return NULL;
        }
    }
    values[count] = NULL;
    *count_out = count;
    return values;
}

static void apply_environment(char **environment) {
    if (environment == NULL) return;
    for (size_t i = 0; environment[i] != NULL; ++i) {
        char *entry = environment[i];
        char *separator = strchr(entry, '=');
        if (separator == NULL || separator == entry) continue;
        *separator = '\0';
        (void) setenv(entry, separator + 1, 1);
        *separator = '=';
    }
}

static bool set_window_size(int fd, int columns, int rows) {
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = (unsigned short) columns;
    size.ws_row = (unsigned short) rows;
    return ioctl(fd, TIOCSWINSZ, &size) == 0;
}

JNIEXPORT jboolean JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeProbe(
    JNIEnv *env,
    jobject self
) {
    (void) env;
    (void) self;
    int fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (fd < 0) return JNI_FALSE;
    const bool ready = grantpt(fd) == 0 && unlockpt(fd) == 0;
    close(fd);
    return ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeOpen(
    JNIEnv *env,
    jobject self,
    jobjectArray command,
    jstring working_directory,
    jobjectArray environment,
    jint rows,
    jint columns
) {
    (void) self;
    if (command == NULL || (*env)->GetArrayLength(env, command) == 0) {
        errno = EINVAL;
        throw_io(env, "PTY 命令为空");
        return 0;
    }

    jsize argc = 0;
    jsize envc = 0;
    char **argv = copy_string_array(env, command, &argc);
    if (argv == NULL) return 0;
    char **env_entries = copy_string_array(env, environment, &envc);
    if (env_entries == NULL) {
        free_strings(argv, argc);
        return 0;
    }
    char *cwd = copy_utf(env, working_directory);
    if (working_directory != NULL && cwd == NULL) {
        free_strings(argv, argc);
        free_strings(env_entries, envc);
        if (!(*env)->ExceptionCheck(env)) {
            errno = ENOMEM;
            throw_io(env, "无法复制 PTY 工作目录");
        }
        return 0;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        free(cwd);
        free_strings(argv, argc);
        free_strings(env_entries, envc);
        throw_io(env, "无法创建 PTY");
        return 0;
    }

    char slave_name[256];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
        free(cwd);
        free_strings(argv, argc);
        free_strings(env_entries, envc);
        throw_io(env, "无法解析 PTY slave");
        return 0;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        free(cwd);
        free_strings(argv, argc);
        free_strings(env_entries, envc);
        throw_io(env, "无法 fork PTY 子进程");
        return 0;
    }

    if (pid == 0) {
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        if (ioctl(slave, TIOCSCTTY, 0) != 0) {
            close(slave);
            _exit(126);
        }
        (void) set_window_size(slave, columns, rows);

        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) {
            close(slave);
            _exit(126);
        }
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        if (cwd != NULL && chdir(cwd) != 0) _exit(126);
        apply_environment(env_entries);

        if (strchr(argv[0], '/') != NULL) {
            execv(argv[0], argv);
        } else {
            execvp(argv[0], argv);
        }
        static const char message[] = "dshpty: exec failed\r\n";
        (void) write(STDERR_FILENO, message, sizeof(message) - 1U);
        _exit(127);
    }

    free(cwd);
    free_strings(argv, argc);
    free_strings(env_entries, envc);

    int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) (void) fcntl(master, F_SETFL, flags | O_NONBLOCK);

    dsh_pty_handle *handle = calloc(1U, sizeof(*handle));
    if (handle == NULL) {
        const int saved = errno;
        (void) kill(-pid, SIGKILL);
        (void) waitpid(pid, NULL, 0);
        close(master);
        errno = saved;
        throw_io(env, "无法分配 PTY 会话");
        return 0;
    }
    handle->master_fd = master;
    handle->pid = pid;
    handle->reaped = false;
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jbyteArray JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeRead(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jint max_bytes
) {
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL || max_bytes <= 0) {
        errno = EINVAL;
        throw_io(env, "PTY 读取参数无效");
        return NULL;
    }

    unsigned char *buffer = malloc((size_t) max_bytes);
    if (buffer == NULL) {
        errno = ENOMEM;
        throw_io(env, "无法分配 PTY 读取缓冲");
        return NULL;
    }

    ssize_t count;
    do {
        count = read(handle->master_fd, buffer, (size_t) max_bytes);
    } while (count < 0 && errno == EINTR);

    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EIO)) {
        count = 0;
    } else if (count < 0) {
        free(buffer);
        throw_io(env, "读取 PTY 失败");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) count);
    if (result != NULL && count > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) count, (const jbyte *) buffer);
    }
    free(buffer);
    return result;
}

JNIEXPORT void JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeWrite(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jbyteArray bytes
) {
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL || bytes == NULL) {
        errno = EINVAL;
        throw_io(env, "PTY 写入参数无效");
        return;
    }

    const jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) return;

    size_t offset = 0;
    while (offset < (size_t) length) {
        ssize_t written = write(
            handle->master_fd,
            (const unsigned char *) data + offset,
            (size_t) length - offset
        );
        if (written > 0) {
            offset += (size_t) written;
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd descriptor = {
                .fd = handle->master_fd,
                .events = POLLOUT,
                .revents = 0,
            };
            int ready;
            do {
                ready = poll(&descriptor, 1, 1000);
            } while (ready < 0 && errno == EINTR);
            if (ready > 0) continue;
            if (ready == 0) errno = ETIMEDOUT;
        }
        (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
        throw_io(env, "写入 PTY 失败");
        return;
    }

    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeResize(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jint columns,
    jint rows
) {
    (void) env;
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL || columns <= 0 || rows <= 0) return JNI_FALSE;
    if (!set_window_size(handle->master_fd, columns, rows)) return JNI_FALSE;
    (void) kill(-handle->pid, SIGWINCH);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeIsAlive(
    JNIEnv *env,
    jobject self,
    jlong raw_handle
) {
    (void) env;
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL || handle->reaped) return JNI_FALSE;

    int status = 0;
    pid_t result;
    do {
        result = waitpid(handle->pid, &status, WNOHANG);
    } while (result < 0 && errno == EINTR);

    if (result == 0) return JNI_TRUE;
    if (result == handle->pid || (result < 0 && errno == ECHILD)) {
        handle->reaped = true;
        return JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeHasReadable(
    JNIEnv *env,
    jobject self,
    jlong raw_handle
) {
    (void) env;
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL || handle->master_fd < 0) return JNI_FALSE;
    int available = 0;
    if (ioctl(handle->master_fd, FIONREAD, &available) == 0 && available > 0) {
        return JNI_TRUE;
    }
    struct pollfd descriptor = {
        .fd = handle->master_fd,
        .events = POLLIN,
        .revents = 0,
    };
    int ready;
    do {
        ready = poll(&descriptor, 1, 0);
    } while (ready < 0 && errno == EINTR);
    return ready > 0 && (descriptor.revents & POLLIN) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_labteto_dshmobile_runtime_NativePtyBridge_nativeClose(
    JNIEnv *env,
    jobject self,
    jlong raw_handle
) {
    (void) env;
    (void) self;
    dsh_pty_handle *handle = (dsh_pty_handle *) (intptr_t) raw_handle;
    if (handle == NULL) return;

    if (handle->master_fd >= 0) {
        (void) close(handle->master_fd);
        handle->master_fd = -1;
    }

    if (!handle->reaped && handle->pid > 0) {
        (void) kill(-handle->pid, SIGHUP);
        (void) kill(-handle->pid, SIGTERM);
        for (int attempt = 0; attempt < 20; ++attempt) {
            int status = 0;
            pid_t result = waitpid(handle->pid, &status, WNOHANG);
            if (result == handle->pid || (result < 0 && errno == ECHILD)) {
                handle->reaped = true;
                break;
            }
            usleep(10000);
        }
        if (!handle->reaped) {
            (void) kill(-handle->pid, SIGKILL);
            (void) waitpid(handle->pid, NULL, 0);
            handle->reaped = true;
        }
    }

    free(handle);
}
