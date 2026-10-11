// Gamepads of the app as evdev devices for the Linux programs (FEX patch 0006,
// patches/fex/0006-android-gamepads.patch, AndroidInputPad.h there).
//
// Each pad slot is a SOCK_SEQPACKET socket <dir>/event<200 + slot>; FEX shows <dir> to the
// guests as /dev/input. A guest that opens the node connects here and first sends
// { u32 magic "FXP1", u32 sizeof(struct input_event) } (24 for x86-64, 16 for i386); from
// then on every packet we send is a run of input_events in that layout ending with
// SYN_REPORT. What guests write (force feedback, EV_MSC) is read and dropped.
// FEX answers the device's ioctls itself (an Xbox 360 pad), so the events sent here must
// use that pad's codes: BTN_A.., ABS_X/Y/RX/RY (-32768..32767), ABS_Z/RZ (0..255), ABS_HAT0X/Y.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include <android/log.h>

#define TAG "fexdroid-pad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define MAX_PADS 4
#define MAX_CLIENTS 8
#define MAX_EVENTS 64
#define FIRST_NODE 200
#define HELLO_MAGIC 0x31505846u // "FXP1"

struct client {
    int fd;
    int event_size; // 0 until the hello arrived
};

struct pad {
    int listen_fd; // -1 = slot closed
    struct client clients[MAX_CLIENTS];
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct pad pads[MAX_PADS];
static char dir[256];
static int wake[2] = {-1, -1};
static int running;
static pthread_t thread;

static void node_path(int slot, char *out, size_t len) {
    snprintf(out, len, "%s/event%d", dir, FIRST_NODE + slot);
}

static void drop_client(struct client *c) {
    if (c->fd >= 0) close(c->fd);
    c->fd = -1;
    c->event_size = 0;
}

static void poke(void) {
    if (wake[1] >= 0) {
        char b = 1;
        (void)!write(wake[1], &b, 1);
    }
}

static void *serve(void *arg) {
    (void)arg;
    for (;;) {
        struct pollfd fds[1 + MAX_PADS * (1 + MAX_CLIENTS)];
        int who[1 + MAX_PADS * (1 + MAX_CLIENTS)]; // slot * 16 + (client index + 1), 0 = listener
        int n = 0;
        pthread_mutex_lock(&lock);
        if (!running) { pthread_mutex_unlock(&lock); break; }
        fds[n] = (struct pollfd){wake[0], POLLIN, 0}; who[n++] = -1;
        for (int s = 0; s < MAX_PADS; s++) {
            if (pads[s].listen_fd < 0) continue;
            fds[n] = (struct pollfd){pads[s].listen_fd, POLLIN, 0}; who[n++] = s * 16;
            for (int c = 0; c < MAX_CLIENTS; c++) {
                if (pads[s].clients[c].fd < 0) continue;
                fds[n] = (struct pollfd){pads[s].clients[c].fd, POLLIN, 0}; who[n++] = s * 16 + c + 1;
            }
        }
        pthread_mutex_unlock(&lock);

        if (poll(fds, n, -1) < 0 && errno != EINTR) break;

        pthread_mutex_lock(&lock);
        for (int i = 0; i < n; i++) {
            if (!fds[i].revents) continue;
            if (who[i] < 0) {
                char buf[64];
                (void)!read(wake[0], buf, sizeof buf);
                continue;
            }
            const int s = who[i] / 16, c = who[i] % 16 - 1;
            struct pad *p = &pads[s];
            if (c < 0) {
                if (p->listen_fd != fds[i].fd) continue; // Closed meanwhile.
                const int fd = accept4(p->listen_fd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
                if (fd < 0) continue;
                int k = 0;
                while (k < MAX_CLIENTS && p->clients[k].fd >= 0) k++;
                if (k == MAX_CLIENTS) { close(fd); continue; }
                p->clients[k] = (struct client){fd, 0};
                continue;
            }
            struct client *cl = &p->clients[c];
            if (cl->fd != fds[i].fd) continue;
            uint32_t msg[64];
            const ssize_t got = recv(cl->fd, msg, sizeof msg, MSG_DONTWAIT);
            if (got == 0 || (got < 0 && errno != EAGAIN && errno != EINTR)) {
                drop_client(cl);
            } else if (got >= 8 && cl->event_size == 0) {
                if (msg[0] == HELLO_MAGIC && (msg[1] == 16 || msg[1] == 24)) {
                    cl->event_size = (int)msg[1];
                    LOGI("pad %d: a %d-bit program opened it", s, msg[1] == 24 ? 64 : 32);
                } else {
                    drop_client(cl);
                }
            }
            // Anything else is a write of the guest (force feedback...): dropped.
        }
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

static void close_slot(int s) {
    struct pad *p = &pads[s];
    for (int c = 0; c < MAX_CLIENTS; c++) drop_client(&p->clients[c]);
    if (p->listen_fd >= 0) {
        close(p->listen_fd);
        char path[300];
        node_path(s, path, sizeof path);
        unlink(path);
    }
    p->listen_fd = -1;
}

JNIEXPORT jstring JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_start(JNIEnv *env, jclass cls, jstring jdir) {
    (void)cls;
    const char *d = (*env)->GetStringUTFChars(env, jdir, NULL);
    pthread_mutex_lock(&lock);
    if (running) {
        pthread_mutex_unlock(&lock);
        (*env)->ReleaseStringUTFChars(env, jdir, d);
        return (*env)->NewStringUTF(env, "Gamepads: already on");
    }
    snprintf(dir, sizeof dir, "%s", d);
    (*env)->ReleaseStringUTFChars(env, jdir, d);
    mkdir(dir, 0700);
    for (int s = 0; s < MAX_PADS; s++) {
        pads[s].listen_fd = -1;
        for (int c = 0; c < MAX_CLIENTS; c++) pads[s].clients[c] = (struct client){-1, 0};
        char path[300];
        node_path(s, path, sizeof path);
        unlink(path); // Left over by an earlier run of the app.
    }
    if (pipe2(wake, O_CLOEXEC | O_NONBLOCK) != 0) {
        pthread_mutex_unlock(&lock);
        return (*env)->NewStringUTF(env, "Gamepads: pipe failed");
    }
    running = 1;
    pthread_create(&thread, NULL, serve, NULL);
    pthread_mutex_unlock(&lock);
    return (*env)->NewStringUTF(env, "Gamepads: on");
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_stop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    pthread_mutex_lock(&lock);
    if (!running) { pthread_mutex_unlock(&lock); return; }
    running = 0;
    for (int s = 0; s < MAX_PADS; s++) close_slot(s);
    poke();
    pthread_mutex_unlock(&lock);
    pthread_join(thread, NULL);
    close(wake[0]); close(wake[1]);
    wake[0] = wake[1] = -1;
}

/** Makes slot `slot` appear as /dev/input/event<200 + slot>. */
JNIEXPORT jboolean JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_open(JNIEnv *env, jclass cls, jint slot) {
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return JNI_FALSE;
    pthread_mutex_lock(&lock);
    if (!running || pads[slot].listen_fd >= 0) {
        const jboolean ok = pads[slot].listen_fd >= 0;
        pthread_mutex_unlock(&lock);
        return ok;
    }
    struct sockaddr_un a = {.sun_family = AF_UNIX};
    node_path(slot, a.sun_path, sizeof a.sun_path);
    unlink(a.sun_path);
    const int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0 || bind(fd, (struct sockaddr *)&a, sizeof a) != 0 || listen(fd, 4) != 0) {
        LOGI("pad %d: cannot listen on %s: %s", slot, a.sun_path, strerror(errno));
        if (fd >= 0) close(fd);
        pthread_mutex_unlock(&lock);
        return JNI_FALSE;
    }
    pads[slot].listen_fd = fd;
    poke();
    pthread_mutex_unlock(&lock);
    LOGI("pad %d: %s", slot, a.sun_path);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_close(JNIEnv *env, jclass cls, jint slot) {
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return;
    pthread_mutex_lock(&lock);
    close_slot(slot);
    poke();
    pthread_mutex_unlock(&lock);
}

/** Sends `count` (type, code, value) triplets plus SYN_REPORT to every program that has the pad open. */
JNIEXPORT void JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_send(JNIEnv *env, jclass cls, jint slot, jintArray jev, jint count) {
    (void)cls;
    if (slot < 0 || slot >= MAX_PADS || count <= 0) return;
    if (count > MAX_EVENTS - 1) count = MAX_EVENTS - 1;
    jint ev[MAX_EVENTS * 3];
    (*env)->GetIntArrayRegion(env, jev, 0, count * 3, ev);
    ev[count * 3] = 0; ev[count * 3 + 1] = 0; ev[count * 3 + 2] = 0; // EV_SYN / SYN_REPORT
    const int total = count + 1;
    struct timeval tv;
    gettimeofday(&tv, NULL);

    // Both layouts: { time (2 x long), u16 type, u16 code, s32 value }.
    uint8_t out64[MAX_EVENTS * 24], out32[MAX_EVENTS * 16];
    for (int i = 0; i < total; i++) {
        const uint16_t type = (uint16_t)ev[i * 3], code = (uint16_t)ev[i * 3 + 1];
        const int32_t value = ev[i * 3 + 2];
        const int64_t s64 = tv.tv_sec, u64 = tv.tv_usec;
        const uint32_t s32 = (uint32_t)tv.tv_sec, u32 = (uint32_t)tv.tv_usec;
        uint8_t *a = out64 + i * 24, *b = out32 + i * 16;
        memcpy(a, &s64, 8); memcpy(a + 8, &u64, 8);
        memcpy(a + 16, &type, 2); memcpy(a + 18, &code, 2); memcpy(a + 20, &value, 4);
        memcpy(b, &s32, 4); memcpy(b + 4, &u32, 4);
        memcpy(b + 8, &type, 2); memcpy(b + 10, &code, 2); memcpy(b + 12, &value, 4);
    }
    pthread_mutex_lock(&lock);
    for (int c = 0; c < MAX_CLIENTS; c++) {
        struct client *cl = &pads[slot].clients[c];
        if (cl->fd < 0 || cl->event_size == 0) continue;
        const void *buf = cl->event_size == 24 ? (const void *)out64 : (const void *)out32;
        // A program that does not read loses events rather than holding up the others.
        if (send(cl->fd, buf, (size_t)total * cl->event_size, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
            errno != EAGAIN && errno != EWOULDBLOCK) {
            drop_client(cl);
        }
    }
    pthread_mutex_unlock(&lock);
}

/** Programs that have pad `slot` open right now (for the report). */
JNIEXPORT jint JNICALL
Java_ro_cobrabm_fexdroid_PadBridge_readers(JNIEnv *env, jclass cls, jint slot) {
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return 0;
    int n = 0;
    pthread_mutex_lock(&lock);
    for (int c = 0; c < MAX_CLIENTS; c++) n += pads[slot].clients[c].fd >= 0 && pads[slot].clients[c].event_size;
    pthread_mutex_unlock(&lock);
    return n;
}
