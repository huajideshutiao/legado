import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("legado.android.application")
    // Baseline Profile app target/consumer: 为 :app 创建 nonMinifiedRelease/benchmarkRelease
    // 扩展变体 (复制 release 配置, 关闭混淆), 并消费 app/src/main/baseline-prof.txt
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.serialization)
    id("legado.compose")
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
val gitCommits = providers.exec {
    commandLine("git", "rev-list", "HEAD", "--count")
}.standardOutput.asText.get().trim().toInt()

android {
    namespace = "io.legado.app"
    compileSdk = 37

    signingConfigs {
        // minSdk 24, v2/v3 签名已足够, 关闭 v1 省去 APK 内 JAR 签名清单 (CERT.SF+MANIFEST.MF ~220KB)
        getByName("debug") {
            enableV1Signing = false
        }
        if (project.hasProperty("RELEASE_STORE_FILE")) {
            create("myConfig") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
                enableV1Signing = false
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
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        base.archivesName.set("${name}_${version}")

        val cronetVersion = libs.versions.cronet.get()
        val cronetMainVersion = cronetVersion.substring(0, cronetVersion.indexOf('.')) + ".0.0.0"
        buildConfigField("String", "Cronet_Version", "\"$cronetVersion\"")
        buildConfigField("String", "Cronet_Main_Version", "\"$cronetMainVersion\"")
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
            signingConfig = if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfigs.getByName("myConfig")
            } else {
                signingConfigs.getByName("debug")
            }
            vcsInfo {
                include = false
            }
            applicationIdSuffix = ".release"
            manifestPlaceholders["app_name"] = "@string/app_name"

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
            manifestPlaceholders["app_name"] = "@string/app_name"

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
            manifestPlaceholders["APP_CHANNEL_VALUE"] = "app"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // 只保留 arm 系 ABI (arm64-v8a + armeabi-v7a); x86/x86_64 不再出包。
            // universal 仍出, 且只含已编译 ABI
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    // 产物命名: 见 androidComponents 块 CopyRenamedApks (原版 outputFileName 语义的 KMP 化实现,
    // 共存版 _releaseA 后缀 + 分目录输出, 避免两次构建互相覆盖)。

    ksp {
        arg("jsapi.extraClasses", "io.legado.app.data.entities.BaseSource,io.legado.app.help.CacheManager")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes.add("META-INF/*")
        resources.excludes.add("META-INF/androidx/**")
        resources.excludes.add("META-INF/jsoup/**")
        resources.excludes.add("META-INF/native-image/**")
        resources.excludes.add("google/protobuf/**")
        resources.excludes.add("kotlin/**")
        resources.excludes.add("META-INF/versions/**")
        // quick-transfer-core 的 tc 词典(1.2MB)运行时不读: 走 RemoteAssetsUtils 缓存/远程下载
        resources.excludes.add("tc/*")
        // 依赖 jar 自带的运行时无用载荷: kotlinx-coroutines 调试探测文件 / Kotlin 工具元数据 /
        // play-services 版本属性 (纯元数据, 运行时无消费方)
        resources.excludes.add("DebugProbesKt.bin")
        resources.excludes.add("kotlin-tooling-metadata.json")
        resources.excludes.add("play-services-*.properties")
        // LICENSE/disclaimer/privacyPolicy.md 统一在 shared/src/commonMain/resources (共享一份),
        // Android 端经 classpath 读 APK 根级副本 (AboutScreen/MainActivity), 不可排除
        jniLibs.excludes.add("lib/*/libcronet*.so")
    }

    lint {
        disable += listOf(
            "MissingTranslation", "NewerVersionAvailable", "GradleDependency",
            // targetSdk 36 是产品决策 (升 37 引入 Android 16 行为变更, 需单独回归验证)
            "OldTargetApi",
            // AGP 8.13.2 是刻意保持 (AGP 9 破坏性变更, 见 build-logic 注释), 暂不升级
            "AndroidGradlePluginVersion",
            // media3/appcompat 私有资源: 依赖版本固定, 资源稳定存在, 保留引用
            "PrivateResource",
            // 代码库只用 md2 (androidx.compose.material) 组件; material3 仅经 ui-tooling
            // 传递依赖进入 classpath, 源码无任何 material3 使用, 属误报
            "UsingMaterialAndMaterial3Libraries",
        )
    }
}

// APK 语言目录过滤: APK 只打包 values/ (英文默认) + values-zh/ (简中) +
// values-zh-rHK/values-zh-rTW (繁体), 排除 4 个小语种目录 (values-es-rES/values-ja-rJP/
// values-pt-rBR/values-vi)。用户决策小语种暂缓打包; 资源文件本体保留在 shared 全量
// (desktop/iOS 资源生成不受影响)。androidResources.localeFilters 只作用于 AAPT 合并的
// res/ 资源, 管不到 composeResources (它作为 assets 走 merge{Variant}Assets)。
// 机制: shared 的 copy*ComposeResourcesToAndroidAssets 只把 composeResources 复制进
// shared AAR, 全量合并发生在本模块的 merge{Variant}Assets (把 AAR 资产复制进合并输出)。
// shared 侧删除会被 merge 覆盖, 故挂在此处 doLast: merge 完成后删除合并输出下的 4 个
// 小语种子目录, 删除先于 package{Variant} 打包 (package 任务消费 merge 输出)。
// 输出目录用 outputs.files 取, 不硬编码路径。
val excludedComposeLocales = listOf(
    "values-es-rES",
    "values-ja-rJP",
    "values-pt-rBR",
    "values-vi",
)
tasks.matching {
    it.name == "mergeAppDebugAssets" || it.name == "mergeAppReleaseAssets"
}.configureEach {
    doLast {
        outputs.files.forEach { output ->
            val resourcesRoot = output.resolve("composeResources/legado.shared.generated.resources")
            excludedComposeLocales.forEach { locale ->
                project.delete(resourcesRoot.resolve(locale))
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "copyRenamedApksFor${variant.name.replaceFirstChar(Char::uppercaseChar)}"
        // 共存构建 (CI 传 -PcoexistBuild=true) 时产物名带 _releaseA 后缀 (原版 outputFileName 语义),
        // 且按 suffix 分目录输出, 避免两次构建 (原包名/共存) 产物互相覆盖。
        // 用 gradle property 判定而非 variant.buildType (新 API 不暴露), 由 CI 显式声明。
        val suffix =
            if (providers.gradleProperty("coexistBuild").orNull == "true") "_releaseA" else ""
        val copyTask = tasks.register<CopyRenamedApks>(taskName) {
            outputDirectory.set(layout.buildDirectory.dir("outputs/renamed-apks/${variant.name}${suffix}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            releaseSuffix.set(suffix)
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
    implementation(libs.kotlinx.atomicfu)

    // Baseline Profile: 安装时由 profileinstaller 读取打包的 baseline-prof.txt 做 ART 预编译
    implementation(libs.androidx.profileinstaller)

    implementation(libs.core.ktx)
    implementation(libs.appcompat.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)

    implementation(libs.runtime)
    implementation(libs.foundation)
    implementation(libs.jetbrains.material)
    implementation(libs.ui)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle.runtime)

    implementation(libs.reorderable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    implementation(libs.material)

    implementation(libs.lifecycle.service)

    implementation(libs.media.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.room.runtime)
    androidTestImplementation(libs.room.testing)

    implementation(libs.ksoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":shared"))
    implementation(project(":modules:quickjs"))
    ksp(project(":modules:quickjs-processor"))

    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.play.services.cronet)
    implementation(libs.cronet.api)
    implementation(libs.cronet.embedded)

    implementation(libs.coil3.compose)
    implementation(libs.coil3.gif)
    implementation(libs.coil3.network.okhttp)

    implementation(libs.androidsvg)
    implementation(libs.nanohttpd.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.libarchive)

    implementation(libs.quick.chinese.transfer.core)
    implementation(libs.hutool.crypto)
}
