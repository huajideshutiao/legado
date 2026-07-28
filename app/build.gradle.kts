import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("legado.android.application")
    alias(libs.plugins.kotlin.serialization)
    // Android 与 shared/desktop 统一由 Compose Multiplatform 插件提供 Compose 依赖坐标，
    // 避免 app 单独走 AndroidX Compose BOM、shared 走 org.jetbrains.compose 的双体系。
    id("legado.compose")
    // K5-c Phase 5: @Database 已下沉 shared/commonMain, room 插件移至 shared 模块
    // alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

fun releaseTime(): String {
    val fmt = DateTimeFormatter.ofPattern("yy.MMddHH")
        .withZone(ZoneId.of("GMT+8"))
    return fmt.format(Instant.now())
}

abstract class CopyRenamedApks : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val releaseSuffix: Property<String>

    @TaskAction
    fun copyApks() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val builtArtifacts = builtArtifactsLoader.get().load(inputDirectory.get())
            ?: error("Cannot load APK metadata from ${inputDirectory.get().asFile}")
        val abiShortNames = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "armv7",
            "x86_64" to "x64",
            "x86" to "x86",
        )

        builtArtifacts.elements.forEach { artifact ->
            val abi = artifact.filters.firstOrNull {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier
            val outputName = "${abiShortNames[abi] ?: "all"}${releaseSuffix.get()}.apk"
            File(artifact.outputFile).copyTo(output.resolve(outputName), overwrite = true)
        }
    }
}

val name = "legado"
val version = "3.${releaseTime()}"
// git rev-list HEAD --count (配置阶段执行, 与原 groovy 行为一致)
val gitCommits = providers.exec {
    commandLine("git", "rev-list", "HEAD", "--count")
}.standardOutput.asText.get().trim().toInt()

// K5-c Phase 5: @Database 已下沉 shared/commonMain, room schema 由 shared 模块生成
// room { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "io.legado.app"

    signingConfigs {
        if (project.hasProperty("RELEASE_STORE_FILE")) {
            create("myConfig") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    defaultConfig {
        applicationId = "shutiao.reader"
        versionCode = 10000 + gitCommits
        versionName = version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        base.archivesName.set("${name}_${version}")

        val cronetVersion = libs.versions.cronet.get()
        val cronetMainVersion = cronetVersion.substring(0, cronetVersion.indexOf('.')) + ".0.0.0"
        buildConfigField("String", "Cronet_Version", "\"$cronetVersion\"")
        buildConfigField("String", "Cronet_Main_Version", "\"$cronetMainVersion\"")

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.incremental" to "true",
                    "room.expandProjection" to "true",
                    "room.schemaLocation" to "$projectDir/schemas".toString()
                )
            }
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
    androidResources {
        localeFilters += listOf("en", "zh", "zh-rHK", "zh-rTW")
    }
    buildTypes {
        release {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("myConfig")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            vcsInfo {
                include = false
            }
            applicationIdSuffix = ".release"
            if (applicationIdSuffix == ".releaseA") {
                manifestPlaceholders.put("app_name", "@string/app_name_a")
            } else {
                manifestPlaceholders.put("app_name", "@string/app_name")
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("myConfig")
            }
            manifestPlaceholders.put("app_name", "@string/app_name")

            applicationIdSuffix = ".debug"
            versionNameSuffix = "debug"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += listOf("mode")
    productFlavors {
        create("app") {
            dimension = "mode"
            manifestPlaceholders.put("APP_CHANNEL_VALUE", "app")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // KSP 参数
    ksp {
        // K5-c Phase 5: Room KSP 已移至 shared 模块, room.* arg 不再需要
        // arg("room.incremental", "true")
        // arg("room.expandProjection", "true")
        // arg("room.generateKotlin", "false")
        //arg("room.schemaLocation", "$projectDir/schemas")
        // BaseSource 已下沉 shared，@JsApi 分派表仍由 app 侧 KSP 从 classpath 解析生成
        // CacheManager 同标 @JsApi 却缺席名单, JS 调 cache.get/put 一直走反射兜底; 补入生成分派表
        arg("jsapi.extraClasses", "io.legado.app.data.entities.BaseSource,io.legado.app.help.CacheManager")
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
        resources.excludes.add("META-INF/androidx/**")
        resources.excludes.add("META-INF/jsoup/**")
        resources.excludes.add("META-INF/native-image/**")
        resources.excludes.add("google/protobuf/**")
        resources.excludes.add("kotlin/**")
        resources.excludes.add("META-INF/versions/**")
        resources.excludes.add("tc/*")
        jniLibs.excludes.add("lib/*/libcronet*.so")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets {
        // Adds exported schema location as test app assets.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas".toString())
    }
    lint {
        checkDependencies = true
        // vi/pt-rBR/ja-rJP/es-rES 不在 localeFilters 中(APK 不打包),
        // 保留翻译源文件备用但不强制要求翻译完整性
        // NewerVersionAvailable/GradleDependency: 依赖版本升级需充分回归测试,不属 lint 修复范畴
        disable += listOf("MissingTranslation", "NewerVersionAvailable", "GradleDependency")
    }
    tasks.withType<JavaCompile>().configureEach {
        //options.compilerArgs.add("-Xlint:unchecked")
    }
}

// The public Variant/Artifacts API deliberately keeps AGP's packaged APK names stable for
// Android Studio deployment. Copy release-facing APKs to a separate directory instead of
// mutating internal variant output objects. Each task is wired lazily to its variant APKs.
androidComponents {
    onVariants { variant ->
        val taskName = "copyRenamedApksFor${variant.name.replaceFirstChar(Char::uppercaseChar)}"
        val copyTask = tasks.register<CopyRenamedApks>(taskName) {
            outputDirectory.set(layout.buildDirectory.dir("outputs/renamed-apks/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            releaseSuffix.set("")
        }
        variant.artifacts.use(copyTask)
            .wiredWith(CopyRenamedApks::inputDirectory)
            .toListenTo(SingleArtifact.APK)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    androidTestImplementation(libs.bundles.androidTest)

    implementation(libs.kotlin.stdlib)

    implementation(libs.bundles.coroutines)
    // KJ3 并发原语下沉前置：atomicfu locks 门面(commonMain-ready synchronized)
    implementation(libs.kotlinx.atomicfu)

    implementation(libs.core.ktx)
    implementation(libs.appcompat.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)

    // Compose 核心统一走 Compose Multiplatform 插件坐标，与 shared/desktop 保持同一依赖体系。
    // 包名仍是 androidx.compose.*；Android target 会自动选择对应 Android 变体。
    implementation(compose.runtime)
    implementation(compose.foundation)
    // Android 使用新版 TextContextMenuProvider；1.11 修复 SelectionContainer 复制后未释放选区，
    // 避免 ActionMode 关闭前短暂只剩“全选”。不是关闭新菜单架构的兼容开关。
    implementation(libs.compose.foundation.android)
    implementation(compose.material)
    implementation(compose.ui)
    // activity-compose / lifecycle-runtime-compose 是 Android 平台集成层，不属于第二套 Compose 核心实现。
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle.runtime)
    // 拖拽排序：P2 规则管理 10 个界面统一用它替代 ItemTouchHelper
    implementation(libs.reorderable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    //google
    implementation(libs.material)
    implementation(libs.flexbox)

    //lifecycle
    implementation(libs.lifecycle.service)

    implementation(libs.media.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.splitties.appctx)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // K5-c Phase 5: @Database 已下沉 shared/commonMain, room.compiler KSP 由 shared 模块处理
    // ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)

    implementation(libs.ksoup)
    implementation(libs.kotlinx.serialization.json)
    // 原 modules:rjpath/book-file/jsoup-compat 已合并到 modules:shared (KMP 最佳范式: 单一 KMP 共享模块)
    implementation(project(":shared"))
    implementation(project(":modules:quickjs"))
    // @JsApi 静态分派表生成 (JsExtensions/BaseSource/CookieStore/CacheManager/image 等 JS 面)
    ksp(project(":modules:quickjs-processor"))

    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.play.services.cronet)

    // 1. 提供 Cronet 核心 API（包含对 HttpEngine 的桥接支持）
    implementation(libs.cronet.api) // 建议使用最新版本

// 2. 提供官方打包的兜底 Cronet 底层实现（相当于你本地的 cronetlib）
    implementation(libs.cronet.embedded)

    implementation(libs.protobuf.javalite)

    implementation(libs.glide.glide)
    implementation(libs.glide.okhttp)
    ksp(libs.glide.ksp)
    implementation(libs.glide.recyclerview)
    // Coil3 批 2: app 端 Glide 消费点换 Coil3 (VerificationCodeDialog/CoverImageView/PhotoDialog/AudioPlayScreen)
    // coil3-compose 含 View 扩展 (imageView.load) + Compose AsyncImage; coil3-network-okhttp 走共享 OkHttpClient
    implementation(libs.coil3.compose)
    implementation(libs.coil3.gif)
    implementation(libs.coil3.network.okhttp)

    implementation(libs.androidsvg)

    implementation(libs.nanohttpd.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    implementation(libs.libarchive)

    implementation(libs.markwon.core)
    implementation(libs.markwon.image.glide)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)

    implementation(libs.quick.chinese.transfer.core)

    //noinspection GradleDependency,GradlePackageUpdate
    implementation(libs.hutool.crypto)
}
