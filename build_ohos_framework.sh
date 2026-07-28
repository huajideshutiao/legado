#!/usr/bin/env bash
# 构建 libmykmp_framework.so (鸿蒙 AntUI Compose 桥接层)
# 依赖: OHOS NDK (DevEco Studio 5.x+ 自带)
# 适用: Linux / macOS / Windows(Git Bash/WSL)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

normalize_dir() {
    local candidate="$1"
    if command -v cygpath >/dev/null 2>&1; then
        candidate="$(cygpath -u "$candidate" 2>/dev/null || printf '%s' "$candidate")"
    elif command -v wslpath >/dev/null 2>&1; then
        candidate="$(wslpath -u "$candidate" 2>/dev/null || printf '%s' "$candidate")"
    fi
    printf '%s' "$candidate"
}

# 解析 OHOS NDK 路径: 优先显式参数，其次环境变量，最后平台默认路径
OHOS_NDK=""
if [ -n "${1:-}" ]; then
    candidate="$(normalize_dir "$1")/default/openharmony/native"
    if [ -d "$candidate" ]; then
        OHOS_NDK="$candidate"
    fi
fi
if [ -z "$OHOS_NDK" ] && [ -n "${OHOS_NDK_HOME:-}" ]; then
    candidate="$(normalize_dir "$OHOS_NDK_HOME")"
    if [ -d "$candidate" ]; then
        OHOS_NDK="$candidate"
    fi
fi
if [ -z "$OHOS_NDK" ] && [ -n "${DEVECO_SDK_HOME:-}" ]; then
    candidate="$(normalize_dir "$DEVECO_SDK_HOME")/default/openharmony/native"
    if [ -d "$candidate" ]; then
        OHOS_NDK="$candidate"
    fi
fi
if [ -z "$OHOS_NDK" ]; then
    # 平台默认路径: macOS DevEco Studio / Linux/WSL 通用安装位置
    for candidate in \
        "$HOME/Library/Huawei/Sdk/default/openharmony/native" \
        "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native" \
        "$HOME/Library/Huawei/Sdk/9.0.0/openharmony/native" \
        "$HOME/.ohos-sdk/native" \
        "/usr/local/ohos-sdk/native"; do
        if [ -d "$candidate" ]; then
            OHOS_NDK="$candidate"
            break
        fi
    done
fi

if [ -z "$OHOS_NDK" ] || [ ! -d "$OHOS_NDK" ]; then
    echo "ERROR: OHOS NDK not found"
    echo "SDK argument: ${1:-<empty>}"
    echo "Please install DevEco Studio, or set OHOS_NDK_HOME / DEVECO_SDK_HOME env variable."
    exit 1
fi

echo "Using OHOS NDK: $OHOS_NDK"

BUILD_DIR="build/native_framework_build/arm64-v8a-release"
SRC_DIR="ohosApp/antui_framework/src/main/cpp"
OUT_DIR="ohosApp/entry/libs/arm64-v8a"

CMAKE="$OHOS_NDK/build-tools/cmake/bin/cmake"
if [ -f "${CMAKE}.exe" ]; then
    CMAKE="${CMAKE}.exe"
elif [ ! -x "$CMAKE" ]; then
    CMAKE="$(command -v cmake || true)"
fi
if [ -z "$CMAKE" ] || [ ! -e "$CMAKE" ]; then
    echo "ERROR: CMake not found in OHOS NDK or PATH"
    exit 1
fi

TOOLCHAIN_FILE="$OHOS_NDK/build/cmake/ohos.toolchain.cmake"
NINJA="$OHOS_NDK/build-tools/cmake/bin/ninja"
if [ -f "${NINJA}.exe" ]; then
    NINJA="${NINJA}.exe"
fi
# Windows 原生 CMake 不认识 Git Bash / WSL 的 Unix 路径，传参前转换为 Windows 路径。
if [[ "$CMAKE" == *.exe ]]; then
    if command -v cygpath >/dev/null 2>&1; then
        TOOLCHAIN_FILE="$(cygpath -w "$TOOLCHAIN_FILE")"
        NINJA="$(cygpath -w "$NINJA")"
    elif command -v wslpath >/dev/null 2>&1; then
        TOOLCHAIN_FILE="$(wslpath -w "$TOOLCHAIN_FILE")"
        NINJA="$(wslpath -w "$NINJA")"
    fi
fi

echo "Building libmykmp_framework.so..."
mkdir -p "$BUILD_DIR"

"$CMAKE" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DOHOS_ARCH=arm64-v8a \
    -DCMAKE_BUILD_TYPE=Release \
    -S "$SRC_DIR"

# 跨平台并行核数: Windows/Linux 用 nproc, macOS 用 sysctl
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || printf '4')
"$CMAKE" --build "$BUILD_DIR" --target mykmp_framework -j"$JOBS"

mkdir -p "$OUT_DIR"
cp "$BUILD_DIR/libmykmp_framework.so" "$OUT_DIR/"

echo "Done: $OUT_DIR/libmykmp_framework.so ($(wc -c < "$OUT_DIR/libmykmp_framework.so" | tr -d ' ') bytes)"
