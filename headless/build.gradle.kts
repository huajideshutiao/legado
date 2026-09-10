// 桌面端无头模式入口 (headless): 无窗口/无 AV/无 UI 初始化的后台进程。
//
// 流程: 便携语义初始化数据目录 → quickjs native 定位 → 核心 provider 注册 (与桌面
// 阶段1+阶段3 的核心子集等价) → WebServerManager.start() 常驻提供 Web 服务。
//
// 硬性约束: 绝不依赖 :desktop (它携带 compose/mediamp/jna/pdfbox 等重依赖),
// 只依赖 :desktop-core (无 UI 核心库)。依赖闭包核查:
// ./gradlew :headless:dependencies --configuration runtimeClasspath
//
// 资源策略 (2026-09-06 裁决: 内置): files/ 资源随 shared jvmJar 分发 (classpath 直读);
// quickjs native 库复制进 jar 资源 (copyQuickjsNativeToHeadlessResources), Main 启动时
// 提取到临时文件 System.load。分发包 headlessDist 只含 bin/ + lib/。

import org.gradle.internal.os.OperatingSystem

plugins {
    // JVM application: 该约定插件只设置 kotlin jvm + Java 21 toolchain (已确认不含 compose)
    id("legado.jvm.application")
    application
}

// QuickJS native 目录架构契约（生产者/desktop/headless/runtime 必须一致）：
// amd64|x86_64|x64 -> x86_64；arm64|aarch64 -> aarch64；其余仅做路径安全化。
fun normalizeJvmNativeArch(rawArch: String): String = when (val arch = rawArch.lowercase()) {
    "amd64", "x86_64", "x64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch.replace(Regex("[^a-z0-9_.-]"), "_")
}

application {
    mainClass.set("io.legado.headless.MainKt")
}

dependencies {
    // 无 UI 核心 (provider 注册 / 运行时环境初始化 / 默认数据, 从 :desktop 抽取)
    implementation(project(":desktop-core"))
    // headless 直接调用 shared API (WebServerManager / registerJvmDebugState / ImageOps ...):
    // desktop-core 对 shared 是 implementation 不外泄, 需显式声明
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    // 基础图片加载单例 (SingletonImageLoader, 供 registerJvmBookImageLoader 注册, 50KB 纯核心无 Compose)
    implementation("io.coil-kt.coil3:coil:${libs.versions.coil3.get()}")
    // 引入 Skia 原生运行时 (支持 WebP/JPG/PNG 2D 硬件级位图编解码与切片混淆解密)
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val skikoTarget = when {
        osName.contains("win") -> "windows-x64"
        osName.contains("mac") || osName.contains("darwin") -> if (osArch == "aarch64" || osArch == "arm64") "macos-arm64" else "macos-x64"
        else -> if (osArch == "aarch64" || osArch == "arm64") "linux-arm64" else "linux-x64"
    }
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:0.144.6")
}

configurations.runtimeClasspath {
    // 排除纯 Compose UI 组件层 (无头后台进程无需 UI, 保留 Skia 图形引擎供反爬切片重排)
    // 保留 compose.runtime (纯响应式核心, 无 UI, 供 components-resources 静态读取 ResourceReader)
    exclude(group = "org.jetbrains.compose.ui")
    exclude(group = "org.jetbrains.compose.foundation")
    exclude(group = "org.jetbrains.compose.material")
    exclude(group = "org.jetbrains.compose.animation")
    exclude(group = "io.coil-kt.coil3", module = "coil-compose")
    exclude(group = "io.coil-kt.coil3", module = "coil-compose-core")
    exclude(group = "org.jetbrains.androidx.lifecycle", module = "lifecycle-runtime-compose-desktop")
    exclude(group = "org.jetbrains.androidx.savedstate", module = "savedstate-compose-desktop")
    exclude(group = "sh.calvin.reorderable")
    exclude(group = "com.mikepenz", module = "multiplatform-markdown-renderer-jvm")
    exclude(group = "com.mikepenz", module = "multiplatform-markdown-renderer-coil3-jvm")
}

// ============================================================
// quickjs native 库复制进资源 (打包自包含)
// ============================================================
// 开发期 :headless:run 无需本任务产物 —— Platform.kt 候选3 会从工作目录向上递归找到
// modules/quickjs/build/libs/jvm/native/<os>-<arch>/ 的开发产物。
val quickjsPlatformId = buildString {
    val osName = when {
        OperatingSystem.current().isWindows -> "windows"
        OperatingSystem.current().isMacOsX -> "macos"
        OperatingSystem.current().isLinux -> "linux"
        else -> System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
    }
    val arch = normalizeJvmNativeArch(System.getProperty("os.arch"))
    append(osName).append('-').append(arch)
}
val quickjsNativeDir =
    file("${rootProject.projectDir}/modules/quickjs/build/libs/jvm/native/$quickjsPlatformId")
val headlessNativeResDir = layout.buildDirectory.dir("generated/quickjs-native")

val copyQuickjsNativeToHeadlessResources by tasks.registering(Copy::class) {
    // 先触发 native 库构建，再从当前平台独占目录复制；避免捎带其他平台的陈旧库。
    dependsOn(project(":modules:quickjs").tasks.named("buildJvmNativeLib"))
    from(quickjsNativeDir)
    include("*.dll", "*.so", "*.dylib")
    into(headlessNativeResDir)
    inputs.dir(quickjsNativeDir).withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        val expected = when {
            OperatingSystem.current().isWindows -> "legado_quickjs.dll"
            OperatingSystem.current().isMacOsX -> "liblegado_quickjs.dylib"
            else -> "liblegado_quickjs.so"
        }
        if (!quickjsNativeDir.resolve(expected).isFile) {
            throw GradleException(
                "QuickJS native library is missing: ${
                    quickjsNativeDir.resolve(
                        expected
                    )
                }"
            )
        }
    }
}

sourceSets {
    main {
        // 资源目录指向任务输出目录 (build/generated, 不污染源码树, 免 .gitignore)
        resources.srcDir(headlessNativeResDir)
    }
}

tasks.named("processResources") {
    dependsOn(copyQuickjsNativeToHeadlessResources)
}

// ============================================================
// 无头分发包 (bin/ + lib/ 自组装)
// ============================================================
// 不走 application 插件 installDist (其目标目录防覆盖校验与增量状态在部分场景互踩),
// 直接自组装: lib = headless jar + runtimeClasspath (Sync 清陈旧), bin = startScripts 产物
// (本仓库 Gradle 8.14.5 下脚本平铺在 build/scripts/, 拷进 bin/ 即标准布局)。
val headlessBundleDir = layout.buildDirectory.dir("headless-bundle")

val bundleLib by tasks.registering(Sync::class) {
    dependsOn(tasks.named("jar"))
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    // mavenLocal 与远端仓库可能并存同一 GA:V 的同名字 jar (依赖图已收敛到单版本,
    // 内容一致), 平铺 lib/ 下同名冲突取其一
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(headlessBundleDir.map { it.dir("lib") })
}

val bundleBin by tasks.registering(Copy::class) {
    dependsOn(tasks.named("startScripts"))
    from(layout.buildDirectory.dir("scripts"))
    into(headlessBundleDir.map { it.dir("bin") })
}

/** 无头分发包: bin/ + lib/ (资源与 native 均内置 jar)。 */
tasks.register("headlessDist") {
    group = "distribution"
    description = "无头分发包 (bin/lib, 资源与 native 内置 jar)"
    dependsOn(bundleLib, bundleBin)
}
