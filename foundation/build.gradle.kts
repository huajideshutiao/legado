// :foundation —— 基础工具层 (从 :shared 切分, 依赖单向最底层)。
//
// 职责: 纯工具与零业务依赖的移植库 (utils/exception/constant/format/lib + rjpath)。
// 不含 Room/Compose/网络抽象; org/jsoup 移植因依赖 help/http 的网络抽象归 :data。
plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

// JVM+Android 共享依赖 (原 shared 的 sharedJvmAndroidDeps helper, 切分后随本源集移动;
// quick-transfer-core/okhttp 用 api, 消费方经 foundation jvm/android target 可见)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

kotlin {
    jvm()

    android {
        namespace = "io.legado.foundation"
        compileSdk = 37
        minSdk = 24
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    // ohosArm64 只存在于 CPF 分支 KGP, 交给 build-logic 的约定插件声明 (与 :shared 同款)。
    if (enableOhosTarget) {
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(libs.okio)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.serialization.json)
                // JsoupExtensions/HtmlFormatter 等直接用 ksoup (org/jsoup 移植在 :data)。
                // 标准 ksoup 无 ohosArm64 klib 而 ksoup-ohos 只有 ohosArm64 target, 两者都
                // 不能进 commonMain; 由 OhosTargetConventionPlugin 在 ohos 配置上把标准 ksoup
                // 替换为 :modules:ksoup-ohos, 其余平台照常解析标准 ksoup。
                implementation(libs.ksoup)
            }
        }
        // JVM+Android 共享源码根 (与 :shared 同款模式: 原 jvmAndAndroidMain 源集删除,
        // 由 jvmMain/androidMain 两个模板源集显式挂同一源码根, IDE 按模板源集分析)。
        // 依赖随之两目标各自声明 (ChineseUtils 用 quick-transfer-core, JvmPlatformTypes
        // 用 okhttp3.ResponseBody, 均以 api 暴露与 :shared 旧 api 面一致)。
        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
                api(libs.okhttp)
                implementation(libs.androidx.documentfile)
                // FileUtilsBase 的 androidx.annotation.IntDef (documentfile 不传递 annotation)
                implementation("androidx.annotation:annotation:1.9.1")
            }
        }
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
                api(libs.okhttp)
                // epublib 的 XmlSerializer (org.xmlpull.v1) 在 JVM 侧由 kxml2 提供
                implementation(libs.kxml2)
                // FileUtilsBase 的 androidx.annotation.IntDef (JVM 侧无 documentfile 传递)
                implementation("androidx.annotation:annotation:1.9.1")
            }
        }
        // iOS+鸿蒙 非 UI 公共层 (utils/constant 等的 posix 实现)
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
            }
        } else null

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
            }
            // KGP 不会自动把 iosArm64Main/iosSimulatorArm64Main 连到自定义的 iosMain,
            // 必须显式 dependsOn (否则 nativeMain 的 actual 进不了 iOS 编译; 与 :data 同款连接)。
            maybeCreate("iosArm64Main").apply {
                dependsOn(iosMain)
            }
            maybeCreate("iosSimulatorArm64Main").apply {
                dependsOn(iosMain)
            }
        }
        if (enableOhosTarget) {
            val ohosMain = maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
            }
            // KGP 不会自动把 ohosArm64Main 连到自定义的 ohosMain, 必须显式 dependsOn
            // (否则 ohosMain 的依赖/源码进不了 ohosArm64 编译; 与 :data 同款连接)。
            maybeCreate("ohosArm64Main").apply {
                dependsOn(ohosMain)
            }
        }
    }
}
