plugins {
    id("com.android.application")
}

// XARVIS Hands is deliberately tiny: no Compose, no Room, no AI, no network — one screen with a
// button, and one Accessibility service. The whole point is to find out whether the S22 will install
// an app whose ONLY sensitive permission is Accessibility (the main app, with all its other
// permissions, gets refused when an Accessibility service is added). Keep its footprint minimal so
// the install test stays clean.
android {
    namespace = "com.xarvis.hands"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.hands"
        minSdk = 28
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Same permanent key as the main app, so builds install over each other as upgrades. CI decodes
    // it from the XARVIS_KEYSTORE secret; without it (a local build) the default debug key is used.
    val permanentKey = System.getenv("XARVIS_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (permanentKey != null) {
            create("xarvis") {
                storeFile = permanentKey
                storePassword = System.getenv("XARVIS_KEYSTORE_PASSWORD") ?: "xarvis-signing-key"
                keyAlias = "xarvis"
                keyPassword = System.getenv("XARVIS_KEYSTORE_PASSWORD") ?: "xarvis-signing-key"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("xarvis")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
}
