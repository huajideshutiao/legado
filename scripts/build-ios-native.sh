#!/usr/bin/env bash
# 为 iOS target 预编译 quickjs-ng / mbedtls 静态库。
# cinterop 只编译 .def 内的 wrapper, 不编 includeDirs 下的 C 源, 缺 .a 时 framework link 阶段必然未定义符号。
#
# 用法: scripts/build-ios-native.sh [ios_arm64|ios_simulator_arm64 ...]   (默认两个都编)
# 产物: shared/build/iosNativeLibs/<konanTarget>/libquickjs.a, libmbedtls.a
#       目录名刻意用 konanTarget.name, 与 shared/build.gradle.kts 的 linkerOpts 一一对应。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUICKJS_DIR="$ROOT_DIR/shared/src/cinterop/quickjs-ng"
MBEDTLS_DIR="$ROOT_DIR/shared/src/cinterop/mbedtls"
OUT_ROOT="$ROOT_DIR/shared/build/iosNativeLibs"
# 与 iosApp/project.yml 的 deploymentTarget.iOS 保持一致
IOS_MIN_VERSION="14.0"

# 编译命令走 xargs 拼串, 路径含空格会被词分割打散; 提前失败比编到一半报怪错好。
case "$ROOT_DIR" in
    *" "*)
        echo "[ios-native] ERROR: 项目路径含空格 ($ROOT_DIR), 本脚本不支持"
        exit 1
        ;;
esac

if ! command -v xcrun >/dev/null 2>&1; then
    echo "[ios-native] ERROR: 未找到 xcrun, 本脚本只能在装了 Xcode 的 macOS 上运行"
    exit 1
fi

JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
TARGET_LIST="${*:-ios_arm64 ios_simulator_arm64}"

echo "======================================================================"
echo "[ios-native] ROOT_DIR   = $ROOT_DIR"
echo "[ios-native] OUT_ROOT   = $OUT_ROOT"
echo "[ios-native] TARGETS    = $TARGET_LIST"
echo "[ios-native] JOBS       = $JOBS"
echo "[ios-native] xcodebuild = $(xcodebuild -version 2>/dev/null | tr '\n' ' ')"
echo "[ios-native] clang      = $(xcrun --sdk iphoneos clang --version 2>/dev/null | head -1)"
echo "======================================================================"

# $1=库名(quickjs/mbedtls) $2=编译参数串 $3=obj 输出目录 $4=.a 输出目录, 其余为源文件
compile_and_archive() {
    local lib_name="$1"; shift
    local flags="$1"; shift
    local obj_dir="$1"; shift
    local out_dir="$1"; shift

    rm -rf "$obj_dir"
    mkdir -p "$obj_dir" "$out_dir"

    echo "[ios-native] --- 编译 $lib_name ($# 个源文件) ---"
    echo "[ios-native] flags: $flags"

    export LG_CC="$CC_BIN" LG_FLAGS="$flags" LG_OBJDIR="$obj_dir"
    # xargs -P 并行; 任一 clang 失败 xargs 返回非零, set -e 中止。sh -c 内 set -x 打印每条命令。
    printf '%s\n' "$@" | xargs -P "$JOBS" -n 1 sh -c '
        set -ex
        "$LG_CC" $LG_FLAGS -c "$1" -o "$LG_OBJDIR/$(basename "$1" .c).o"
    ' _

    # libtool 而非 ar: Apple 官方静态库归档工具; pk_ecc.c 在 EC 全关时编出空目标文件, 屏蔽其告警。
    "$LIBTOOL_BIN" -static -no_warning_for_no_symbols -o "$out_dir/lib${lib_name}.a" "$obj_dir"/*.o
    rm -rf "$obj_dir"

    echo "[ios-native] 产物: $out_dir/lib${lib_name}.a"
    ls -lh "$out_dir/lib${lib_name}.a"
    lipo -info "$out_dir/lib${lib_name}.a"
}

for KONAN_TARGET in $TARGET_LIST; do
    case "$KONAN_TARGET" in
        ios_arm64)
            SDK="iphoneos"
            TRIPLE="arm64-apple-ios${IOS_MIN_VERSION}"
            ;;
        ios_simulator_arm64)
            SDK="iphonesimulator"
            TRIPLE="arm64-apple-ios${IOS_MIN_VERSION}-simulator"
            ;;
        *)
            echo "[ios-native] ERROR: 未知 target '$KONAN_TARGET' (仅支持 ios_arm64 / ios_simulator_arm64)"
            exit 1
            ;;
    esac

    CC_BIN="$(xcrun --sdk "$SDK" --find clang)"
    LIBTOOL_BIN="$(xcrun --sdk "$SDK" --find libtool)"
    SYSROOT="$(xcrun --sdk "$SDK" --show-sdk-path)"
    OUT_DIR="$OUT_ROOT/$KONAN_TARGET"
    OBJ_ROOT="$ROOT_DIR/shared/build/tmp/iosNativeObj/$KONAN_TARGET"

    echo "======================================================================"
    echo "[ios-native] target  = $KONAN_TARGET"
    echo "[ios-native] sdk     = $SDK"
    echo "[ios-native] triple  = $TRIPLE"
    echo "[ios-native] sysroot = $SYSROOT"
    echo "[ios-native] cc      = $CC_BIN"
    echo "======================================================================"

    # 目标码不开 -fvisibility=hidden / LTO: 前者在 Mach-O 上会把符号标成 private extern,
    # 后者产出 bitcode 归档, 两者都可能干扰 K/N 的 ld 解析, 首版求稳。
    COMMON_FLAGS="-target $TRIPLE -isysroot $SYSROOT -O2 -fPIC"

    # 编译参数依据: modules/quickjs-android-native 的 CMakeLists 与 ohosApp/entry .../CMakeLists.txt
    # (quickjs 4 个 .c; cutils 已并入 quickjs.c)
    QUICKJS_FLAGS="$COMMON_FLAGS -std=gnu11 -D_GNU_SOURCE -DQUICKJS_NG_BUILD -I$QUICKJS_DIR \
-Wno-unused-parameter -Wno-sign-compare -Wno-missing-field-initializers -Wno-implicit-fallthrough"
    compile_and_archive quickjs "$QUICKJS_FLAGS" "$OBJ_ROOT/quickjs" "$OUT_DIR" \
        "$QUICKJS_DIR/quickjs.c" \
        "$QUICKJS_DIR/dtoa.c" \
        "$QUICKJS_DIR/libregexp.c" \
        "$QUICKJS_DIR/libunicode.c"

    # mbedtls: 与 mbedtls.def 的 compilerOpts / ohos CMakeLists 同一套 config 宏与 include 路径
    MBEDTLS_FLAGS="$COMMON_FLAGS -std=gnu99 -DMBEDTLS_CONFIG_FILE=\"legado_mbedtls_config.h\" \
-I$MBEDTLS_DIR/include -I$MBEDTLS_DIR"
    # shellcheck disable=SC2046
    compile_and_archive mbedtls "$MBEDTLS_FLAGS" "$OBJ_ROOT/mbedtls" "$OUT_DIR" \
        $(ls "$MBEDTLS_DIR"/library/*.c)
done

echo "======================================================================"
echo "[ios-native] 全部完成, 产物清单:"
find "$OUT_ROOT" -name "*.a" -exec ls -lh {} \;
echo "======================================================================"
