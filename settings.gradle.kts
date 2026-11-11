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
// The link between XARVIS phones (relay, XARVIS Direct, pairing, encryption), shared by XARVIS and XARVIS Helper.
include(":shared")
// XARVIS Hands: a tiny separate app that holds only the Accessibility permission, so the S22 will
// install it (the main app refuses an Accessibility service). This is the install test first.
include(":hands")
// XARVIS Eyes: a small separate app that holds only the camera permission (the main app has none,
// to keep the S22's installer happy). It shows the camera and asks the main app's Gemma to look.
include(":eyes")
// XARVIS Helper: a tiny separate app for a family phone (Rex's wife's): she taps "Ask for help" and
// Rex sees (and, if she allows, controls) her screen from his XARVIS, from anywhere.
include(":helper")
// XARVIS Remote: a separate remote-control app for Rex's own phones, so the S22 stays reachable from the benco
// while XARVIS there is down or being updated (it controls on one phone and is controlled on the other).
include(":remote")
