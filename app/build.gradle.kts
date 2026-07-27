import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    // K5-c Phase 5: @Database 已下沉 shared/commonMain, room 插件移至 shared 模块
    // alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

fun releaseTime(): String {
    val fmt = DateTimeFormatter.ofPattern("yy.MMddHH")
        .withZone(ZoneId.of("GMT+8"))
    return fmt.format(Instant.now())
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
    compileSdk = rootProject.extra["compile_sdk_version"] as Int
    namespace = "io.legado.app"
    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

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
        minSdk = 26
        targetSdk = 36
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

    // 改输出 APK 文件名: ApkVariantOutput 才有 getFilter/outputFileName, configureEach receiver 推断为
    // 父类型 BaseVariantOutput, 需 cast (AGP 8 旧 VariantOutput API 仍可用, 新 Variant API 无 outputFileName 写入入口)
    applicationVariants.configureEach {
        outputs.configureEach {
            val apkOutput = this as com.android.build.gradle.api.ApkVariantOutput
            val abi = apkOutput.getFilter(com.android.build.VariantOutput.FilterType.ABI)
            // 简写架构名称
            val abiShortMap = mapOf(
                "arm64-v8a" to "arm64",
                "armeabi-v7a" to "armv7",
                "x86_64" to "x64",
                "x86" to "x86",
                "all" to "all"
            )
            val abiName = abiShortMap[abi ?: "all"]
            var suffix = ""
            if (buildType.name == "release") {
                suffix = if (buildType.applicationIdSuffix == ".releaseA") "_releaseA" else ""
            }
            apkOutput.outputFileName = "${abiName}${suffix}.apk"
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
        // Sets Java compatibility
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    //compose：BOM 统一版本，platform 引入
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    // MD2 迁移: AppTheme 基于 M2 MaterialTheme, 界面组件统一 material(M2), material3 仅剩存量待清
    implementation(libs.compose.material)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
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
