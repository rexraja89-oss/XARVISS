# XARVIS - Local-First Personal AI System

A personal AI assistant for Android that runs entirely on-device: natural-language commands, an on-device LLM (Gemma 4 E2B via LiteRT-LM), persistent memory, app control, and encrypted linking between your own devices over Wi-Fi.

Developed by Sajjad Raja. Tested on a Samsung Galaxy S22 Ultra (SM-S908E) and a benco V91s Plus, both on Android 13.

## What it does

| Area | Details |
|---|---|
| Commands | `open <app>`, `search <query>`, `navigate to <place>`, `status`, `time`, `help`; chain steps with "then" |
| Memory | `remember <fact>` stores it (rewritten to "your …"), `what do you remember [about x]`, `forget everything`. Room database, survives restarts |
| On-device AI | Anything that isn't an exact command goes to Gemma 4 E2B running locally. It chats, answers from memory, and can trigger the commands above itself (e.g. "fire up the calculator for me") |
| Linked devices | `pair with <device>`, `code 123456`, `devices`, `<device> status`, `send to <device>: <text>`, `unlink <device>`. A device without a working model uses a linked device's model as its "brain". Remembered facts are shared between linked devices |
| Background | Runs as a foreground service ("XARVIS is running" notification with a Stop button) so linked devices can always reach it, including after a reboot. `always off` / `always on` toggles it |

## Architecture

```
app/src/main/java/com/xarvis/ai/
├── XarvisApp.kt / XarvisCore.kt   app-wide core: model, link server and chat outlive the screen
├── MainActivity.kt
├── agent/XarvisAgent.kt           rule parser + LLM routing; parses the LLM's "ACTION:" lines
├── llm/LocalLlm.kt                LiteRT-LM engine, GPU→CPU fallback with an output sanity check
├── net/DeviceLink.kt              mDNS discovery, pairing, AES-GCM encrypted requests
├── workflow/WorkflowEngine.kt     runs steps (launch app, open URL, memory, linked-device ops)
├── memory/MemorySystem.kt         Room database
├── device/DeviceCapabilityManager.kt
├── viewmodel/XarvisViewModel.kt
└── ui/XarvisScreen.kt, ui/theme/Theme.kt
```

**Stack:** Kotlin 2.4, Jetpack Compose + Material 3, Room 2.8 (KSP), LiteRT-LM 0.17.1, AGP 9.4, Gradle 9.8, compileSdk/targetSdk 37, minSdk 28, JDK 17 target (built with Android Studio's bundled JDK).

## On-device model

The model isn't in the APK. Download `gemma-4-E2B-it.litertlm` (2.41 GB, Apache-2.0) from
[litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and copy it to the phone:

```bash
adb shell mkdir -p /sdcard/Android/data/com.xarvis.ai/files/models
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.xarvis.ai/files/models/
```

Restart XARVIS; the header shows `AI MODEL: GEMMA 4 E2B · <backend>` when it's ready.

**GPU note:** on the S22 Ultra's Adreno driver (July 2022) LiteRT-LM's GPU path produces garbled text (its fp16 activations; 0.17.1 has no fp32 switch). XARVIS runs a sanity check on each backend at startup, remembers a backend that failed it, and falls back to the CPU. The `-gpu` build of the model can't run on the CPU, so use the general build above.

## Linking devices

1. Put both devices on the same Wi-Fi and open XARVIS on both. They find each other automatically (mDNS `_xarvis._tcp`, TCP port 47470).
2. On one device type `pair with <other device's name>` (or its IP, e.g. `pair with 192.168.1.20`).
3. The other device shows a 6-digit code; type `code <number>` on the first device. A wrong code cancels the pairing.

Pairing uses an ECDH (P-256) exchange with a key commitment, so a device in the middle can't fake the code; afterwards every request is AES-256-GCM encrypted. The background service keeps linking working when XARVIS is closed.

Remembered facts sync between linked devices: new facts are pushed immediately, and devices that were offline catch up within 5 minutes. `forget everything` wipes memories on all linked devices; the wipe time is kept so an offline device can't bring old facts back.

## Building

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. `local.properties` must point `sdk.dir` at your Android SDK (Android Studio creates it).

## Known limitations / next steps

- Linking is local-network only; remote access (e.g. Tailscale) is the next step, then a Windows laptop companion.
- Aggressive battery savers (e.g. Samsung "Put app to sleep") can still stop the background service; exclude XARVIS there if that happens.
- The GPU path is disabled on the S22 until a LiteRT-LM release allows fp32 activations.
- Only the permissions it uses are requested (vibrate, network). Camera/location/contacts features aren't built yet.

## License

Internal use.
