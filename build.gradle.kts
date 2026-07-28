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
    // 桌面端入口模块需要
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val ohosLibsDir = layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a")
val ohosSharedLibrary = layout.projectDirectory.file(
    "shared/build/bin/ohosArm64/debugShared/liblegado_shared.so"
)

val buildOhosFramework by tasks.registering(Exec::class) {
    group = "ohos"
    description = "Build the HarmonyOS Compose bridge library."
    workingDir = rootProject.projectDir
    doFirst {
        val sdkHome = project.findProperty("devecoSdkHome")?.toString()?.takeIf(String::isNotBlank)
            ?: System.getenv("DEVECO_SDK_HOME")
        require(!sdkHome.isNullOrBlank()) {
            "HarmonyOS SDK path is missing. Pass -PdevecoSdkHome=<DevEco Studio>/sdk."
        }
        commandLine("bash", "build_ohos_framework.sh", sdkHome)
    }
    inputs.dir("ohosApp/antui_framework/src/main/cpp")
    outputs.file(ohosLibsDir.file("libmykmp_framework.so"))
}

project(":shared") {
    tasks.matching { it.name == "linkDebugSharedOhosArm64" }.configureEach {
        dependsOn(rootProject.tasks.named("buildOhosFramework"))
    }
}

val stageOhosNativeLibraries by tasks.registering(Copy::class) {
    group = "ohos"
    description = "Build and stage all native libraries required by the HarmonyOS entry module."
    dependsOn(":shared:linkDebugSharedOhosArm64")
    from(ohosSharedLibrary)
    into(ohosLibsDir)
    doFirst {
        if (!ohosSharedLibrary.asFile.isFile) {
            throw GradleException(
                "Missing ${ohosSharedLibrary.asFile}. " +
                    "Run with -PenableOhosTarget=true on a host with the HarmonyOS Kotlin/Native toolchain."
            )
        }
    }
}

val verifyOhosNativeLibraries by tasks.registering {
    group = "verification"
    description = "Verify that HarmonyOS native libraries have been staged."
    dependsOn(stageOhosNativeLibraries)
    doLast {
        val required = listOf("liblegado_shared.so", "libmykmp_framework.so")
            .map { ohosLibsDir.file(it).asFile }
        val missing = required.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException("Missing HarmonyOS native libraries: ${missing.joinToString()}")
        }
    }
}
