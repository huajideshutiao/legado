// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val isHarmonyMode = providers.gradleProperty("enableOhosTarget").getOrNull() == "true"

subprojects {
    configurations.all {
        // 彻底移除 Material Icons 依赖，满足“只允许用共享 XML”的要求
        exclude(group = "androidx.compose.material", module = "material-icons-core")
        exclude(group = "androidx.compose.material", module = "material-icons-extended")

        resolutionStrategy.eachDependency {
            if (isHarmonyMode && requested.group == "org.jetbrains.kotlin") {
                useVersion("2.2.21-0.4.0")
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val ohosLibsDir = layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a")
val ohosIncludeDir = layout.projectDirectory.dir("ohosApp/entry/src/main/cpp/include/arm64-v8a")
val ohosSharedOutputDir = layout.projectDirectory.dir("shared/build/bin/ohosArm64/debugShared")
val ohosSharedLibrary = ohosSharedOutputDir.file("liblegado_shared.so")

val stageOhosNativeLibraries by tasks.registering(Copy::class) {
    group = "ohos"
    description = "Build and stage CPF-KMP-CMP OHOS shared library and generated API header."
    dependsOn(":shared:linkDebugSharedOhosArm64")
    into(layout.projectDirectory.dir("ohosApp"))
    from(ohosSharedLibrary) {
        into("entry/libs/arm64-v8a")
    }
    from(ohosSharedOutputDir) {
        include("*.h")
        into("entry/src/main/cpp/include/arm64-v8a")
    }
    doFirst {
        val generatedHeaders =
            ohosSharedOutputDir.asFile.listFiles { file -> file.extension == "h" }.orEmpty()
        val missing = buildList {
            if (!ohosSharedLibrary.asFile.isFile) add(ohosSharedLibrary.asFile)
            if (generatedHeaders.isEmpty()) add(ohosSharedOutputDir.file("<generated-api-header>.h").asFile)
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing CPF OHOS outputs: ${missing.joinToString()}. " +
                    "Run with -PenableOhosTarget=true and rendererBackend=fusion-renderer."
            )
        }
    }
}

val verifyOhosNativeLibraries by tasks.registering {
    group = "verification"
    description = "Verify that the CPF OHOS fusion-renderer artifacts have been staged."
    dependsOn(stageOhosNativeLibraries)
    doLast {
        val stagedHeaders =
            ohosIncludeDir.asFile.listFiles { file -> file.extension == "h" }.orEmpty()
        val missingLibrary = !ohosLibsDir.file("liblegado_shared.so").asFile.isFile
        if (missingLibrary || stagedHeaders.isEmpty()) {
            throw GradleException(
                "Missing HarmonyOS native artifacts in ${ohosLibsDir.asFile} or ${ohosIncludeDir.asFile}."
            )
        }
    }
}
