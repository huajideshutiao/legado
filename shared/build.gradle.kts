import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    id("legado.compose")
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    generateResClass = always
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = isMacHost &&
    (providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: true)
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.legado.shared"
        compileSdk = 36
        minSdk = 26
    }

    if (enableIosTarget) {
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            binaries {
                framework {
                    baseName = "shared"
                    isStatic = false
                }
            }
            compilations.getByName("main").cinterops {
                create("quickjs") {
                    defFile(file("src/cinterop/quickjs.def"))
                    includeDirs(file("${projectDir}/src/cinterop/quickjs-ng"))
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
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }

    if (enableOhosTarget) {
        apply(from = "ohos.gradle.kts")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            api(project(":modules:js-api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            api(libs.okio)
            api(libs.room.common)
            api(libs.room.runtime)
            implementation(libs.ksoup)
            implementation(libs.kotlinx.serialization.json)
            api(compose.components.resources)
        }
        val sharedUiMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
            }
        }
        val nonOhosUiMain by creating {
            dependsOn(sharedUiMain)
            dependencies {
                implementation(libs.reorderable)
                implementation(libs.coil3.compose)
                implementation(libs.multiplatformMarkdown)
                implementation(libs.multiplatformMarkdown.coil3)
            }
        }
        val skikoUiMain by creating {
            dependsOn(sharedUiMain)
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(libs.quick.chinese.transfer.core)
                implementation(libs.hutool.crypto)
                api(project(":modules:quickjs"))
                api(libs.okhttp)
                implementation(libs.coil3.network.okhttp)
                implementation(libs.nanohttpd.nanohttpd)
                implementation(libs.nanohttpd.websocket)
            }
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
            dependsOn(nonOhosUiMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                api(libs.androidx.documentfile)
                implementation(libs.room.ktx)
                implementation(libs.core.ktx)
                implementation(libs.coil3.gif)
                implementation(libs.compose.foundation.android)
                implementation(libs.compose.activity)
                implementation(compose.components.uiToolingPreview)
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            dependsOn(nonOhosUiMain)
            dependsOn(skikoUiMain)
            dependencies {
                implementation("net.sf.kxml:kxml2:2.3.0")
                implementation(libs.androidx.sqlite.bundled)
                implementation(compose.components.uiToolingPreview)
            }
        }
        val nonAndroidIconMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(nonAndroidIconMain)

        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependencies {
                    implementation("io.ktor:ktor-server-core:3.1.0")
                    implementation("io.ktor:ktor-server-cio:3.1.0")
                    implementation("io.ktor:ktor-server-websockets:3.1.0")
                }
            }
        } else null

        if (enableIosTarget) maybeCreate("iosMain").apply {
            dependsOn(nonOhosUiMain)
            dependsOn(nonAndroidIconMain)
            dependencies {
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.krypto)
                implementation(libs.coil3.network.ktor3)
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-cio:3.1.0")
            }
        }

        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-reflect")
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

tasks.matching {
    it.name == "copyDebugComposeResourcesToAndroidAssets" ||
        it.name == "copyReleaseComposeResourcesToAndroidAssets"
}.configureEach {
    doFirst {
        outputs.files.forEach { output -> project.delete(output) }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
    if (enableOhosTarget) {
        // 构建鸿蒙时自动适配 configuration 名
        configurations.matching { it.name == "kspOhosArm64" }.all {
            add(name, libs.room.compiler)
        }
    }
}
