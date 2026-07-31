# quickjs-ng（vendored 上游副本）

> ⚠️ **本目录禁止直接修改。**
>
> 这里的每个文件都应当与上游 tag 逐字节一致。需要定制行为时，按以下优先级选择：
>
> 1. **cinterop / JNI wrapper 层**（首选）——在 `shared/src/cinterop/quickjs.def`、
>    `modules/quickjs-android-native/src/main/cpp/` 的 wrapper 里加代码，不碰 C 源码。
> 2. **编译开关**——在各消费方的 CMakeLists / 编译参数里加 `-D` 宏，不改源码。
> 3. **patch 文件**（万不得已）——把改动做成 `patches/*.patch`，并登记到本文末尾
>    「已知本地改动」小节。同步工作流会在覆盖源码后重新应用 patch。
>
> 直接编辑源码会在下次执行同步工作流时被**静默覆盖**，且没有任何记录能说明改了什么、为什么改。

## 版本

| 项 | 值 |
| --- | --- |
| 上游仓库 | https://github.com/quickjs-ng/quickjs |
| **当前 pin** | commit `5f2fb55994413afcaeec2942021cc93bfafd0f81`（2026-06-27，*Port Bellard's register-based regexp engine*） |
| 所处区间 | 在 `v0.15.1` 之后、`v0.16.0` 之前的 master 快照（距 `v0.16.0` 还差 62 个提交） |
| 核对方式 | 22 个文件的 blob hash 与该 commit 逐字节一致（LF 归一后） |

> ⚠️ **别信 `quickjs.h` 里的版本宏。** 它写着 `QJS_VERSION_MAJOR/MINOR/PATCH = 0/15/1`，
> 但本目录**不是 v0.15.1**。上游只在打 tag 时才 bump 版本宏，所以 v0.15.1 发布之后的
> 每一个 master 提交都仍然自称 0.15.1。
>
> 实测差异很大：本目录的 `libregexp.c` 已经是 Bellard 的寄存器式正则引擎
> （`REGISTER_COUNT_MAX` / `REString`），而 v0.15.1 还是老的栈式引擎
> （`STACK_SIZE_MAX`），两者相差约 29 KB。**按 `v0.15.1` 去"还原"本目录等于降级。**

升级时**必须同时更新**本表格与 `.github/workflows/sync-quickjs-ng.yml` 里 `ref` input 的默认值。

下一个自然升级目标是 tag `v0.16.0`。

## 为何是 vendored 副本而不是 git submodule

本目录早期是 submodule（`.git/modules/` 至今仍有残留），后改为 vendored，原因：

- **多端构建友好**：Android/JVM 的 CMake、iOS/鸿蒙的 cinterop、鸿蒙 native 的 CMake
  一共 5 处消费方引用同一份源码。submodule 一旦没初始化，这 5 条构建线全部以
  「找不到头文件」的形式失败，报错信息离根因很远。
- **CI 检出友好**：CI 不必给每个 job 都配 `submodules: recursive`，也不受上游仓库
  可用性影响；上游删 tag / 改历史不会让历史提交变得无法构建。
- **cinterop 需要稳定相对路径**：`quickjs.def` 的 `includeDirs` 与
  `OhosTargetConventionPlugin` 的 `includeDirs` 都写死了相对路径，submodule 的
  checkout 时机不确定，容易在 configuration 阶段就取不到目录。
- **只需要 22 个文件**：上游仓库还带着 test262、fuzz、examples、CMake 工程等大量
  与本项目无关的内容，vendored 只取核心源码，仓库体积可控。

代价是「升级不再是 `git submodule update`」——这个代价由本目录的同步工作流补偿。

## 消费方清单（改动源码会同时影响这 5 处）

| # | 平台 | 文件 | 引用方式 |
| --- | --- | --- | --- |
| 1 | Android / JVM (JNI) | `modules/quickjs-android-native/src/main/cpp/CMakeLists.txt` | `set(QUICKJS_NG_DIR "${LEGADO_PROJECT_ROOT}/shared/src/cinterop/quickjs-ng")` |
| 2 | 鸿蒙 native (.so) | `ohosApp/entry/src/main/cpp/CMakeLists.txt` | `set(LEGADO_QUICKJS_NG_DIR .../shared/src/cinterop/quickjs-ng)` |
| 3 | iOS cinterop | `shared/src/cinterop/quickjs.def` + `shared/build.gradle.kts` (`includeDirs`) | `#include "quickjs.h"` + wrapper 函数 |
| 4 | 鸿蒙 cinterop | `build-logic/src/ohos/kotlin/io/legado/buildlogic/OhosTargetConventionPlugin.kt` | `includeDirs(File(cinteropDir, "quickjs-ng"))` |
| 5 | iOS 静态库预编译 | `scripts/build-ios-native.sh` | `QUICKJS_DIR="$ROOT_DIR/shared/src/cinterop/quickjs-ng"` |

上层 Kotlin 消费方（仅供定位，不直接读本目录）：
`modules/quickjs/`（JVM/Android 引擎）、`shared/src/nativeMain/.../NativeJsEngine.native.kt`（iOS/鸿蒙引擎）。

### 实际参与编译的源文件

各消费方只编译 4 个 `.c`（`cutils.c` 在 quickjs-ng 中已并入 `quickjs.c`）：

```
quickjs.c  libregexp.c  libunicode.c  dtoa.c
```

其余 `.h` / `.js` 均为它们的依赖（`.js` 是 `builtin-*.h` 的生成源，构建期不使用，
保留是为了让 `builtin-*.h` 的来源可追溯）。

## 升级流程

### 1. 跑同步工作流

GitHub → Actions → **Sync quickjs-ng** → Run workflow：

- `ref`：目标 tag / 分支 / commit sha，例如 `v0.16.0`
- `mode`：`sync`

工作流会逐文件从上游拉取、覆盖、打印 diff，然后开一个 PR（**不会直推 master**）。

本地等价操作（用于离线核对单个文件）：

```bash
REF=5f2fb55994413afcaeec2942021cc93bfafd0f81
curl -sL --ssl-no-revoke \
  "https://raw.githubusercontent.com/quickjs-ng/quickjs/$REF/quickjs.h" \
  -o /tmp/quickjs.h
# 本地工作树是 CRLF（见下），比对前必须归一化
tr -d '\r' < shared/src/cinterop/quickjs-ng/quickjs.h > /tmp/local.h
diff -u /tmp/quickjs.h /tmp/local.h
```

> **行尾说明**：仓库 `core.autocrlf=true`，本目录在 Windows 工作树里是 CRLF，但
> **committed blob 是 LF**，与上游一致。这不是本地改动，**不要手动转换行尾**——
> 转了也会在下次 checkout 时被 git 变回去。任何比对脚本都必须先 `tr -d '\r'`。

### 2. 审 diff

重点看：

- 有没有 API 签名变化打断 `quickjs.def` 的 wrapper（`qjs_*` 系列函数）——这是最容易
  漏的一处，wrapper 编译失败的报错离根因很远；
- 有没有新增 / 删除 `.c` 文件，需要同步改 5 处消费方的源文件列表；
- `quickjs.h` 的 `QJS_VERSION_*` 仅作参考，**不能用来判定版本**（见上文「版本」小节的警告），
  以本文件记录的 commit sha 为准；
- 本目录「已知本地改动」小节登记的 patch 是否仍能干净应用（当前为空）。

### 3. 三端编译验证（本地跑，CI 不覆盖 native 编译）

```bash
# Android / JVM（JNI + CMake）
./gradlew :modules:quickjs-android-native:externalNativeBuildAppDebug
./gradlew :modules:quickjs:compileDebugKotlinAndroid

# Desktop / shared-jvm
./gradlew :shared:compileKotlinJvm

# iOS（仅 macOS 可跑）
bash scripts/build-ios-native.sh
./gradlew :shared:cinteropQuickjsIosArm64

# 鸿蒙（需鸿蒙 SDK）
./gradlew :shared:cinteropQuickjsLinuxArm64
```

> 本机只有 Windows，`app` / `desktop` / `shared-jvm` 三条可跑；iOS / 鸿蒙两条须在
> 对应环境验证，结果不可在本机臆测。

### 4. 更新本文件

改「版本」表格里的 **commit sha**（不是 tag 名——即使你同步的是 tag，也把该 tag 指向的
commit sha 记下来，这是唯一能精确复现的坐标），并在「已知本地改动」小节记录本次是否
引入 / 移除了 patch。

## 纯净度校验

`.github/workflows/sync-quickjs-ng.yml` 的 `mode: verify` 只比对不修改，用于确认
没有人绕过流程偷改源码。建议在怀疑构建行为异常时先跑一次 verify。

## 已知本地改动

**审计结论（2026-07-31，基准 = 上游 commit `5f2fb55`）：本目录是纯净的上游副本，零本地改动。**

22 个文件的 git blob hash 与上游该 commit 逐一相等（LF 归一后），无一例外。

### A 类 · IDE 误改（行尾 / 空白 / BOM / 格式化）

<!-- AUDIT-A-START -->
**无。不需要还原，也请不要"顺手"还原。**

Windows 工作树里 22 个文件都是 CRLF，看起来像被 IDE 改过，实际不是：
仓库 `core.autocrlf=true`，committed blob 全部是 LF，与上游一致，`git status` 干净。
CRLF 只是 checkout 时 git 自己加的。手动转成 LF 属于无效改动——下次 checkout 会变回去，
中途还会白白污染 diff、干扰正在读本目录编译静态库的构建。

无 BOM，无行尾空白差异，无重新格式化。
<!-- AUDIT-A-END -->

### B 类 · 实质改动（逻辑 / 宏 / 平台 `#ifdef` / 分配器 / 编译开关）

<!-- AUDIT-B-START -->
**无。** 没有任何逻辑改动、宏改写、平台条件分支、内存分配器替换或源码内编译开关。

所有平台适配都已经正确地做在了源码之外——各消费方 CMakeLists 的 `-D` 定义、
`quickjs.def` 里的 `qjs_*` wrapper 函数——这正是本目录顶部要求的做法，请继续保持。
<!-- AUDIT-B-END -->

### 上游有而本地缺失的文件

<!-- AUDIT-MISSING-START -->
**唯一需要补的：`LICENSE`**（MIT，Bellard / Gorelli 等）。vendored 第三方源码应当随带
上游许可证原件，建议下次同步时一并取回（同步工作流已把它列入文件清单）。

其余上游根目录文件均为 CLI / 测试 / 工具，**故意不 vendored**，不必补：

```
api-test.c  ctest.c  fuzz.c  lre-test.c  qjs.c  qjsc.c
qjs-wasi-reactor.c  quickjs-libc.c  quickjs-libc.h
run-test262.c  unicode_gen.c
```

已核对 4 个 `.c` 的 `#include` 闭包（`quickjs.c` / `libregexp.c` / `libunicode.c` / `dtoa.c`），
全部落在本目录现有的 22 个文件内，无悬空依赖。
<!-- AUDIT-MISSING-END -->
