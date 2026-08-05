import org.gradle.api.tasks.Sync

plugins {
    id("legado.kmp.library")
}

val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
check(enableOhosTarget) { "ksoup-ohos is only available with -PenableOhosTarget=true" }

val sourceArchives = configurations.create("ksoupSourceArchives")

dependencies {
    sourceArchives("com.fleeksoft.ksoup:ksoup:0.2.6:sources@jar")
    sourceArchives("com.fleeksoft.io:io-core:0.0.8:sources@jar")
    sourceArchives("com.fleeksoft.charset:charset:0.0.8:sources@jar")
    // 版本从 rootProject 的 catalog 读取 (*-ohos 键, 与 build-logic 同款模式), 禁止硬编码
    val ohosCatalog =
        rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    fun ohosVersion(key: String): String =
        ohosCatalog.findVersion(key).get().requiredVersion
    commonMainImplementation("co.touchlab:stately-concurrency:${ohosVersion("stately-ohos")}")
    commonMainImplementation("org.jetbrains.kotlinx:atomicfu:${ohosVersion("atomicfu-ohos")}")
}

val generatedSourceRoot = layout.buildDirectory.dir("generated/ksoupSources")
val unpackSourceArchives = tasks.register<Sync>("unpackKsoupSourceArchives") {
    into(generatedSourceRoot)
    from({ sourceArchives.files.filter { it.name.startsWith("ksoup-") }.map(::zipTree) }) {
        include("commonMain/**", "nativeMain/**")
        into("ksoup")
    }
    from({ sourceArchives.files.filter { it.name.startsWith("io-core-") }.map(::zipTree) }) {
        include("commonMain/**", "nonJvmMain/**")
        into("io-core")
    }
    from({ sourceArchives.files.filter { it.name.startsWith("charset-") }.map(::zipTree) }) {
        include("commonMain/**", "nonJvmMain/**")
        into("charset")
    }
}

kotlin {
    this::class.java.getMethod("ohosArm64").invoke(this)
    sourceSets {
        val commonMain = getByName("commonMain")
        commonMain.kotlin.srcDir(generatedSourceRoot.map { it.dir("ksoup/commonMain") })
        commonMain.kotlin.srcDir(generatedSourceRoot.map { it.dir("io-core/commonMain") })
        commonMain.kotlin.srcDir(generatedSourceRoot.map { it.dir("charset/commonMain") })

        val ohosMain = maybeCreate("ohosMain").apply {
            dependsOn(commonMain)
            kotlin.srcDir("src/ohosMain/kotlin")
            kotlin.srcDir(generatedSourceRoot.map { it.dir("ksoup/nativeMain") })
            kotlin.srcDir(generatedSourceRoot.map { it.dir("io-core/nonJvmMain") })
            kotlin.srcDir(generatedSourceRoot.map { it.dir("charset/nonJvmMain") })
        }
        maybeCreate("ohosArm64Main").dependsOn(ohosMain)
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(unpackSourceArchives)
    }
}
