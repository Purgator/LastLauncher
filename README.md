# LastLauncher

**The last launcher you'll ever need.** A one-page Android home screen that learns your habits and puts the right app under your thumb before you even look for it.

No app drawers to organize, no pages to swipe through, no widgets to babysit. Just a clock, the three apps you're most likely to want right now, and a command bar where typing two letters launches anything.

## Why it feels like the future

LastLauncher keeps a small on-device memory of *when* you open *what*: the hour, the day, the app you came from, and what your phone was doing (Bluetooth just connected? headphones plugged in? charger attached?). From that it predicts your next app:

- Plug in your headphones → your music app glows in the center.
- Connect to your car's Bluetooth → navigation is one tap away.
- Every evening you open the same reader after the same messenger → it's already waiting.

The most likely app is the big glowing button; two runners-up flank it. Everything is computed on your phone with a few milliseconds of math over a tiny SQLite table. **Nothing ever leaves your device.**

## Features

- 🧠 **Launcher memory** — on-device prediction from time of day, weekday rhythm, app-to-app sequences, and context triggers (Bluetooth, headphones, charging)
- ⌨️ **Type to launch** — the keyboard can stay always ready; two letters + Enter opens the best match (accent-insensitive, initials work too: "gm" → Google Maps)
- 👆 **Double-tap to lock** the screen (tiny accessibility service, never reads screen content)
- 📲 **One page, zero friction** — swipe up for all apps (keyboard opens with it), swipe down for notifications, long-press for settings
- ✦ **Assistant button** — summons Gemini / Google Assistant from the command bar
- 🕶️ **Futuristic glass UI** over your wallpaper — accent colors, wallpaper dimming, subtle animations (all optional)
- 🫥 **Hidden apps**, app info / uninstall on long-press, web-search fallback when nothing matches
- 🔄 **Auto-updates** straight from GitHub releases — checked once a day, downloaded on Wi-Fi, installed after your tap
- 🪶 **Lightweight & battery-friendly** — a single ~1.8 MB APK, no background services, no wakelocks, no trackers, no network calls except the update check

## Install (the simple way)

1. On your phone, open [the latest release](https://github.com/Purgator/LastLauncher/releases/latest) and download **`LastLauncher.apk`**.
2. Open the downloaded file. If Android asks, allow your browser to *install unknown apps* (Settings does this in two taps — it's the normal flow for apps outside the Play Store).
3. Open LastLauncher, long-press an empty area → **Settings** → **Set as default launcher**.
4. Optional but recommended: enable **double-tap to lock** — the app guides you to the accessibility toggle it needs.

That's it. Give it a few days of normal use and the suggestions start feeling telepathic. Updates arrive by themselves.

### Good to know

- **Privacy:** launch history stays in a local database. You can wipe it anytime (*Settings → Forget everything learned*) or turn predictions off entirely.
- The Bluetooth permission is only used to notice "a device just connected" as a prediction signal. Deny it and everything else still works.
- Requires Android 8.0 (API 26) or newer.

## For developers

### Build

```bash
git clone https://github.com/Purgator/LastLauncher.git
cd LastLauncher
# point local.properties at your Android SDK (API 34), e.g.:
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # lint
```

Requirements: JDK 17, Android SDK platform 34 + build-tools 34.0.0. Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 come via the wrapper.

### Release signing

`assembleRelease` signs automatically when a `keystore.properties` file exists at the repo root (it is gitignored):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, the release build is unsigned. Auto-update relies on Android's same-signature rule, so released APKs must always be signed with the same key.

### Architecture (single module, ~15 classes, no third-party runtime deps)

| Package | Role |
|---|---|
| `apps/` | `AppRepository` — in-memory app catalog, icon cache warm-up, fuzzy search |
| `predict/` | The launcher memory: `UsageDb` (pruned SQLite log), `PredictionEngine` (recency-decayed frequency × time affinity × transitions × context triggers), `ContextSignals` (Bluetooth / headset / charger receivers) |
| `lock/` | `LockService` — accessibility service for lock-screen & notification-shade global actions |
| `update/` | `UpdateManager` (GitHub releases check, throttled, Wi-Fi-only download) + `ApkInstaller` (PackageInstaller session with installer-intent fallback) |
| `settings/` | `Prefs` + `SettingsActivity` (PreferenceFragmentCompat) |
| root | `MainActivity` — the one-page home screen; `LauncherApp` — wiring |

Design rules: everything heavier than a map lookup runs off the main thread; the UI thread only reads immutable snapshots. No coroutines, no DI framework, no Compose — plain Views keep the APK under 2 MB and cold start instant.

### Releasing a new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`
3. Publish the APK as a release asset named exactly **`LastLauncher.apk`** with tag `vX.Y.Z`:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk LastLauncher.apk
   gh release create vX.Y.Z LastLauncher.apk --title "..." --notes "..."
   ```
   Installed apps pick it up within a day (checks are throttled to daily and downloads happen on unmetered networks).

## License

[GPL-3.0](LICENSE)
