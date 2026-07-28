pluginManagement {
    repositories {
        maven {
            url = uri("https://mykmp-cn-shanghai.devops.aliyuncs.com/packages/api/protocol/maven/1585-release-a6wqiz")
            credentials {
                username = "5a59cbcd-8eca-4d13-bfef-b2524c554e8b"
                password = "6udwdad)zZp1"
            }
        }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://mykmp-cn-shanghai.devops.aliyuncs.com/packages/api/protocol/maven/1585-release-a6wqiz")
            credentials {
                username = "5a59cbcd-8eca-4d13-bfef-b2524c554e8b"
                password = "6udwdad)zZp1"
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "legado-build-logic"
