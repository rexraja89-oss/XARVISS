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
        maven("https://jitpack.io")
    }
}
rootProject.name = "XARVIS"
include(":app")
// XARVIS Hands: a tiny separate app that holds only the Accessibility permission, so the S22 will
// install it (the main app refuses an Accessibility service). This is the install test first.
include(":hands")
// XARVIS Eyes: a small separate app that holds only the camera permission (the main app has none,
// to keep the S22's installer happy). It shows the camera and asks the main app's Gemma to look.
include(":eyes")
