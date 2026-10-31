/*
 * Ultra-Low-Latency Virtual Mouse uinput Daemon for Rooted Android
 * Listens on 127.0.0.1:29876 (UDP loopback) for 12-byte binary packets from JNI,
 * coalesces bursts in C, and writes batched input_event frames directly to /dev/uinput.
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

#define CMD_MOVE     1
#define CMD_DOWN     2
#define CMD_UP       3
#define CMD_CLICK    4
#define CMD_SCROLL_V 5
#define CMD_SCROLL_H 6
#define CMD_PING     7
#define CMD_QUIT     99

struct vmouse_packet {
    int32_t cmd;
    int32_t arg1;
    int32_t arg2;
};

static int uinput_fd = -1;
static int sock_fd = -1;
static volatile int keep_running = 1;

static inline void emit_single_with_syn(int fd, int type, int code, int val) {
    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));
    ev[0].type = type;
    ev[0].code = code;
    ev[0].value = val;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    if (write(fd, ev, sizeof(ev)) < 0) {
        // Ignore transient write errors
    }
}

static inline void emit_move_batched(int fd, int dx, int dy) {
    if (dx == 0 && dy == 0) return;
    struct input_event ev[3];
    int count = 0;
    memset(ev, 0, sizeof(ev));

    if (dx != 0) {
        ev[count].type = EV_REL;
        ev[count].code = REL_X;
        ev[count].value = dx;
        count++;
    }
    if (dy != 0) {
        ev[count].type = EV_REL;
        ev[count].code = REL_Y;
        ev[count].value = dy;
        count++;
    }
    ev[count].type = EV_SYN;
    ev[count].code = SYN_REPORT;
    ev[count].value = 0;
    count++;

    if (write(fd, ev, sizeof(struct input_event) * count) < 0) {
        // Ignore transient write errors
    }
}

void cleanup_device() {
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

int init_uinput() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (fd < 0) {
        LOGE("Cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    // Enable relative events
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);

    // Enable button keys
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_KEYBIT, BTN_SIDE);
    ioctl(fd, UI_SET_KEYBIT, BTN_EXTRA);

    // Enable sync events
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Virtual Mobile Trackpad Mouse");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

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

    LOGI("Virtual mouse device successfully created in kernel!");

    // Wake up Android PointerController immediately
    usleep(40000);
    emit_move_batched(fd, 1, 1);

    return fd;
}

int init_fast_socket() {
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

static inline int map_button(int btn) {
    switch (btn) {
        case 1: return BTN_LEFT;
        case 2: return BTN_RIGHT;
        case 3: return BTN_MIDDLE;
        default: return BTN_LEFT;
    }
}

static void execute_non_move_packet(
    const struct vmouse_packet *pkt,
    const struct sockaddr_in *client_addr,
    socklen_t client_len
) {
    switch (pkt->cmd) {
        case CMD_DOWN:
            emit_single_with_syn(uinput_fd, EV_KEY, map_button(pkt->arg1), 1);
            break;
        case CMD_UP:
            emit_single_with_syn(uinput_fd, EV_KEY, map_button(pkt->arg1), 0);
            break;
        case CMD_CLICK: {
            int code = map_button(pkt->arg1);
            emit_single_with_syn(uinput_fd, EV_KEY, code, 1);
            usleep(6000);
            emit_single_with_syn(uinput_fd, EV_KEY, code, 0);
            break;
        }
        case CMD_SCROLL_V:
            if (pkt->arg1 != 0) {
                emit_single_with_syn(uinput_fd, EV_REL, REL_WHEEL, pkt->arg1);
            }
            break;
        case CMD_SCROLL_H:
            if (pkt->arg1 != 0) {
                emit_single_with_syn(uinput_fd, EV_REL, REL_HWHEEL, pkt->arg1);
            }
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

    // Elevate scheduling priority to maximum real-time responsiveness
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
    struct vmouse_packet next_pkt;
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

        int32_t accum_dx = 0;
        int32_t accum_dy = 0;

        if (pkt.cmd == CMD_MOVE) {
            accum_dx += pkt.arg1;
            accum_dy += pkt.arg2;
        } else {
            execute_non_move_packet(&pkt, &client_addr, client_len);
        }

        // Immediately drain any additional packets already queued in the kernel socket buffer
        while (keep_running) {
            client_len = sizeof(client_addr);
            ssize_t m = recvfrom(sock_fd, &next_pkt, sizeof(next_pkt), MSG_DONTWAIT,
                                 (struct sockaddr *)&client_addr, &client_len);
            if (m != sizeof(next_pkt)) {
                break;
            }
            if (next_pkt.cmd == CMD_MOVE) {
                accum_dx += next_pkt.arg1;
                accum_dy += next_pkt.arg2;
            } else {
                if (accum_dx != 0 || accum_dy != 0) {
                    emit_move_batched(uinput_fd, accum_dx, accum_dy);
                    accum_dx = 0;
                    accum_dy = 0;
                }
                execute_non_move_packet(&next_pkt, &client_addr, client_len);
            }
        }

        // Emit coalesced movement delta in a single syscall
        if (accum_dx != 0 || accum_dy != 0) {
            emit_move_batched(uinput_fd, accum_dx, accum_dy);
        }
    }

    cleanup_device();
    LOGI("VMouse daemon terminated normally.");
    return 0;
}
