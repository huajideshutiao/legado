plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.script.quickjs.nativebridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        ndk {
            // 与 app splits 对齐: 只编 arm64-v8a + armeabi-v7a, x86 系不再编译
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
        // x86_64 ABI 是刻意决策: 与 :app splits 对齐, 只编 arm64-v8a + armeabi-v7a
        disable += "ChromeOsAbiSupport"
    }
}
