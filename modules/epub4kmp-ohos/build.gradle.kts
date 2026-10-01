import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("legado.kmp.native.library")
    alias(libs.plugins.kotlin.serialization)
}

check(providers.gradleProperty("enableOhosTarget").orNull.toBoolean())

// Recompile published sources with the CPF toolchain; upstream publishes no OHOS klibs.
val sourceArchives = configurations.create("epubSourceArchives")
dependencies {
    sourceArchives("com.darkrockstudios:epub4kmp-core:${libs.versions.epub4kmp.get()}:sources@jar")
    sourceArchives("io.github.pdvrieze.xmlutil:core:0.91.3:sources@jar")
    sourceArchives("no.synth:kmp-zip:${libs.versions.kmp.zip.get()}:sources@jar")
    sourceArchives("no.synth:kmp-zip-okio:${libs.versions.kmp.zip.get()}:sources@jar")
    commonMainImplementation("com.squareup.okio:okio:${libs.versions.okio.ohos.get()}")
    commonMainImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.ohos.get()}")
    commonMainImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core:${libs.versions.serialization.ohos.get()}")
}

val generatedSources = layout.buildDirectory.dir("generated/epubSources")
val unpackSources = tasks.register<Sync>("unpackEpubSources") {
    into(generatedSources)
    fun archive(prefix: String, vararg roots: String) {
        from({ sourceArchives.files.filter { it.name.startsWith(prefix) }.map(::zipTree) }) {
            include(*roots.map { "$it/**" }.toTypedArray())
            into(prefix)
        }
    }
    archive("epub4kmp-core-", "commonMain")
    archive("core-", "commonMain", "commonDomMain", "nativeMain")
    archive("kmp-zip-0", "commonMain", "commonNonJvmMain", "nativeMain", "linuxMain", "pureKotlinCryptoMain")
    archive("kmp-zip-okio-", "commonMain", "nativeMain")
    // CPF provides POSIX; the SDK zlib is bound locally rather than platform.zlib.
    filesMatching("**/*.kt") {
        filter { line -> line.replace("import platform.zlib.", "import no.synth.kmpzip.zlib.") }
    }
    // Only Date uses kotlinx-datetime. Instant's ISO form provides the same UTC date
    // without introducing another library lacking an OHOS variant.
    doLast {
        val date = generatedSources.get().file(
            "epub4kmp-core-/commonMain/io/documentnode/epub4kmp/domain/Date.kt"
        ).asFile
        date.writeText(date.readText()
            .replace(Regex("(?m)^import kotlinx\\.datetime.*\\n"), "")
            .replace(Regex("    constructor\\(date: LocalDate[^\\n]*\\n"), "")
            .replace("instant.toLocalDateTime(TimeZone.UTC).date.toString()", "instant.toString().substringBefore('T')"))
    }
}

kotlin {
    this::class.java.getMethod("ohosArm64").invoke(this)
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("epubZlib") {
            defFile(file("src/cinterop/zlib.def"))
        }
        binaries.all { linkerOpts("-lz") }
    }
    sourceSets {
        val commonMain = getByName("commonMain")
        listOf("epub4kmp-core-", "core-", "kmp-zip-0", "kmp-zip-okio-").forEach { library ->
            commonMain.kotlin.srcDir(generatedSources.map { it.dir("$library/commonMain") })
        }
        val ohosMain = maybeCreate("ohosMain").apply {
            dependsOn(commonMain)
            listOf(
                "core-/commonDomMain", "core-/nativeMain",
                "kmp-zip-0/commonNonJvmMain", "kmp-zip-0/nativeMain",
                "kmp-zip-0/linuxMain", "kmp-zip-0/pureKotlinCryptoMain",
                "kmp-zip-okio-/nativeMain",
            ).forEach { root -> kotlin.srcDir(generatedSources.map { it.dir(root) }) }
        }
        maybeCreate("ohosArm64Main").dependsOn(ohosMain)
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("nl.adaptivity.xmlutil.ExperimentalXmlUtilApi")
        }
    }
}
tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) dependsOn(unpackSources)
}
