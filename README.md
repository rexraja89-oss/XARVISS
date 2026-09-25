# XARVIS - Local-First Personal AI System

A fully autonomous Android AI agent system designed for the Samsung Galaxy S22 Ultra, featuring local inference, persistent memory, workflow automation, and device control.

## Architecture

### Core Components

1. **XarvisAgent** - Main AI agent that processes natural language commands
2. **MemorySystem** - Room database for persistent local memory
3. **WorkflowEngine** - Orchestrates multi-step task execution
4. **DeviceCapabilityManager** - Detects and manages device capabilities
5. **AIInferenceService** - Handles local AI model inference
6. **UI Layer** - Jetpack Compose-based sci-fi interface

### Technology Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Database**: Room ORM
- **Architecture**: MVVM with LiveData
- **Concurrency**: Coroutines
- **Min SDK**: 28 (Android 9.0)
- **Target SDK**: 34 (Android 14.0)

## Features

- ✅ Natural language command processing
- ✅ Persistent local memory system
- ✅ Autonomous workflow execution
- ✅ Device capability detection
- ✅ File access and management
- ✅ App control and launching
- ✅ Contact/Calendar access
- ✅ Camera and location support
- ✅ Sci-fi UI with futuristic design
- ✅ Local inference ready (awaiting model)

## Build Requirements

- Android SDK 34
- Gradle 8.1.0
- Kotlin 1.9.10
- Java 11+

## Building the Project

### Prerequisites

Install Android Studio with SDK 34 or use command-line tools:

```bash
# Set ANDROID_HOME to your SDK location
export ANDROID_HOME=/path/to/android/sdk
```

### Build Commands

```bash
# Build debug APK
cd /path/to/XARVIS
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test
```

### Direct Gradle Build (CLI)

```bash
# Full build
gradle wrapper
./gradlew clean build

# APK output
# Debug: app/build/outputs/apk/debug/app-debug.apk
# Release: app/build/outputs/apk/release/app-release.apk
```

## Installation

1. Build the APK as shown above
2. Transfer to your S22 Ultra or install via ADB:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Grant permissions when prompted
4. Launch XARVIS

## Permissions Granted on Install

- INTERNET
- READ/WRITE_EXTERNAL_STORAGE
- CAMERA
- LOCATION (GPS)
- MICROPHONE
- CONTACTS
- CALENDAR
- VIBRATE

## Project Structure

```
XARVIS/
├── app/
│   ├── src/main/
│   │   ├── java/com/xarvis/ai/
│   │   │   ├── MainActivity.kt
│   │   │   ├── agent/XarvisAgent.kt
│   │   │   ├── memory/MemorySystem.kt
│   │   │   ├── workflow/WorkflowEngine.kt
│   │   │   ├── device/DeviceCapabilityManager.kt
│   │   │   ├── service/AIInferenceService.kt
│   │   │   ├── viewmodel/XarvisViewModel.kt
│   │   │   └── ui/
│   │   │       ├── XarvisScreen.kt
│   │   │       └── theme/Theme.kt
│   │   └── res/
│   │       ├── drawable/
│   │       ├── values/
│   │       └── mipmap/
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties
```

## Usage Example Commands

```
"Open WhatsApp"
"Find my latest CV"
"Organize these files"
"Search for jobs"
"Create a project"
"Build me an Android app"
```

## Local AI Model Integration (Future)

The app is designed for local inference but currently awaits integration of a quantized language model. When whitelist approval is granted, the following will be implemented:

- Ollama/llama.cpp integration
- Quantized 7B model (4-6GB)
- On-device inference
- Complete offline capability

## Next Phases

1. **Phase 2**: Integrate local LLM with llama.cpp
2. **Phase 3**: Download and optimize quantized model
3. **Phase 4**: Advanced workflow templates
4. **Phase 5**: Voice interface (TTS/STT)
5. **Phase 6**: App building agent

## Contributing

This is a personal AI system project. Modifications welcome.

## License

Internal Use

---

**Status**: Ready for compilation and testing
**Target Device**: Samsung Galaxy S22 Ultra
**Last Updated**: 2026-09-25
