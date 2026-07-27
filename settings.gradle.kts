pluginManagement {
    repositories {
        // fork 版 Compose 工具链接入: 阿里云云效 DevOps 私服 (托管 fork 版 Kotlin/Compose/Skiko/kotlinx -alipay 制品)
        // 只读凭证, 已公开嵌入 mykmp-antui 仓库的 settings.gradle.kts
        maven {
            url = uri("https://mykmp-cn-shanghai.devops.aliyuncs.com/packages/api/protocol/maven/1585-release-a6wqiz")
            credentials {
                username = "5a59cbcd-8eca-4d13-bfef-b2524c554e8b"
                password = "6udwdad)zZp1"
            }
        }
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // fork 版 Compose 工具链接入: 阿里云云效 DevOps 私服 (托管 fork 版 Kotlin/Compose/Skiko/kotlinx -alipay 制品)
        maven {
            url = uri("https://mykmp-cn-shanghai.devops.aliyuncs.com/packages/api/protocol/maven/1585-release-a6wqiz")
            credentials {
                username = "5a59cbcd-8eca-4d13-bfef-b2524c554e8b"
                password = "6udwdad)zZp1"
            }
        }
        mavenLocal()
        google()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "legado"

include(":app")
// @JsApi 注解独立微型 KMP 模块 (全 target): shared/commonMain 的 BaseSource/CacheManager 需在
// iOS/鸿蒙 metadata 编译下可见, 而 modules:quickjs 仅 jvm/android target
include(":modules:js-api")
include(":modules:quickjs")
include(":modules:quickjs-processor")
include(":shared")
// 桌面端入口: 验证 KMP shared jvm target 可被独立运行调用 (plan 附录J: desktop/jvm 作 common 代码廉价验证靶)
include(":desktop")

// fork 版 Compose 工具链接入: 强制 kotlinx-coroutines / kotlinx-datetime 全局使用 fork 版 -alipay
// 原因: compose-multiplatform 1.8.2.99-alipay 传递依赖可能引入上游官方版本, 导致 KMP 依赖解析冲突
// 参考: mykmp-antui/settings.gradle.kts 同款 resolutionStrategy.eachDependency 配置
//
// AntUI fork 1.8.2.99-alipay 未发布 material3 / material-icons-extended / desktop 制品
// (fork 仅发布 runtime/foundation/ui/material(MD2)/ui-tooling/components-resources/components-ui-tooling-preview)
// 强制回退到官方版本: material3/desktop 用 1.8.2 (fork 基线版本, 保证 API 兼容),
// material-icons-extended 用 1.7.3 (Compose 1.8+ 已废弃该 artifact, 1.7.3 是末版)
// ohosMain 直接继承 sharedUiMain (引用 material3, 走 force 官方 1.8.2 基线)
gradle.beforeProject {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) {
                    useVersion("1.10.2-alipay")
                    because("Compose resources library requires runBlockingK from SNAPSHOT coroutines built with Kotlin 2.3")
                }
                if (requested.group == "org.jetbrains.kotlinx" && requested.name == "kotlinx-datetime") {
                    useVersion("0.6.1-alipay")
                    because("Force consistent datetime version across all modules")
                }
                // AntUI fork 未发布 material3 制品, 使用官方 1.8.2 (fork 基线版本)
                if (requested.group == "org.jetbrains.compose.material3") {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 material3, 回退官方 1.8.2 基线版本")
                }
                // material-icons-extended 在 Compose 1.8+ 已废弃, 末版 1.7.3 (无 1.8.x 制品)
                if (requested.group == "org.jetbrains.compose.material" && requested.name == "material-icons-extended") {
                    useVersion("1.7.3")
                    because("material-icons-extended 1.8+ 已废弃, 末版 1.7.3")
                }
                // AntUI fork 未发布 desktop 制品, 使用官方 1.8.2 (fork 基线版本)
                if (requested.group == "org.jetbrains.compose.desktop") {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 desktop 制品, 回退官方 1.8.2 基线版本")
                }
                // AntUI fork 未发布 material-desktop / ui-tooling-desktop 制品, 使用官方 1.8.2 (fork 基线版本)
                if (requested.group == "org.jetbrains.compose.material" && requested.name == "material-desktop") {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 material-desktop, 回退官方 1.8.2 基线版本")
                }
                if (requested.group == "org.jetbrains.compose.ui" && requested.name == "ui-tooling-desktop") {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 ui-tooling-desktop, 回退官方 1.8.2 基线版本")
                }
                // AntUI fork 未发布 components-resources 的 jvm 变体, 使用官方 1.8.2 (fork 基线版本)
                if (requested.group == "org.jetbrains.compose.components" && requested.name == "components-resources") {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 components-resources jvm 变体, 回退官方 1.8.2 基线版本")
                }
                // AntUI fork 未发布 ui-tooling/ui-tooling-preview/ui-text 的 jvm 变体 (Preview 注解 + fromHtml), 使用官方 1.8.2
                if (requested.group == "org.jetbrains.compose.ui" && requested.name.startsWith("ui-tooling")) {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 ui-tooling jvm 变体, 回退官方 1.8.2 基线版本")
                }
                if (requested.group == "org.jetbrains.compose.ui" && requested.name.startsWith("ui-text")) {
                    useVersion("1.8.2")
                    because("AntUI fork 1.8.2.99-alipay 未发布 ui-text jvm 变体, 回退官方 1.8.2 基线版本")
                }
            }
            // force() 在依赖解析阶段生效, 优先级高于所有 eachDependency 规则
            // 原因: compose.material3/materialIconsExtended/desktop DSL 访问器可能在 CMP fork 插件
            // 内部注册后置 eachDependency 规则, 覆盖上面 useVersion 的版本回退, 仍拉取 -alipay 版本
            // force 不受 eachDependency 注册顺序影响, 确保版本对齐官方基线
            // desktop-jvm-* 平台变体: compose.desktop.currentOs 按运行 OS 拉取对应变体, 全部 force 保证 CI 多端一致
            force(
                "org.jetbrains.compose.material3:material3:1.8.2",
                "org.jetbrains.compose.material:material-icons-extended:1.7.3",
                "org.jetbrains.compose.material:material-desktop:1.8.2",
                "org.jetbrains.compose.ui:ui-tooling:1.8.2",
                "org.jetbrains.compose.ui:ui-tooling-desktop:1.8.2",
                "org.jetbrains.compose.ui:ui-tooling-preview:1.8.2",
                "org.jetbrains.compose.ui:ui-tooling-preview-desktop:1.8.2",
                "org.jetbrains.compose.ui:ui-text:1.8.2",
                "org.jetbrains.compose.ui:ui-text-desktop:1.8.2",
                "org.jetbrains.compose.components:components-resources:1.8.2",
                "org.jetbrains.compose.desktop:desktop:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-windows-arm64:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-macos-x64:1.8.2",
                "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:1.8.2"
            )
        }
    }
}
