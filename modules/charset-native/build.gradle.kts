import org.gradle.api.tasks.Sync

// :modules:charset-native —— iOS/鸿蒙 native 端真实字符集编解码承载模块。
//
// 平台原生转换器与 JVM 映射不保证一致 (iOS CP936 对 U+20AC 输出 0x80 而 JDK GBK 是 A2E3;
// 鸿蒙 @ohos.util 对 GB2312 的默认替换是 A1A1 不是 '?'; iOS 严格解码+裁尾重试也不等价
// JVM REPLACE), 故 native 端改用 JDK 移植版编解码器 (com.fleeksoft.charset, OpenJDK
// sun.nio.cs 移植), 编解码字节级对齐 JVM:
// - 标准集与缓冲层: Maven com.fleeksoft.charset:charset + com.fleeksoft.io:io-core 0.0.8
//   (iOS 走官方 klib; 鸿蒙无官方变体, OhosTargetConventionPlugin 把这两个坐标替换为
//   :modules:ksoup-ohos 已重编的同一份源码, 不产生重复类);
// - GBK/Big5: 构建期从 Maven charset-ext 0.0.8 sources 解包这两个类 (映射表来自上游,
//   仓库不存副本);
// - GB18030: charset-ext-big 尚未发布到 Maven, vendor 上游固定提交源文件
//   (见 vendor/PROVENANCE.md, 不得手抄映射表);
// - UTF-32: 上游解码放行代理区码点 (isBmpCodePoint 不排除代理区, 与 JDK 同源), 本仓约束
//   非法码点→U+FFFD, 由 com.legado.charset.Utf32Coder 换包小改承载 (依赖 klib 已含原类,
//   同包会重类冲突)。
//
// 源码全部在 nativeMain (nonJvm API 依赖 Unicode/DoubleByte 等不在 common 元数据内,
// 与 ksoup-ohos 挂 ohosMain 同理不进 commonMain); 仅 native 目标, foundation 只在
// nativeMain 依赖本模块, Android/JVM 产物不含。
plugins {
    id("legado.kmp.native.library")
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

check(enableIosTarget || enableOhosTarget) {
    "charset-native is only available with native targets enabled"
}

val sourceArchives = configurations.create("charsetExtSourceArchives")

dependencies {
    // 编解码 API (Charset/CharsetEncoder/CharBuffer) 以 nativeMain api 传递给 foundation
    // nativeMain (声明在下方 sourceSets.nativeMain); 鸿蒙配置由 OhosTargetConventionPlugin
    // 把两坐标替换为 :modules:ksoup-ohos
    sourceArchives("com.fleeksoft.charset:charset-ext:${libs.versions.fleeksoft.charset.get()}:sources@jar")
}

val generatedSources = layout.buildDirectory.dir("generated/charsetExtSources")

val unpackCharsetExtSources = tasks.register<Sync>("unpackCharsetExtSources") {
    into(generatedSources)
    from({ sourceArchives.files.map(::zipTree) }) {
        include(
            "nonJvmMain/com/fleeksoft/charset/cs/ext/GBK.kt",
            "nonJvmMain/com/fleeksoft/charset/cs/ext/Big5.kt",
        )
        into("charset-ext")
    }
    doLast {
        // 产物校验: 上游 sources jar 改名/变结构时显式失败, 而非静默缺类。
        // 路径写成字面量: 引用脚本级 val 会捕获脚本对象, 配置缓存无法序列化
        check(File(destinationDir, "charset-ext/nonJvmMain/com/fleeksoft/charset/cs/ext/GBK.kt").isFile) {
            "unpackCharsetExtSources: 上游 charset-ext 源文件缺失 (GBK.kt), sources jar 结构可能已变化"
        }
        check(File(destinationDir, "charset-ext/nonJvmMain/com/fleeksoft/charset/cs/ext/Big5.kt").isFile) {
            "unpackCharsetExtSources: 上游 charset-ext 源文件缺失 (Big5.kt), sources jar 结构可能已变化"
        }
    }
}

kotlin {
    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    // ohosArm64 target 注册 + 鸿蒙配置的坐标替换 (charset/io-core → :modules:ksoup-ohos)
    if (enableOhosTarget) {
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        // 编解码源码挂 nativeMain (iOS/鸿蒙共用), 两平台各自编译进目标 klib
        val nativeMain = maybeCreate("nativeMain").apply {
            dependsOn(commonMain.get())
            dependencies {
                // 编解码 API 以 api 传递给 foundation nativeMain; 鸿蒙配置由
                // OhosTargetConventionPlugin 把两坐标替换为 :modules:ksoup-ohos
                api(libs.fleeksoft.charset)
                api(libs.fleeksoft.io.core)
            }
            kotlin.srcDir("src/nativeMain/kotlin")
            // vendor/com/fleeksoft/charset/cs/ext/GB18030.kt (上游固定提交源文件 + 溯源说明)
            kotlin.srcDir("vendor")
            kotlin.srcDir(generatedSources.map { it.dir("charset-ext/nonJvmMain") })
        }

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain)
            }
            // KGP 不会自动把 iosArm64Main/iosSimulatorArm64Main 连到自定义的 iosMain,
            // 必须显式 dependsOn (与 foundation/data 同款连接)
            maybeCreate("iosArm64Main").dependsOn(iosMain)
            maybeCreate("iosSimulatorArm64Main").dependsOn(iosMain)
        }
        if (enableOhosTarget) {
            val ohosMain = maybeCreate("ohosMain").apply {
                dependsOn(nativeMain)
            }
            maybeCreate("ohosArm64Main").dependsOn(ohosMain)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(unpackCharsetExtSources)
    }
}
