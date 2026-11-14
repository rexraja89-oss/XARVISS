import java.security.MessageDigest

plugins {
    id("com.android.application")
}

/**
 * What this app is built from: git's hashes of [paths] (its folders and the shared build settings), as "xs" + 16 hex.
 * It stays the same while nothing in them changes, so XARVIS Updater doesn't offer the same app again with only a new
 * number. Empty without git (a local build).
 */
fun sourceFingerprint(vararg paths: String): String = runCatching {
    val r = providers.exec {
        commandLine(listOf("git", "rev-parse") + paths.map { "HEAD:$it" })
        isIgnoreExitValue = true
    }
    val out = r.standardOutput.asText.get().trim()
    if (r.result.get().exitValue != 0 || out.lines().size != paths.size) ""
    else "xs" + MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}.getOrDefault("")

// XARVIS Ears (Nov 2026; Rex: "respond anytime, anywhere when I say Xarvis… even when my phone is in sleep mode or
// screen off"): a small separate app whose only sensitive permission is the microphone, for a phone whose XARVIS
// may not have it (the benco refuses to install XARVIS with it). It listens for "Xarvis" offline (:wake), hears what
// Rex says with the phone's speech recognizer and hands the words to XARVIS, which answers aloud. No internet
// permission: nothing it hears can leave the phone through this app.
android {
    namespace = "com.xarvis.ears"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.ears"
        minSdk = 28
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        // Both phones are 64-bit ARM (TensorFlow Lite's library comes with :wake).
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["xarvisSrc"] = sourceFingerprint("ears", "wake", "build.gradle.kts", "gradle.properties", "gradle")
    }

    // Same permanent key as XARVIS (the signature permission between them depends on it).
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
    implementation(project(":wake"))
    implementation("androidx.core:core-ktx:1.19.1")
}
