#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <string>
#include <map>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>

extern char** environ;

static jlong pack_handle(pid_t pid, int fd) {
    return (static_cast<jlong>(static_cast<uint32_t>(pid)) << 32) |
           static_cast<uint32_t>(fd);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeSpawn(
    JNIEnv* env, jobject, jstring executable, jobjectArray arguments,
    jstring working_directory, jint rows, jint columns, jboolean inherit_environment, jobjectArray environment) {
    if (!executable || !working_directory || rows < 1 || columns < 1) return 0;
    const char* exe = env->GetStringUTFChars(executable, nullptr);
    const char* cwd = env->GetStringUTFChars(working_directory, nullptr);
    if (!exe || !cwd) {
        if (exe) env->ReleaseStringUTFChars(executable, exe);
        if (cwd) env->ReleaseStringUTFChars(working_directory, cwd);
        return 0;
    }

    std::vector<std::string> args;
    args.emplace_back(exe);
    const jsize count = arguments ? env->GetArrayLength(arguments) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        if (!item) continue;
        const char* value = env->GetStringUTFChars(item, nullptr);
        if (value) {
            args.emplace_back(value);
            env->ReleaseStringUTFChars(item, value);
        }
        env->DeleteLocalRef(item);
    }
    std::vector<char*> argv;
    for (auto& value : args) argv.push_back(value.data());
    argv.push_back(nullptr);

    std::map<std::string, std::string> env_map;
    if (inherit_environment) for (char** item = environ; item && *item; ++item) {
        std::string entry(*item);
        const auto separator = entry.find('=');
        if (separator != std::string::npos && separator > 0)
            env_map[entry.substr(0, separator)] = entry.substr(separator + 1);
    }
    const jsize env_count = environment ? env->GetArrayLength(environment) : 0;
    for (jsize i = 0; i < env_count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(environment, i));
        if (!item) continue;
        const char* value = env->GetStringUTFChars(item, nullptr);
        if (value) {
            std::string entry(value);
            const auto separator = entry.find('=');
            if (separator != std::string::npos && separator > 0)
                env_map[entry.substr(0, separator)] = entry.substr(separator + 1);
            env->ReleaseStringUTFChars(item, value);
        }
        env->DeleteLocalRef(item);
    }
    std::vector<std::string> env_storage;
    std::vector<char*> envp;
    env_storage.reserve(env_map.size());
    for (const auto& pair : env_map) env_storage.push_back(pair.first + "=" + pair.second);
    for (auto& value : env_storage) envp.push_back(value.data());
    envp.push_back(nullptr);

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        env->ReleaseStringUTFChars(executable, exe);
        env->ReleaseStringUTFChars(working_directory, cwd);
        return 0;
    }
    char slave_name[256] = {};
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
        env->ReleaseStringUTFChars(executable, exe);
        env->ReleaseStringUTFChars(working_directory, cwd);
        return 0;
    }

    struct winsize size {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(master, TIOCSWINSZ, &size);

    pid_t pid = fork();
    if (pid == 0) {
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        if (ioctl(slave, TIOCSCTTY, 0) != 0) _exit(126);
        if (dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) _exit(126);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(cwd) != 0) _exit(126);
        execve(exe, argv.data(), envp.data());
        _exit(errno == ENOENT ? 127 : 126);
    }

    env->ReleaseStringUTFChars(executable, exe);
    env->ReleaseStringUTFChars(working_directory, cwd);
    if (pid < 0) {
        close(master);
        return 0;
    }
    return pack_handle(pid, master);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeRead(
    JNIEnv* env, jobject, jint fd, jbyteArray output) {
    if (fd < 0 || !output) return -EBADF;
    const jsize length = env->GetArrayLength(output);
    if (length == 0) return 0;
    std::vector<jbyte> buffer(static_cast<size_t>(length));
    ssize_t count;
    do { count = read(fd, buffer.data(), buffer.size()); } while (count < 0 && errno == EINTR);
    // Linux PTY masters report EIO after the slave side closes. This is the
    // normal end-of-stream condition when the guest shell exits, not a transport
    // failure that should surface as an exception in the terminal read loop.
    if (count < 0 && errno == EIO) return 0;
    if (count < 0) return -errno;
    if (count > 0) env->SetByteArrayRegion(output, 0, static_cast<jsize>(count), buffer.data());
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeWrite(
    JNIEnv* env, jobject, jint fd, jbyteArray input) {
    if (fd < 0 || !input) return -EBADF;
    const jsize length = env->GetArrayLength(input);
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return -ENOMEM;
    ssize_t total = 0;
    while (total < length) {
        ssize_t count = write(fd, bytes + total, static_cast<size_t>(length - total));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) {
            int error = errno;
            env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
            return -error;
        }
        if (count == 0) {
            env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
            return -EIO;
        }
        total += count;
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    return static_cast<jint>(total);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeResize(
    JNIEnv*, jobject, jint fd, jint rows, jint columns) {
    if (fd < 0 || rows < 1 || columns < 1) return -EINVAL;
    struct winsize size {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    return ioctl(fd, TIOCSWINSZ, &size) == 0 ? 0 : -errno;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeSignal(
    JNIEnv*, jobject, jint pid, jint signal_number) {
    if (pid <= 0 || signal_number <= 0) return -EINVAL;
    return kill(-pid, signal_number) == 0 ? 0 : -errno;
}

extern "C" JNIEXPORT void JNICALL
Java_org_anlr_linuxruntime_NativePty_nativeClose(
    JNIEnv*, jobject, jint fd, jint pid) {
    if (pid > 0) kill(-pid, SIGHUP);
    if (fd >= 0) close(fd);
    if (pid > 0) {
        int status = 0;
        for (int i = 0; i < 10; ++i) {
            pid_t result = waitpid(pid, &status, WNOHANG);
            if (result == pid || (result < 0 && errno == ECHILD)) return;
            usleep(10 * 1000);
        }
        kill(-pid, SIGKILL);
        pid_t result;
        do {
            result = waitpid(pid, &status, 0);
        } while (result < 0 && errno == EINTR);
    }
}
