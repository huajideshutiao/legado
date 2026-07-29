plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    google()
    gradlePluginPortal()
    mavenCentral()
}

group = "io.legado.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.plugins.android.application.toPluginDependency())
    implementation(libs.plugins.android.library.toPluginDependency())
    implementation(libs.plugins.android.kmp.library.toPluginDependency())
    implementation(libs.plugins.kotlin.android.toPluginDependency())
    implementation(libs.plugins.kotlin.multiplatform.toPluginDependency())
    implementation(libs.plugins.kotlin.jvm.toPluginDependency())
    implementation(libs.plugins.compose.multiplatform.toPluginDependency())
    implementation(libs.plugins.compose.compiler.toPluginDependency())
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "legado.android.application"
            implementationClass = "io.legado.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("kmpLibrary") {
            id = "legado.kmp.library"
            implementationClass = "io.legado.buildlogic.KmpLibraryConventionPlugin"
        }
        register("jvmApplication") {
            id = "legado.jvm.application"
            implementationClass = "io.legado.buildlogic.JvmApplicationConventionPlugin"
        }
        register("compose") {
            id = "legado.compose"
            implementationClass = "io.legado.buildlogic.ComposeConventionPlugin"
        }
    }
}

private fun Provider<PluginDependency>.toPluginDependency(): Provider<String> = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}
