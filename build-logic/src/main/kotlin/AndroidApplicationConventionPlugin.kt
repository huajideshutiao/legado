package io.legado.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        // AGP 8.13.2 built-in Kotlin has a CheckClasspathTask serialization defect.
        // Retain kotlin-android only until the AGP version upgrade; AGP 9 will remove this line.
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.findByType(KotlinBaseExtension::class.java)?.jvmToolchain(17)
        extensions.findByType(KotlinAndroidProjectExtension::class.java)?.apply {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }

        extensions.configure<ApplicationExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 26
                targetSdk = 36
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
