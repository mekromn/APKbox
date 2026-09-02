pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LibADB Android is published through JitPack. APKbox consumes it under Apache-2.0.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "APKbox"
include(":app")
