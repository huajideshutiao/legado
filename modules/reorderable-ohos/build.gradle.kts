import org.gradle.api.tasks.Sync

// :modules:reorderable-ohos —— 上游 sh.calvin.reorderable (Apache-2.0) 的鸿蒙变体承载模块。
//
// 上游只发布 android/jvm/iOS/macOS/js/wasm 变体, 无 ohosArm64 klib; 而它的全部源码都在
// commonMain 且零平台专属 API (只用 Compose + coroutines), 故用 CPF 工具链重编同一份源码即可。
// 仓库不存源码副本: 源码在构建期从上游 sources jar 解包, 只存版本号 (gradle/libs.versions.toml
// 的 reorderable 键) 与解包校验。
//
// 其余三端继续用上游官方变体: :ui 的 sharedUiMain 声明 api(libs.reorderable),
// OhosTargetConventionPlugin 只在 ohos 配置上把该坐标替换为本模块。
plugins {
    id("legado.kmp.native.library")
}

// @Composable 源码需要 Compose 编译器插件; 本模块无资源/无 jvm/android 目标, 不用 legado.compose
// 约定插件 (它会额外应用 CMP 插件)。
pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

check(providers.gradleProperty("enableOhosTarget").orNull.toBoolean()) {
    "reorderable-ohos is only available with -PenableOhosTarget=true"
}

val sourceArchives = configurations.create("reorderableSourceArchives")

dependencies {
    sourceArchives("sh.calvin.reorderable:reorderable:${libs.versions.reorderable.get()}:sources@jar")
    val composeVersion = libs.versions.cmp.get()
    commonMainImplementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
    commonMainImplementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
    commonMainImplementation("org.jetbrains.compose.ui:ui:$composeVersion")
    commonMainImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.ohos.get()}")
}

val generatedSources = layout.buildDirectory.dir("generated/reorderableSources")

/** 解包后必须存在的包目录 (相对解包根), 供构建期校验上游 sources jar 结构未变。 */
val unpackedPackagePath = "commonMain/sh/calvin/reorderable"

val unpackSources = tasks.register<Sync>("unpackReorderableSources") {
    into(generatedSources)
    from({ sourceArchives.files.map(::zipTree) }) {
        include("commonMain/**")
        into("reorderable")
    }
    doLast {
        // 产物校验: 上游 sources jar 改名/变结构时显式失败, 而非静默产出空 klib。
        // 路径写成字面量: 引用脚本级 val 会捕获脚本对象, 配置缓存无法序列化
        check(File(destinationDir, "reorderable/commonMain/sh/calvin/reorderable").isDirectory) {
            "unpackReorderableSources: 上游 reorderable 源码目录缺失, sources jar 结构可能已变化"
        }
    }
}

kotlin {
    this::class.java.getMethod("ohosArm64").invoke(this)
    sourceSets {
        // 上游源码全在 commonMain, 单一 fragment; 经 dependsOn 传给 ohosArm64 编译
        val commonMain = getByName("commonMain")
        commonMain.kotlin.srcDir(generatedSources.map { it.dir("reorderable/commonMain") })
        val ohosMain = maybeCreate("ohosMain").apply {
            dependsOn(commonMain)
        }
        // KGP 不会自动把 ohosArm64Main 连到自定义的 ohosMain, 必须显式 dependsOn
        maybeCreate("ohosArm64Main").dependsOn(ohosMain)
    }
}

tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) dependsOn(unpackSources)
}
