plugins {
    id("com.android.application")
}

// XARVIS Eyes: a small separate app, like XARVIS Hands. Its only sensitive permission is the camera
// (the main app deliberately has none, so the S22's installer keeps accepting it). It shows a live
// camera view and, when Rex taps a button, sends one photo to the main XARVIS app, whose Gemma
// looks at it and answers. No AI and no Compose in here.
android {
    namespace = "com.xarvis.eyes"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.eyes"
        minSdk = 28
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Same permanent key as the main app and Hands (signature permissions between them depend on it).
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
    implementation("androidx.activity:activity:1.13.0")
    // CameraX: the live preview and the photo.
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
