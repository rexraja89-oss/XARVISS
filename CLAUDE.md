# XARVIS: notes for Claude

Personal on-device AI assistant for Android, owned by Rex (developer credit: Sajjad Raja). Rex isn't an Android developer: explain in plain language, pick sensible defaults, do the work yourself, and give a short summary of what works and what's next.

## How development works now

- There is no local machine with adb. Code changes go to `main` on GitHub; `.github/workflows/build.yml` runs the unit tests, builds `app-debug.apk` (artifact **XARVIS-v1.0.<run>-apk**, JDK 21) and checks its signature. A green CI run is the build check.
- Rex installs each new APK on the phone himself (download the artifact from the Actions run, unzip, tap the APK, tap Update). Tell him exactly what to test after installing, since nobody else can run it on the device.
- Unit tests (`app/src/test`, plain JVM logic: tool-line parsing, link commands, the prompt, identity guard, contact matching) run in CI before the build. Add a test when adding a tool. If CI fails, compiler and test errors show as a "Build errors" annotation on the check run (readable via the GitHub API even when raw logs aren't).
- `./gradlew assembleDebug` needs an Android SDK with platform 37; if the environment has none, rely on CI.

## Versions and signing (so updates install as upgrades)

- versionCode = GitHub run number, versionName = `1.0.<run>`; the screen shows `v1.0.<run>` at the start of the AI MODEL line.
- Every CI build is signed with one permanent key from the **XARVIS_KEYSTORE** repo secret (base64 PKCS12, alias `xarvis`, password `xarvis-signing-key`). CI fails if a build signed with the secret doesn't match `.github/signing-cert-sha256.txt`. Without the secret, CI warns and falls back to a throwaway debug key, and that APK can't upgrade a permanently signed install.
- Before the permanent key, every CI build had a different random debug key, so updates often failed to install ("App not installed") while Rex thought they had: that's why fixes seemed not to work. Switching keys needs one uninstall; memories come back by re-pairing with the other phone (memory sync).
- Never commit the keystore. Don't use session credentials to set repo secrets; Rex adds the secret himself.

## The AI model file

- Gemma lives in the app's own folder (`Android/data/com.xarvis.ai/files/models/`), so **uninstalling XARVIS deletes it**. With no model, the screen shows a "DOWNLOAD AI MODEL" button (`llm/ModelDownloader.kt`): Android's DownloadManager fetches `gemma-4-E2B-it.litertlm` (~2.4 GB, Wi-Fi or mobile data, not roaming: Rex chose mobile data) from huggingface.co/litert-community/gemma-4-E2B-it-litert-lm to a `.download` file, renames it when complete, then loads it. huggingface.co is blocked from the Claude sandbox, so this can't be tested here.

## Devices

- **Galaxy S22 Ultra** (SM-S908E, Android 13, Snapdragon 8 Gen 1, 12 GB): main phone. Has the model at `/sdcard/Android/data/com.xarvis.ai/files/models/gemma-4-E2B-it.litertlm`.
- **benco V91s Plus** (Unisoc T606, 6 GB real RAM, Android 13, no camera, no GPS): linked to the S22, has no model and uses the S22's over Wi-Fi.
- Both are paired and on the same Wi-Fi. Memories sync between them.

## How XARVIS answers

- Every message goes to Gemma (`agent/XarvisAgent.kt`); on a phone without the model, a linked phone's Gemma answers (`remoteChat`) and the tools still run on the phone that was typed on. There is no keyword matcher; Rex asked for Gemma to decide.
- Gemma's tools, called with a line like `TOOL: contact Atiq`: `time`, `remember <fact>`, `open <app>`, `contact <name>`, `call <name|number>`, `whatsapp <name>: <text>`, `sms <name>: <text>`, `location`, `battery`, `bluetooth` (status) / `bluetooth on|off`, `wifi on|off`, `flashlight on|off`, `alarm <time>`, `timer <duration>`, `search <words>`. Readers are `tools/LocationTool`, `BatteryTool`, `BluetoothTool`; actions are `workflow/PhoneActions`; alarm/timer text is read by `agent/TimeSpecs`. `map [place]` opens Maps. `find <app>: <words>` searches inside an app (its ACTION_SEARCH, a search URL for LinkedIn/Drive/GitHub/YouTube, else opens it and copies the words to paste). App names go through `device/AppNames.kt` (Rex's aliases: "chat gpt", "YT studio", "files", "calender"...; web apps: payslip/Olive = https://altrad.olivehrms.com, a guess from his payslip download host). XARVIS can't tap inside other apps (no accessibility service); for multi-step tasks it opens the app and says what Rex must do. Calls: placed directly only when Rex's own message asks ("call Atiq", "Ali ko call karo": `XarvisAgent.forUser`, which also turns Gemma's `contact` into a call then); a call Gemma chooses by itself opens the dialer. If Gemma talks about a tool instead of using it ("I can use the location tool if you ask"), `skippedTool` triggers one nudge asking for the TOOL line. Safety otherwise:, WhatsApp/SMS open prefilled (user taps Send); Android won't let apps toggle Wi-Fi/Bluetooth off, so those open the system switch. `agent/ToolCalls.kt` reads them (tolerant of markdown, `ACTION:`, `<>`, "'s number"); `workflow/WorkflowEngine.kt` runs them. Tool results are shown directly, not sent back to Gemma (one pass, and Gemma can't ignore the data).
- `contact` searches this phone (`tools/ContactFinder.kt`, `ContactsDirectory` matching) and, if nobody matches, linked phones (link op `contacts`).
- The only exact commands are for linking phones (`agent/LinkCommands`): `pair with <device>`, `code <6 digits>`, `devices`, `unlink <device>`. The pairing code is a security check, so it isn't left to Gemma.
- The system prompt is short on purpose: identity ("You are XARVIS, a personal AI assistant created by Rex..."), the tools with one few-shot example each, then all saved memories under "Facts you know:" (capped at 3000 chars, newest kept). It's rebuilt before every call and `LocalLlm.chat` restarts the conversation when it changes. Each message also gets `IDENTITY_REMINDER`, and `fixIdentity` replaces "developed by Google" replies. Context is 4096 tokens, so facts aren't repeated per message.

## Things learned the hard way

- LiteRT-LM's GPU path corrupts text on the S22 (fp16 on a 2022 Adreno driver): obviously with the `-gpu` model build, subtly (odd mangled words) with the general build. `LocalLlm.calibrate()` compares GPU and CPU answers once per model file and picked **CPU** (~15 tokens/s). Don't switch the S22 to GPU; don't reintroduce the `-gpu` model (it can't run on CPU).
- LiteRT-LM 0.17.1 has no fp32 activation option (it exists only on their main branch).
- Gemma E2B ignores instructions as the prompt grows (it answered "I don't have access to your location" with the location in its prompt). Keep the prompt short, prefer few-shot examples, and show tool data directly.
- When a fix "doesn't work", first check the version on the phone's screen. An update restarts XARVIS and clears the chat; old chat history still showing means the update didn't install.
- Anything Android 13 related: the notification permission prompt arrives after the service's first notification, so `MainActivity` re-posts it once granted.

## Architecture

Entry points: `XarvisApp`/`XarvisCore` (process-wide core), `agent/XarvisAgent.kt`, `llm/LocalLlm.kt`, `net/DeviceLink.kt` (mDNS + ECDH pairing + AES-GCM; reconnects when Wi-Fi returns), `memory/MemorySync.kt`, `service/XarvisService.kt` (always-on foreground service; opening the app turns it back on). Runtime permissions go through `tools/PermissionGate`, which MainActivity attaches to.

## Roadmap Rex asked for

1. Reach devices away from home Wi-Fi (Tailscale; Rex must create the account himself).
2. A Windows laptop companion that joins the linked devices.
