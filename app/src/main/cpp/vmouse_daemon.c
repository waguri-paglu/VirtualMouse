/*
 * Unified Real-Time Virtual Mouse Engine (Direct EV_ABS Digitizer + JNI Bridge)
 * Eliminates Android TouchInputMapper cursor hiding/teleporting by pairing a
 * 120Hz hardware-accelerated overlay arrow with a direct 0..32767 EV_ABS uinput device.
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sched.h>
#include <jni.h>
#include <sys/time.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "VMouseDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stdout, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

#define FAST_UDP_PORT 29876

#define CMD_DRAG_MOVE 1
#define CMD_TOUCH_DOWN 2
#define CMD_TOUCH_UP   3
#define CMD_LEFT_CLICK 4
#define CMD_RIGHT_CLICK 5
#define CMD_SCROLL_STEP 6
#define CMD_PING       7
#define CMD_QUIT       99

struct vmouse_packet {
    int32_t cmd;
    int32_t arg1; // absX (0..32767) or scroll delta
    int32_t arg2; // absY (0..32767)
};

/* ============================================================================
 * PART 1: JNI Client Fast-Path (Used in-process by Android App)
 * ============================================================================ */
static int client_sock = -1;
static struct sockaddr_in daemon_addr;

static void ensure_client_socket(void) {
    if (client_sock >= 0) return;

    client_sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (client_sock < 0) return;

    int sndbuf = 262144;
    setsockopt(client_sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    memset(&daemon_addr, 0, sizeof(daemon_addr));
    daemon_addr.sin_family = AF_INET;
    daemon_addr.sin_port = htons(FAST_UDP_PORT);
    daemon_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
}

JNIEXPORT jboolean JNICALL
Java_com_virtualmouse_app_RootMouseController_nativeSendPacket(
    JNIEnv *env,
    jclass clazz,
    jint cmd,
    jint arg1,
    jint arg2
) {
    (void)env;
    (void)clazz;

    ensure_client_socket();
    if (client_sock < 0) return JNI_FALSE;

    struct vmouse_packet pkt;
    pkt.cmd = (int32_t)cmd;
    pkt.arg1 = (int32_t)arg1;
    pkt.arg2 = (int32_t)arg2;

    ssize_t sent = sendto(
        client_sock,
        &pkt,
        sizeof(pkt),
        MSG_DONTWAIT,
        (const struct sockaddr *)&daemon_addr,
        sizeof(daemon_addr)
    );

    if (cmd == CMD_PING && sent == sizeof(pkt)) {
        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 40000;
        setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        int32_t pong = 0;
        ssize_t r = recv(client_sock, &pong, sizeof(pong), 0);
        return (r == sizeof(pong) && pong == 1) ? JNI_TRUE : JNI_FALSE;
    }

    return (sent == sizeof(pkt)) ? JNI_TRUE : JNI_FALSE;
}

/* ============================================================================
 * PART 2: Root Kernel /dev/uinput Direct Digitizer Daemon
 * ============================================================================ */
static int uinput_fd = -1;
static int sock_fd = -1;
static volatile int keep_running = 1;

static inline int clamp_abs(int val) {
    if (val < 0) return 0;
    if (val > 32767) return 32767;
    return val;
}

static void emit_abs_down(int fd, int abs_x, int abs_y, int is_right) {
    struct input_event ev[6];
    int count = 0;
    memset(ev, 0, sizeof(ev));

    ev[count].type = EV_ABS;
    ev[count].code = ABS_X;
    ev[count].value = clamp_abs(abs_x);
    count++;

    ev[count].type = EV_ABS;
    ev[count].code = ABS_Y;
    ev[count].value = clamp_abs(abs_y);
    count++;

    ev[count].type = EV_KEY;
    ev[count].code = BTN_TOUCH;
    ev[count].value = 1;
    count++;

    if (is_right) {
        ev[count].type = EV_KEY;
        ev[count].code = BTN_STYLUS2;
        ev[count].value = 1;
        count++;

        ev[count].type = EV_KEY;
        ev[count].code = BTN_RIGHT;
        ev[count].value = 1;
        count++;
    }

    ev[count].type = EV_SYN;
    ev[count].code = SYN_REPORT;
    ev[count].value = 0;
    count++;

    if (write(fd, ev, sizeof(struct input_event) * count) < 0) {}
}

static void emit_abs_move(int fd, int abs_x, int abs_y) {
    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));

    ev[0].type = EV_ABS;
    ev[0].code = ABS_X;
    ev[0].value = clamp_abs(abs_x);

    ev[1].type = EV_ABS;
    ev[1].code = ABS_Y;
    ev[1].value = clamp_abs(abs_y);

    ev[2].type = EV_SYN;
    ev[2].code = SYN_REPORT;
    ev[2].value = 0;

    if (write(fd, ev, sizeof(ev)) < 0) {}
}

static void emit_abs_up(int fd, int is_right) {
    struct input_event ev[4];
    int count = 0;
    memset(ev, 0, sizeof(ev));

    ev[count].type = EV_KEY;
    ev[count].code = BTN_TOUCH;
    ev[count].value = 0;
    count++;

    if (is_right) {
        ev[count].type = EV_KEY;
        ev[count].code = BTN_STYLUS2;
        ev[count].value = 0;
        count++;

        ev[count].type = EV_KEY;
        ev[count].code = BTN_RIGHT;
        ev[count].value = 0;
        count++;
    }

    ev[count].type = EV_SYN;
    ev[count].code = SYN_REPORT;
    ev[count].value = 0;
    count++;

    if (write(fd, ev, sizeof(struct input_event) * count) < 0) {}
}

void cleanup_device(void) {
    if (sock_fd >= 0) {
        close(sock_fd);
        sock_fd = -1;
    }
    if (uinput_fd >= 0) {
        LOGI("Destroying virtual mouse device");
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
}

void signal_handler(int sig) {
    (void)sig;
    keep_running = 0;
    cleanup_device();
    exit(0);
}

int init_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (fd < 0) {
        LOGE("Cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    // Direct screen digitizer property: maps 0..32767 directly to screen pixels
    // and prevents Android PointerController from spawning a second freezing/teleporting cursor!
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_PEN);
    ioctl(fd, UI_SET_KEYBIT, BTN_STYLUS);
    ioctl(fd, UI_SET_KEYBIT, BTN_STYLUS2);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);

    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Virtual Mouse Direct Digitizer");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x18d1;
    uidev.id.product = 0x4ee7;
    uidev.id.version = 1;

    uidev.absmin[ABS_X] = 0;
    uidev.absmax[ABS_X] = 32767;
    uidev.absmin[ABS_Y] = 0;
    uidev.absmax[ABS_Y] = 32767;

    if (write(fd, &uidev, sizeof(uidev)) < 0) {
        LOGE("Failed to write uinput_user_dev: %s", strerror(errno));
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed to ioctl UI_DEV_CREATE: %s", strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("Virtual direct digitizer device created in kernel!");
    return fd;
}

int init_fast_socket(void) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("Failed to create UDP socket: %s", strerror(errno));
        return -1;
    }

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    int rcvbuf = 262144;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(FAST_UDP_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind loopback UDP socket: %s", strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("Bound fast loopback UDP socket on 127.0.0.1:%d", FAST_UDP_PORT);
    return fd;
}

static void handle_packet(
    const struct vmouse_packet *pkt,
    const struct sockaddr_in *client_addr,
    socklen_t client_len
) {
    switch (pkt->cmd) {
        case CMD_DRAG_MOVE:
            emit_abs_move(uinput_fd, pkt->arg1, pkt->arg2);
            break;

        case CMD_TOUCH_DOWN:
            emit_abs_down(uinput_fd, pkt->arg1, pkt->arg2, 0);
            break;

        case CMD_TOUCH_UP:
            emit_abs_up(uinput_fd, 0);
            break;

        case CMD_LEFT_CLICK:
            emit_abs_down(uinput_fd, pkt->arg1, pkt->arg2, 0);
            usleep(14000);
            emit_abs_up(uinput_fd, 0);
            break;

        case CMD_RIGHT_CLICK:
            // Trigger context menu at exact cursor coordinates via long-press / stylus secondary
            emit_abs_down(uinput_fd, pkt->arg1, pkt->arg2, 1);
            usleep(520000); // 520ms native Android context-menu hold at cursor position
            emit_abs_up(uinput_fd, 1);
            break;

        case CMD_PING:
            if (client_addr != NULL && client_len > 0) {
                int32_t pong = 1;
                sendto(sock_fd, &pong, sizeof(pong), MSG_DONTWAIT,
                       (const struct sockaddr *)client_addr, client_len);
            }
            break;

        case CMD_QUIT:
            keep_running = 0;
            break;

        default:
            break;
    }
}

int main(int argc, char *argv[]) {
    (void)argc;
    (void)argv;

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    setpriority(PRIO_PROCESS, 0, -20);
    struct sched_param sp;
    memset(&sp, 0, sizeof(sp));
    sp.sched_priority = 50;
    sched_setscheduler(0, SCHED_FIFO, &sp);

    uinput_fd = init_uinput();
    if (uinput_fd < 0) {
        return 1;
    }

    sock_fd = init_fast_socket();
    if (sock_fd < 0) {
        cleanup_device();
        return 1;
    }

    printf("READY\n");
    fflush(stdout);

    struct vmouse_packet pkt;
    struct sockaddr_in client_addr;
    socklen_t client_len = sizeof(client_addr);

    while (keep_running) {
        client_len = sizeof(client_addr);
        ssize_t n = recvfrom(sock_fd, &pkt, sizeof(pkt), 0,
                             (struct sockaddr *)&client_addr, &client_len);
        if (n != sizeof(pkt)) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            break;
        }
        handle_packet(&pkt, &client_addr, client_len);
    }

    cleanup_device();
    LOGI("VMouse daemon terminated normally.");
    return 0;
}
