// evgrab：獨占讀取 USB 鍵盤，把按鍵轉成 8 位元組 HID boot keyboard 報告寫到 stdout
//   報告格式：[修飾鍵, 0, 鍵1..鍵6]
//   用法：evgrab /dev/input/eventN        （需要 root）
//   結束：SIGTERM / SIGINT、stdout 被關閉、鍵盤被拔除，或按 Ctrl+Alt+Q
//   結束碼：0 = 使用者按了 Ctrl+Alt+Q 或被要求停止；非 0 = 錯誤或鍵盤被拔除
//   結束時 close(fd) 會讓核心自動釋放獨占，手機即恢復正常接收這支鍵盤。
//
// 編譯（NDK）：
//   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang   -O2 -pie -o libevgrab.so evgrab.c
//   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang -O2 -pie -o libevgrab.so evgrab.c
// 編譯（Termux，測試用）：pkg install clang && clang -O2 evgrab.c -o evgrab

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static volatile sig_atomic_t stop_flag = 0;
static void on_signal(int s) { (void)s; stop_flag = 1; }

static unsigned char hid_of[256];   // evdev code -> HID usage（0 = 不處理）
static unsigned char mod_of[256];   // evdev code -> 修飾鍵位元

static void init_map(void) {
    static const struct { int code; unsigned char usage; } t[] = {
        {1,0x29},{12,0x2D},{13,0x2E},{14,0x2A},{15,0x2B},{26,0x2F},{27,0x30},{28,0x28},
        {39,0x33},{40,0x34},{41,0x35},{43,0x31},{51,0x36},{52,0x37},{53,0x38},{55,0x55},
        {57,0x2C},{58,0x39},{69,0x53},{70,0x47},{74,0x56},{78,0x57},{83,0x63},{86,0x64},
        {87,0x44},{88,0x45},{96,0x58},{98,0x54},{99,0x46},{102,0x4A},{103,0x52},{104,0x4B},
        {105,0x50},{106,0x4F},{107,0x4D},{108,0x51},{109,0x4E},{110,0x49},{111,0x4C},{119,0x48},
    };
    static const unsigned char q[] = {0x14,0x1A,0x08,0x15,0x17,0x1C,0x18,0x0C,0x12,0x13};   // q..p
    static const unsigned char a[] = {0x04,0x16,0x07,0x09,0x0A,0x0B,0x0D,0x0E,0x0F};        // a..l
    static const unsigned char z[] = {0x1D,0x1B,0x06,0x19,0x05,0x11,0x10};                  // z..m
    static const int kp_c[] = {71,72,73,75,76,77,79,80,81,82};
    static const unsigned char kp_u[] = {0x5F,0x60,0x61,0x5C,0x5D,0x5E,0x59,0x5A,0x5B,0x62};

    for (size_t i = 0; i < sizeof t / sizeof t[0]; i++) hid_of[t[i].code] = t[i].usage;
    for (int i = 0; i < 10; i++) { hid_of[2 + i] = 0x1E + i; hid_of[59 + i] = 0x3A + i; }   // 1..0、F1..F10
    for (int i = 0; i < 10; i++) hid_of[16 + i] = q[i];
    for (int i = 0; i < 9; i++)  hid_of[30 + i] = a[i];
    for (int i = 0; i < 7; i++)  hid_of[44 + i] = z[i];
    for (int i = 0; i < 10; i++) hid_of[kp_c[i]] = kp_u[i];

    mod_of[29] = 0x01; mod_of[42] = 0x02; mod_of[56] = 0x04; mod_of[125] = 0x08;
    mod_of[97] = 0x10; mod_of[54] = 0x20; mod_of[100] = 0x40; mod_of[126] = 0x80;
}

static int emit(unsigned char mods, const unsigned char *keys, int n) {
    unsigned char r[8] = { mods, 0 };
    for (int i = 0; i < n && i < 6; i++) r[2 + i] = keys[i];
    return write(1, r, sizeof r) == (ssize_t)sizeof r ? 0 : -1;    // 8 位元組，管線寫入不會被拆開
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s /dev/input/eventN\n", argv[0]);
        return 2;
    }
    init_map();

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;          // 不設 SA_RESTART，讓 read() 能被訊號中斷
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    if (ioctl(fd, EVIOCGRAB, (void *)1) < 0) { perror("EVIOCGRAB"); close(fd); return 1; }

    unsigned char mods = 0, keys[6];
    int n = 0, rc = 0;
    struct input_event ev;

    while (!stop_flag) {
        ssize_t r = read(fd, &ev, sizeof ev);
        if (r < 0) {
            if (errno == EINTR) continue;
            rc = 1;                      // 多半是 ENODEV：鍵盤被拔掉
            break;
        }
        if (r != (ssize_t)sizeof ev) continue;
        if (ev.type != EV_KEY || ev.value == 2 || ev.code > 255) continue;   // 忽略自動重複
        int down = (ev.value == 1);

        if (mod_of[ev.code]) {
            mods = down ? (mods | mod_of[ev.code]) : (mods & (unsigned char)~mod_of[ev.code]);
        } else if (hid_of[ev.code]) {
            unsigned char u = hid_of[ev.code];
            int idx = -1;
            for (int i = 0; i < n; i++) if (keys[i] == u) idx = i;
            if (down && idx < 0 && n < 6) keys[n++] = u;
            else if (!down && idx >= 0) { memmove(&keys[idx], &keys[idx + 1], n - idx - 1); n--; }
        } else {
            continue;
        }

        if (down && ev.code == KEY_Q && (mods & 0x05) == 0x05) break;   // Ctrl+Alt+Q：結束並釋放鍵盤
        if (emit(mods, keys, n) < 0) break;                              // stdout 被關閉
    }

    emit(0, keys, 0);                    // 放開所有按鍵
    ioctl(fd, EVIOCGRAB, (void *)0);
    close(fd);
    return rc;
}
