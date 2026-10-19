// https://developer.android.com/build#settings-file
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudStream"
include(":app", ":shared", ":library", ":docs", ":desktopApp")
include(":macApp") // fork-only macOS app, kept on its own line so upstream syncs merge cleanly
