package io.legado.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        // AGP 8.13.2 built-in Kotlin has a CheckClasspathTask serialization defect.
        // Retain kotlin-android only until the AGP version upgrade; AGP 9 will remove this line.
        pluginManager.apply("org.jetbrains.kotlin.android")

        configureKotlinAndroidJvm21()

        extensions.configure<ApplicationExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 24
                targetSdk = 36
            }
            compileOptions {
                configureJavaCompat21()
            }
        }
    }
}
