plugins {
    id("legado.kmp.library")
}

kotlin {
    jvm()
    android {
        namespace = "com.script.quickjs.test"
        compileSdk = 37
        minSdk = 24
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":modules:quickjs"))
                api(libs.junit)
                api(libs.jetbrains.kotlin.test)
            }
        }
    }
}
