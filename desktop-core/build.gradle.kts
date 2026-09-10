// 桌面端无 UI 核心库 (从 :desktop 机械抽取的"无头核心")。
//
// 抽取规则 (机械可验证): 遍历 desktop/src/main/kotlin 全部 .kt, import 命中
// androidx.compose / org.jetbrains.skiko / org.jetbrains.skia (随 skiko 提供) / java.awt /
// javax.swing / org.openani.mediamp / com.sun.jna / pdfbox / commons-compress / junrar
// 之一的文件留在 :desktop, 其余搬入本模块 (包名保持 io.legado.desktop* 不变, 减少 import 改动)。
//
// 本模块只依赖 shared + quickjs, 不引 compose/mediamp/jna/pdfbox/junrar/commons-compress/xz,
// 使 :headless 能构建无 UI 依赖闭包的后台进程 (见 headless/build.gradle.kts)。
plugins {
    // 普通 JVM library 模块: 复用 JVM 约定插件只取其 toolchain 配置 (Java 21 + Adoptium,
    // 与 :desktop 编译目标一致; 该插件仅设置 toolchain/jvmTarget, 不引 compose)。
    // 名字虽叫 application, 但未配 mainClass/compose, 对 library 无副作用。
    id("legado.jvm.application")
    // 搬入文件间接引用 shared 的 @Serializable 类的字段/序列化器时需要序列化插件在 classpath
    // (本模块自身暂无 @Serializable 声明, 与 :desktop 同款声明保持一致)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // 引入 shared 模块 jvm target (传递 commonMain + jvmMain 全部 API;
    // okhttp/jsoup/room 等经 shared 的 api 依赖可见, 无需显式声明)
    implementation(project(":shared"))
    // KP1.1: quickjs 自研 JNI 桥 (DesktopQuickJsSharedJsScopeProvider 直接 import
    // com.script.quickjs.*; shared/jvmMain 已 api 本模块, 显式声明对齐 :desktop 写法)
    implementation(project(":modules:quickjs"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // 桌面端无 UI Skia 图形引擎 (DesktopImageOps 依赖, 纯 2D 位图与切片重排, 无 Compose UI)
    implementation("org.jetbrains.skiko:skiko-awt:0.144.6")
}
