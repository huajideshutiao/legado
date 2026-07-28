plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.script.quickjs.nativebridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                cFlags += "-fvisibility=hidden"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        checkDependencies = true
    }
}
