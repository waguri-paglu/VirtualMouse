/*
 * Ultra-Low-Latency Virtual Mouse uinput Daemon for Rooted Android
 * Uses batched single-syscall kernel writes and high-priority scheduling.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/time.h>
#include <sys/resource.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "VMouseDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int uinput_fd = -1;
static volatile int keep_running = 1;

static inline void emit_single_With_syn(int fd, int type, int code, int val) {
    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));
    ev[0].type = type;
    ev[0].code = code;
    ev[0].value = val;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    write(fd, ev, sizeof(ev));
}

static inline void emit_move_batched(int fd, int dx, int dy) {
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
    if (count > 0) {
        ev[count].type = EV_SYN;
        ev[count].code = SYN_REPORT;
        ev[count].value = 0;
        count++;
        write(fd, ev, sizeof(struct input_event) * count);
    }
}

void cleanup_device() {
    if (uinput_fd >= 0) {
        LOGI("Destroying virtual mouse device");
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
}

void signal_handler(int sig) {
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
        printf("ERROR_OPEN_UINPUT\n");
        fflush(stdout);
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
        printf("ERROR_WRITE_UIDEV\n");
        fflush(stdout);
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("Failed to ioctl UI_DEV_CREATE: %s", strerror(errno));
        printf("ERROR_IOCTL_CREATE\n");
        fflush(stdout);
        close(fd);
        return -1;
    }

    LOGI("Virtual mouse device successfully created in kernel!");

    // Wake up Android PointerController immediately
    usleep(50000);
    emit_move_batched(fd, 1, 1);

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

int main(int argc, char *argv[]) {
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP, signal_handler);

    // Elevate daemon scheduling priority to real-time urgent (-20 nice)
    setpriority(PRIO_PROCESS, 0, -20);

    // Disable buffering on stdin and stdout for zero-latency IPC
    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    uinput_fd = init_uinput();
    if (uinput_fd < 0) {
        fprintf(stderr, "ERROR: Could not initialize /dev/uinput\n");
        return 1;
    }

    printf("READY\n");
    fflush(stdout);

    char line[128];
    while (keep_running && fgets(line, sizeof(line), stdin) != NULL) {
        char cmd = line[0];
        if (cmd == '\0' || cmd == '\n') continue;

        int arg1 = 0, arg2 = 0;

        switch (cmd) {
            case 'M': // Move: M <dx> <dy>
                if (sscanf(line + 1, "%d %d", &arg1, &arg2) == 2) {
                    emit_move_batched(uinput_fd, arg1, arg2);
                }
                break;

            case 'D': // Button Down: D <btn>
                if (sscanf(line + 1, "%d", &arg1) == 1) {
                    emit_single_With_syn(uinput_fd, EV_KEY, map_button(arg1), 1);
                }
                break;

            case 'U': // Button Up: U <btn>
                if (sscanf(line + 1, "%d", &arg1) == 1) {
                    emit_single_With_syn(uinput_fd, EV_KEY, map_button(arg1), 0);
                }
                break;

            case 'C': // Click: C <btn>
                if (sscanf(line + 1, "%d", &arg1) == 1) {
                    int code = map_button(arg1);
                    emit_single_With_syn(uinput_fd, EV_KEY, code, 1);
                    usleep(4000); // 4ms fast click pulse
                    emit_single_With_syn(uinput_fd, EV_KEY, code, 0);
                }
                break;

            case 'W': // Vertical Scroll Wheel: W <val>
                if (sscanf(line + 1, "%d", &arg1) == 1 && arg1 != 0) {
                    emit_single_With_syn(uinput_fd, EV_REL, REL_WHEEL, arg1);
                }
                break;

            case 'H': // Horizontal Scroll Wheel: H <val>
                if (sscanf(line + 1, "%d", &arg1) == 1 && arg1 != 0) {
                    emit_single_With_syn(uinput_fd, EV_REL, REL_HWHEEL, arg1);
                }
                break;

            case 'Q': // Quit
                keep_running = 0;
                break;

            default:
                break;
        }
    }

    cleanup_device();
    LOGI("VMouse daemon terminated normally.");
    return 0;
}
