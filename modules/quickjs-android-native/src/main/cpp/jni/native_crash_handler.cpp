// 原生崩溃捕获 (Android NDK): 信号处理器 + 栈回溯 + 落盘 + 转交系统处理。
//
// # 为什么必须接这一层
// Kotlin 的未捕获异常处理器只覆盖 JVM 异常。NDK/JNI 侧的越界、野指针、栈溢出等
// 直接以 POSIX 信号终止进程, 一条 Java 日志都不会留下 —— 与"没接崩溃捕获"无区别。
//
// # 与 ART / debuggerd 的关系 (实测依据)
// Android 上 SIGSEGV 是 ART 隐式空指针检查与 debuggerd 都在用的信号, 但**不会冲突**:
// ART 的 sigchain (art/sigchainlib/sigchain.cc) 拦截 sigaction/signal 调用, 把用户注册的
// handler 记进 user_handlers 链, 真正的信号入口恒是 SignalChain::Handler。它的处理顺序是:
//   1. special_handlers (ART fault handler / native bridge / 未捕获异常处理器)
//   2. android_handle_signal —— 交给 debuggerd 处理并生成系统 tombstone
//   3. 链到用户 handler
// 且 sigchain 明确声明 "libsigchain provides an interception layer for signal handlers,
// to allow ART and others to give their signal handlers the first stab at handling signals
// before passing them on to user code."
// ⇒ 本 handler 跑在 ART/debuggerd 之后; 处理完**不返回而是退出进程** (见 crashSignalHandler
// 末尾注释): 从同步信号处理器返回会重新执行出错指令并再次触发同一信号, 形成"每次进入都写
// 一份日志"的死循环并写满磁盘。系统 tombstone 由更前面的 debuggerd 生成, 不受本退出影响。
//
// # 为什么用 SA_SIGINFO + SA_ONSTACK + SA_RESTART
// - SA_SIGINFO: 需要 siginfo (si_addr 崩溃地址) 与 ucontext (寄存器/栈指针)
// - SA_ONSTACK: 栈溢出 (SIGSEGV 落在已耗尽的主栈上) 时必须切到备用栈, 否则 handler 自己
//   也压栈即二次崩溃
// - SA_RESTART: 与 sigchain 注册自身 handler 时的 flag 集保持同向
//
// # 落盘通道
// 走已有的 native 崩溃日志机制 (NativeCrashLogWriter): 信号上下文内只允许
// async-signal-safe 调用, 故用安装期预分配缓冲 + open/write/close, 不分配、不加锁。
// 标记文件与崩溃日志同目录, 供"崩溃后重启提示"读取。

#ifdef __ANDROID__

#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <ucontext.h>
#include <unwind.h>
#include <time.h>
#include <sys/stat.h>

// JNI 入口: JNIEXPORT/JNIEnv/jstring 都来自 jni.h (NDK sysroot 自带)
#include <jni.h>
#include <android/log.h>

// 本文件是独立 TU, jni_bridge.cpp 里的 LOGI 宏不可见, 就地定义
#define TAG_CRASH "legado_crash"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG_CRASH, __VA_ARGS__)

namespace {

// 与 NativeCrashLogWriter (Kotlin) 同口径的常量
constexpr const char *kCrashFileNamePrefix = "crash-";
constexpr const char *kMarkerFileName = "pending-crash";
constexpr size_t kPathMax = 4096;
constexpr size_t kBufferSize = 16 * 1024;
constexpr size_t kMaxFrames = 64;
// 备用栈 (SA_ONSTACK 用): 栈溢出场景下必须切走, 否则 handler 内压栈即二次崩溃
constexpr size_t kAltStackSize = 64 * 1024;

// 信号落盘缓冲 (安装期填好路径与参数头, 处理器内只追加栈帧文本)
char g_buffer[kBufferSize];
// 参数头长度 (处理器内从该偏移开始追加)
size_t g_bodyLength = 0;
// 崩溃目录 (安装期快照, 末尾无 '/')
char g_crashDir[kPathMax];
// 标记文件绝对路径 (与崩溃日志同目录)
char g_markerPath[kPathMax];
// 备用栈
char g_altStack[kAltStackSize];
bool g_installed = false;

// 重入守卫: 写完日志即 _exit, 正常不会二次进入; 一旦进入说明落盘动作本身又崩了
// (例如回溯过程再次触发同信号), 此时只退出、不再写盘, 否则会无限循环写满磁盘。
volatile sig_atomic_t g_inHandler = 0;

// ===== async-signal-safe 小工具 (只用 write/整数运算) =====

void appendStr(char *dst, size_t capacity, size_t &at, const char *src) {
    size_t i = 0;
    while (src[i] != '\0' && at < capacity) {
        dst[at++] = src[i++];
    }
}

/** 追加无符号数 (十进制), 用于栈帧地址与信号号。 */
void appendUnsigned(char *dst, size_t capacity, size_t &at, unsigned long long value) {
    char tmp[24];
    int n = 0;
    if (value == 0) {
        tmp[n++] = '0';
    } else {
        while (value > 0 && n < 24) {
            tmp[n++] = static_cast<char>('0' + (value % 10));
            value /= 10;
        }
    }
    while (n > 0 && at < capacity) {
        dst[at++] = tmp[--n];
    }
}

/** 追加 16 进制 (带 0x), 用于地址。 */
void appendHex(char *dst, size_t capacity, size_t &at, unsigned long long value) {
    const char *digits = "0123456789abcdef";
    appendStr(dst, capacity, at, "0x");
    bool started = false;
    for (int shift = 60; shift >= 0; shift -= 4) {
        unsigned nibble = static_cast<unsigned>((value >> shift) & 0xF);
        if (!started && nibble == 0 && shift != 0) continue;
        started = true;
        if (at < capacity) dst[at++] = digits[nibble];
    }
}

/**
 * 写一个文件 (O_WRONLY|O_CREAT|O_TRUNC), 全部失败静默。
 *
 * 只用 open/write/close 三个 async-signal-safe 调用。
 */
void writeFileAll(const char *path, const char *data, size_t length) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    size_t offset = 0;
    while (offset < length) {
        ssize_t n = write(fd, data + offset, length - offset);
        if (n <= 0) break;
        offset += static_cast<size_t>(n);
    }
    close(fd);
}

/** 时间戳 (yy-MM-dd-HH-mm-ss), 与 Kotlin 侧文件名口径一致 (本地时间)。 */
void appendTimestamp(char *dst, size_t capacity, size_t &at) {
    time_t now = time(nullptr);
    struct tm tmValue;
    if (localtime_r(&now, &tmValue) == nullptr) return;
    // 两位数补零
    auto append2 = [&](int value) {
        if (at < capacity) dst[at++] = static_cast<char>('0' + (value / 10) % 10);
        if (at < capacity) dst[at++] = static_cast<char>('0' + value % 10);
    };
    append2(tmValue.tm_year % 100);
    if (at < capacity) dst[at++] = '-';
    append2(tmValue.tm_mon + 1);
    if (at < capacity) dst[at++] = '-';
    append2(tmValue.tm_mday);
    if (at < capacity) dst[at++] = '-';
    append2(tmValue.tm_hour);
    if (at < capacity) dst[at++] = '-';
    append2(tmValue.tm_min);
    if (at < capacity) dst[at++] = '-';
    append2(tmValue.tm_sec);
}

/** 毫秒时间戳 (文件名后缀, 与 Kotlin 侧同口径)。 */
unsigned long long currentMillis() {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) return 0;
    return static_cast<unsigned long long>(ts.tv_sec) * 1000ULL +
           static_cast<unsigned long long>(ts.tv_nsec) / 1000000ULL;
}

/** 信号名 (与 NativeCrashLogWriter.signalName 同表)。 */
const char *signalName(int sig) {
    switch (sig) {
        case SIGHUP: return "SIGHUP";
        case SIGILL: return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGSEGV: return "SIGSEGV";
        default: return "SIG(unknown)";
    }
}

// ===== 栈回溯 =====
//
// 用 NDK 自带的 libunwind (_Unwind_Backtrace, 随 NDK 的 libc++ 提供), 不引第三方崩溃库。
// 信号处理器内回溯的是**当前**栈 —— SA_ONSTACK 已把执行切到备用栈, 故回溯到的是 handler
// 自身的调用链, 真实崩溃现场从 ucontext 的 pc/lr 取 (见下)。
// 这是已知限制: 完整回溯需要 unwindstack 级别的寄存器级展开, 而本实现的目标是"拿到崩溃
// 地址与上层调用链的粗粒度现场", 足够定位是哪个 so 崩的。

struct BacktraceState {
    void **frames;
    size_t count;
    size_t max;
};

_Unwind_Reason_Code unwindCallback(struct _Unwind_Context *context, void *arg) {
    auto *state = static_cast<BacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0 && state->count < state->max) {
        state->frames[state->count++] = reinterpret_cast<void *>(pc);
    }
    return state->count >= state->max ? _URC_END_OF_STACK : _URC_NO_REASON;
}

/**
 * 信号处理器: 记录现场 (崩溃地址 + 寄存器 + 栈回溯), 然后退出进程。
 *
 * **写完不返回**: 从同步信号处理器返回会重新执行出错指令、再次触发同一信号, 于是再次进入本处
 * 理器 —— 每次进入写一份日志, 形成死循环并写满磁盘 (靠 [g_inHandler] 兜底也不该让正常路径
 * 走到那一步)。系统 tombstone 由链更前面的 debuggerd 生成, 与本退出无关。
 */
void crashSignalHandler(int sig, siginfo_t *info, void *ucontextRaw) {
    if (g_inHandler) {
        // 已在处理中又崩一次: 只退出, 绝不再写盘
        _exit(139);
    }
    g_inHandler = 1;
    size_t at = g_bodyLength;
    appendStr(g_buffer, kBufferSize, at, "\nsignal=");
    appendStr(g_buffer, kBufferSize, at, signalName(sig));
    appendUnsigned(g_buffer, kBufferSize, at, static_cast<unsigned long long>(sig));
    appendStr(g_buffer, kBufferSize, at, "\nsource=native-signal\n");

    if (info != nullptr) {
        appendStr(g_buffer, kBufferSize, at, "si_code=");
        appendUnsigned(g_buffer, kBufferSize, at,
                       static_cast<unsigned long long>(info->si_code));
        appendStr(g_buffer, kBufferSize, at, "\nfault_addr=");
        appendHex(g_buffer, kBufferSize, at,
                  reinterpret_cast<unsigned long long>(info->si_addr));
        appendStr(g_buffer, kBufferSize, at, "\n");
    }

    // 崩溃现场的指令指针 (从 ucontext 取, 不是 handler 自己的)
    if (ucontextRaw != nullptr) {
        auto *uc = static_cast<ucontext_t *>(ucontextRaw);
#if defined(__aarch64__)
        appendStr(g_buffer, kBufferSize, at, "pc=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.pc));
        appendStr(g_buffer, kBufferSize, at, "\nlr=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.regs[30]));
        appendStr(g_buffer, kBufferSize, at, "\nsp=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.sp));
        appendStr(g_buffer, kBufferSize, at, "\n");
#elif defined(__arm__)
        appendStr(g_buffer, kBufferSize, at, "pc=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_pc));
        appendStr(g_buffer, kBufferSize, at, "\nlr=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_lr));
        appendStr(g_buffer, kBufferSize, at, "\nsp=");
        appendHex(g_buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_sp));
        appendStr(g_buffer, kBufferSize, at, "\n");
#endif
    }

    // handler 自身栈上的调用链 (粗粒度, 见上方限制说明)
    void *frames[kMaxFrames];
    BacktraceState state{frames, 0, kMaxFrames};
    _Unwind_Backtrace(unwindCallback, &state);
    appendStr(g_buffer, kBufferSize, at, "frames:\n");
    for (size_t i = 0; i < state.count && at < kBufferSize; i++) {
        appendStr(g_buffer, kBufferSize, at, "  #");
        appendUnsigned(g_buffer, kBufferSize, at, static_cast<unsigned long long>(i));
        appendStr(g_buffer, kBufferSize, at, " ");
        appendHex(g_buffer, kBufferSize, at,
                  reinterpret_cast<unsigned long long>(frames[i]));
        appendStr(g_buffer, kBufferSize, at, "\n");
    }

    // 落盘: 崩溃日志 + 异常退出标记 (同一次崩溃, 标记供启动期提示)
    char path[kPathMax];
    size_t p = 0;
    appendStr(path, kPathMax, p, g_crashDir);
    if (p < kPathMax) path[p++] = '/';
    appendStr(path, kPathMax, p, kCrashFileNamePrefix);
    appendTimestamp(path, kPathMax, p);
    if (p < kPathMax) path[p++] = '-';
    appendUnsigned(path, kPathMax, p, currentMillis());
    appendStr(path, kPathMax, p, ".log");
    if (p < kPathMax) path[p] = '\0';
    else path[kPathMax - 1] = '\0';
    writeFileAll(path, g_buffer, at);

    static const char marker[] = "1\n";
    writeFileAll(g_markerPath, marker, sizeof(marker) - 1);

    // **写完不返回**: 从同步信号处理器返回会重新执行出错指令、再次触发同一信号, 于是再次
    // 进入本处理器 —— 每次进入写一份日志, 形成死循环并写满磁盘。故写完直接退出进程。
    _exit(139);
}

/** 安装期把参数头 (与 Kotlin 侧 NativeCrashLogWriter 同项) 写进缓冲。 */
void buildParamsHeader() {
    size_t at = 0;
    appendStr(g_buffer, kBufferSize, at, "crashKind=native-signal\n");
    appendStr(g_buffer, kBufferSize, at, "TIME=");
    appendTimestamp(g_buffer, kBufferSize, at);
    appendStr(g_buffer, kBufferSize, at, "\n");
    g_bodyLength = at;
}

} // namespace

/**
 * 安装原生信号处理器 (由 Kotlin 侧 NativeSignalCrashHandler 经 JNI 调用一次)。
 *
 * @param crashDir 崩溃日志目录绝对路径 (Kotlin 侧 NativeCrashLogWriter.crashDir)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_script_quickjs_QuickJsNative_nativeInstallCrashHandler(JNIEnv *env, jobject clazz,
                                                                jstring crashDir) {
    if (g_installed) return;
    if (crashDir == nullptr) return;
    const char *dirChars = env->GetStringUTFChars(crashDir, nullptr);
    if (dirChars == nullptr) return;

    size_t p = 0;
    appendStr(g_crashDir, kPathMax, p, dirChars);
    // 去掉尾部斜杠 (拼路径时统一补一个)
    while (p > 1 && (g_crashDir[p - 1] == '/' || g_crashDir[p - 1] == '\\')) p--;
    g_crashDir[p] = '\0';

    size_t m = 0;
    appendStr(g_markerPath, kPathMax, m, g_crashDir);
    if (m < kPathMax) g_markerPath[m++] = '/';
    appendStr(g_markerPath, kPathMax, m, kMarkerFileName);
    g_markerPath[m < kPathMax ? m : kPathMax - 1] = '\0';

    env->ReleaseStringUTFChars(crashDir, dirChars);

    // 目录必须先存在: open 带 O_CREAT 只建文件, 不建父目录
    mkdir(g_crashDir, 0755);

    buildParamsHeader();

    // 备用栈 (SA_ONSTACK)
    stack_t altStack{};
    altStack.ss_sp = g_altStack;
    altStack.ss_size = kAltStackSize;
    altStack.ss_flags = 0;
    sigaltstack(&altStack, nullptr);

    struct sigaction action{};
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = crashSignalHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&action.sa_mask);

    const int signals[] = {SIGHUP, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV};
    for (int sig : signals) {
        sigaction(sig, &action, nullptr);
    }

    g_installed = true;
    LOGI("native crash handler installed, dir=%s", g_crashDir);
}

#endif // __ANDROID__
