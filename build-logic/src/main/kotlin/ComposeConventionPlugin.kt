package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

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
