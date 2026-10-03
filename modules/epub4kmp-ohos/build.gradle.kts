import org.gradle.api.tasks.Sync

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

/**
 * 各上游 sources jar 解包后实际被挂载的源根 (prefix → 目录名)。
 * 挂载 (kotlin.srcDir) 与解包产物校验共用本清单, 避免两处各写一份而漏检。
 */
val unpackedSourceRoots: Map<String, List<String>> = mapOf(
    "epub4kmp-core-" to listOf("commonMain"),
    "core-" to listOf("commonMain", "commonDomMain", "nativeMain"),
    "kmp-zip-0" to listOf("commonMain", "commonNonJvmMain", "nativeMain", "linuxMain", "pureKotlinCryptoMain"),
    "kmp-zip-okio-" to listOf("commonMain", "nativeMain"),
)

val unpackSources = tasks.register<Sync>("unpackEpubSources") {
    into(generatedSources)
    fun archive(prefix: String, vararg roots: String) {
        from({ sourceArchives.files.filter { it.name.startsWith(prefix) }.map(::zipTree) }) {
            include(*roots.map { "$it/**" }.toTypedArray())
            into(prefix)
        }
    }
    unpackedSourceRoots.forEach { (prefix, roots) -> archive(prefix, *roots.toTypedArray()) }
    // Only Date uses kotlinx-datetime. Instant's ISO form provides the same UTC date
    // without introducing another library lacking an OHOS variant.
    // 校验值先解析成可序列化局部量再进 doLast: 闭包引用脚本对象会破坏配置缓存存储。
    val rootsToVerify: List<Pair<String, String>> =
        unpackedSourceRoots.flatMap { (prefix, roots) -> roots.map { prefix to it } }
    val dateRelPath = "epub4kmp-core-/commonMain/io/documentnode/epub4kmp/domain/Date.kt"
    doLast {
        // 产物校验: 任一源根缺失说明上游 sources jar 改名/变结构, 显式失败而非静默产出缺源集的空壳
        rootsToVerify.forEach { (prefix, root) ->
            check(File(destinationDir, "$prefix/$root").isDirectory) {
                "unpackEpubSources: $prefix/$root 源码目录缺失, 上游 sources jar 结构可能已变化"
            }
        }
        val date = File(destinationDir, dateRelPath)
        date.writeText(date.readText()
            .replace(Regex("(?m)^import kotlinx\\.datetime.*\\n"), "")
            .replace(Regex("    constructor\\(date: LocalDate[^\\n]*\\n"), "")
            .replace("instant.toLocalDateTime(TimeZone.UTC).date.toString()", "instant.toString().substringBefore('T')"))
    }
}

kotlin {
    // zlib 绑定用 K/N 自带 platform.zlib (含 -lz linkerOpts): 自建 cinterop 与预导入
    // platform PCH 重复, struct/函数会被静默去重只剩宏与 typedef, 编译期才报 Unresolved。
    this::class.java.getMethod("ohosArm64").invoke(this)
    sourceSets {
        // 每个 .kt 只能属于一个 fragment (-Xfragment-sources 校验), native 根挂 ohosMain
        // 后经 dependsOn 传递给下游编译, 严禁在 commonMain 重复挂载。
        val commonMain = getByName("commonMain")
        unpackedSourceRoots.keys.forEach { prefix ->
            commonMain.kotlin.srcDir(generatedSources.map { it.dir("$prefix/commonMain") })
        }
        val ohosMain = maybeCreate("ohosMain").apply {
            dependsOn(commonMain)
            listOf(
                "core-/commonDomMain", "core-/nativeMain",
                "kmp-zip-0/commonNonJvmMain", "kmp-zip-0/nativeMain",
                "kmp-zip-0/pureKotlinCryptoMain",
                "kmp-zip-okio-/nativeMain",
            ).forEach { root -> kotlin.srcDir(generatedSources.map { it.dir(root) }) }
        }
        // kmp-zip 的 zlibCrc32Update expect 在 nativeMain、actual 在 linuxMain,
        // 上游为父子源集; 平铺同 fragment 会报 expect/actual 同模块冲突, 故 linux 独立下游 fragment。
        val kmpZipLinux = maybeCreate("kmpZipLinuxMain").apply {
            dependsOn(ohosMain)
            kotlin.srcDir(generatedSources.map { it.dir("kmp-zip-0/linuxMain") })
        }
        maybeCreate("ohosArm64Main").apply {
            dependsOn(ohosMain)
            dependsOn(kmpZipLinux)
        }
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("nl.adaptivity.xmlutil.ExperimentalXmlUtilApi")
            languageSettings.optIn("nl.adaptivity.xmlutil.XmlUtilInternal")
            languageSettings.optIn("nl.adaptivity.xmlutil.XmlUtilDeprecatedInternal")
        }
    }
}
tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) dependsOn(unpackSources)
}
