plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
}

group = "io.legado.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // 与主构建工具链对齐，避免约定插件向子项目注入旧版 Kotlin/Compose 插件。
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("com.android.tools.build:gradle:8.13.2")
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.9.2")
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
