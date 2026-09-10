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
plugins {
    // JVM application: 该约定插件只设置 kotlin jvm + Java 21 toolchain (已确认不含 compose)
    id("legado.jvm.application")
    application
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
}

// ============================================================
// quickjs native 库复制进资源 (打包自包含)
// ============================================================
// 开发期 :headless:run 无需本任务产物 —— Platform.kt 候选3 会从工作目录向上递归找到
// modules/quickjs/build/libs/jvm/native/ 的开发产物。
val quickjsNativeDir = file("${rootProject.projectDir}/modules/quickjs/build/libs/jvm/native")
val headlessNativeResDir = layout.buildDirectory.dir("generated/quickjs-native")

val copyQuickjsNativeToHeadlessResources by tasks.registering(Copy::class) {
    // 先触发 native 库构建 (cmake 编译 legado_quickjs.dll), 再复制进资源输出目录
    dependsOn(project(":modules:quickjs").tasks.named("buildJvmNativeLib"))
    from(quickjsNativeDir)
    // 只复制 native 库文件, 避免带入其他构建产物
    include("*.dll", "*.so", "*.dylib")
    into(headlessNativeResDir)
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
