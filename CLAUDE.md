# CLAUDE.md — project instructions for AI assistants

LastLauncher is a one-page Android launcher (Kotlin, single module) that predicts the
user's next app from on-device usage logs. It is developed conversationally: the owner
describes features/fixes, the assistant implements, verifies, commits and publishes a
GitHub release that existing installs pick up automatically. Read this whole file
before touching code.

## The golden rules

1. **No third-party runtime dependencies.** AndroidX + Material only. Plain Views —
   no Compose, no coroutines (use `java.util.concurrent`), no image/DI/network libs
   (`HttpURLConnection` + `org.json` for the two network calls). This keeps the APK
   < 2 MB and cold start instant. Don't add a library without being asked.
2. **UI thread only reads memory.** Anything heavier than a map lookup (DB, network,
   PackageManager queries, scoring) runs on an executor; results post back to main.
3. **R8 is on for release builds.** Never reference a class/method by name string
   (reflection, XML `app:fragment` resolution, etc.) without a direct code reference
   or a proguard keep rule. This has already caused one release-only crash — check
   `app/build/outputs/mapping/release/mapping.txt` when in doubt.
4. **Locale-safe formatting.** The owner is French (device language English, may
   change): always `"%…".format(Locale.US, …)` when code parses/trims the result,
   and add translations to `values-fr/strings.xml` for every new string — the app
   must stay fully usable in both English and French.
5. **Battery is a feature.** No polling, no wakelocks, no continuous animations that
   run while the screen is idle unless the user explicitly asked (gesture hints are
   the sanctioned exception, and they stop on pause/search). Prefer static styling
   over animators; gate all animation on `prefs.animations`.
6. **Every change ships.** After implementing: build + test + lint, commit with
   gitmoji, bump the version, push, publish the release (see Release recipe). The
   installed app auto-updates from GitHub releases within a day.

## Environment

- Windows 11, Git Bash for shell commands. JDK 17 on PATH.
- Android SDK lives in the **sibling repo**: `local.properties` →
  `sdk.dir=C:/dev/Perso/AdBlocker4Android/.tools/android-sdk` (platform 34,
  build-tools 34.0.0). Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 via wrapper.
- **Signing**: `release.keystore` + `keystore.properties` at repo root, both
  gitignored (copied from the AdBlocker4Android sibling repo, alias `adblocker`).
  Never commit them, never print their contents. `assembleRelease` signs
  automatically when they exist. Auto-update requires the same key forever.
- **No emulator/device on this machine.** Verification = compile + unit tests +
  lint + reasoning. The owner tests on a **Pixel 8 Pro** (system language English)
  and reports back — earlier notes blaming MIUI quirks were wrong, it was never a
  Xiaomi. Be explicit in your summary about what could not be exercised.

## Commands

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug   # the standard gate
./gradlew assembleRelease                              # signed release APK
# lint errors count (must stay 0):
grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml
```

## Release recipe (after the feature commits)

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; commit as
   `🔖 Bump version to X.Y.Z`.
2. `./gradlew assembleRelease && git push`
3. Copy APK: `cp app/build/outputs/apk/release/app-release.apk LastLauncher.apk`
   — the asset **must** be named `LastLauncher.apk` and the tag `vX.Y.Z`
   (the in-app updater matches both; tag must equal versionName).
4. `gh release create vX.Y.Z LastLauncher.apk --title "..." --notes-file <file>`
   — write notes to a temp file first: inline `--notes` with backticks gets eaten
   by bash command substitution. Then `rm LastLauncher.apk` (it's gitignored).
5. Verify: `gh release view vX.Y.Z --json tagName,assets`.

Commit style: gitmoji first (`✨ 🐛 💄 📝 🔖 ✅ 🚨 🌐`), imperative subject, body
explains the why; end with `Co-Authored-By:` trailer for the assistant. Group commits
by feature; shared files (MainActivity) make perfect per-commit compilability
impractical — prioritize a readable history.

## Architecture map (single module, package `fr.arichard.lastlauncher`)

| Where | What |
|---|---|
| `MainActivity.kt` | The home screen: all gestures/touch routing, suggestions trio + swipe-to-cycle, drawers wiring, ticker, weather, status line, new-app spotlight, drag & drop lifecycle. Big by design — it *is* the launcher. |
| `LauncherApp.kt` | Application: repo bootstrap, package-change receiver (feeds new-app spotlight), context-signal registration. |
| `apps/AppRepository.kt` | In-memory app catalog + icon cache, loaded/refreshed off-thread; fuzzy search (prefix > word > initials > substring > subsequence > package tokens). UI reads immutable snapshots. |
| `predict/PredictionEngine.kt` | The "launcher memory". Scores = recency-decayed launches × hour/day-type match + Markov transition + context trigger + notification bonus + user boost − just-used penalty. All weights are named constants at the top. `snapshot()` powers the insights screen. |
| `predict/UsageDb.kt` | SQLite log of launches (pruned at 5000 rows). Never leaves the device. |
| `predict/ContextSignals.kt` | Bluetooth/headset/charger trigger events (5-minute window). |
| `notify/NotifListener.kt` | NotificationListenerService → badge counts + ticker messages, in-memory only. |
| `calendar/Agenda.kt` | Pure agenda logic (unit-tested): event instances → row list with day separators, next-event flag, countdown minutes; all-day UTC-midnight normalization. |
| `calendar/CalendarFeed.kt` | CalendarContract reads on a dedicated executor (instances window, calendar list); results post to main. READ_CALENDAR, read-only. |
| `calendar/AgendaView.kt` | The home agenda stream: terminal-style event lines under the status line, capped-height vertical scroll, tap-to-unfold (location → maps, open-in-calendar), long-press → calendar app. Forwards horizontal/multi-finger/dead-vertical swipes to the host. |
| `ui/WheelDrawer.kt` | Custom view: the arc/wheel edge drawer. Angular layout, roll/fling physics, swipe-to-close, drag & drop target. Feel constants in its companion. |
| `ui/SparkleView.kt` | Canvas particle overlay: firework powder around the finger during the suggestion swipe. Self-stopping frame loop — animates only while particles are alive. |
| `ui/StatusLine.kt` | Pure formatter for the terminal status line (unit-tested) + next-alarm reconciliation helpers; MainActivity adds per-token ClickableSpans. |
| `ui/AppAdapter.kt`, `ui/AppPickerDialog.kt` | Results list rows; icon-row dialogs used for all menus/pickers. |
| `command/CommandProcessor.kt` | Command-bar smarts: quick actions, settings jump, calculator, URL, assistant routing. Modes in `command/SearchMode.kt`. |
| `weather/WeatherProvider.kt` | Open-Meteo (key-less), last-known coarse location, 1 h cache. |
| `update/UpdateManager.kt` + `ApkInstaller.kt` | GitHub-releases self-update (daily, Wi-Fi download, PackageInstaller session). |
| `settings/SettingsActivity.kt` | Root list + one fragment per domain. **Navigation maps preference keys to constructors — never reinstate reflective `pref.fragment` instantiation (R8).** `SettingsActivity.open(context, SCREEN_X)` deep-links (used by contextual long-press). |
| `settings/Prefs.kt` | Every setting, typed, with defaults. Add new keys here + constant. |
| `settings/InsightsActivity.kt` | "How suggestions work": live engine report. |

Layout: one file, `res/layout/activity_main.xml` — ordered scrim → content column
(clock row, date, weather, status line, ticker, pills, middle area with suggestions/
hints/results, command bar) → overlay views (swipe glow, drag ghost, spotlight) →
the two `WheelDrawer`s (last = on top).

## Interaction contracts (don't break these)

- The launcher never finishes; back collapses overlays only.
- Both drawers can be open at once; they are non-modal (home stays usable). A tap on
  empty home closes them; the keyboard appearing closes them; opening one drops the
  keyboard. A swipe matching a drawer's closing direction closes it; other swipes run
  their bound action even over an open drawer (the drawer forwards inward and
  multi-finger swipes to the host instead of eating them).
- Edge pulls (40 dp zones) track the finger when that edge's gesture slot is bound to
  a drawer; flings elsewhere fire `GestureBinding` actions (1- vs 2-finger slots).
- Suggestions: trio = top of a 12-deep ranking; a one-finger horizontal swipe
  starting in the lower band (60% of screen height down to the row, any x) pages it.
  Live feedback: glow under the finger, particle sparkles (`ui/SparkleView`), the
  trio leans into its coin flip as the swipe builds; release completes or cancels
  the turn. Long-press-for-Settings is disabled inside the band. Long-press on the
  trio = menu, or drag into an open drawer. The just-launched app is excluded-ish
  for 45 min (15 s grace).
- Opening a different drawer on a side that already shows one plays close-then-open
  (`WheelDrawer.swapTo`); an explicit close cancels the pending swap. One-finger
  swipes matching the close direction close the drawer; two-finger swipes always
  run their bound action.
- The agenda stream sits between the status line and the ticker, only on the empty
  home: it yields to search results AND to open drawers, and is a centered block
  capped at N visible lines (setting, default 6; hard cap 35% of screen) / 300dp
  width so the middle area keeps its room. A thin accent rail at the block's right
  (drawn in dispatchDraw) appears when there is more to scroll. It scrolls its
  horizon vertically but hands the host every gesture it can't use — horizontal,
  multi-finger, and verticals ONLY when the content doesn't scroll at all
  (per-direction forwarding cancelled taps at the scroll edges and fired all-apps
  at the end of a scroll — don't reintroduce it). Optional gesture-summon mode
  (`agenda_on_gesture` + `GestureAction.AGENDA`): hidden until the bound swipe
  toggles it; back or resetToHome dismisses. Options: lines, text size, all-day
  on/off, countdown on/off, days, tap behavior, calendars. Refresh: on resume +
  ContentObserver while resumed + the minute tick for the countdown. Never polls.
- Haptics on every deliberate action, gated by `prefs.haptics` via `haptic(view)`.
- Hints, spotlight and ticker all yield to drawers/search and stop on pause.

## Testing

Unit tests (`app/src/test`) cover pure logic only: StatusLine formatting, version
comparison, gesture-binding encoding, CommandProcessor parsing/locale, AppEntry
normalization. Keep new pure logic testable and add cases (locale regressions have
JVM-default-locale tests — follow that pattern). UI/touch code is untestable here;
compensate with careful reasoning and honest caveats to the owner.

## Documentation duties

- `README.md`: user-facing features list + developer instructions — update when a
  feature is user-visible.
- Release notes: short, user-language (the owner reads them on the phone), grouped
  Improved/New/Fixed, always ending with the auto-update reminder line.
- This file: update when conventions or architecture change.
