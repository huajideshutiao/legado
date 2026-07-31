import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("legado.android.application")
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
val gitCommits = providers.exec {
    commandLine("git", "rev-list", "HEAD", "--count")
}.standardOutput.asText.get().trim().toInt()

android {
    namespace = "io.legado.app"
    compileSdk = 36

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
        minSdk = 26
        targetSdk = 36
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
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    ksp {
        arg("jsapi.extraClasses", "io.legado.app.data.entities.BaseSource,io.legado.app.help.CacheManager")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
        resources.excludes.add("META-INF/androidx/**")
        resources.excludes.add("google/protobuf/**")
        jniLibs.excludes.add("lib/*/libcronet*.so")
    }

    lint {
        disable += listOf("MissingTranslation", "NewerVersionAvailable", "GradleDependency")
    }
}

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
    implementation(libs.kotlinx.atomicfu)

    implementation(libs.core.ktx)
    implementation(libs.appcompat.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(libs.compose.foundation.android)
    implementation(compose.material)
    implementation(compose.ui)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle.runtime)

    implementation(libs.reorderable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    implementation(libs.material)
    implementation(libs.flexbox)

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
    implementation(libs.protobuf.javalite)

    implementation(libs.glide.glide)
    implementation(libs.glide.okhttp)
    ksp(libs.glide.ksp)
    implementation(libs.glide.recyclerview)
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
    implementation(libs.hutool.crypto)
}
