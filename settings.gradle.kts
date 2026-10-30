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
        // 👇 这一行能让 PhotoView 真正下载成功
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "demo"
include(":app")