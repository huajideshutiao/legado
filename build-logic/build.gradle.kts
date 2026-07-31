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
        // 保持 17: 约定插件字节码由 Gradle daemon 自身加载, daemon 可能仍跑系统 JDK 17
        // (命令行 gradlew) 而非 Android Studio 的 JBR 21; 17 字节码在两者上都能加载。
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// 官方 KGP 没有 ohosArm64 target，只有 CPF 分支有；开关打开时整条插件工具链切到 CPF 版本。
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun version(alias: String) = catalog.findVersion(alias).get().requiredVersion
val kotlinVersion = version(if (enableOhosTarget) "kotlin-ohos" else "kotlin")
val composeVersion = version(if (enableOhosTarget) "composeMultiplatform-ohos" else "composeMultiplatform")

dependencies {
    // 与主构建工具链对齐，避免约定插件向子项目注入旧版 Kotlin/Compose 插件。
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    // composeCompiler {} DSL (稳定性配置/metrics) 需要编译期可见。
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
    implementation("com.android.tools.build:gradle:${version("agp")}")
    implementation("org.jetbrains.compose:compose-gradle-plugin:$composeVersion")
}

// ohosArm64 DSL 只在 CPF KGP 上存在，关闭时整个源码目录不参与编译。
if (enableOhosTarget) {
    sourceSets.named("main") {
        kotlin.srcDir("src/ohos/kotlin")
    }
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
        if (enableOhosTarget) {
            register("kmpOhos") {
                id = "legado.kmp.ohos"
                implementationClass = "io.legado.buildlogic.OhosTargetConventionPlugin"
            }
        }
    }
}
