/*
 * Virtual Mouse uinput Daemon for Rooted Android
 * Handles /dev/uinput virtual mouse registration and commands via stdin.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
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

void emit_event(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    gettimeofday(&ie.time, NULL);
    if (write(fd, &ie, sizeof(ie)) < 0) {
        LOGE("Failed to write event: %s", strerror(errno));
    }
}

void syn(int fd) {
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
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
    LOGI("Received signal %d, exiting", sig);
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
    return fd;
}

int map_button(int btn) {
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

    uinput_fd = init_uinput();
    if (uinput_fd < 0) {
        fprintf(stderr, "ERROR: Could not initialize /dev/uinput. Are you root?\n");
        return 1;
    }

    printf("READY\n");
    fflush(stdout);

    char line[256];
    while (keep_running && fgets(line, sizeof(line), stdin) != NULL) {
        char cmd;
        int arg1 = 0, arg2 = 0;
        int parsed = sscanf(line, " %c %d %d", &cmd, &arg1, &arg2);

        if (parsed <= 0) continue;

        switch (cmd) {
            case 'M': // Move: M <dx> <dy>
                emit_event(uinput_fd, EV_REL, REL_X, arg1);
                emit_event(uinput_fd, EV_REL, REL_Y, arg2);
                syn(uinput_fd);
                break;

            case 'D': // Button Down: D <btn>
                emit_event(uinput_fd, EV_KEY, map_button(arg1), 1);
                syn(uinput_fd);
                break;

            case 'U': // Button Up: U <btn>
                emit_event(uinput_fd, EV_KEY, map_button(arg1), 0);
                syn(uinput_fd);
                break;

            case 'C': // Click: C <btn>
                emit_event(uinput_fd, EV_KEY, map_button(arg1), 1);
                syn(uinput_fd);
                usleep(15000); // 15ms click duration
                emit_event(uinput_fd, EV_KEY, map_button(arg1), 0);
                syn(uinput_fd);
                break;

            case 'W': // Vertical Scroll Wheel: W <val>
                emit_event(uinput_fd, EV_REL, REL_WHEEL, arg1);
                syn(uinput_fd);
                break;

            case 'H': // Horizontal Scroll Wheel: H <val>
                emit_event(uinput_fd, EV_REL, REL_HWHEEL, arg1);
                syn(uinput_fd);
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
