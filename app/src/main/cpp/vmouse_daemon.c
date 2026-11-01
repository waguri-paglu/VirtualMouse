/*
 * Virtual Mouse v1.2.0 — Kernel EVIOCGRAB + Original /dev/uinput Engine
 *
 * Features:
 * 1. Genuine /dev/uinput hardware mouse (EV_REL + BTN_LEFT/BTN_RIGHT/BTN_MIDDLE)
 * 2. Kernel EVIOCGRAB touchscreen capture in Trackpad Mode
 * 3. Gesture: 1-Finger Hold + 2nd-Finger Drag:
 *    - Hold 1st finger on screen (>90ms, or stationary when 2nd finger touches down)
 *    - 1st finger immediately holds LMB down (BTN_LEFT = 1)
 *    - 2nd finger exclusively controls cursor movement (REL_X, REL_Y)
 *    - Supports multi-stroke dragging (lifting and re-placing 2nd finger while 1st finger stays held)
 *    - Releases BTN_LEFT = 0 when 1st finger lifts!
 * 4. Gesture: 2-Finger Scroll (only when both fingers touch down together <90ms apart and slide together)
 * 5. Gesture: Bi-directional 3-Finger Tap -> swaps Trackpad <-> Touch mode in both states!
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>
#include <errno.h>
#include <math.h>
#include <signal.h>
#include <sched.h>
#include <poll.h>
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
#define LOGI(...) do { fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

#define DAEMON_UDP_PORT 29876
#define APP_CALLBACK_PORT 29877

#define CMD_MOVE        1
#define CMD_DOWN        2
#define CMD_UP          3
#define CMD_CLICK       4
#define CMD_SCROLL_V    5
#define CMD_SCROLL_H    6
#define CMD_PING        7
#define CMD_SET_GRAB    8
#define CMD_SET_CONFIG  9
#define CMD_SET_DOCK    10
#define CMD_QUIT        99

/* Callback codes sent from daemon -> Android app on port 29877 */
#define CB_MODE_TOUCH    0
#define CB_MODE_TRACKPAD 1
#define CB_HIDE_OVERLAY  2

struct vmouse_packet {
    int32_t cmd;
    int32_t arg1;
    int32_t arg2;
    int32_t arg3;
    int32_t arg4;
};

/* ============================================================================
 * PART 1: JNI Client Bridge
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
    daemon_addr.sin_port = htons(DAEMON_UDP_PORT);
    daemon_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
}

JNIEXPORT jboolean JNICALL
Java_com_virtualmouse_app_RootMouseController_nativeSendPacket(
    JNIEnv *env,
    jclass clazz,
    jint cmd,
    jint arg1,
    jint arg2,
    jint arg3,
    jint arg4
) {
    (void)env;
    (void)clazz;

    ensure_client_socket();
    if (client_sock < 0) return JNI_FALSE;

    struct vmouse_packet pkt;
    pkt.cmd = (int32_t)cmd;
    pkt.arg1 = (int32_t)arg1;
    pkt.arg2 = (int32_t)arg2;
    pkt.arg3 = (int32_t)arg3;
    pkt.arg4 = (int32_t)arg4;

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
        tv.tv_usec = 50000;
        setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        int32_t pong = 0;
        ssize_t r = recv(client_sock, &pong, sizeof(pong), 0);
        return (r == sizeof(pong) && pong == 1) ? JNI_TRUE : JNI_FALSE;
    }

    return (sent == sizeof(pkt)) ? JNI_TRUE : JNI_FALSE;
}

/* ============================================================================
 * PART 2: Root Kernel Daemon (/dev/uinput + /dev/input/eventX EVIOCGRAB)
 * ============================================================================ */
static int uinput_fd = -1;
static int touch_fd = -1;
static int sock_fd = -1;
static volatile int keep_running = 1;
static int is_grabbed = 0;

static int touch_min_x = 0, touch_max_x = 1080;
static int touch_min_y = 0, touch_max_y = 2400;

static int screen_w = 1080;
static int screen_h = 2400;
static int screen_rotation = 0;
static float pointer_sens = 1.4f;
static float scroll_sens = 0.5f;
static int three_finger_tap_enabled = 1;

static int dock_left = 0;
static int dock_top = 0;
static int dock_right = 0;
static int dock_bottom = 0;

#define MAX_SLOTS 10
#define ROLE_NONE     0
#define ROLE_TRACKPAD 1
#define ROLE_BTN_L    2
#define ROLE_BTN_MODE 3
#define ROLE_BTN_R    4
#define ROLE_BTN_HIDE 5

struct touch_slot {
    int active;
    int tracking_id;
    int raw_x;
    int raw_y;
    int screen_x;
    int screen_y;
    int prev_x;
    int prev_y;
    int down_x;
    int down_y;
    int anchor_x; /* Position of 1st finger at the moment 2nd finger touched down */
    int anchor_y;
    int64_t down_time_ms;
    int role;
    int just_down;
    int just_up;
    int moved_beyond_tap;
};

static struct touch_slot slots[MAX_SLOTS];
static int current_slot = 0;
static float rem_dx = 0.0f;
static float rem_dy = 0.0f;
static float rem_scroll_y = 0.0f;
static float rem_scroll_x = 0.0f;

/* Multi-finger gesture state tracking */
static int two_finger_scroll_mode = 0;
static int hold_and_drag_mode = 0;
static int hold_anchor_slot = -1;
static int last_mid_x = 0;
static int last_mid_y = 0;

/* 3-finger tap detector */
static int gesture_max_fingers = 0;
static int64_t gesture_start_ms = 0;
static int gesture_moved_too_far = 0;
static int pending_mode_swap = 0;
static int pending_hide_overlay = 0;

static int64_t now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000LL + (tv.tv_usec / 1000);
}

static void notify_app_event(int32_t event_code) {
    if (sock_fd < 0) return;
    struct sockaddr_in app_addr;
    memset(&app_addr, 0, sizeof(app_addr));
    app_addr.sin_family = AF_INET;
    app_addr.sin_port = htons(APP_CALLBACK_PORT);
    app_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    sendto(sock_fd, &event_code, sizeof(event_code), MSG_DONTWAIT,
           (const struct sockaddr *)&app_addr, sizeof(app_addr));
}

static inline void emit_single_with_syn(int fd, int type, int code, int val) {
    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));
    ev[0].type = type;
    ev[0].code = code;
    ev[0].value = val;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    if (write(fd, ev, sizeof(ev)) < 0) {}
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

    if (write(fd, ev, sizeof(struct input_event) * count) < 0) {}
}

static inline int map_button(int btn) {
    switch (btn) {
        case 1: return BTN_LEFT;
        case 2: return BTN_RIGHT;
        case 3: return BTN_MIDDLE;
        default: return BTN_LEFT;
    }
}

static void sync_slot_state_from_kernel(void) {
    if (touch_fd < 0) return;
    struct input_absinfo si;
    if (ioctl(touch_fd, EVIOCGABS(ABS_MT_SLOT), &si) == 0) {
        if (si.value >= 0 && si.value < MAX_SLOTS) current_slot = si.value;
    }
    struct input_absinfo ax, ay;
    int init_x = (touch_min_x + touch_max_x) / 2;
    int init_y = (touch_min_y + touch_max_y) / 2;
    if (ioctl(touch_fd, EVIOCGABS(ABS_MT_POSITION_X), &ax) == 0) init_x = ax.value;
    if (ioctl(touch_fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay) == 0) init_y = ay.value;
    for (int k = 0; k < MAX_SLOTS; k++) {
        slots[k].raw_x = init_x;
        slots[k].raw_y = init_y;
    }
}

static void set_touch_grab(int enable) {
    if (touch_fd < 0) return;
    if (enable && !is_grabbed) {
        if (ioctl(touch_fd, EVIOCGRAB, 1) == 0) {
            is_grabbed = 1;
            memset(slots, 0, sizeof(slots));
            sync_slot_state_from_kernel();
            rem_dx = 0.0f;
            rem_dy = 0.0f;
            two_finger_scroll_mode = 0;
            hold_and_drag_mode = 0;
            hold_anchor_slot = -1;
            gesture_max_fingers = 0;
            pending_mode_swap = 0;
            LOGI("Touchscreen EVIOCGRAB enabled (Trackpad Mode)");
            emit_move_batched(uinput_fd, 1, 1);
            notify_app_event(CB_MODE_TRACKPAD);
        } else {
            LOGE("EVIOCGRAB=1 failed: %s", strerror(errno));
        }
    } else if (!enable && is_grabbed) {
        ioctl(touch_fd, EVIOCGRAB, 0);
        is_grabbed = 0;
        memset(slots, 0, sizeof(slots));
        sync_slot_state_from_kernel();
        two_finger_scroll_mode = 0;
        if (hold_and_drag_mode) {
            hold_and_drag_mode = 0;
            emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
        }
        hold_anchor_slot = -1;
        gesture_max_fingers = 0;
        pending_mode_swap = 0;
        emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
        LOGI("Touchscreen EVIOCGRAB released (Normal Touch Mode)");
        notify_app_event(CB_MODE_TOUCH);
    }
}

void cleanup_device(void) {
    if (touch_fd >= 0) {
        if (is_grabbed) {
            ioctl(touch_fd, EVIOCGRAB, 0);
            is_grabbed = 0;
        }
        close(touch_fd);
        touch_fd = -1;
    }
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

#define TEST_BIT(bit, array) ((array[(bit) / 8] & (1 << ((bit) % 8))) != 0)

static int open_touchscreen_device(void) {
    char path[64];
    char name[128];
    uint8_t ev_bits[(EV_MAX + 1) / 8];
    uint8_t abs_bits[(ABS_MAX + 1) / 8];

    for (int i = 0; i < 32; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        memset(name, 0, sizeof(name));
        ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);

        if (strstr(name, "Virtual Mobile Trackpad") != NULL ||
            strstr(name, "uinput") != NULL) {
            close(fd);
            continue;
        }

        memset(ev_bits, 0, sizeof(ev_bits));
        memset(abs_bits, 0, sizeof(abs_bits));
        if (ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), ev_bits) < 0 ||
            !TEST_BIT(EV_ABS, ev_bits)) {
            close(fd);
            continue;
        }

        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) < 0) {
            close(fd);
            continue;
        }

        if (TEST_BIT(ABS_MT_POSITION_X, abs_bits) && TEST_BIT(ABS_MT_POSITION_Y, abs_bits)) {
            struct input_absinfo abs_x, abs_y;
            memset(&abs_x, 0, sizeof(abs_x));
            memset(&abs_y, 0, sizeof(abs_y));
            if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_x) == 0 &&
                ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_y) == 0) {
                touch_min_x = abs_x.minimum;
                touch_max_x = (abs_x.maximum > abs_x.minimum) ? abs_x.maximum : 1080;
                touch_min_y = abs_y.minimum;
                touch_max_y = (abs_y.maximum > abs_y.minimum) ? abs_y.maximum : 2400;
            }
            LOGI("Found physical touchscreen: %s (%s) X=[%d..%d] Y=[%d..%d]",
                 path, name, touch_min_x, touch_max_x, touch_min_y, touch_max_y);
            return fd;
        }

        close(fd);
    }
    return -1;
}

static int init_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (fd < 0) {
        LOGE("Cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_KEYBIT, BTN_SIDE);
    ioctl(fd, UI_SET_KEYBIT, BTN_EXTRA);

    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Virtual Mobile Trackpad Mouse");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

    if (write(fd, &uidev, sizeof(uidev)) < 0) {
        close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }

    usleep(50000);
    emit_move_batched(fd, 2, 2);
    return fd;
}

static int init_fast_socket(void) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    int rcvbuf = 262144;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(DAEMON_UDP_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void map_raw_to_screen(int raw_x, int raw_y, int *out_x, int *out_y) {
    int range_x = (touch_max_x - touch_min_x);
    int range_y = (touch_max_y - touch_min_y);
    if (range_x <= 0) range_x = 1080;
    if (range_y <= 0) range_y = 2400;

    float nx = (float)(raw_x - touch_min_x) / (float)range_x;
    float ny = (float)(raw_y - touch_min_y) / (float)range_y;
    if (nx < 0.0f) nx = 0.0f;
    if (nx > 1.0f) nx = 1.0f;
    if (ny < 0.0f) ny = 0.0f;
    if (ny > 1.0f) ny = 1.0f;

    switch (screen_rotation) {
        case 1: /* ROTATION_90 */
            *out_x = (int)(ny * screen_w);
            *out_y = (int)((1.0f - nx) * screen_h);
            break;
        case 2: /* ROTATION_180 */
            *out_x = (int)((1.0f - nx) * screen_w);
            *out_y = (int)((1.0f - ny) * screen_h);
            break;
        case 3: /* ROTATION_270 */
            *out_x = (int)((1.0f - ny) * screen_w);
            *out_y = (int)(nx * screen_h);
            break;
        default: /* ROTATION_0 */
            *out_x = (int)(nx * screen_w);
            *out_y = (int)(ny * screen_h);
            break;
    }
}

static int classify_dock_zone(int sx, int sy) {
    if (dock_right > dock_left && dock_bottom > dock_top) {
        if (sx >= dock_left && sx <= dock_right && sy >= dock_top && sy <= dock_bottom) {
            int width = dock_right - dock_left;
            int rel_x = sx - dock_left;
            if (rel_x < (width * 28) / 100) {
                return ROLE_BTN_L;
            } else if (rel_x < (width * 56) / 100) {
                return ROLE_BTN_MODE;
            } else if (rel_x < (width * 82) / 100) {
                return ROLE_BTN_R;
            } else {
                return ROLE_BTN_HIDE;
            }
        }
    }
    return ROLE_TRACKPAD;
}

static inline int dist_sq(int x1, int y1, int x2, int y2) {
    int dx = x1 - x2;
    int dy = y1 - y2;
    return dx * dx + dy * dy;
}

static void emit_scaled_move(int raw_dx, int raw_dy) {
    if (raw_dx == 0 && raw_dy == 0) return;
    float vel = sqrtf((float)(raw_dx * raw_dx + raw_dy * raw_dy));
    float accel = 1.0f + (vel * 0.025f);
    if (accel > 2.4f) accel = 2.4f;

    rem_dx += (float)raw_dx * pointer_sens * accel;
    rem_dy += (float)raw_dy * pointer_sens * accel;

    int out_dx = (int)rem_dx;
    int out_dy = (int)rem_dy;
    if (out_dx != 0 || out_dy != 0) {
        rem_dx -= (float)out_dx;
        rem_dy -= (float)out_dy;
        emit_move_batched(uinput_fd, out_dx, out_dy);
    }
}

static void process_touch_frame_syn(void) {
    int64_t now = now_ms();
    int active_trackpad_slots[MAX_SLOTS];
    int active_trackpad_count = 0;
    int total_active_fingers = 0;
    int new_trackpad_finger_down = 0;

    for (int i = 0; i < MAX_SLOTS; i++) {
        struct touch_slot *s = &slots[i];

        if (s->active) {
            map_raw_to_screen(s->raw_x, s->raw_y, &s->screen_x, &s->screen_y);
        }

        if (s->just_down) {
            s->just_down = 0;
            s->prev_x = s->screen_x;
            s->prev_y = s->screen_y;
            s->down_x = s->screen_x;
            s->down_y = s->screen_y;
            s->anchor_x = s->screen_x;
            s->anchor_y = s->screen_y;
            s->down_time_ms = now;
            s->moved_beyond_tap = 0;

            if (is_grabbed) {
                s->role = classify_dock_zone(s->screen_x, s->screen_y);
                if (s->role == ROLE_BTN_L) {
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 1);
                } else if (s->role == ROLE_BTN_R) {
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_RIGHT, 1);
                } else if (s->role == ROLE_BTN_MODE) {
                    pending_mode_swap = 1;
                } else if (s->role == ROLE_BTN_HIDE) {
                    pending_hide_overlay = 1;
                } else if (s->role == ROLE_TRACKPAD) {
                    new_trackpad_finger_down = 1;
                }
            } else {
                s->role = ROLE_TRACKPAD;
            }
        }

        if (s->active && !s->just_up) {
            total_active_fingers++;
            if (dist_sq(s->screen_x, s->screen_y, s->down_x, s->down_y) > (55 * 55)) {
                gesture_moved_too_far = 1;
            }
            if (s->role == ROLE_TRACKPAD) {
                active_trackpad_slots[active_trackpad_count++] = i;
            }
        }
    }

    if (total_active_fingers > 0) {
        if (gesture_max_fingers == 0) {
            gesture_start_ms = now;
            gesture_moved_too_far = 0;
        }
        if (total_active_fingers > gesture_max_fingers) {
            gesture_max_fingers = total_active_fingers;
        }
    }

    /* In Normal Touch mode (is_grabbed == 0), watch for 3-Finger Tap to switch back to Trackpad! */
    if (!is_grabbed) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots[i].just_up) {
                slots[i].just_up = 0;
                slots[i].active = 0;
                slots[i].role = ROLE_NONE;
            }
        }
        if (total_active_fingers == 0 && gesture_max_fingers > 0) {
            int64_t gesture_dur = now - gesture_start_ms;
            if (three_finger_tap_enabled && gesture_max_fingers == 3 &&
                !gesture_moved_too_far && gesture_dur < 420) {
                gesture_max_fingers = 0;
                set_touch_grab(1);
                return;
            }
            gesture_max_fingers = 0;
        }
        return;
    }

    /* ========================================================================
     * Trackpad Mode (is_grabbed == 1)
     * ======================================================================== */
    if (active_trackpad_count == 2 && gesture_max_fingers < 3) {
        int idx0 = active_trackpad_slots[0];
        int idx1 = active_trackpad_slots[1];
        struct touch_slot *s0 = &slots[idx0];
        struct touch_slot *s1 = &slots[idx1];

        /* Identify earlier (1st) finger and later (2nd) finger */
        int first_idx = (s0->down_time_ms <= s1->down_time_ms) ? idx0 : idx1;
        int second_idx = (s0->down_time_ms <= s1->down_time_ms) ? idx1 : idx0;
        struct touch_slot *first_f = &slots[first_idx];
        struct touch_slot *second_f = &slots[second_idx];

        /* When 2nd finger just touched down, snapshot 1st finger's current position as anchor! */
        if (new_trackpad_finger_down) {
            first_f->anchor_x = first_f->screen_x;
            first_f->anchor_y = first_f->screen_y;
            int64_t time_gap = second_f->down_time_ms - first_f->down_time_ms;

            /*
             * If 1st finger was already held on the screen for >= 90ms before 2nd finger touched down,
             * immediately engage Hold-and-Drag (1st finger holds LMB, 2nd finger moves cursor)!
             */
            if (time_gap >= 90 && !two_finger_scroll_mode) {
                if (!hold_and_drag_mode) {
                    hold_and_drag_mode = 1;
                    hold_anchor_slot = first_idx;
                    first_f->moved_beyond_tap = 1;
                    second_f->moved_beyond_tap = 1;
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 1);
                }
            }
        }

        /* Also check if 1st finger is stationary near its anchor while 2nd finger drags */
        int first_drift_sq = dist_sq(first_f->screen_x, first_f->screen_y, first_f->anchor_x, first_f->anchor_y);
        int second_drag_sq = dist_sq(second_f->screen_x, second_f->screen_y, second_f->down_x, second_f->down_y);

        if (!hold_and_drag_mode && !two_finger_scroll_mode &&
            first_drift_sq < (25 * 25) && second_drag_sq > (20 * 20)) {
            hold_and_drag_mode = 1;
            hold_anchor_slot = first_idx;
            first_f->moved_beyond_tap = 1;
            second_f->moved_beyond_tap = 1;
            emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 1);
        }

        if (hold_and_drag_mode) {
            /* Only the 2nd (moving) finger controls the cursor! */
            struct touch_slot *mover = (hold_anchor_slot == idx0) ? s1 : s0;
            int raw_dx = mover->screen_x - mover->prev_x;
            int raw_dy = mover->screen_y - mover->prev_y;
            s0->prev_x = s0->screen_x;
            s0->prev_y = s0->screen_y;
            s1->prev_x = s1->screen_x;
            s1->prev_y = s1->screen_y;

            emit_scaled_move(raw_dx, raw_dy);
        } else {
            /* Standard 2-Finger Scroll when both fingers were placed together (<90ms) and move together */
            int mid_x = (s0->screen_x + s1->screen_x) / 2;
            int mid_y = (s0->screen_y + s1->screen_y) / 2;

            if (!two_finger_scroll_mode) {
                if (first_drift_sq > (20 * 20) && second_drag_sq > (20 * 20)) {
                    two_finger_scroll_mode = 1;
                    last_mid_x = mid_x;
                    last_mid_y = mid_y;
                    rem_scroll_x = 0.0f;
                    rem_scroll_y = 0.0f;
                    s0->moved_beyond_tap = 1;
                    s1->moved_beyond_tap = 1;
                }
            } else {
                int dy = mid_y - last_mid_y;
                int dx = mid_x - last_mid_x;
                last_mid_x = mid_x;
                last_mid_y = mid_y;

                rem_scroll_y += (float)dy * scroll_sens * 0.18f;
                rem_scroll_x += (float)dx * scroll_sens * 0.18f;

                int sy = (int)rem_scroll_y;
                int sx = (int)rem_scroll_x;
                if (sy != 0) {
                    rem_scroll_y -= (float)sy;
                    emit_single_with_syn(uinput_fd, EV_REL, REL_WHEEL, sy);
                }
                if (sx != 0) {
                    rem_scroll_x -= (float)sx;
                    emit_single_with_syn(uinput_fd, EV_REL, REL_HWHEEL, sx);
                }
            }
            s0->prev_x = s0->screen_x;
            s0->prev_y = s0->screen_y;
            s1->prev_x = s1->screen_x;
            s1->prev_y = s1->screen_y;
        }
    } else if (active_trackpad_count == 1 && gesture_max_fingers < 3 && !two_finger_scroll_mode) {
        int idx = active_trackpad_slots[0];
        struct touch_slot *s = &slots[idx];
        int raw_dx = s->screen_x - s->prev_x;
        int raw_dy = s->screen_y - s->prev_y;
        s->prev_x = s->screen_x;
        s->prev_y = s->screen_y;

        if (hold_and_drag_mode) {
            /* If 1st anchor finger is held alone (2nd finger lifted for multi-stroke), keep BTN_LEFT held! */
            if (idx != hold_anchor_slot) {
                emit_scaled_move(raw_dx, raw_dy);
            }
        } else {
            int dist_from_down_sq = dist_sq(s->screen_x, s->screen_y, s->down_x, s->down_y);
            if (dist_from_down_sq > (24 * 24)) {
                s->moved_beyond_tap = 1;
            }
            emit_scaled_move(raw_dx, raw_dy);
        }
    }

    /* Handle finger releases in Trackpad Mode */
    for (int i = 0; i < MAX_SLOTS; i++) {
        struct touch_slot *s = &slots[i];
        if (s->just_up) {
            s->just_up = 0;
            s->active = 0;
            int64_t duration = now - s->down_time_ms;

            if (s->role == ROLE_BTN_L) {
                emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
            } else if (s->role == ROLE_BTN_R) {
                emit_single_with_syn(uinput_fd, EV_KEY, BTN_RIGHT, 0);
            } else if (s->role == ROLE_TRACKPAD) {
                if (hold_and_drag_mode && i == hold_anchor_slot) {
                    /* Anchor finger lifted -> release BTN_LEFT! */
                    hold_and_drag_mode = 0;
                    hold_anchor_slot = -1;
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
                } else if (!hold_and_drag_mode && !two_finger_scroll_mode &&
                           gesture_max_fingers == 1 && !s->moved_beyond_tap && duration < 220) {
                    /* 1-Finger Tap -> Left Click */
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 1);
                    usleep(12000);
                    emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
                }
            }
            s->role = ROLE_NONE;
        }
    }

    if (total_active_fingers == 0) {
        int64_t gesture_dur = now - gesture_start_ms;

        if (hold_and_drag_mode) {
            hold_and_drag_mode = 0;
            hold_anchor_slot = -1;
            emit_single_with_syn(uinput_fd, EV_KEY, BTN_LEFT, 0);
        }

        if (gesture_max_fingers == 2 && !two_finger_scroll_mode &&
            !gesture_moved_too_far && gesture_dur < 260) {
            /* 2-Finger Tap -> Right Click */
            emit_single_with_syn(uinput_fd, EV_KEY, BTN_RIGHT, 1);
            usleep(12000);
            emit_single_with_syn(uinput_fd, EV_KEY, BTN_RIGHT, 0);
        } else if (three_finger_tap_enabled && gesture_max_fingers == 3 &&
                   !gesture_moved_too_far && gesture_dur < 420) {
            /* 3-Finger Tap -> Swap to Touch Mode! */
            pending_mode_swap = 1;
        }

        two_finger_scroll_mode = 0;
        gesture_max_fingers = 0;

        if (pending_hide_overlay) {
            pending_hide_overlay = 0;
            dock_left = dock_top = dock_right = dock_bottom = 0;
            notify_app_event(CB_HIDE_OVERLAY);
        }

        if (pending_mode_swap) {
            pending_mode_swap = 0;
            set_touch_grab(0);
        }
    }
}

static void handle_touch_events(void) {
    struct input_event ev[64];
    ssize_t rd = read(touch_fd, ev, sizeof(ev));
    if (rd < (ssize_t)sizeof(struct input_event)) return;

    int count = (int)(rd / sizeof(struct input_event));
    for (int i = 0; i < count; i++) {
        uint16_t type = ev[i].type;
        uint16_t code = ev[i].code;
        int32_t val = ev[i].value;

        if (type == EV_ABS) {
            if (code == ABS_MT_SLOT) {
                if (val >= 0 && val < MAX_SLOTS) {
                    current_slot = val;
                }
            } else if (code == ABS_MT_TRACKING_ID) {
                if (val >= 0) {
                    slots[current_slot].active = 1;
                    slots[current_slot].tracking_id = val;
                    slots[current_slot].just_down = 1;
                    slots[current_slot].just_up = 0;
                } else {
                    if (slots[current_slot].active) {
                        slots[current_slot].just_up = 1;
                    }
                }
            } else if (code == ABS_MT_POSITION_X || code == ABS_X) {
                slots[current_slot].raw_x = val;
            } else if (code == ABS_MT_POSITION_Y || code == ABS_Y) {
                slots[current_slot].raw_y = val;
            }
        } else if (type == EV_KEY && code == BTN_TOUCH) {
            if (val == 1 && !slots[current_slot].active) {
                slots[current_slot].active = 1;
                slots[current_slot].just_down = 1;
            } else if (val == 0 && slots[current_slot].active) {
                slots[current_slot].just_up = 1;
            }
        } else if (type == EV_SYN && code == SYN_REPORT) {
            process_touch_frame_syn();
        }
    }
}

static void handle_udp_packet(
    const struct vmouse_packet *pkt,
    const struct sockaddr_in *client_addr,
    socklen_t client_len
) {
    switch (pkt->cmd) {
        case CMD_MOVE:
            emit_move_batched(uinput_fd, pkt->arg1, pkt->arg2);
            break;
        case CMD_DOWN:
            emit_single_with_syn(uinput_fd, EV_KEY, map_button(pkt->arg1), 1);
            break;
        case CMD_UP:
            emit_single_with_syn(uinput_fd, EV_KEY, map_button(pkt->arg1), 0);
            break;
        case CMD_CLICK: {
            int code = map_button(pkt->arg1);
            emit_single_with_syn(uinput_fd, EV_KEY, code, 1);
            usleep(12000);
            emit_single_with_syn(uinput_fd, EV_KEY, code, 0);
            break;
        }
        case CMD_SCROLL_V:
            emit_single_with_syn(uinput_fd, EV_REL, REL_WHEEL, pkt->arg1);
            break;
        case CMD_SCROLL_H:
            emit_single_with_syn(uinput_fd, EV_REL, REL_HWHEEL, pkt->arg1);
            break;
        case CMD_SET_GRAB:
            set_touch_grab(pkt->arg1);
            break;
        case CMD_SET_CONFIG:
            if (pkt->arg1 > 0) pointer_sens = (float)pkt->arg1 / 100.0f;
            if (pkt->arg2 > 0) scroll_sens = (float)pkt->arg2 / 100.0f;
            if (pkt->arg3 > 0) screen_w = pkt->arg3;
            if ((pkt->arg4 & 0xFFFF) > 0) screen_h = (pkt->arg4 & 0xFFFF);
            screen_rotation = (pkt->arg4 >> 16) & 0x3;
            three_finger_tap_enabled = ((pkt->arg4 >> 20) & 0x1) ? 1 : 0;
            break;
        case CMD_SET_DOCK:
            dock_left = pkt->arg1;
            dock_top = pkt->arg2;
            dock_right = pkt->arg3;
            dock_bottom = pkt->arg4;
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
    if (uinput_fd < 0) return 1;

    touch_fd = open_touchscreen_device();
    sync_slot_state_from_kernel();

    sock_fd = init_fast_socket();
    if (sock_fd < 0) {
        cleanup_device();
        return 1;
    }

    printf("READY\n");
    fflush(stdout);

    struct pollfd pfds[2];
    int nfds = 0;

    while (keep_running) {
        pfds[0].fd = sock_fd;
        pfds[0].events = POLLIN;
        nfds = 1;

        if (touch_fd >= 0) {
            pfds[1].fd = touch_fd;
            pfds[1].events = POLLIN;
            nfds = 2;
        }

        int ret = poll(pfds, nfds, 250);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ret == 0) continue;

        if (nfds == 2 && (pfds[1].revents & POLLIN)) {
            handle_touch_events();
        }

        if (pfds[0].revents & POLLIN) {
            struct vmouse_packet pkt;
            struct sockaddr_in client_addr;
            socklen_t client_len = sizeof(client_addr);
            while (keep_running) {
                client_len = sizeof(client_addr);
                ssize_t n = recvfrom(sock_fd, &pkt, sizeof(pkt), MSG_DONTWAIT,
                                     (struct sockaddr *)&client_addr, &client_len);
                if (n != sizeof(pkt)) break;
                handle_udp_packet(&pkt, &client_addr, client_len);
            }
        }
    }

    cleanup_device();
    LOGI("VMouse daemon terminated normally.");
    return 0;
}
