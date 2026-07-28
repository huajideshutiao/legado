package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    }
}
