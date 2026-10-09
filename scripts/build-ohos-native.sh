#!/usr/bin/env bash
# 鸿蒙 shared 库 (.so + 生成的头文件) 的一站式本地/自托管校验入口。
#
# 定位: CI-hosted runner 跑不了鸿蒙 (共享库依赖的 CPF fork 构件只在私有 Nexus
# maven.eazytec-cloud.com, 且 HAP 打包需工程 targetSdkVersion 6.1.1(24) 对应的
# HarmonyOS SDK, 无 API 24 公开下载渠道 —— 查证结论见 .github/workflows/test.yml 的
# 鸿蒙注释块)。故这里只把"能本地做的那部分"固化成一条命令, 供开发者与将来的
# self-hosted runner 调用; 本脚本不引入任何新的外部服务与密钥。
#
# 做的事: 前置检查 -> 转调仓库既有的 verifyOhosNativeLibraries 任务 (它 dependsOn
#         stageOhosNativeLibraries, 并自带产物完整性校验), 把 release .so 与派生的
#         C 头文件拷进 ohosApp/。不做 HAP 打包 (那一步需要 DevEco Studio 的 hvigorw,
#         见下方"后续步骤")。
#
# 用法:
#   scripts/build-ohos-native.sh                 # release 构建 (默认)
#   scripts/build-ohos-native.sh --debug         # debug 构建 (带符号, ~50-70MB)
#   scripts/build-ohos-native.sh --verify-only   # 只校验产物已就位, 不重新构建
#   scripts/build-ohos-native.sh -- --dry-run    # "--" 之后原样透传给 gradlew
#
# 前置条件:
#   1. 能访问 maven.eazytec-cloud.com (CPF fork 的 kotlin/compose/room3/sqlite 构件)
#   2. JDK 21 (与 CI 一致)
# 产物:
#   ohosApp/entry/libs/arm64-v8a/liblegado_shared.so
#   ohosApp/entry/src/main/cpp/include/arm64-v8a/*.h
#   (源: ui/build/bin/ohosArm64/<buildType>Shared/)
#
# 后续步骤 (本脚本不代做, 需 HarmonyOS SDK):
#   在 DevEco Studio 的 SDK Manager 装 HarmonyOS SDK 6.1.1 (API 24), 然后在 ohosApp/
#   目录下用 DevEco 自带的 hvigorw 打包 (仓库未提交 hvigorw 包装脚本):
#     hvigorw assembleHap --mode module -p product=default --no-daemon
#   产物: ohosApp/entry/build/default/outputs/default/entry-default-{unsigned,signed}.hap
#   签名配置见 ohosApp/build-profile.json5 的 signingConfigs。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

BUILD_TYPE="release"
VERIFY_ONLY=0
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --debug)
            BUILD_TYPE="debug"
            shift
            ;;
        --release)
            BUILD_TYPE="release"
            shift
            ;;
        --verify-only)
            VERIFY_ONLY=1
            shift
            ;;
        -h|--help)
            # 打印本文件头部的注释块 (用法/前置条件/产物), 避免 usage 文本维护两份
            sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --)
            shift
            EXTRA_ARGS=("$@")
            break
            ;;
        *)
            echo "[ohos-native] ERROR: 未知参数 '$1' (试 --help)" >&2
            exit 2
            ;;
    esac
done

LIB_DIR="$ROOT_DIR/ohosApp/entry/libs/arm64-v8a"
INCLUDE_DIR="$ROOT_DIR/ohosApp/entry/src/main/cpp/include/arm64-v8a"
STAGED_BUILD_DIR="$ROOT_DIR/ui/build/bin/ohosArm64/${BUILD_TYPE}Shared"

# 路径含空格时 gradlew 的路径转换与 native 工具链都可能出怪错, 提前失败更省事
case "$ROOT_DIR" in
    *" "*)
        echo "[ohos-native] ERROR: 项目路径含空格 ($ROOT_DIR), 本脚本不支持" >&2
        exit 1
        ;;
esac

if ! command -v java >/dev/null 2>&1; then
    echo "[ohos-native] ERROR: 未找到 java, 需要 JDK 21" >&2
    exit 1
fi

# 任务名从根 build.gradle.kts 的 ohosBuildType 推导, 与其 rules 保持一致:
# release -> :ui:linkReleaseSharedOhosArm64, debug -> :ui:linkDebugSharedOhosArm64
if [ "$BUILD_TYPE" = "debug" ]; then
    LINK_TASK=":ui:linkDebugSharedOhosArm64"
else
    LINK_TASK=":ui:linkReleaseSharedOhosArm64"
fi

echo "======================================================================"
echo "[ohos-native] ROOT_DIR    = $ROOT_DIR"
echo "[ohos-native] BUILD_TYPE  = $BUILD_TYPE"
echo "[ohos-native] 任务         = :verifyOhosNativeLibraries (依赖 $LINK_TASK 与 :stageOhosNativeLibraries)"
echo "[ohos-native] 产物目录     = $STAGED_BUILD_DIR"
echo "[ohos-native] ohosApp 落地 = entry/libs/arm64-v8a + entry/src/main/cpp/include/arm64-v8a"
echo "[ohos-native] java        = $(java -version 2>&1 | head -1)"
echo "======================================================================"

verify_staged() {
    local missing=0
    if [ ! -f "$LIB_DIR/liblegado_shared.so" ]; then
        echo "[ohos-native] MISSING: $LIB_DIR/liblegado_shared.so" >&2
        missing=1
    fi
    if ! compgen -G "$INCLUDE_DIR/*.h" >/dev/null; then
        echo "[ohos-native] MISSING: $INCLUDE_DIR/*.h (无生成的头文件)" >&2
        missing=1
    fi
    [ "$missing" -eq 0 ]
}

if [ "$VERIFY_ONLY" -eq 1 ]; then
    echo "[ohos-native] --verify-only: 只校验已就位产物, 不构建"
    if ! verify_staged; then
        echo "[ohos-native] ERROR: 产物未就位, 去掉 --verify-only 先跑一次构建" >&2
        exit 1
    fi
    echo "[ohos-native] 校验通过"
    ls -lh "$LIB_DIR/liblegado_shared.so"
    ls -lh "$INCLUDE_DIR"/*.h
    exit 0
fi

if [ ! -x "$ROOT_DIR/gradlew" ]; then
    echo "[ohos-native] ERROR: 缺可执行的 gradlew" >&2
    exit 1
fi

echo "[ohos-native] --- 构建 + stage + 校验 (转调仓库既有任务) ---"
# enableOhosTarget=true 才会注册 ohosArm64 target 并把工具链切到 CPF 分支;
# rendererBackend 已在 gradle.properties 固定为 fusion-renderer, 无需在此重复传。
# 用 verifyOhosNativeLibraries 而非 stageOhosNativeLibraries: 前者自带产物完整性校验
# (缺 .so 或缺头文件即失败), 校验规则只维护在根 build.gradle.kts 一处。
./gradlew verifyOhosNativeLibraries \
    -PenableOhosTarget=true \
    "-PohosBuildType=$BUILD_TYPE" \
    --console=plain \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}

echo "[ohos-native] --- 确认 ohosApp 落地产物存在 ---"
if ! verify_staged; then
    echo "[ohos-native] ERROR: stage 任务返回成功但产物不完整, 拒绝放行" >&2
    exit 1
fi

echo "======================================================================"
echo "[ohos-native] 完成。shared 库与头文件已就位:"
ls -lh "$LIB_DIR/liblegado_shared.so"
ls -lh "$INCLUDE_DIR"/*.h
echo "[ohos-native] 下一步 (需 HarmonyOS SDK API 24): 在 ohosApp/ 下用 DevEco 的 hvigorw"
echo "[ohos-native]   hvigorw assembleHap --mode module -p product=default --no-daemon"
echo "======================================================================"
