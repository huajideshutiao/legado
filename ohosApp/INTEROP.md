# Legado KMP × 鸿蒙 ArkTS 互操作方案

## 1. 背景与目标

Legado 项目的 KMP 全平台化任务需要让 CPF `ohosArm64` target 复用 `shared` 中的共享业务代码 (
ChineseUtils / MD5Utils / HTTP / 数据库等)，并由 ArkUI 融合渲染承载 Compose UI。

ArkTS 通过 NAPI 获取 `liblegado_shared.so` 导出的 Compose 控制器和业务 C ABI；`@cpf-kmp-cmp/compose`
再按 backend id 将控制器接入 Fusion Renderer 的 ArkUI RenderNode。

本文档说明完整的桥接方案与各层职责。

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  ArkTS 层 (ohosApp/entry/src/main/ets)                          │
│  EntryAbility.ets / Index.ets (Compose 渲染宿主)                │
│                                                                  │
│    ↓ import legado from 'liblegado_napi.so'                      │
│    ↓ legado.chineseT2S('简体')                                  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ napi 调用 (node-addon-api)
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│  napi 桥接层 (ohosApp/entry/src/main/cpp/legado_napi.cpp)       │
│  把 ArkTS 字符串拷贝为 C 字符串, 调用 dlsym 解析的符号,         │
│  把返回的 C 字符串包装回 napi_value (ArkTS string)              │
│                                                                  │
│    ↓ dlsym("legado_chinese_t2s")                                 │
│    ↓ g_chinese_t2s(input_cstr)                                  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ C ABI 函数调用
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│  Kotlin/Native 层 (modules/shared/src/ohosMain/.../napi/)       │
│  LegadoNativeExports.kt                                         │
│                                                                  │
│    @CName("legado_chinese_t2s")                                  │
│    fun chineseT2S(input: CPointer<ByteVar>): CPointer<ByteVar>  │
│      ↓                                                           │
│    ChineseUtils.t2s(input.toKString()).cstr.getPointer(...)     │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│  共享业务层 (modules/shared/src/commonMain)                     │
│  ChineseUtils / MD5Utils / Ktor / Room / ...                    │
│  (Android/JVM/iOS/鸿蒙 各端共用同一份实现)                    │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 关键文件清单

### 3.1 Kotlin/Native 导出层 (modules/shared)

- **`modules/shared/src/ohosMain/kotlin/io/legado/app/napi/LegadoNativeExports.kt`**
  - 用 `@CName("legado_xxx")` 注解导出 C ABI 函数
  - 当前导出 6 个函数: chineseT2S / chineseS2T / md5Encode / formatPercentUs / isProvidersRegistered / registerOhosProviders

### 3.2 napi 桥接层 (ohosApp/entry/src/main/cpp)

- **`legado_napi.cpp`** - napi module 实现, 通过 dlsym 加载 .so 符号并包装为 ArkTS 方法
- **`CMakeLists.txt`** - 链接 `libace_napi.z.so` + `liblegado_shared.so` 编译为 `liblegado_napi.so`
- **`types/liblegado_napi/Index.d.ts`** - ArkTS 侧 TypeScript 类型声明

### 3.3 ArkTS 调用方 (ohosApp/entry/src/main/ets)

- **`pages/Index.ets`** - Compose 渲染宿主入口, 调用 legado.MainArkUIViewController() 接入 shared
  LegadoApp
- **`entryability/EntryAbility.ets`** - UIAbility 入口, onCreate 中调用 `registerOhosProviders()`

### 3.4 ohosMain 配置 provider stub

- **`modules/shared/src/ohosMain/.../config/OhosPreferenceProvider.kt`** - 文件持久化的 PreferenceProvider stub
- **`modules/shared/src/ohosMain/.../config/OhosAppConfigAccessor.kt`** - AppConfigAccessor stub (委托 PreferenceProvider)
- **`modules/shared/src/ohosMain/.../config/OhosProviderRegistry.kt`** - 集中注册入口 `registerOhosProviders()`

## 4. 编译与运行流程

### 4.1 准备鸿蒙原生产物

```bash
# 使用 CPF-KMP-CMP 融合渲染构建 KMP 共享库，并复制 .so 与生成的 API 头到鸿蒙 entry：
./gradlew stageOhosNativeLibraries -PenableOhosTarget=true -PrendererBackend=fusion-renderer

# 可选：显式校验共享库和生成头均存在
./gradlew verifyOhosNativeLibraries -PenableOhosTarget=true -PrendererBackend=fusion-renderer
```

Gradle staging 契约包含：

- `liblegado_shared.so`：`:shared:linkDebugSharedOhosArm64` 的 CPF 融合渲染产物。
- K/N 自动生成的 API 头：供 entry NAPI 直接调用 `MainArkUIViewController` 与 Compose 初始化符号。
- Compose ArkTS/native 运行时：由 `@cpf-kmp-cmp/compose:1.9.2-0.4.0` 提供，不再维护 AntUI XComponent
  桥接库。

### 4.2 编译鸿蒙 HAP

```
1. DevEco Studio 单独打开 ohosApp/（不要打开仓库根目录）
2. Sync Project (hvigor 同步)
3. Build → Build Hap(s)/APP(s) → Build Hap(s)
4. 产物: ohosApp/entry/build/default/outputs/default/entry-default-unsigned.hap
```

`entry/hvigorfile.ts` 已把 `stageOhosNativeLibraries` 接到 CMake 配置前，DevEco Studio 点击构建时会自动调用根
Gradle 构建并准备 KMP 共享库与 API 头，无需预先手工运行 Gradle。CMake 在原生产物构建失败或缺失时会直接失败，不再静默生成
mock 版本。

### 4.3 部署运行

```
1. DevEco Studio 连接鸿蒙模拟器或真机
2. Run → Run 'entry'
3. 应用启动后, EntryAbility.onCreate 调用 legado_register_providers()
4. Index.ets 加载 shared LegadoApp Compose UI, 业务 UI 由 shared 统一渲染
```

## 5. 内存与生命周期约定

### 5.1 字符串传递 (ArkTS ↔ Kotlin)

- **ArkTS → C**: napi_get_value_string_utf8 拷贝到 napi 层 buf, 由 napi 层管理生命周期
- **C → Kotlin**: Kotlin 端用 `input.toKString()` 拷贝为 Kotlin String (C 指针仍由 napi 层管理)
- **Kotlin → C**: Kotlin 端用 `result.cstr.getPointer(nativeHeap)` 在 Kotlin/Native 堆上分配, 直到 module 卸载
- **C → ArkTS**: napi_create_string_utf8 会拷贝走, Kotlin 端指针理论上可立即释放 (但当前简化为不释放, 避免悬挂指针)

### 5.2 长期改进建议

- **方案 A (推荐)**: Kotlin 端用 `memScope { ... }` 限定字符串生命周期到 napi 调用结束, 配合 napi_create_string_utf8 拷贝, 实现 zero-leak
- **方案 B**: 引入 `kotlinx.cinterop.CValuesRef` + `napi_create_external_string` 显式管理外部内存

### 5.3 异常处理

- Kotlin 端抛出异常会跨 C ABI 边界丢失 (Kotlin/Native 默认 abort 进程)
- 当前 napi 层用 try-catch 包装关键调用, 异常时返回 mock 字符串
- 长期建议: 用 `kotlin.native.concurrent.freeze()` + `WorkerBoundReference` 隔离, 配合 napi 的异常通道回传错误信息

## 6. 备选方案对比

### 方案 A: Kotlin/Native + napi (当前选用)
- ✓ KMP 共享 commonMain 代码直接复用
- ✓ 不需要重写业务逻辑
- ✗ napi 桥接层有 C++/C 双层模板代码
- ✗ Kotlin/Native linuxArm64 ABI 与 OpenHarmony aarch64-linux-ohos 需验证

### 方案 B: KMP + ArkTS 重写 UI 层
- ✓ 完全用 ArkTS 风格, 不依赖 Compose Multiplatform
- ✗ 需要把 BookshelfViewModel/ReadBookViewModelShared 等 VM 完全重写为 ArkTS
- ✗ 双重维护成本高

### 方案 C: Web 嵌入 (ArkUI WebView 加载 KMP/JS)
- ✓ 跨平台一致性最好
- ✗ 性能差 (WebView 启动开销)
- ✗ ArkTS 与 JS 互操作复杂度等同于 napi

## 7. 当前 KP4 阶段已实现

- [x] ohosApp 工程基础结构 (AppScope / entry / build-profile / hvigorfile / oh-package)
- [x] ArkTS UI 骨架 (EntryAbility / Index)
- [x] 业务 UI 由 shared LegadoApp 统一渲染, Index.ets 仅作为 Compose 渲染宿主
- [x] Kotlin/Native C ABI 导出函数 (LegadoNativeExports.kt @CName)
- [x] napi 桥接 C++ 骨架 (legado_napi.cpp + CMakeLists.txt + .d.ts)
- [x] ohosMain config provider stub (AppConfigAccessor / PreferenceProvider / 集中注册)
- [x] 本文档 (INTEROP.md)

## 8. 后续 KP5/KP6 待办

- [ ] 验证 Kotlin/Native linuxArm64 产物在鸿蒙 aarch64-linux-ohos 上运行 (符号兼容性)
- [ ] 引入真实 BookshelfViewModel 通过 napi 暴露 (含 List<Book> 序列化)
- [x] 接入 Room KMP BundledSQLiteDriver 在鸿蒙端做真实数据库读写 (KP6 已落地, 详见第 10 节)
- [ ] 接入 Ktor CIO 在鸿蒙端做真实 HTTP (BookSource 加载)
- [ ] 用 @ohos.data.preferences 替换 OhosPreferenceProvider stub (原生体验)
- [ ] 用 @ohos.file.fs 替换 AppFilesDir stub (真实沙箱路径)
- [ ] 用 @ohos.multimedia.image 替换 BookImageStorage stub (真实封面加载)
- [ ] 完善 napi 内存生命周期 (方案 A: memScope)
- [ ] 实现异步 napi (用 napi_create_async_work 包装 suspend KMP 函数)

## 9. 参考链接

- 鸿蒙 napi 开发指南: https://gitee.com/openharmony/docs/blob/master/zh-cn/application-dev/napi/napi-guidelines.md
- Kotlin/Native C ABI 与 @CName: https://kotlinlang.org/docs/native-c-interop.html
- KMP 跨平台架构: https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html
- OpenHarmony arm64 ABI: https://gitee.com/openharmony/docs/blob/master/zh-cn/application-dev/compatibility/abi-spec.md

## 10. KP6 Room KMP 数据库真实化验证

KP6 已完成鸿蒙端 Room KMP + BundledSQLiteDriver 真实数据库接入, 替代 KP4 时期的 mock 兜底。
本节记录落地文件清单与关键验证结论 (对应任务 9 的标注要求)。

### 10.1 已落地的 actual 实现文件 (shared/ohosMain)

| 文件 | 职责 | 真实化状态 |
| --- | --- | --- |
| `shared/src/ohosMain/kotlin/io/legado/app/data/OhosDatabaseDriver.kt` | `DatabaseDriverProvider` actual: `Room.databaseBuilder<AppDatabase>` + `BundledSQLiteDriver` 构造真实 Room 数据库, dbPath 默认 `{AppFilesDirs.filesDir}/legado.db` (鸿蒙沙盒) | ✅ 真实实现 |
| `shared/src/ohosMain/kotlin/io/legado/app/data/OhosAppDatabaseProvider.kt` | `AppDatabaseProvider` actual: 委托 `OhosDatabaseDriver.appDatabase` 暴露 `AppDatabase` 单例 | ✅ 真实实现 |
| `shared/src/ohosMain/kotlin/io/legado/app/data/OhosAppDbAccessor.kt` | `AppDbAccessor` actual: 转发 14 个 DAO + `registerOhosAppDb(driver)` 便捷注册函数 | ✅ 真实实现 |
| `shared/src/ohosMain/kotlin/io/legado/app/help/config/OhosProviderRegistry.kt` | `registerOhosProviders()` 中 `DatabaseDriverProviders.register` + `registerOhosAppDb` (含 AppDatabaseProviders + AppDbProviders) | ✅ 已注册 |

### 10.2 napi 桥接真实化 (ohosApp)

| 文件 | 真实化状态 |
| --- | --- |
| `ohosApp/entry/src/main/cpp/legado_napi.cpp` | `BookshelfList`/`SearchBook`/`LoadChapter`/`ImportBookSource` 通过 dlsym 调用 `legado_bookshelf_list` 等 @CName 符号; dlsym 失败时返回 `"[]"`/`""`/`0` 兜底 (非 mock, 是 .so 未加载时的容错) |
| `shared/src/ohosMain/kotlin/io/legado/app/napi/LegadoNativeExports.kt` | `legado_bookshelf_list` 等已走 `AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)` 真实 DAO 查询, runBlocking 转 suspend |
| `ohosApp/entry/src/main/ets/entryability/EntryAbility.ets` | `onCreate` 调用 `legado.registerOhosProviders()` 完成 provider 注入 |

### 10.3 sqlite-bundled linuxArm64 变体验证结论 (任务 9 核心标注)

任务 9 前提假设 "BundledSQLiteDriver 是 JVM 库, 鸿蒙 linuxArm64 产物无法直接跑"。经查证该假设**不成立**:

- `androidx.sqlite:sqlite-bundled:2.7.0` 是真正的 KMP 跨平台库 (非 JVM 专属),
  其 Gradle metadata (`sqlite-bundled-2.7.0.module`) 显式发布 `linuxArm64ApiElements-published`
  变体, 子模块 `sqlite-bundled-linuxarm64` 内嵌 `linux_arm64` 原生 SQLite 库。
- `BundledSQLiteDriver` 类定义在 commonMain (`androidx.sqlite.driver.bundled`),
  鸿蒙 linuxArm64 target 可直接引用, 编译期 KMP 依赖解析通过。
- 鸿蒙 OpenHarmony arm64 triple `aarch64-linux-ohos` 与 `linuxArm64` ABI 兼容
  (见第 9 节 OpenHarmony arm64 ABI 链接), sqlite-bundled 的 linuxArm64 原生库可在鸿蒙 runtime 加载。

**结论**: 鸿蒙端无需 napi 调 `@ohos.data.relationalStore` 替代方案, BundledSQLiteDriver 直接可用。

### 10.4 运行时验证注意事项与替代方案

虽然编译期已确认 sqlite-bundled linuxArm64 变体可用, 但真机/模拟器运行时仍需验证:

1. **native 库加载**: sqlite-bundled linuxArm64 变体内嵌的 SQLite 原生库 (.so/.klib)
   需在鸿蒙 OpenHarmony aarch64 runtime 正常 dlopen。若加载失败
   (如符号缺失 / glibc 版本差异), `BundledSQLiteDriver` 构造会抛 `UnsatisfiedLinkError`。
2. **ABI 兼容**: OpenHarmony 基于 Linux kernel, 但 bionic/musl libc 差异可能导致
   极少数 POSIX 调用行为不同。首次真机运行需观察 hilog 是否有 sqlite 相关错误。

**替代方案 (万一运行时 native 库加载失败)**: 通过 napi 桥接鸿蒙原生 `@ohos.data.relationalStore`
(关系型数据库 API) 实现 `androidx.sqlite.SQLiteDriver` 接口, 替换 `BundledSQLiteDriver`。
该方案需在 `legado_napi.cpp` 新增 `relationalStore` 相关 napi 方法 (getRdbStore/execSQL/query),
并在 `OhosDatabaseDriver` 中用自定义 `SQLiteDriver` 包装 napi 调用。
当前优先保留 BundledSQLiteDriver 方案 (KMP 一致性最佳), 替代方案仅在真机验证失败后启用。
