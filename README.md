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
- ⌨️ **Command bar with modes** — the wheel on its left cycles **Smart · Apps · Settings · Ask**. Smart mode launches apps *and* jumps to settings by name (`wifi` → Wi‑Fi settings, no prefix), does quick math (`12*7` → tap to copy) and opens URLs; a `>` prefix forces the quick-action palette. Long or question-like text routes to the assistant — no web search. App search even matches package keywords (`music` finds Spotify).
- 🖐️ **Configurable edge gestures** — one- and two-finger swipes on each side (`>`, `>>`, `<`, `<<` in the launcher's CLI notation), each mappable to any action or app. Monospace hints on the screen edges travel the way you should swipe, fading in and out as they cycle.
- 🗄️ **Wheel app drawers** — create several named drawers (each with its own apps) and bind any of them to an edge swipe. Background-less and non-modal: apps trace a half-circle arc (first and last hugging the edge, the middle one bulging toward the screen center) that always fills the drawer's height — few apps spread out, many roll like a wheel on vertical drags. Opening rolls the icons in from the bottom. Both edges can be open at once, and the clock, command bar and gestures stay usable while they are. Close with a tap on empty space, a swipe toward the edge, or back.
- 🎯 **Notification-aware suggestions** — apps with unread notifications get a capped boost in the guessed trio, so what demands attention is one tap away without drowning your habits.
- 🌤️ **Weather by the clock** *(opt-in)* — a tap-to-open weather chip, beside the clock or under the date, configurable in units (°C/°F) and what it shows (icon, temperature, or both). An optional **living clock** tints the clock's glow with the sky — golden sun, steel-blue rain, violet storms — as a static effect with zero battery cost. Uses your approximate last-known location and a key-less service (Open-Meteo), refreshed hourly; off by default.
- 🧭 **Transparent brain** — *Settings → How suggestions work* shows a live terminal-style report of the engine: its signal weights, how much data it keeps (and the database's size on disk, also available as a status-line field), the current context, and the live top-10 ranking. Long-press any app to **boost** it in the suggestions; long-press a home-screen element to jump straight to its settings.
- 🔔 **Notification bubbles & message ticker** *(opt-in)* — a count bubble on app icons for unread notifications, and an optional ticker under the clock that cycles your unread messages one at a time, fading between them. Reads notifications on-device only; nothing is stored or sent.
- 🖥️ **Customizable CLI status line** — an optional terminal-style readout under the date; pick which fields show (battery, network, next alarm, apps launched today, free storage).
- 👆 **Double-tap to lock** the screen (tiny accessibility service, never reads screen content)
- 📲 **One page, zero friction** — swipe up for all apps (keyboard opens with it), swipe down for notifications, long-press for settings
- ✦ **Assistant button** — summons Gemini / Google Assistant from the command bar
- 🕶️ **Futuristic glass UI** over your wallpaper — accent colors, wallpaper dimming, subtle animations (all optional)
- 🚀 **Starter picks** — on first run, choose your go-to apps (smart guesses pre-checked); they fill the suggestion slots until the launcher has learned your habits, and become a static-favorites mode if you turn predictions off
- 🕐 **Clock tap** opens the clock app — or any app you choose in settings
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
