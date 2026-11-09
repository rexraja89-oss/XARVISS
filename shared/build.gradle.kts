plugins {
    id("com.android.library")
}

// The link between XARVIS phones, shared by XARVIS (:app) and XARVIS Helper (:helper): the relay (MQTT),
// XARVIS Direct (UDP straight between the phones), pairing and the AES-GCM encrypted conversations.
// No resources and no Android UI in here — only plain Kotlin on the Android framework.
android {
    namespace = "com.xarvis.ai.shared"
    compileSdk = 37

    defaultConfig {
        // The Helper goes on family phones, which may be older than Rex's.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
