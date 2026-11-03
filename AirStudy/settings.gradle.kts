pluginManagement {
    // convention plugin 放在 buildSrc/（Gradle 对 buildSrc 自动加入所有构建的 classpath，
    // 且其 META-INF/gradle-plugins 描述符会被直接识别，无需 includeBuild + resolutionStrategy）。
    // 早期尝试的 build-logic 组合构建在 Gradle 9.3 下无法自动暴露插件 id，已弃用。
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "HuaweiHms"
                    url = uri("https://developer.huawei.com/repo/")
                }
            }
            filter { includeGroupByRegex("com\\.huawei\\..*") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:core-common")
include(":core:core-model")
include(":core:core-database")
include(":core:core-network")
include(":core:core-datastore")
include(":core:core-ui")
include(":core:core-analytics")

include(":feature:feature-onboarding")
include(":feature:feature-home")
include(":feature:feature-airport")
include(":feature:feature-booking")
include(":feature:feature-flight")
include(":feature:feature-focus")
include(":feature:feature-completion")
include(":feature:feature-record")
include(":feature:feature-stats")
include(":feature:feature-achievement")
include(":feature:feature-scene")
include(":feature:feature-sound")
include(":feature:feature-group")
include(":feature:feature-profile")

include(":server")

include(":widget")
include(":service-focus")

rootProject.name = "AirStudy"
