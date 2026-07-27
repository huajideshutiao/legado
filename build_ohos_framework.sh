#!/usr/bin/env bash
# 构建 libmykmp_framework.so (鸿蒙 AntUI Compose 桥接层)
# 依赖: OHOS NDK (DevEco Studio 5.x 自带)
# 适用: Linux / macOS / Windows(WSL)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 解析 OHOS NDK 路径: 优先 OHOS_NDK_HOME, 其次 DEVECO_SDK_HOME, 最后平台默认路径
if [ -n "$OHOS_NDK_HOME" ] && [ -d "$OHOS_NDK_HOME" ]; then
    OHOS_NDK="$OHOS_NDK_HOME"
elif [ -n "$DEVECO_SDK_HOME" ] && [ -d "$DEVECO_SDK_HOME/default/openharmony/native" ]; then
    OHOS_NDK="$DEVECO_SDK_HOME/default/openharmony/native"
else
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
    echo "Please install DevEco Studio 5.x, or set OHOS_NDK_HOME / DEVECO_SDK_HOME env variable."
    echo "Alternatively, place a prebuilt libmykmp_framework.so in ohosApp/entry/libs/arm64-v8a/"
    exit 1
fi

echo "Using OHOS NDK: $OHOS_NDK"

BUILD_DIR="build/native_framework_build"
SRC_DIR="ohosApp/antui_framework/src/main/cpp"
OUT_DIR="ohosApp/entry/libs/arm64-v8a"

# 已有预构建产物则跳过
if [ -f "$OUT_DIR/libmykmp_framework.so" ]; then
    echo "Found existing: $OUT_DIR/libmykmp_framework.so ($(wc -c < "$OUT_DIR/libmykmp_framework.so" | tr -d ' ') bytes), skip build."
    exit 0
fi

echo "Building libmykmp_framework.so..."
mkdir -p "$BUILD_DIR"

cmake -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$OHOS_NDK/build/cmake/ohos.toolchain.cmake" \
    -DOHOS_ARCH=arm64-v8a \
    -DCMAKE_BUILD_TYPE=Release \
    -S "$SRC_DIR"

# 跨平台并行核数: macOS 用 sysctl, Linux/WSL 用 nproc
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)
cmake --build "$BUILD_DIR" --target mykmp_framework -j"$JOBS"

mkdir -p "$OUT_DIR"
cp "$BUILD_DIR/libmykmp_framework.so" "$OUT_DIR/"

echo "Done: $OUT_DIR/libmykmp_framework.so ($(wc -c < "$OUT_DIR/libmykmp_framework.so" | tr -d ' ') bytes)"
