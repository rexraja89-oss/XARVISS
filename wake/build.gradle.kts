plugins {
    id("com.android.library")
}

// XARVIS's ears (Nov 2026; Rex: "respond anytime, anywhere when I say Xarvis… even when my phone is in sleep mode
// or screen off"): the offline wake word "Xarvis" and the listening loop around it, shared by XARVIS on the S22
// (which listens itself) and the small XARVIS Ears app (for a phone whose XARVIS may not use the microphone — the
// benco). The models are in assets/wake: openWakeWord's mel + speech-embedding models and a small detector trained
// for "Xarvis" (training/wake).
android {
    namespace = "com.xarvis.wake"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Runs the mel and speech-embedding models; the detector on top of them is plain Kotlin (WakeModel).
    api("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation("junit:junit:4.13.2")
}
