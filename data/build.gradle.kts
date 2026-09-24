// :data —— 数据与领域层 (从 :shared 切分, 依赖 foundation)。
//
// 职责: 领域模型与运行时核心 —— data (Room 实体/DAO/数据库) + model (书源/书籍领域逻辑)
// + api + org/jsoup 移植 + help/http (KMP HTTP 抽象, jsoup 依赖) + 被 data/model/http
// 依赖的 help Provider 接口与 JS 桥核心 (JsExtensionsCommon/StrResponse/model.script)。
// JS 桥架构约束: model/script + JsExtensionsCommon + StrResponse 必须同模块
// (KSP 生成的 NativeGeneratedDispatch 是 internal object, 桥代码在 model/script 内引用)。
import io.legado.buildlogic.generateCNamesAliases
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

room3 {
    // 鸿蒙走派生目录 (与 :shared 同款: CPF fork 的 room3 编译器是 alpha01, 见 deriveOhosRoomSchemas)
    schemaDirectory(
        if (providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() == true) {
            layout.buildDirectory.dir("ohosRoomSchemas").get().asFile.absolutePath
        } else {
            "$projectDir/schemas"
        }
    )
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

// 带 `@Entity(withoutRowId = ...)` 的实体源码单独放一个 srcDir (同 :shared 的机制,
// 鸿蒙构建由 deriveOhosRoomEntities 换派生副本)。
val ROOM_ENTITIES_SRC_DIR = "src/roomEntitiesMain/kotlin"
val ENTITY_PACKAGE_PATH = "io/legado/app/data/entities"

// nativeMain 里依赖 C 符号的桥文件: 从 nativeMain 排除, stage 进 leaf 源集
// (quickjs = model/script JS 桥; mbedtls = help/crypto 加密, generateCNamesAliases 在 build-logic)。
val nativeInteropSourcePatterns = listOf(
    "io/legado/app/model/script/*.native.kt",
    "io/legado/app/help/crypto/MbedTls*.native.kt",
    "io/legado/app/help/crypto/MbedTlsOps.native.kt",
)
val nativeInteropSourceRoot = file("src/nativeMain/kotlin")

val stageNativeInteropForIos = if (enableIosTarget) {
    tasks.register<Sync>("stageNativeInteropForIos") {
        val stagedDir = layout.buildDirectory.dir("generated/nativeInterop/iosLeaf").get().asFile
        from(nativeInteropSourceRoot) {
            include(*nativeInteropSourcePatterns.toTypedArray())
        }
        into(stagedDir)
        doLast {
            generateCNamesAliases(stagedDir)
        }
    }
} else null
fun registerOhosInteropStage(taskName: String, outputDirName: String): TaskProvider<Sync>? =
    if (enableOhosTarget) {
        tasks.register<Sync>(taskName) {
            val stagedDir = layout.buildDirectory.dir(outputDirName).get().asFile
            from(nativeInteropSourceRoot) {
                include(*nativeInteropSourcePatterns.toTypedArray())
            }
            into(stagedDir)
            doLast {
                generateCNamesAliases(stagedDir)
            }
        }
    } else null
val stageNativeInteropForOhos =
    registerOhosInteropStage("stageNativeInteropForOhos", "generated/nativeInterop/ohosArm64Main")

// ============ 鸿蒙 Room 派生三件套 (从 :shared 迁移, 见 foundation/data/core/ui 各模块 build 脚本注释) ============
abstract class DeriveOhosRoomSchemas : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun derive() {
        val src = sourceDirectory.get().asFile
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        src.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val target = out.resolve(file.relativeTo(src).invariantSeparatorsPath)
            target.parentFile.mkdirs()
            target.writeText(strip(file.readText()))
        }
    }

    private fun strip(json: String): String = UNKNOWN_KEYS.fold(json) { text, key ->
        text.replace(Regex(""",\s*"$key"\s*:\s*$JSON_SCALAR"""), "")
            .replace(Regex(""""$key"\s*:\s*$JSON_SCALAR\s*,\s*"""), "")
    }

    private companion object {
        val UNKNOWN_KEYS = listOf("withoutRowId")
        const val JSON_SCALAR = """(?:true|false|null|-?\d+(?:\.\d+)?|"(?:[^"\\]|\\.)*")"""
    }
}

abstract class DeriveOhosRoomEntities : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val guardedSources: ConfigurableFileCollection

    @get:Input
    abstract val packagePath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun derive() {
        val stray = guardedSources.files.filter { it.readText().contains("withoutRowId") }
        check(stray.isEmpty()) {
            "以下实体用了 withoutRowId 但不在 roomEntitiesMain 源集, 鸿蒙构建会编不过: " +
                stray.joinToString { it.name }
        }
        val outRoot = outputDirectory.get().asFile
        outRoot.deleteRecursively()
        val outDir = outRoot.resolve(packagePath.get())
        outDir.mkdirs()
        sources.files.forEach { file ->
            val derived = file.readText()
                .replace(Regex(""",\s*withoutRowId\s*=\s*(?:true|false)"""), "")
                .replace(Regex("""withoutRowId\s*=\s*(?:true|false)\s*,\s*"""), "")
            outDir.resolve(file.name).writeText(HEADER + derived)
        }
    }

    private companion object {
        const val HEADER = "// 由 :data:deriveOhosRoomEntities 从 commonMain 派生, 勿手改 " +
            "(剥 @Entity 的 withoutRowId 实参; CPF fork 的 room3 无此成员)。\n"
    }
}

abstract class DeriveOhosRoomImpl : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val generatedSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun derive() {
        val sources = generatedSources.files.sortedBy { it.name }
        check(sources.any { it.name == "AppDatabase_Impl.kt" }) {
            "KSP 未产出 AppDatabase_Impl.kt; 本任务须在 kspKotlinOhosArm64 之后执行"
        }
        val outRoot = outputDirectory.get().asFile
        outRoot.deleteRecursively()
        val outDir = outRoot.resolve("io/legado/app/data")
        outDir.mkdirs()
        sources.forEach { source ->
            val derived = source.readText()
                .replace("override suspend fun ", "override fun ")
                .replace("import androidx.sqlite.executeSQL", "import androidx.sqlite.execSQL")
                .replace("connection.executeSQL(", "connection.execSQL(")
            outDir.resolve(source.name.removeSuffix(".kt") + ".ohos.kt").writeText(HEADER + derived)
        }
    }

    private companion object {
        const val HEADER = "// 由 :data:deriveOhosRoomImpl 从 KSP 生成物派生, 勿手改 " +
            "(剥 override 上的 suspend 修饰; SQL 扩展名改 fork 的 execSQL)。\n"
    }
}

abstract class VerifyOhosRoomDerived : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val generatedSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val derivedSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val generated = generatedSources.files.associateBy { it.name }
        val derived = derivedSources.files
            .associateBy { it.name.removeSuffix(".ohos.kt") + ".kt" }
        val problems = mutableListOf<String>()
        val report = mutableListOf<String>()
        (generated.keys - derived.keys).sorted().takeIf { it.isNotEmpty() }?.let {
            problems += "KSP 有而派生缺: $it (KSP 新增了不兼容生成物, 对齐收集/exclude 通配)"
        }
        (derived.keys - generated.keys).sorted().takeIf { it.isNotEmpty() }?.let {
            problems += "派生有而 KSP 无: $it (残留旧派生件)"
        }
        report += "files=" + derived.keys.sorted()
        val genImpl = generated["AppDatabase_Impl.kt"]?.readText()
        val derImpl = derived["AppDatabase_Impl.kt"]?.readText()
        if (genImpl == null || derImpl == null) {
            problems += "AppDatabase_Impl 缺失 (KSP=${genImpl != null}, 派生=${derImpl != null})"
        } else {
            val genDaos = DAO_IMPL.findAll(genImpl).map { it.value }.toSortedSet()
            val derDaos = DAO_IMPL.findAll(derImpl).map { it.value }.toSortedSet()
            if (genDaos != derDaos) {
                problems += "DAO 集不一致: KSP ${genDaos.size} 个 $genDaos, 派生 ${derDaos.size} 个 $derDaos"
            }
            val genVersion = DB_VERSION.find(genImpl)?.groupValues?.get(1)
            val derVersion = DB_VERSION.find(derImpl)?.groupValues?.get(1)
            if (genVersion != derVersion) {
                problems += "@Database version 不一致: KSP $genVersion, 派生 $derVersion"
            }
            report += "version=${genVersion ?: "unknown"}"
            report += "dao=${genDaos.size} $genDaos"
        }
        derived.forEach { (name, file) ->
            val text = file.readText()
            if (text.contains("override suspend fun")) problems += "$name 残留 override suspend fun"
            if (text.contains("executeSQL")) problems += "$name 残留 executeSQL"
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(report.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "鸿蒙 Room 派生产物校验失败:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
    }

    private companion object {
        val DAO_IMPL = Regex("""\b\w+Dao_Impl\b""")
        val DB_VERSION = Regex("""RoomOpenDelegate\(\s*(\d+)""")
    }
}

val ohosRoomDerivedDir = layout.buildDirectory.dir("generated/ohosRoomDerived/kotlin")

fun ohosRoomKspSources(): ConfigurableFileTree {
    val kspDir = layout.buildDirectory
        .dir("generated/ksp/ohosArm64/ohosArm64Main/kotlin").get().asFile
    return fileTree(kspDir).apply {
        include(
            "io/legado/app/data/AppDatabase_Impl.kt",
            "io/legado/app/data/AppDatabase_AutoMigration_*_Impl.kt",
        )
    }
}

val deriveOhosRoomEntities = if (enableOhosTarget) {
    tasks.register<DeriveOhosRoomEntities>("deriveOhosRoomEntities") {
        sources.setFrom(
            layout.projectDirectory.dir("$ROOM_ENTITIES_SRC_DIR/$ENTITY_PACKAGE_PATH").asFileTree
        )
        guardedSources.setFrom(
            layout.projectDirectory.dir("src/commonMain/kotlin/$ENTITY_PACKAGE_PATH").asFileTree
        )
        packagePath.set(ENTITY_PACKAGE_PATH)
        outputDirectory.set(layout.buildDirectory.dir("ohosRoomEntities"))
    }
} else null

val deriveOhosRoomSchemas = if (enableOhosTarget) {
    tasks.register<DeriveOhosRoomSchemas>("deriveOhosRoomSchemas") {
        sourceDirectory.set(layout.projectDirectory.dir("schemas"))
        outputDirectory.set(layout.buildDirectory.dir("ohosRoomSchemas"))
    }
} else null

val deriveOhosRoomImpl = if (enableOhosTarget) {
    tasks.register<DeriveOhosRoomImpl>("deriveOhosRoomImpl") {
        generatedSources.from(ohosRoomKspSources())
        outputDirectory.set(ohosRoomDerivedDir)
        dependsOn("kspKotlinOhosArm64")
    }
} else null

val verifyOhosRoomDerived = if (enableOhosTarget) {
    tasks.register<VerifyOhosRoomDerived>("verifyOhosRoomDerived") {
        generatedSources.from(ohosRoomKspSources())
        derivedSources.from(fileTree(ohosRoomDerivedDir))
        reportFile.set(layout.buildDirectory.file("reports/ohosRoomDerived/summary.txt"))
        dependsOn(deriveOhosRoomImpl!!)
    }
} else null

// ohosArm64 只存在于 CPF 分支 KGP, 交给 build-logic 的约定插件声明。
if (enableOhosTarget) {
    pluginManager.apply("legado.kmp.ohos")
    // cinterop (quickjs/mbedtls) 的 def/头文件在本模块 src/cinterop, 故 cinterop 绑定
    // 也随本模块配置 (旧版在 OhosTargetConventionPlugin 里对全部模块注入, 其它模块无 def
    // 文件导致 cinterop 任务失败)。用标准 targets.named API 配置, 不依赖 CPF DSL。
    extensions.configure<KotlinMultiplatformExtension> {
        targets.named<KotlinNativeTarget>("ohosArm64") {
            compilations.getByName("main").cinterops {
                create("quickjs") {
                    defFile(file("src/cinterop/quickjs.def"))
                    // 头文件与 C 源码在 modules/quickjs/src/main/cinterop/quickjs-ng (Android CMake 也引用该目录),
                    // 本模块只编 cinterop 绑定, 链接用预编译 native 库
                    includeDirs(file("${rootProject.projectDir}/modules/quickjs/src/main/cinterop/quickjs-ng"))
                }
                create("mbedtls") {
                    defFile(file("src/cinterop/mbedtls.def"))
                    includeDirs(
                        file("${projectDir}/src/cinterop/mbedtls/include"),
                        file("${projectDir}/src/cinterop/mbedtls"),
                    )
                }
            }
        }
    }
}

// JVM+Android 共享依赖 (与 :shared 同款 helper, 切分后随本源集移动)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun KotlinDependencyHandler.sharedJvmAndroidDeps() {
    api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
    implementation(sharedLibs.findLibrary("hutool-crypto").get())
    api(project(":modules:quickjs"))
    api(sharedLibs.findLibrary("okhttp").get())
    implementation(sharedLibs.findLibrary("coil3-network-okhttp").get())
    implementation(sharedLibs.findLibrary("nanohttpd-nanohttpd").get())
    implementation(sharedLibs.findLibrary("nanohttpd-websocket").get())
}

kotlin {
    jvm()

    android {
        namespace = "io.legado.data"
        compileSdk = 37
        minSdk = 24
    }

    if (enableIosTarget) {
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            if (isMacHost) {
                val nativeLibDir = file("${projectDir}/build/iosNativeLibs/${konanTarget.name}")
                binaries {
                    all {
                        linkerOpts("-L${nativeLibDir.absolutePath}", "-lquickjs", "-lmbedtls", "-lsqlite3")
                    }
                }
                compilations.getByName("main").cinterops {
                    create("quickjs") {
                        defFile(file("src/cinterop/quickjs.def"))
                        // 头文件与 C 源码在 modules/quickjs/src/main/cinterop/quickjs-ng (Android CMake 也引用该目录),
                        // 本模块只编 cinterop 绑定, 链接用预编译 native 库
                        includeDirs(file("${rootProject.projectDir}/modules/quickjs/src/main/cinterop/quickjs-ng"))
                    }
                    create("mbedtls") {
                        defFile(file("src/cinterop/mbedtls.def"))
                        includeDirs(
                            file("${projectDir}/src/cinterop/mbedtls/include"),
                            file("${projectDir}/src/cinterop/mbedtls"),
                        )
                    }
                    create("nskeyvalueobserving") {
                        defFile(file("src/cinterop/nskeyvalueobserving.def"))
                    }
                }
            }
        }
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }

    sourceSets {
        commonMain {
            if (enableOhosTarget) {
                kotlin.srcDir("src/ohosCompatMain/kotlin")
                kotlin.srcDir(layout.buildDirectory.dir("ohosRoomEntities"))
            } else {
                kotlin.srcDir("src/nonOhosCompatMain/kotlin")
                kotlin.srcDir(ROOM_ENTITIES_SRC_DIR)
            }
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":foundation"))
                api(project(":modules:js-api"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                api(libs.okio)
                api(libs.room.common)
                api(libs.room.runtime)
                // 标准 ksoup 无 ohosArm64 klib 而 ksoup-ohos 只有 ohosArm64 target, 两者都
                // 不能进 commonMain; 由 OhosTargetConventionPlugin 在 ohos 配置上把标准 ksoup
                // 替换为 :modules:ksoup-ohos, 其余平台照常解析标准 ksoup。
                implementation(libs.ksoup)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // 实体源码 src/roomEntitiesMain/kotlin 直接挂 commonMain (与 :shared 同款:
        // roomEntitiesMain 只是目录约定, 非独立 KMP 源集; 鸿蒙构建换派生副本)
        androidMain {
            // 共享 JVM+Android 源码根 (与 :shared 同款模式)
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kotlinx.coroutines.android)
                api(libs.androidx.documentfile)
                implementation(libs.core.ktx)
                // ImageProvider.android 的 SVG 解码 (与 :shared 同款)
                implementation(libs.androidsvg)
            }
        }
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kxml2)
                implementation(libs.androidx.sqlite.bundled)
                // EpubFilePlatform.jvm 的 Skia 图片编码 (与 desktop-core 同款 skiko-awt)
                implementation("org.jetbrains.skiko:skiko-awt:0.144.6")
            }
        }
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
                kotlin.exclude(
                    *nativeInteropSourcePatterns.toTypedArray(),
                )
                dependencies {
                    implementation(libs.ktor.server.core)
                    implementation(libs.ktor.server.cio)
                    implementation(libs.ktor.server.websockets)
                }
            }
        } else null

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependencies {
                    implementation(libs.androidx.sqlite.framework)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                }
                // 非 mac: nskeyvalueobserving cinterop (需 Xcode sysroot) 无法生成,
                // KVO 观察器 (依赖其协议类型) 排除, 由 iosWindowsCheckMain 源根的
                // 同签名 stub 类顶替 (与旧 :shared 同款处理)。
                if (!isMacHost) {
                    kotlin.exclude(
                        "io/legado/app/help/media/AvPlayerBufferingObserver.ios.kt",
                        "io/legado/app/help/media/AvPlayerItemStatusObserver.ios.kt",
                    )
                    kotlin.srcDir("src/iosWindowsCheckMain/kotlin")
                }
            }
            // 显式 dependsOn 会让 KGP 回退到 pre-1.9.20 默认边, 中间源集须手工连。
            maybeCreate("iosArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
                if (!isMacHost) {
                    kotlin.exclude(
                        *nativeInteropSourcePatterns.toTypedArray(),
                        "io/legado/app/napi/quickjs/CNamesAliases.kt",
                        "io/legado/app/nativecrypto/mbedtls/CNamesAliases.kt",
                    )
                }
            }
            maybeCreate("iosSimulatorArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
                if (!isMacHost) {
                    kotlin.exclude(
                        *nativeInteropSourcePatterns.toTypedArray(),
                        "io/legado/app/napi/quickjs/CNamesAliases.kt",
                        "io/legado/app/nativecrypto/mbedtls/CNamesAliases.kt",
                    )
                }
            }
        }
        if (enableOhosTarget) {
            val ohosMain = maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
                dependencies {
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                }
            }
            maybeCreate("ohosArm64Main").apply {
                dependsOn(ohosMain)
                // sqlite-framework 的 klib 供 KSP 生成的 DAO (prepare/step) 编译使用,
                // 声明在叶源集上确保进 ohosArm64 主编译 classpath (经 ohosMain 的
                // implementation 在 K/N klib 传递中不可靠)。
                dependencies {
                    implementation("androidx.sqlite:sqlite-framework:2.7.0-alpha01-0.3.0")
                }
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/ohosArm64Main"))
                kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/ohosArm64/ohosArm64Main/kotlin"))
                kotlin.srcDir(ohosRoomDerivedDir)
                kotlin.exclude(
                    "io/legado/app/data/AppDatabase_Impl.kt",
                    "io/legado/app/data/AppDatabase_AutoMigration_*_Impl.kt",
                )
            }
        }

        // 测试 (从 :shared 迁移: 大部分测 data/model 实体与解析)
        val jvmAndAndroidTest = create("jvmAndAndroidTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.kotlin.reflect)
            }
        }
        matching { it.name == "androidHostTest" }.configureEach {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
        }
    }
}
dependencies {
    add("kspCommonMainMetadata", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
        // native 端 @JsApi 分派表生成器 (与 :shared 同款)
        add("kspIosArm64", project(":modules:quickjs-processor"))
        add("kspIosSimulatorArm64", project(":modules:quickjs-processor"))
    }
    if (enableOhosTarget) {
        add("kspOhosArm64", "androidx.room3:room3-compiler:3.0.0-alpha01")
        add("kspOhosArm64", project(":modules:quickjs-processor"))
    }
}

// native 目标 KSP 全局 arg (与 :shared 同款; 目标类分布: Connection.Response/JsURL 在
// foundation, BaseSource/QueryTTF 在 :data, StrResponse/JsExtensionsCommon 在 :data)
if (enableIosTarget || enableOhosTarget) {
    ksp {
        arg("jsapi.native", "true")
        arg(
            "jsapi.nativeTargets",
            "org.jsoup.Connection.Response,io.legado.app.help.http.StrResponse,io.legado.app.data.entities.BaseSource," +
                "io.legado.app.model.analyzeRule.QueryTTF,io.legado.app.utils.JsURL"
        )
    }
}

if (enableIosTarget) {
    tasks.matching {
        it.name == "compileKotlinIosArm64" || it.name == "compileKotlinIosSimulatorArm64" ||
            it.name == "kspKotlinIosArm64" || it.name == "kspKotlinIosSimulatorArm64"
    }.configureEach {
        stageNativeInteropForIos?.let { dependsOn(it) }
    }
}

if (enableOhosTarget) {
    tasks.matching {
        it.name == "compileKotlinOhosArm64" || it.name == "kspKotlinOhosArm64"
    }.configureEach {
        stageNativeInteropForOhos?.let { dependsOn(it) }
    }
    tasks.matching {
        it.name == "kspKotlinOhosArm64" || it.name == "compileKotlinOhosArm64"
    }.configureEach {
        deriveOhosRoomSchemas?.let { dependsOn(it) }
        deriveOhosRoomEntities?.let { dependsOn(it) }
    }
    tasks.matching { it.name == "compileKotlinOhosArm64" }.configureEach {
        deriveOhosRoomImpl?.let { dependsOn(it) }
        verifyOhosRoomDerived?.let { dependsOn(it) }
    }
}
