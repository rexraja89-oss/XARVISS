plugins {
    id("com.android.application")
}

// XARVIS Helper: a tiny separate app for a family phone (Rex's wife's, Nov 2026). She taps "Ask for help";
// Rex's XARVIS gets a notification and shows her screen (and taps, if she allowed it), from anywhere — through
// the relay and XARVIS Direct (the :shared link code). No AI, no Compose, no other permissions.
android {
    namespace = "com.xarvis.helper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.helper"
        // Family phones may be older than Rex's.
        minSdk = 26
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // Same permanent key as XARVIS, so updates install over the last one.
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
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
