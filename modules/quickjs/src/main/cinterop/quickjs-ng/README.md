# quickjs-ng（vendored 上游副本）

> ⚠️ **本目录禁止直接修改。**
>
> 这里的每个文件都应当与下表 pin 的上游 commit 逐字节一致。需要定制行为时，按以下优先级选择：
>
> 1. **cinterop / JNI wrapper 层**（首选）——在 `shared/src/cinterop/quickjs.def`、
>    `modules/quickjs-android-native/src/main/cpp/` 的 wrapper 里加代码，不碰 C 源码。
> 2. **编译开关**——在各消费方的 CMakeLists / 编译参数里加 `-D` 宏，不改源码。
> 3. **改源码**（万不得已，仅限上游 bug）——只有在上游自己的代码有内存安全 / 正确性
>    缺陷、且 wrapper 层无法规避时才允许，改完必须登记到本文末尾「已知本地改动」的
>    B 类小节，写清上游版本、症状、复现方式。
>
> ⚠️ **同步工作流不会替你重放任何补丁。** `.github/workflows/sync-quickjs-ng.yml` 只有
> `Apply upstream files`（直接 `cp` 覆盖），没有 apply-patch 步骤，也没有 `patches/` 目录。
> 所以 `mode=sync` 会把本地改动**静默冲掉**，`mode=verify` 会因为存在差异直接 exit 1 ——
> 每次升级都必须照着 B 类小节的记录手工重新打一遍，并在 PR 里勾掉那一项。
> （当前 B 类为空，无补丁需要重放。）

## 版本

| 项 | 值 |
| --- | --- |
| 上游仓库 | https://github.com/quickjs-ng/quickjs |
| **当前 pin** | commit `02368b6b1689c28ff2ebe6d1f73d9c1893ae4ce7`（master 快照，2026-09-05） |
| 所处区间 | release tag `v0.16.2`（`1ab8676`）之后 19 个 commit，v0.16.3 尚未发布（2026-09-05 同步；此前 pin 依次为 `5f2fb55`、`1ab8676`） |
| 升级动机 | 上游 PR #1709 修掉了 `JS_ToCStringLenUTF16()` 的宽字符子串缺陷（本项目 2026-09-03 上报 issue #1708，翌日合入 master，无 release tag 含此修复），本地 B-1 补丁随之撤销 |
| 核对方式 | 23 个文件的 git blob hash 与该 commit 的上游 tree 逐一相等（`git hash-object` vs `git/trees` API），零本地改动 |

> ⚠️ **别单独信 `quickjs.h` 里的版本宏判定快照版本。** 上游只在打 tag 时才 bump
> `QJS_VERSION_MAJOR/MINOR/PATCH`，master 快照会一直自称上一个 release 的版本号
> （实测差异很大：`5f2fb55` 时期的 `libregexp.c` 已是 Bellard 寄存器式正则引擎，却仍自称
> 0.15.1，与真 v0.15.1 相差约 29 KB）。**当前 pin 正是这种情况**：它是 v0.16.2 之后的 master
> 快照，宏值却仍写 `0/16/2`。一切以上表 commit sha 为准，**按宏值去"还原"等于降级**。

升级时**必须同时更新**本表格与 `.github/workflows/sync-quickjs-ng.yml` 里 `ref` input 的默认值。

升级坐标记录方式：同步 tag 时也记 tag 指向的 commit sha（唯一能精确复现的坐标）。

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
- **只需要 23 个文件**：上游仓库还带着 test262、fuzz、examples、CMake 工程等大量
  与本项目无关的内容，vendored 只取核心源码（22 个）加上游 `LICENSE`，仓库体积可控。

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

- `ref`：目标 tag / 分支 / commit sha，例如 `v0.16.3`（没有合适 tag 时直接写 master 快照的 sha）
- `mode`：`sync`

工作流会逐文件从上游拉取、覆盖、打印 diff，然后开一个 PR（**不会直推 master**）。

本地等价操作（用于离线核对单个文件）：

```bash
REF=02368b6b1689c28ff2ebe6d1f73d9c1893ae4ce7
curl -sL --ssl-no-revoke \
  "https://raw.githubusercontent.com/quickjs-ng/quickjs/$REF/quickjs.h" \
  -o /tmp/quickjs.h
# 本地工作树是 CRLF（见下），比对前必须归一化
tr -d '\r' < shared/src/cinterop/quickjs-ng/quickjs.h > /tmp/local.h
diff -u /tmp/quickjs.h /tmp/local.h
```

> **行尾说明**：仓库 `core.autocrlf=true`，本目录经 git checkout 落到 Windows 工作树后是
> CRLF（刚跑过同步、由 raw 下载直接覆盖的文件则是 LF），但**committed blob 一律是 LF**，
> 与上游一致。两种状态 `git status` 都干净，**不要手动转换行尾**——转了也会在下次
> checkout 时被 git 变回去。任何按内容比对的脚本都必须先 `tr -d '\r'`；按 blob 比对
> （`git hash-object` vs 上游 `git/trees`）则天然免疫行尾差异，更省事。

### 2. 审 diff

重点看：

- 有没有 API 签名变化打断 `quickjs.def` 的 wrapper（`qjs_*` 系列函数）——这是最容易
  漏的一处，wrapper 编译失败的报错离根因很远；
- 有没有新增 / 删除 `.c` 文件，需要同步改 5 处消费方的源文件列表；
- `quickjs.h` 的 `QJS_VERSION_*` 仅作参考，**不能用来判定版本**（见上文「版本」小节的警告），
  以本文件记录的 commit sha 为准；
- 「已知本地改动」B 类小节登记的改动是否需要重新手打一遍（**目前 B 类为空，无需重放**），
  以及上游是否已经自己修掉了（修了就撤掉本地改动，别两头都改）。

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
commit sha 记下来，这是唯一能精确复现的坐标），并更新「已知本地改动」B 类小节：本次是否
新增了本地改动、原有的是否已被上游修掉（是则删掉那条登记并撤销本地改动）。

## 纯净度校验

`.github/workflows/sync-quickjs-ng.yml` 的 `mode: verify` 只比对不修改，用于确认
没有人绕过流程偷改源码。建议在怀疑构建行为异常时先跑一次 verify。

## 已知本地改动

**审计结论（2026-09-05，基准 = 上游 commit `02368b6b16`）：本目录是纯净的上游副本，零本地改动。**

23 个文件的 git blob hash 与上游该 commit 的 tree 逐一相等，无一例外。

### A 类 · IDE 误改（行尾 / 空白 / BOM / 格式化）

<!-- AUDIT-A-START -->
**无。不需要还原，也请不要"顺手"还原。**

Windows 工作树里这些文件的行尾会是 CRLF 或 LF（取决于是「经 git checkout」还是「刚由
同步直接写入」），两者都不是本地改动：仓库 `core.autocrlf=true`，committed blob 全部是 LF，
与上游一致，`git status` 干净。手动改行尾属于无效改动——下次 checkout 会变回来，
中途还会白白污染 diff、干扰正在读本目录编译静态库的构建。

无 BOM，无行尾空白差异，无重新格式化。
<!-- AUDIT-A-END -->

### B 类 · 实质改动（逻辑 / 宏 / 平台 `#ifdef` / 分配器 / 编译开关）

<!-- AUDIT-B-START -->
**无。** 没有任何逻辑改动、宏改写、平台条件分支、内存分配器替换或源码内编译开关。

历史上曾有过一处（B-1：`JS_ToCStringLenUTF16()` 对宽字符 `KIND_SLICE`/`KIND_INDIRECT` 字符串
返回的指针 `JS_FreeCStringUTF16()` 无法释放，导致父串静默腐败 / SIGSEGV），已在 2026-09-05
随 pin 至 `02368b6b16` 撤销：本项目 2026-09-03 向上游提了 issue #1708，上游翌日以 PR #1709
（commit `396e1e0b4f`）修入 master，修法与本地补丁同义（判定取反写成
`if (!(p->is_wide_char && p->kind == JS_STRING_KIND_NORMAL))`，拷贝复用已有的 `copy_str16()`
并把它前提到该函数之前），并带了 `api-test.c` 回归用例。本侧回归测试
`QuickJsEngineTestBase.testWideSliceToJavaStringDoesNotCorruptParent` 保留，防止日后回退。

此后若再需本地修上游 bug，按本文顶部的优先级先试 wrapper / 编译开关，真要改源码则在此
新增 B-n 登记（上游版本 / 根因 / 症状 / 复现 / 对应回归测试），否则下次 `mode=sync` 会静默冲掉。

所有平台适配都做在了源码之外——各消费方 CMakeLists 的 `-D` 定义、
`quickjs.def` 里的 `qjs_*` wrapper 函数——这正是本目录顶部要求的做法，请继续保持。
<!-- AUDIT-B-END -->

### 上游有而本地缺失的文件

<!-- AUDIT-MISSING-START -->
**无。** `LICENSE`（MIT，Bellard / Gorelli 等）已于 2026-08-29 同步时补齐。

其余上游根目录文件均为 CLI / 测试 / 工具，**故意不 vendored**，不必补：

```
api-test.c  ctest.c  fuzz.c  lre-test.c  qjs.c  qjsc.c
qjs-wasi-reactor.c  quickjs-libc.c  quickjs-libc.h
run-test262.c  unicode_gen.c
amalgam.js  repl.js  standalone.js
```

已核对 4 个 `.c` 的 `#include` 闭包（`quickjs.c` / `libregexp.c` / `libunicode.c` / `dtoa.c`），
全部落在本目录现有文件内，无悬空依赖（`02368b6b16` 与 `v0.16.2` 的 include 清单逐行相同）。
<!-- AUDIT-MISSING-END -->
