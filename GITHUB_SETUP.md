# Getting the APK from GitHub

Every push to `main` builds a debug APK automatically (`.github/workflows/build.yml`).

1. Open the repository on GitHub and go to the **Actions** tab.
2. Open the latest **Build XARVIS APK** run with a green check.
3. Under **Artifacts**, download **XARVIS-debug-apk** and unzip it to get `app-debug.apk`.
4. Install it on the phone (copy the file over and tap it, or `adb install -r app-debug.apk`). Android may ask you to allow installs from that app first.

The on-device AI model isn't part of the APK; see "On-device model" in the README for how to add it.
