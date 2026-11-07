pluginManagement {
    includeBuild("../../build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

// The same source/build file can run without configuring the Android application or SDK.
rootProject.name = "desktop"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":core:domain", ":core:common", ":core:data", ":core:network", ":app:shared")
project(":app").projectDir = file("..")
// The standalone Desktop graph needs the parent path, not the Android application build script.
project(":app").buildFileName = "standalone-parent.gradle.kts"
project(":app:shared").projectDir = file("../shared")
project(":core").projectDir = file("../../core")
project(":core:domain").projectDir = file("../../core/domain")
project(":core:common").projectDir = file("../../core/common")
project(":core:data").projectDir = file("../../core/data")
project(":core:network").projectDir = file("../../core/network")
