// :core —— 业务核心层 (从 :shared 切分, 依赖 data/foundation)。
//
// 职责: 业务逻辑 —— help 剩余 (book/config/update/notification/image 的非 UI 部分
// + 顶层件) + web (WebServer) + service + api (BookController 等 web API)。
// UI 源集文件 (sharedUiMain/skikoUiMain/nonOhosUiMain/iosAndOhosUiMain 的 help 实现)
// 与 ui 包留 :shared, 由 :ui 批次吸收。
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

// JVM+Android 共享依赖 (与 :shared/:data 同款 helper)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun KotlinDependencyHandler.sharedJvmAndroidDeps() {
    api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
    implementation(sharedLibs.findLibrary("hutool-crypto").get())
    api(project(":modules:quickjs"))
    api(sharedLibs.findLibrary("okhttp").get())
    implementation(sharedLibs.findLibrary("coil3-network-okhttp").get())
    implementation(sharedLibs.findLibrary("nanohttpd-nanohttpd").get())
    implementation(sharedLibs.findLibrary("nanohttpd-websocket").get())
}

kotlin {
    jvm()

    android {
        namespace = "io.legado.core"
        compileSdk = 37
        minSdk = 24
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    if (enableOhosTarget) {
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":foundation"))
                api(project(":data"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                api(libs.okio)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // JVM+Android 共享源码根 (与 :shared 同款模式)
        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.core.ktx)
            }
        }
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kxml2)
                // help/image 的 jvm 实现 (ImageBitmapLoader.jvm 等) 用 Skia 编码
                implementation("org.jetbrains.skiko:skiko-awt:0.144.6")
            }
        }
        // iOS+鸿蒙 非 UI 公共层 (help/web 的 native 实现)
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
                dependencies {
                    implementation(libs.ktor.server.core)
                    implementation(libs.ktor.server.cio)
                    implementation(libs.ktor.server.websockets)
                }
            }
        } else null

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                    implementation(libs.coil3.network.ktor3)
                }
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
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                }
            }
            // KGP 不会自动把 ohosArm64Main 连到自定义的 ohosMain, 必须显式 dependsOn
            // (否则 ohosMain 的依赖/源码进不了 ohosArm64 编译; 与 :data 同款连接)。
            maybeCreate("ohosArm64Main").apply {
                dependsOn(ohosMain)
            }
        }

        // 测试 (从 :shared 迁移)
        val jvmAndAndroidTest = create("jvmAndAndroidTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.kotlin.reflect)
            }
        }
        matching { it.name == "androidHostTest" }.configureEach {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
        }
    }
}
