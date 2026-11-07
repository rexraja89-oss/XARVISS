plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    // Two native libraries may bring the same C++ runtime; keep one.
    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
    namespace = "com.xarvis.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xarvis.ai"
        minSdk = 28
        targetSdk = 37
        // Each GitHub build gets its own number, shown on the XARVIS screen, so it's clear which one is installed.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        // Both phones (and any phone that can run the AI) are 64-bit ARM; leaving out the others
        // keeps the APK about 30 MB smaller.
        ndk { abiFilters += "arm64-v8a" }
    }

    // One permanent key, so every new build installs over the last one as an upgrade. CI decodes it
    // from the XARVIS_KEYSTORE secret; without it (e.g. a local build) the default debug key is used.
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
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Two builds of the same app: "full" for the S22, and "benco" without the permission to place
    // calls, the microphone permission, "Hey Jarvis" and the own-voice engine, which the benco
    // refuses to install ("package appears to be invalid"). On the benco, calls open the dialer.
    flavorDimensions += "phone"
    productFlavors {
        create("full") { dimension = "phone" }
        create("benco") { dimension = "phone" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Unit tests cover plain logic; any Android call they touch returns a default instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Runs the three small offline "Hey Jarvis" models (openWakeWord).
    // "Hey Jarvis" and the own-voice engine are only in the S22's build; the benco refuses them.
    "fullImplementation"("org.tensorflow:tensorflow-lite:2.17.0")
    // Offline speech engine for Rex's own voice; CI downloads the AAR into app/libs (see build.yml).
    "fullImplementation"(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the real org.json; Android's is a stub in unit tests
}
