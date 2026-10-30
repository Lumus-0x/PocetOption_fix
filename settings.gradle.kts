pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.10.0"
        id("org.jetbrains.kotlin.android") version "2.1.10"
        id("com.google.dagger.hilt.android") version "2.52"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ScreenCaptureBalanceSync"
include(":app")