// 原生崩溃捕获 (Android NDK): 信号处理器 + 栈回溯 + 落盘 + 转交系统处理。
//
// # 为什么必须接这一层
// Kotlin 的未捕获异常处理器只覆盖 JVM 异常。NDK/JNI 侧的越界、野指针、栈溢出等
// 直接以 POSIX 信号终止进程, 一条 Java 日志都不会留下 —— 与"没接崩溃捕获"无区别。
//
// # 与 ART / debuggerd 的关系 (依据 AOSP art/sigchainlib/sigchain.cc)
// Android 上 SIGSEGV 是 ART 隐式空指针检查与 debuggerd 都在用的信号, 但**不会冲突**:
// ART 的 sigchain 拦截 sigaction/signal 调用 —— 信号已被 claimed 时, 应用注册的 handler 只
// 被记进链上的用户槽位 (逐字: "chains[signal].SetAction(new_action);"), 不下发内核; 真正的
// 内核入口恒是 SignalChain::Handler。它的顺序是 (逐字):
//   if (!GetHandlingSignal(signo)) { for (special_handlers_) … }
//   if (android_handle_signal != nullptr && android_handle_signal(…)) { return; }
//   chains[signo].action_.sa_sigaction(signo, siginfo, ucontext_raw);
// 即: ART fault handler 等 special handler 先试; android_handle_signal 只对 GWP-ASan 可恢复
// 崩溃等返回 true (直接 return, 根本不进用户槽位), 普通崩溃返回 false; 最后才轮到用户槽位。
//
// 用户槽位是**每信号唯一一个** (chains[signal].action_): bionic 的 sigaction 在信号已被认领时
// 直接 SetAction(新动作) 覆盖旧动作, 旧动作只回填给调用方 (AOSP art/sigchainlib/sigchain.cc)。
// 系统崩溃处理器 (debuggerd_signal_handler) 正是以普通 sigaction 装在这个槽位上的, 因此本
// handler 一旦装上就顶替了它 —— 只要本 handler 自己 _exit, 系统 tombstone 与内核默认处置
// **同时不可达**。
// ⇒ 故本 handler 不自行终止: 安装期用 sigaction 的 old_action 出参保存被顶替的处置, 处理器内
// 写完自有日志后把它交还回去再返回。返回时内核重新执行出错指令、再次触发同一信号, 这次由
// 交还后的系统处理器接管 —— 系统崩溃现场照常产生, 进程也以原信号退出。
// 直接 _exit 会让系统现场不再产生; 直接 return (不交还) 则反复进入本处理器、反复写盘。
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
// 备用栈 (SA_ONSTACK 用): 栈溢出场景下必须切走, 否则 handler 内压栈即二次崩溃
constexpr size_t kAltStackSize = 64 * 1024;

// 安装期预写的日志头 (参数头等): 崩溃时 memcpy 进**本线程的栈缓冲**。
// 不用全局缓冲: 多个线程同时崩溃时两份现场会交叉写坏彼此。
char g_header[kBufferSize];
// 日志头长度 (处理器内从该偏移开始追加)
size_t g_bodyLength = 0;
// 崩溃目录 (安装期快照, 末尾无 '/')
char g_crashDir[kPathMax];
// 标记文件绝对路径 (与崩溃日志同目录)
char g_markerPath[kPathMax];
// 备用栈
char g_altStack[kAltStackSize];
bool g_installed = false;

// 本 handler 接管的信号 (安装与查找共用一张表)
//
// 覆盖面与系统崩溃处理器 (debuggerd 的注册集合) 对齐: 除通用致命信号外还含 SIGSYS
// (seccomp 拦截, 应用沙箱下会发生) 与 SIGSTKFLT。
const int kHandledSignals[] = {SIGHUP, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV, SIGSYS,
                               SIGSTKFLT};
constexpr size_t kHandledSignalCount = sizeof(kHandledSignals) / sizeof(kHandledSignals[0]);

// 被本 handler 顶替的处置 (安装期保存): 写日志后交还, 系统崩溃现场才会产生
struct sigaction g_previousActions[kHandledSignalCount];

// 本地时间相对 UTC 的偏移秒数 (安装期算好)。
// 处理器内不得调 localtime_r: 它的实现会取时区锁 (bionic libc/tzcode/localtime.c 的
// localtime_tzset -> lock), 崩溃发生在同线程持锁期间即自死锁。
long g_tzOffsetSeconds = 0;

/** 信号在注册表中的下标; 未注册返回 -1。只用整数比较。 */
int signalIndexOf(int sig) {
    for (size_t i = 0; i < kHandledSignalCount; i++) {
        if (kHandledSignals[i] == sig) return static_cast<int>(i);
    }
    return -1;
}

// 重入守卫 (线程局部): 写完日志即交还处置, 同线程再次进入说明交还失败、或落盘动作自身又崩,
// 此时只退出、不再写盘 (否则会无限循环写满磁盘)。用线程局部而非进程级 —— 进程级会让
// "另一个线程同时崩溃"直接退出、连一份现场都留不下, 而各线程本来各写各的栈缓冲。
__thread volatile sig_atomic_t g_inHandler = 0;

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
 * 历法换算: epoch 秒 -> 年月日时分秒 (Howard Hinnant 的 civil_from_days, 纯整数运算)。
 *
 * 处理器内不能调 gmtime_r/localtime_r 这类带全局状态与时区锁的函数, 故自算。
 */
void civilFromSeconds(long long seconds, int &year, int &month, int &day, int &hour, int &minute,
                      int &second) {
    long long days = seconds / 86400;
    long long rem = seconds % 86400;
    if (rem < 0) {
        rem += 86400;
        days -= 1;
    }
    hour = static_cast<int>(rem / 3600);
    minute = static_cast<int>((rem % 3600) / 60);
    second = static_cast<int>(rem % 60);
    const long long z = days + 719468;
    const long long era = (z >= 0 ? z : z - 146096) / 146097;
    const unsigned long long doe = static_cast<unsigned long long>(z - era * 146097);
    const unsigned long long yoe =
        (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const long long y = static_cast<long long>(yoe) + era * 400;
    const unsigned long long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned long long mp = (5 * doy + 2) / 153;
    const unsigned long long d = doy - (153 * mp + 2) / 5 + 1;
    const unsigned long long m = mp < 10 ? mp + 3 : mp - 9;
    year = static_cast<int>(y + (m <= 2));
    month = static_cast<int>(m);
    day = static_cast<int>(d);
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

/** 时间戳 (yy-MM-dd-HH-mm-ss), 与 Kotlin 侧文件名口径一致 (本地时间)。
 *
 * 全程整数运算: time() 属 async-signal-safe, 时区偏移在安装期算好。
 */
void appendTimestamp(char *dst, size_t capacity, size_t &at) {
    int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0;
    civilFromSeconds(static_cast<long long>(time(nullptr)) + g_tzOffsetSeconds, year, month, day,
                     hour, minute, second);
    // 两位数补零
    auto append2 = [&](int value) {
        if (at < capacity) dst[at++] = static_cast<char>('0' + (value / 10) % 10);
        if (at < capacity) dst[at++] = static_cast<char>('0' + value % 10);
    };
    append2(year % 100);
    if (at < capacity) dst[at++] = '-';
    append2(month);
    if (at < capacity) dst[at++] = '-';
    append2(day);
    if (at < capacity) dst[at++] = '-';
    append2(hour);
    if (at < capacity) dst[at++] = '-';
    append2(minute);
    if (at < capacity) dst[at++] = '-';
    append2(second);
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
        case SIGSYS: return "SIGSYS";
        case SIGSTKFLT: return "SIGSTKFLT";
        default: return "SIG(unknown)";
    }
}

// ===== 崩溃现场 =====
//
// 本 handler **不做栈回溯**: 可用的展开器 (_Unwind_Backtrace / backtrace / libunwind) 全都不在
// POSIX async-signal-safe 清单内, AOSP 自身也不在崩溃进程内展开 (debuggerd 改为 fork crash_dump
// 后由它 ptrace 读内存; 其回退展开器还要先切到 linker 专用分配器)。
// 记录的是从 ucontext 取到的**出错现场** pc/lr/sp —— 足够定位是哪个 so 崩的; 完整线程列表、
// 内存映射、模块与符号由系统崩溃现场提供 (本 handler 已把处置交还, 系统现场照常产生)。

/**
 * 信号处理器: 记录现场 (崩溃地址 + 寄存器 + 栈回溯), 然后退出进程。
 *
 * **写完不返回**: 从同步信号处理器返回会重新执行出错指令、再次触发同一信号, 于是再次进入本处
 * 理器 —— 每次进入写一份日志, 形成死循环并写满磁盘 (靠 [g_inHandler] 兜底也不该让正常路径
 * 走到那一步)。系统 tombstone 由链更前面的 debuggerd 生成, 与本退出无关。
 */
void crashSignalHandler(int sig, siginfo_t *info, void *ucontextRaw) {
    if (g_inHandler) {
        // 已在处理中又崩一次 (处置交还失败或落盘动作自身又崩): 只退出, 绝不再写盘。
        // 退出码按实际信号取 128+signo, 与 shell 对信号的常规约定一致。
        _exit(128 + (sig & 0x7f));
    }
    g_inHandler = 1;
    // 本线程自己的缓冲: 备用栈 (SA_ONSTACK) 装得下, 多线程同时崩溃时各写各的、不交叉写坏
    char buffer[kBufferSize];
    memcpy(buffer, g_header, g_bodyLength);
    size_t at = g_bodyLength;
    appendStr(buffer, kBufferSize, at, "\nsignal=");
    appendStr(buffer, kBufferSize, at, signalName(sig));
    appendUnsigned(buffer, kBufferSize, at, static_cast<unsigned long long>(sig));
    appendStr(buffer, kBufferSize, at, "\nsource=native-signal\n");

    if (info != nullptr) {
        appendStr(buffer, kBufferSize, at, "si_code=");
        appendUnsigned(buffer, kBufferSize, at,
                       static_cast<unsigned long long>(info->si_code));
        appendStr(buffer, kBufferSize, at, "\nfault_addr=");
        appendHex(buffer, kBufferSize, at,
                  reinterpret_cast<unsigned long long>(info->si_addr));
        appendStr(buffer, kBufferSize, at, "\n");
    }

    // 崩溃现场的指令指针 (从 ucontext 取, 不是 handler 自己的)
    if (ucontextRaw != nullptr) {
        auto *uc = static_cast<ucontext_t *>(ucontextRaw);
#if defined(__aarch64__)
        appendStr(buffer, kBufferSize, at, "pc=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.pc));
        appendStr(buffer, kBufferSize, at, "\nlr=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.regs[30]));
        appendStr(buffer, kBufferSize, at, "\nsp=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.sp));
        appendStr(buffer, kBufferSize, at, "\n");
#elif defined(__arm__)
        appendStr(buffer, kBufferSize, at, "pc=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_pc));
        appendStr(buffer, kBufferSize, at, "\nlr=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_lr));
        appendStr(buffer, kBufferSize, at, "\nsp=");
        appendHex(buffer, kBufferSize, at,
                  static_cast<unsigned long long>(uc->uc_mcontext.arm_sp));
        appendStr(buffer, kBufferSize, at, "\n");
#endif
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
    writeFileAll(path, buffer, at);

    static const char marker[] = "1\n";
    writeFileAll(g_markerPath, marker, sizeof(marker) - 1);

    // 交还被顶替的处置后返回 (见文件头): 返回时内核重新执行出错指令、再次触发同一信号, 这次
    // 走交还回来的系统处理器 —— 系统崩溃现场照常产生、进程以原信号退出。
    // 若交还失败 (sigaction 返回 -1), 再次进入本处理器时由 g_inHandler 直接退出, 不写盘。
    const int index = signalIndexOf(sig);
    if (index >= 0) {
        sigaction(sig, &g_previousActions[index], nullptr);
    }
}

/** 安装期算本地时间相对 UTC 的偏移秒数 (处理器内只用整数加法)。 */
void initTimezoneOffset() {
    const time_t now = time(nullptr);
    struct tm localTm{};
    struct tm utcTm{};
    if (localtime_r(&now, &localTm) == nullptr || gmtime_r(&now, &utcTm) == nullptr) return;
    const time_t localAsUtc = timegm(&localTm);
    if (localAsUtc == static_cast<time_t>(-1)) return;
    g_tzOffsetSeconds = static_cast<long>(localAsUtc - now);
}

/** 安装期把参数头 (与 Kotlin 侧 NativeCrashLogWriter 同项) 写进缓冲。 */
void buildParamsHeader() {
    size_t at = 0;
    appendStr(g_header, kBufferSize, at, "crashKind=native-signal\n");
    appendStr(g_header, kBufferSize, at, "TIME=");
    appendTimestamp(g_header, kBufferSize, at);
    appendStr(g_header, kBufferSize, at, "\n");
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

    // 时区偏移 (处理器内不能调时区函数, 在此一次算好)
    initTimezoneOffset();

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

    // 保存被顶替的处置 (旧动作只经 old_action 出参回填, 见文件头): 处理器写完后交还
    for (size_t i = 0; i < kHandledSignalCount; i++) {
        sigaction(kHandledSignals[i], &action, &g_previousActions[i]);
    }

    g_installed = true;
    LOGI("native crash handler installed, dir=%s", g_crashDir);
}

#endif // __ANDROID__
