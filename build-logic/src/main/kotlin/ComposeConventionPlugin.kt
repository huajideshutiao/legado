package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class CoilSkikoExclusionRule : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        if (context.details.id.group == "io.coil-kt.coil3") {
            context.details.allVariants {
                withDependencies {
                    removeIf { it.group == "org.jetbrains.skiko" }
                }
            }
        }
    }
}

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        // skiko 版本归 CMP 拥有, 统一剔除 coil3 组件元数据中的 skiko 依赖, 由 compose-ui 供给
        dependencies.components.all(CoilSkikoExclusionRule::class.java)

        configure<ComposeCompilerGradlePluginExtension> {
            // Room 实体等外部类默认被判 unstable, 列表项因此永远无法 skip 重组。
            stabilityConfigurationFiles.add(
                isolated.rootProject.projectDirectory.file("compose_compiler_config.conf")
            )
            // ./gradlew ... -PcomposeMetrics=true 生成可跳过性报告。
            if (providers.gradleProperty("composeMetrics").orNull?.toBoolean() == true) {
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
            }
        }
    }
}
