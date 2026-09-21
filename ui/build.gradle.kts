// :ui —— UI 层 (从 :shared 切分, 依赖 core/data/foundation)。
//
// 职责: 全部 Compose UI —— ui 包 (commonMain + sharedUiMain 页面/组件) +
// help/image 的图像加载实现 (coil/skiko) + model 的封面烘焙 (WallpaperBaker/
// DefaultCoverBaker, 依赖 compose ImageBitmap) + composeResources (随本模块分发)。
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    id("legado.compose")
}

compose.resources {
    generateResClass = always
    // app/desktop 等消费方访问 Res 资源, 显式公开
    publicResClass = true
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
val composeVersion = libs.versions.cmp.get()

// JVM+Android 共享依赖 (okhttp/quickjs 随 data/core 的 api 面传入, 本模块仅补 UI 侧)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun KotlinDependencyHandler.sharedJvmAndroidDeps() {
    api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
    api(project(":modules:quickjs"))
    api(sharedLibs.findLibrary("okhttp").get())
    implementation(sharedLibs.findLibrary("coil3-network-okhttp").get())
}

kotlin {
    jvm()

    android {
        namespace = "io.legado.ui"
        compileSdk = 37
        minSdk = 24
        // compose.resources 需要 assets 接入 (与 :shared 同款机制)
        androidResources.enable = true
    }

    if (enableIosTarget) {
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            if (isMacHost) {
                // native 库 (quickjs/mbedtls) 由 scripts/build-ios-native.sh 预编译,
                // 产物在 :data/build/iosNativeLibs (与 :data 的 cinterop 同一套 native 库)
                val nativeLibDir = file("${rootProject.projectDir}/data/build/iosNativeLibs/${konanTarget.name}")
                binaries {
                    framework {
                        baseName = "shared"
                        isStatic = false
                        binaryOption("bundleId", "shutiao.reader.shared")
                    }
                    all {
                        linkerOpts("-L${nativeLibDir.absolutePath}", "-lquickjs", "-lmbedtls", "-lsqlite3")
                        optimized = false
                    }
                }
            }
        }
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }

    if (enableOhosTarget) {
        // CPF fork 只发布 Android/iOS/OHOS 变体, 不发布 Desktop JVM (与 :shared 同款策略)
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":foundation"))
                api(project(":data"))
                api(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                api(libs.okio)
                implementation(libs.kotlinx.serialization.json)
                // KsoupHtmlAnnotatedString/ReadRssRoute 等用 ksoup 解析 HTML
                if (enableOhosTarget) {
                    implementation(project(":modules:ksoup-ohos"))
                } else {
                    implementation(libs.ksoup)
                }
                api("org.jetbrains.compose.components:components-resources:$composeVersion")
            }
        }
        // Compose 基线源集 (四端共享)
        val sharedUiMain = create("sharedUiMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.material:material:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation(libs.compose.lifecycle.runtime.multiplatform)
            }
        }
        // 鸿蒙无变体三方库隔离层 (与 :shared 同款)
        val nonOhosUiMain = create("nonOhosUiMain") {
            dependsOn(sharedUiMain)
            dependencies {
                implementation(libs.reorderable)
                implementation(libs.coil3.compose)
                implementation(libs.multiplatformMarkdown)
                implementation(libs.multiplatformMarkdown.coil3)
            }
        }
        // 三端 skiko 共享层 (jvm + iOS + 鸿蒙; Android 无 skiko)
        val skikoUiMain = create("skikoUiMain") {
            dependsOn(sharedUiMain)
        }
        // iOS+鸿蒙共用 UI 层
        val iosAndOhosUiMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("iosAndOhosUiMain").apply {
                dependsOn(sharedUiMain)
                dependsOn(skikoUiMain)
            }
        } else null

        androidMain {
            dependsOn(nonOhosUiMain)
            // 共享 JVM+Android 源码根 (与 :shared 同款模式)
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.core.ktx)
                implementation(libs.coil3.gif)
                implementation(libs.vectordrawable.animated)
                implementation(libs.compose.activity)
                // SVG 解码 (ImageProvider.android 同款)
                implementation(libs.androidsvg)
                implementation(libs.androidx.webkit)
            }
        }
        jvmMain {
            dependsOn(nonOhosUiMain)
            dependsOn(skikoUiMain)
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kxml2)
                implementation(libs.androidx.sqlite.bundled)
                // SvgRasterizer 的 jsvg 渲染 + skiko 编码
                implementation(libs.jsvg)
                implementation("org.jetbrains.skiko:skiko-awt:0.144.6")
            }
        }
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
            }
        } else null

        if (enableIosTarget) {
            maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(nonOhosUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    implementation(libs.androidx.sqlite.framework)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                    implementation(libs.coil3.network.ktor3)
                }
            }
        }
        if (enableOhosTarget) {
            maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(sharedUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    implementation("androidx.sqlite:sqlite-framework:2.7.0-alpha01-0.3.0")
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                }
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
