# XARVIS: notes for Claude

Personal on-device AI assistant for Android, owned by Rex (developer credit: Sajjad Raja). Rex isn't an Android developer: explain in plain language, pick sensible defaults, and give a short summary of what works and what's next.

## How development works now

- There is no local machine with adb. Code changes go to `main` on GitHub; `.github/workflows/build.yml` builds `app-debug.apk` (artifact **XARVIS-debug-apk**, JDK 21). A green CI run is the build check.
- Rex installs each new APK on the phone himself (download the artifact from the Actions run, unzip, tap the APK). Tell him exactly what to test after installing, since nobody else can run it on the device.
- Unit tests (`app/src/test`, plain JVM logic: command parsing, tool keywords, contact matching, the router) run in CI before the build: `./gradlew testDebugUnitTest`. Add a test when adding a command or tool. If CI fails, compiler and test errors show as a "Build errors" annotation on the check run (readable via the GitHub API even when raw logs aren't).
- `./gradlew assembleDebug` needs an Android SDK with platform 37; if the environment has none, rely on CI.

## Devices

- **Galaxy S22 Ultra** (SM-S908E, Android 13, Snapdragon 8 Gen 1, 12 GB): main phone. Has the model at `/sdcard/Android/data/com.xarvis.ai/files/models/gemma-4-E2B-it.litertlm`.
- **benco V91s Plus** (Unisoc T606, 6 GB real RAM, Android 13, no camera, no GPS): linked to the S22, has no model and uses the S22's over Wi-Fi.
- Both are paired and on the same Wi-Fi. Memories sync between them.

## Things learned the hard way

- LiteRT-LM's GPU path corrupts text on the S22 (fp16 on a 2022 Adreno driver): obviously with the `-gpu` model build, subtly (odd mangled words) with the general build. `LocalLlm.calibrate()` compares GPU and CPU answers once per model file and picked **CPU** (~15 tokens/s). Don't switch the S22 to GPU; don't reintroduce the `-gpu` model (it can't run on CPU).
- LiteRT-LM 0.17.1 has no fp32 activation option (it exists only on their main branch).
- Small-model prompt behaviour is tuned in `XarvisAgent.SYSTEM_PROMPT`: questions must be answered, not turned into `remember`; `search` only for live info. There's also a code guard that turns re-saving a known fact into an answer.
- Gemma E2B often ignores `[DEVICE DATA]` and answers "I don't have access to your location", worse as the system prompt grows. `XarvisAgent.ignoresData` catches refusals and replies that mention none of the data's numbers, and shows the data instead (`dataAnswer`). Keep the prompt short.
- Identity: `SYSTEM_PROMPT` opens with "You are XARVIS, a personal AI assistant created by Rex...". Gemma's template shows the system prompt only at the start of a chat, so each message also gets `IDENTITY_REMINDER`, and `fixIdentity` replaces "made by Google"/"I am a large language model" replies. "Who made you" is answered by rule (`CREATOR_QUESTION`). The system prompt is rebuilt before every call with all saved facts ("Facts you know:", capped at 3000 chars, newest kept) and `LocalLlm.chat` restarts the conversation when it changes. Facts are not repeated in each message: 4096-token context.
- `open <x>` only becomes a LaunchApp when an installed app matches (with an LLM available); otherwise whole sentences like "open benco contact and check atiq..." were taken as app names.
- The screen header shows `B<build number>` (GitHub run number = versionCode), to confirm which build is installed.
- Anything Android 13 related: the notification permission prompt arrives after the service's first notification, so `MainActivity` re-posts it once granted.

## Architecture

See README.md. Entry points: `XarvisApp`/`XarvisCore` (process-wide core), `agent/XarvisAgent.kt` (rules + LLM routing via `ACTION:` lines), `llm/LocalLlm.kt`, `net/DeviceLink.kt` (mDNS + ECDH pairing + AES-GCM), `memory/MemorySync.kt`, `service/XarvisService.kt` (always-on foreground service).

Device tools: `tools/DeviceToolRouter.kt` runs before a free-form message goes to the LLM. Each `DeviceTool` (location, battery, time, Bluetooth, contacts) that matches the message reads live data, which is prepended as `[DEVICE DATA]` lines; the system prompt tells Gemma to use it. Tools run on the device the user typed on, even when a linked device's LLM answers, unless the message names a linked device (`DeviceLink.mentionedPeer`, e.g. "check my benco"): then that device runs them (link op `tools`) and lines come back as "On <device>: ...". A contact not found locally is also looked up on linked devices. With device data present, an unrequested `ACTION: open` is dropped (Gemma used to open Contacts instead of reading the number). Runtime permissions go through `PermissionGate`, which MainActivity attaches to. Add new tools to the list in `XarvisCore`.

Phone actions (call, WhatsApp, SMS, flashlight, alarm, timer, Bluetooth, Wi-Fi) are `Step`s parsed by `agent/PhoneCommands.kt` (also the LLM's `ACTION:` forms) and run by `workflow/PhoneActions.kt`. Safety rules: only a call the user typed is placed directly; an LLM `ACTION: call` opens the dialer. WhatsApp/SMS open prefilled and the user taps Send. Android 10+/13+ don't let apps toggle Wi-Fi/Bluetooth off, so those open the system switch.

## Roadmap Rex asked for

1. Reach devices away from home Wi-Fi (Tailscale; Rex must create the account himself).
2. A Windows laptop companion that joins the linked devices.
