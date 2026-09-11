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

- Windows 11, Git Bash for shell commands. **JDK: Temurin 25 LTS at
  `C:/dev/tools/jdk-25`**, set as the user-scope `JAVA_HOME` and prepended to the
  user PATH, so `./gradlew` needs no override in a fresh shell. A shell started
  BEFORE that was set keeps the old environment — then export it by hand:
  `export JAVA_HOME="C:/dev/tools/jdk-25"`.
  Do NOT move the build to a newer JVM: an Oracle JDK 26 is installed and sits
  first on the **machine** PATH (so interactive `java -version` reports 26), but
  no Gradle/AGP/Kotlin release supports 26 yet — the build stays on 25. A 32-bit
  Oracle Java 8 also lingers further down that PATH; if a build ever dies with
  "Could not reserve enough space for 2097152KB object heap", that is the one
  that answered, and JAVA_HOME was not set.
- Android SDK lives in the **sibling repo**: `local.properties` →
  `sdk.dir=C\:/dev/Perso/AdBlocker4Android/.tools/android-sdk` — the drive colon
  MUST be escaped (`C\:`) or AGP lint fails the gate with PropertyEscape
  (platform 34, build-tools 34.0.0). Gradle 9.5.1 / AGP 8.13.2 / Kotlin 2.2.21
  via wrapper. BOTH upper bounds are deliberate. Gradle is pinned to 9.5.x: 9.6 removed the internal
  `InternalProblems` API that AGP 8.x relies on, so Gradle 9.6+ demands AGP 9.x,
  which in turn wants compileSdk 36 and newer build-tools — a separate migration,
  not a wrapper bump. Kotlin is pinned to 2.2.x because the R8 bundled with AGP
  8.13 cannot parse newer Kotlin metadata (2.4.20 emitted 16 "error parsing
  kotlin metadata" warnings while shrinking the release build); raise Kotlin only
  together with AGP.
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
| `apps/PinShortcutActivity.kt` | Toast-and-finish handler for `CONFIRM_PIN_SHORTCUT`. Exists so `ShortcutManager.isRequestPinShortcutSupported()` is true while we're the home app — Chrome checks that before offering to install a PWA at all. We don't render pinned shortcuts (installed PWAs are WebAPKs = normal apps); don't remove it. |
| `apps/AppRepository.kt` | In-memory app catalog + icon cache, loaded/refreshed off-thread; fuzzy search (prefix > word > initials > substring > subsequence > package tokens). UI reads immutable snapshots. |
| `predict/PredictionEngine.kt` | The "launcher memory". Scores = recency-decayed launches × hour/day-type match + Markov transition + context trigger + notification bonus + user boost − context-matched miss rows (corrected trios) − just-used penalty. All weights are named constants at the top. `snapshot()` powers the insights screen; `exportData()` dumps everything to a shareable JSON. |
| `predict/UsageDb.kt` | SQLite log of launches (pruned at 5000 rows) + misses (corrected-trio rows, pruned at 1500), both context-stamped. Never leaves the device unless the user exports. |
| `predict/ContextSignals.kt` | Trigger events (5-minute window): Bluetooth/headset/charger receivers, Wi-Fi join/leave + connectivity lost/regained (NetworkCallbacks), "started moving" via the one-shot significant-motion sensor (never the raw accelerometer — battery). With `ssid_signal` + fine location, a join records `wifi:<sha256(salt+ssid)[:12]>` — salt is a per-install random UUID, so tokens are opaque and app-only. |
| `notify/NotifListener.kt` | NotificationListenerService → badge counts + ticker messages, in-memory only. |
| `notify/MediaWatch.kt` | Active media sessions via MediaSessionManager (rides the notification-listener grant): the now-playing row's data + transport controls. Registered only while resumed. |
| `notify/MediaState.kt` | Pure playback-state ranking (unit-tested): which session the row shows. |
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
| `backup/ConfigBackup.kt` | Pure logic (unit-tested) for the settings export/import file: the `PORTABLE_KEYS` allowlist (excludes device-local/transient state — caches, "have we asked" flags, the Wi-Fi salt, timestamps), `buildPayload`/`resolveImport`. **New user-facing setting → add its key here or it silently isn't backed up.** |
| `backup/ConfigBackupIO.kt` | The Android side: writes/reads the JSON file (cache dir, shared via the same FileProvider as the brain export), re-matches calendar exclusions by display name against the destination device's calendars (their provider row ids never travel). Settings → General → *Export/Import settings*. |
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
- Edge pulls (40 dp zones) arm on touch-down; additionally, a one-finger horizontal
  drag from anywhere outside the suggestion band arms mid-gesture when that
  direction's one-finger slot is bound to a drawer — both track the finger live.
  Flings elsewhere fire `GestureBinding` actions (1- vs 2-finger slots).
- Typing in all-apps shows the same smart command rows as normal search — all-apps
  is only command-free while its query is empty.
- Suggestions: trio = top of a 12-deep ranking; a one-finger horizontal swipe pages
  it. The band is anchored to the row (72 dp above it down to its bottom, any x
  outside the 40 dp edge zones) and capture happens in `dispatchTouchEvent` —
  BEFORE the icons can consume the touch — claiming the gesture at ~1.5×slop of
  horizontal travel and cancelling whatever was under the finger; trigger = 56 dp.
  Live feedback: glow under the finger, particle sparkles (`ui/SparkleView`), the
  trio leans into its coin flip as the swipe builds; release completes or cancels
  the turn. Long-press-for-Settings is disabled inside the band. Long-press on the
  trio lifts the app (see Drag & drop below; release in place = menu). The
  just-launched app is excluded-ish for 45 min (15 s grace).
- Opening a different drawer on a side that already shows one plays close-then-open
  (`WheelDrawer.swapTo`); an explicit close cancels the pending swap. One-finger
  swipes matching the close direction close the drawer; two-finger swipes always
  run their bound action.
- The agenda stream sits between the status line and the ticker, only on the empty
  home: it yields to search results AND to open drawers, and is a centered block
  capped at N visible lines (setting, default 6; hard cap 30% of screen, enforced
  by a post-measure clamp in onMeasure) / 300dp width so the middle area keeps its
  room. When there is more to scroll, a faint rounded outline boxes the block and
  a thin accent rail with a thumb (both drawn in dispatchDraw) shows the position. It scrolls its
  horizon vertically but hands the host every gesture it can't use — horizontal,
  multi-finger, and verticals ONLY when the content doesn't scroll at all
  (per-direction forwarding cancelled taps at the scroll edges and fired all-apps
  at the end of a scroll — don't reintroduce it). Optional gesture-summon mode
  (`agenda_on_gesture` + `GestureAction.AGENDA`): hidden until the bound swipe
  toggles it; back or resetToHome dismisses. Options: lines, text size, all-day
  on/off, countdown on/off, days, tap behavior, calendars. Refresh: on resume +
  ContentObserver while resumed + the minute tick for the countdown. Never polls.
- The now-playing row (music_widget, on by default) sits under the ticker, only when
  a media session is playing/paused and no search results are shown: `♪ artist —
  title` (tap = open the app) + ⏮ ⏯ ⏭ transport controls (glyphs carry U+FE0E —
  the emoji forms ignore the accent tint). Swiping the row horizontally skips
  (left = next); long-press opens its settings domain. While the row shows an app,
  that app leaves the suggestion trio; with the row disabled, a live session pulls
  the playing app to the trio's front instead. Session watching is event-driven and
  resumed-only; no polling.
- Home pressed while already home is a toggle: bar closed → focus + keyboard; bar
  open (typing/all-apps/keyboard up) → clean home. Returning home from an app stays
  keyboard-free unless `keyboardAlways`.
- Haptics on every deliberate action, gated by `prefs.haptics` via `haptic(view)`.
- The agenda has a header line above the box (`▤ agenda … +`): title tap opens the
  calendar app, `+` fires ACTION_INSERT, long-press (header or stream) deep-links to
  the agenda settings. All-day view intents must send providerBegin (raw UTC) +
  EXTRA_EVENT_ALL_DAY or calendar apps fall back to their slow main view.
- The floating slots (new-app spotlight + park slot) are visual siblings: same
  geometry (placeSlot — bottom-anchored above the trio, 22 dp in from the border,
  margin computed only while the IME is hidden; insets changes re-place them, or
  a closed keyboard's shift freezes into the position), same glow breath, same
  rising soda bubbles (shared slotBubbles runnable). Park specifics: any app drag
  reveals its dashed drop circle (kept VISIBLE at alpha 0 otherwise — GONE views
  never join a drag), attraction scaling within 120 dp, drop pins for park_hours,
  park_multi rotates several. Tap launches — and does NOT dismiss the spotlight:
  it lives for `new_app_hours` (tap-to-dismiss made new apps "vanish"). Apps shown in
  either slot (and the now-playing app) are excluded from the suggestion trio
  (visibleSuggestionPool). At rest the park slot yields to a drawer on its side;
  during a drag it stays live and moves in past the drawer band (placeSlot
  pastDrawer) so a drop is always possible.
- Drag & drop, one model everywhere (trio, results rows, spotlight, park slot,
  drawer items): long-press LIFTS the app into a system drag; releasing it within
  DRAG_MENU_DP (18 dp) of the lift point is a long-press → app menu (onDragEnded
  decides by dragMaxTravel; in-place drops on a drawer/the park slot are accepted
  as no-ops so the menu still follows). The ClipData carries a private MIME type
  and the command bar has its own drag listener that swallows drops (an EditText
  otherwise inserts any dropped ClipData as text — even non-text MIME). Drop
  targets: open drawers; a CLOSED drawer whose edge the finger nears (within
  DRAG_EDGE_OPEN_DP = 48 dp, once per side per drag, only after real travel)
  opens itself — but a view GONE at drag start never receives the system's drop,
  so the ROOT catches drops over a mid-drag-opened drawer and forwards them
  (WheelDrawer.acceptForwardedDrop); the park slot; and, for apps lifted from a
  drawer list or the park slot only, the remove bands (removeTop/removeBottom,
  REMOVE_ZONE_DP = 120 deep, fading in from REMOVE_REACH_DP = 260) — dropping
  there removes from the source drawer / unpins. Anything else flies back home.
  Every finger position (root, drawers, command bar) funnels through trackDrag.
- The gesture hints are ROOT-level children centered on the screen — inside the
  middle area the agenda's height dragged them below center.
- Corrected-trio feedback: cycling the suggestions snapshots the pre-swipe trio;
  launching an app outside it within 8 s writes context-stamped miss rows for the
  shown apps (scored as negative launches, same decay/matching) plus one extra
  reinforcement launch row for the opened app. Cycling with no launch after =
  play, no signal.
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
- `docs/PROJECT_STATUS.md`: **read this first in every new conversation** —
  it's the running history/current-state doc (what shipped, when, why, and
  what's still open) so a fresh session doesn't have to reconstruct context
  from `git log`. Add or update a bullet there after any significant piece of
  work (a shipped feature, a fixed bug, an infra change) — before the
  conversation ends, not "eventually." Keep entries factual and condensed;
  this is a status doc, not a diary — don't restate what `git log` already
  says clearly, just synthesize the why and the current implication.
