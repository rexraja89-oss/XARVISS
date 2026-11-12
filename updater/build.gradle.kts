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
    else "xs" + java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}.getOrDefault("")

// XARVIS Updater (Nov 2026; Rex: "fed up with massive data consumption"): updates XARVIS by downloading only what
// changed (a patch, usually well under 1 MB instead of the 107 MB APK), rebuilding the exact new APK from the
// installed one and checking it before Android installs it. A separate app on purpose: XARVIS itself stays untouched,
// and the "install apps" permission stays out of XARVIS (with Accessibility + camera that mix gets apps refused).
android {
    namespace = "com.xarvis.updater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.updater"
        minSdk = 26
        targetSdk = 37
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        manifestPlaceholders["xarvisSrc"] = sourceFingerprint("updater", "build.gradle.kts", "gradle.properties", "gradle")
    }

    // Same permanent key as XARVIS.
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the real org.json; Android's is a stub in unit tests
}
