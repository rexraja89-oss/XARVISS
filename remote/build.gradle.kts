import java.security.MessageDigest

plugins {
    id("com.android.application")
}

/**
 * What this app is built from: git's hashes of [paths] (its folders and the shared build settings), as "xs" + 16 hex.
 * It stays the same while nothing in them changes, so XARVIS Updater doesn't offer the same app again with only a new
 * number (Rex, 13 Nov 2026: Remote + Updater cost ~3 MB every release). Empty without git (a local build).
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

// XARVIS Remote (Nov 2026): a separate remote-control app for Rex's own phones, for emergencies — it keeps
// working while XARVIS on the S22 is down or being updated. One app: it controls a phone (benco) or is controlled
// (S22, with no one there to tap Allow). Same link code (:shared: relay, XARVIS Direct) and the same viewer as XARVIS.
android {
    namespace = "com.xarvis.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.remote"
        // Any Android 8+ phone.
        minSdk = 26
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        manifestPlaceholders["xarvisSrc"] = sourceFingerprint("remote", "shared", "build.gradle.kts", "gradle.properties", "gradle")
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
