# XARVIS Development Roadmap V2

Goal: make XARVIS a genuinely capable personal AI co-worker across the benco V91s Plus,
the Samsung S22 Ultra, and future PCs — while never breaking the working app on Rex's phones.

Owner: Rex (developer credit: Sajjad Raja). Rex is non-technical: every checkpoint is explained
in plain language, ships as an installable version, and is tested by Rex on the real phones.

## Non-negotiable engineering safeguards (apply to every checkpoint)

1. **Secure credential handling.** API keys and tokens are entered by Rex in-app and stored with
   the Android Keystore (`llm/BrainKeys.kt`, to be added). **No key or token is ever hard-coded,
   committed to git, printed to logs, or sent anywhere except the provider it belongs to.** The
   activity log already scrubs codes/passwords (`policy/Policy.kt` `scrub`); the same applies to
   any new secret. The signing keystore stays a GitHub secret and is never committed.
2. **Rollback and preservation of the working build.** Before each checkpoint a git tag marks the
   last known-good commit (e.g. `known-good-v1.0.91`). Work happens so Gemma and all existing
   features keep working; a checkpoint that can't prove that on-device is reverted. Because Android
   blocks downgrades, "undo" ships as a new higher version built from the older working code, never
   a reinstall. A future safe-mode (two crashes at start → an UNDO screen) and a GitHub "revert last
   change" path are planned in the bucket list.
3. **Separate device authentication with revocation.** Each linked device (benco, S22, future PC)
   has its own identity and its own credential in the pairing store (`net/DeviceLink.kt`), so one
   device can be **unlinked/revoked** without touching the others (`unlink <device>` exists; extend
   to show and revoke each device individually). Losing or replacing one phone never exposes the rest.

## Architecture principle: avoid rebuilding the APK where possible

A new APK is unavoidable for anything touching `AndroidManifest.xml` (new permissions, services,
camera, accessibility) — that is a compiled Android app. To keep *ongoing* work rebuild-free, new
behaviour is made **data, not code**:

- Cloud providers + keys → entered/switched in-app (no rebuild).
- System prompt / identity → moved from the compiled `SYSTEM_PROMPT` into a small synced text file,
  so tuning wit/rules needs no build.
- Memory, folder grants, job criteria, reminders → already data.

So the engine changes (which need rebuilds) are front-loaded; most later tuning is free.

## Must be preserved (do not break)

`XarvisApp`/`XarvisCore` process core · on-device Gemma (`llm/LocalLlm.kt`, Fast E2B / Smart E4B) ·
the one-pass tool loop (`agent/XarvisAgent.kt`, `agent/ToolCalls.kt`, `workflow/WorkflowEngine.kt`) ·
encrypted device link (`net/DeviceLink.kt`, ECDH + AES-GCM + Tailscale) · memory + sync (`memory/`) ·
permissions + audit log (`policy/`) · JARVIS identity, humour, English + Hindi voice, "Hey Jarvis",
phone search, camera-to-chat · the **benco install constraints** (no CALL_PHONE, no mic / TF-Lite /
sherpa; `src/benco`) · the **fresh-install-needs-benco-build-first** trick on the S22.

---

## PHASE 1 — AI INTELLIGENCE (no install risk; INTERNET already declared)

| Feature | Files | Notes |
|---|---|---|
| One "Brain" interface (local + cloud) | new `llm/Brain.kt`; `llm/LocalLlm.kt` implements it | keystone |
| Cloud providers + fallback chain | new `llm/CloudBrain.kt` | Gemini → Groq → … ; then Rex's OpenAI credit; Gemma offline |
| Secure keys + BRAIN settings | new `llm/BrainKeys.kt` (Keystore), `ui/ChatParts.kt` | keys never leave the phone |
| Fast / Smart / Cloud switch | `XarvisCore.kt`, `agent/XarvisAgent.kt`, `ui/` | Rex controls; private stays local |
| Identity across models | move `SYSTEM_PROMPT` → synced file | cloud replies still sound like XARVIS |
| Web reading / research | new `tools/WebRead.kt`, `Step.WebRead`; extend `tools/WebLookup.kt` | best with a cloud brain |
| Persistent memory (exists) | `memory/` — extend only | keep |

Test on device: enter a Gemini key → hard question shows "Cloud"; Wi-Fi off → falls back to Gemma;
"read <site> and summarise" → real summary. Benco: routed via S22 or its own key.
Dependency: Phase 4 planning, job matching and any future XARVIS Code depend on this. **Do first.**

## PHASE 2 — CROSS-DEVICE INTELLIGENCE (mostly done)

Secure pairing / discovery / auth, shared identity + memory: **exist** (`net/DeviceLink.kt`,
`memory/MemorySync.kt`). Add: per-device view + revoke; more authorized remote capabilities (new
link ops, gated by `policy/`); **live camera session** — visible + authorized only (new
`net/LiveView.kt` + a preview screen showing "● LIVE to benco" and STOP; needs CAMERA on the S22, so
it goes through the benco-build-first install test). No silent capture.

## PHASE 3 — ANDROID CAPABILITIES

Contacts/files/gallery: mostly done (`tools/`, `files/PhoneSearch.kt`). Add an AccessibilityService
(new `service/XarvisA11y.kt` + manifest) to read the screen, assist, and do **user-confirmed**
actions (reuse `policy/` AskCard); background ops via the existing foreground service. Install-risk
gate applies. Line kept: no auto-submit of legal / payment / signature.

## PHASE 4 — PERSONAL AI CO-WORKER (depends on Phase 1; no install risk)

Job-ad reading, CV compare, tailored application documents, application tracker, reminders/calendar
(CalendarContract), morning briefing, multi-step planning with confirmations. New `profile/JobProfile.kt`,
`jobs/JobMatch.kt`, `jobs/Applications.kt`, `tools/Calendar.kt`, `agent/Planner.kt`; reuse
`files/FileStore.kt`, `policy/`.

## PHASE 5 — PC AND FUTURE DEVICES

One XARVIS identity, shared memory, distributed processing (PC does heavy brain/coding), secure sync.
Extend `net/DeviceLink.kt` for a desktop peer. Depends on Phase 1's Brain interface. Needs a PC.

---

## First development checkpoint: Phase 1 — Brain interface + Gemini + key screen

Why first: it is the keystone every "smart" feature draws from; it carries no install risk (INTERNET
already declared); it buys the no-rebuild future (providers + prompt become data); it is immediately
testable and reversible (remove the key → back to Gemma); and it is small and self-contained.

Checkpoint 1 = `llm/Brain.kt` + `llm/CloudBrain.kt` (Gemini) + `llm/BrainKeys.kt` + the BRAIN menu,
with Gemma left fully working.
