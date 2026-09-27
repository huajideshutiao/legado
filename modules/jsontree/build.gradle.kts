// :modules:jsontree —— 内嵌第三方库 (vendored) 的独立承载模块。
//
// 承载 com.sebastianneubauer.jsontree (JSON 树形展示, Apache-2.0)。来源与本地改动见
// 同包目录 VENDORED.md。相对上游已裁剪: 删除 diff 包 (消除 kotlin-multiplatform-diff
// 依赖) 与其唯一调用点 toRenderString, 并把 material3 换成 md2。
//
// 本模块持有自己的 composeResources (展开箭头图标与子项计数文案), 不引用 :ui 的资源
// —— 依赖方向是 :ui -> :modules:jsontree, 反向不可。
plugins {
    id("legado.kmp.library")
    id("legado.compose")
}

compose.resources {
    generateResClass = always
    publicResClass = true
    // 默认包名按项目名推导 (legado.jsontree.generated.resources), 显式钉成上游包名前缀，
    // 使本模块的 Res 与第三方源码同属一个命名空间，便于识别。
    packageOfResClass = "com.sebastianneubauer.jsontree.generated.resources"
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
val composeVersion = libs.versions.cmp.get()

// CPF fork 只发布 Android/iOS/OHOS 变体, 不发布 Desktop JVM 变体 (与 :ui 同款策略)。
// enableOhosTarget 时 settings 把 catalog 的 cmp 整体切到 fork 版本, 非 ohos 配置
// (jvm/android) 的依赖解析也会拿到 fork 版本, 触发
// "KMP Dependencies Resolution Failure ... Unresolved platforms: [jvm]"。
// 对策: 非 ohos 配置把 fork 版本重写回官方基线主版本; ohos 配置保持 fork 版本
// (root build.gradle.kts 的 eachDependency 已对 ohos 配置统一强制 fork 版本)。
if (enableOhosTarget) {
    val forkComposeVersion =
        extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("composeMultiplatform-ohos").get().requiredVersion
    configurations.configureEach {
        if (name.contains("ohos", ignoreCase = true)) return@configureEach
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose") &&
                requested.version == forkComposeVersion
            ) {
                useVersion(forkComposeVersion.substringBefore("-"))
                because("CPF does not publish Desktop JVM variants; non-ohos targets use the official base version")
            }
        }
    }
}

kotlin {
    jvm()

    android {
        namespace = "com.sebastianneubauer.jsontree"
        compileSdk = 37
        minSdk = 24
        // compose.resources 需要 assets 接入 (与 :ui 同款机制)
        androidResources.enable = true
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    if (enableOhosTarget) {
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // JsonTree 的公开签名引用 Compose 类型 (Modifier / PaddingValues / TextStyle /
                // TreeColors 里的 Color 等), 故这些必须用 api 暴露, 否则消费方在 native 链接期报
                // "exposed in public API" 缺失。不引入 material3 (已换 md2)。
                api("org.jetbrains.compose.runtime:runtime:$composeVersion")
                api("org.jetbrains.compose.foundation:foundation:$composeVersion")
                api("org.jetbrains.compose.material:material:$composeVersion")
                api("org.jetbrains.compose.ui:ui:$composeVersion")
                api("org.jetbrains.compose.components:components-resources:$composeVersion")
            }
        }
    }
}
