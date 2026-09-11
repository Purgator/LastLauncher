# Project status — read this first in a new conversation

This file exists so a new conversation about LastLauncher can pick up with full
context instead of re-discovering it. **Read this, then `CLAUDE.md`** (project
rules, architecture map, environment setup, release recipe — the operating
manual) before doing anything else. This file is the *history and current
state*; `CLAUDE.md` is the *rules*; `README.md` is the *user-facing feature
list*; `docs/BRAIN_IDEAS.md` is the *prediction-engine roadmap*.

**Keep this file current.** After any significant piece of work (a shipped
feature, a fixed bug, an infra change), add or update a bullet here before
ending the conversation — that's the whole point of the file.

## What this is

A one-page Android launcher (single Kotlin module, no third-party runtime
deps) that predicts the user's next app from on-device usage patterns and
puts three guesses under the thumb. Built conversationally over ~2 months
(first commit 2026-07-11), 120 commits, currently at **v1.19.0**. Owner tests
on a **Pixel 8 Pro, English system language** — no emulator/device available
in the dev environment, so verification is always compile + unit tests +
lint + reasoning, with real-device confirmation coming back from the owner
in a later message.

## Timeline (condensed — see `git log` for full detail)

- **v1.0–v1.9**: core launcher shipped — the wheel wobble/drag home screen,
  suggestion trio, command bar with modes (Smart/Apps/Settings/Ask), edge
  gesture drawers, weather chip, notification badges/ticker, status line,
  now-playing media controls, double-tap-to-lock. Along the way: alarm-time
  reconciliation work (see "Open threads" below), haptics pass, drawer
  gesture pass-through fixes.
- **v1.10**: agenda stream (calendar events on the home screen) shipped as a
  new feature — compact centered block, gesture-summon mode, scroll rail,
  tap-to-unfold. Several follow-up polish/bugfix releases (1.10.1–1.10.6).
- **v1.11–v1.12**: now-playing row (media session transport controls) shipped
  and polished. Floating slots (new-app spotlight + drop-to-pin park slot)
  redesigned as visual siblings with shared geometry/glow/bubbles. Context
  senses added (Bluetooth/headset/charger/Wi-Fi/motion triggers).
- **v1.12–v1.13**: prediction engine reworked — corrections (swipe-away +
  launch-something-else feedback) became real persisted memory instead of a
  temporary patch; per-network Wi-Fi place signal (salted-hash SSID); a full
  "brain export" feature to dump the engine's whole world as shareable JSON
  for offline analysis.
- **v1.13→v1.14 (2026-08-07)**: the owner's real 27-day usage export was used
  to **backtest the entire roadmap** (`docs/BRAIN_IDEAS.md` "researched &
  critiqued prediction-engine ideas" → "backtest verdicts from the owner's
  real export"). This re-priced several planned Tier-2 ideas as flat-or-
  negative on real data and reordered priorities. **Tier 0 shipped in
  v1.14.0**: `predict/ScoreMath.kt` (single shared scoring formula used by
  both the live engine and any replay), `predict/Backtester.kt` (walk-forward
  replay + LRU/MFU baselines, auto-runs and shows in Insights),
  `predict/Calibration.kt` (trio-hit-rate EMA + reliability bins), and
  `tools/backtest_brain.py` (standalone Python analysis script for a fresh
  export). See `docs/BRAIN_IDEAS.md`'s "BACKTEST VERDICTS" section for the
  actual measured results — don't re-derive them from first principles.
- **v1.15.0 (2026-09-07)**: drag & drop overhauled into one consistent model
  everywhere (suggestions trio, search results, spotlight, pin slot, drawer
  items): long-press lifts the app into a system drag; releasing near the
  lift point opens the app menu instead. New: remove-by-dragging (drag a
  drawer/pinned app to the top or bottom of the screen to take it out/unpin
  it), edges auto-open their drawer when a dragged app nears them, the pin
  slot stays reachable during a drag even with a drawer open on its side.
  Fixed a real bug found along the way: the command bar (an `EditText`)
  would have swallowed a dropped app as typed text.
- **v1.16.0 (2026-09-08)**: pure toolchain modernization, no app behavior
  change. The dev machine's only usable JDKs had rotted (PATH `java` was a
  32-bit Java 8; JetBrains runtimes were Java 25). Downloaded and wired
  **Temurin 25 LTS** as the default JDK (user-scope `JAVA_HOME` + PATH, no
  per-command export needed in a fresh shell), which forced upgrading
  **Gradle 8.7→9.5.1, AGP 8.5.2→8.13.2, Kotlin 1.9.24→2.2.21** (both upper
  bounds are deliberate — see `CLAUDE.md`'s Environment section for why).
  Verified the release APK's signing certificate is byte-identical to
  v1.15.0's before publishing, since auto-update depends on that. Full
  rationale and gotchas are in the `build-jdk-location` memory and in
  `CLAUDE.md`.
- **v1.16.1 (2026-09-09)**: fixed the agenda stream only showing the owner's
  main calendar. `CalendarFeed.query()` was hard-filtering on the provider's
  `Instances.VISIBLE` column on top of our own `agendaExcludedCalendars`
  picker (Settings → Agenda → Calendars); some account types sync
  secondary/imported calendars' events without ever setting that flag, so
  they never reached the stream even with nothing excluded in our settings.
  The picker (already present since the agenda feature's original commit,
  lists every calendar on the device with per-calendar checkboxes) is now
  the sole source of truth for what shows, default unchanged (empty
  exclusion = show every calendar).
- **v1.17.0 (2026-09-09)**: two-finger swipe down now opens quick settings
  (falls back to the notification shade if the accessibility service can't
  reach it directly) — the vertical swipe branch in `handleSwipe()` used to
  ignore finger count entirely, so one and two fingers both just opened the
  shade. Also replaced the plain cross-fade the park (pinned-app) slot and
  new-app spotlight used when rotating between several apps with the same
  coin-flip turn (`rotationY`) the suggestion trio already uses, via a new
  shared `coinFlipIcon()` helper — reviewed the rotation logic itself while
  investigating an owner report that it "didn't work" and found it
  structurally sound (same pattern as the park slot); the much more visible
  flip should make actual rotation obvious, but this is unconfirmed on-device
  — revisit if the owner still sees it stuck on one app.
- **v1.18.0 (2026-09-10)**: fixed a real gap the owner spotted — a same-day
  birthday/holiday (all-day event) could sit unhighlighted while a later
  timed event that day took the agenda's single "next" arrow, easy to miss.
  `Agenda.rows()` (`nextEvent = live.firstOrNull { !it.allDay } ?: live.first()`)
  always favored the first timed event. Presented three fix options and the
  owner asked for all three as a user-facing choice with a strong default,
  so it shipped as `Agenda.AllDayHighlight` (BOTH / DISTINCT / SMART) behind
  Settings → Agenda → "Highlight today's all-day events", default **BOTH**
  (today's all-day event(s) and the next timed event both get the
  arrow/accent). DISTINCT gives all-day its own `●` marker independent of
  the timed "next" arrow; SMART keeps one highlight but lets all-day win it
  when the next timed event is 3+ hours off. All pure logic in `Agenda.kt`,
  5 new unit tests.
- **v1.19.0 (2026-09-11)**: settings export/import, for moving to a new phone.
  New `backup/ConfigBackup.kt` (pure, unit-tested — `PORTABLE_KEYS` allowlist +
  `buildPayload`/`resolveImport`) and `backup/ConfigBackupIO.kt` (the Android
  side: JSON file in the cache dir, shared via the same FileProvider as the
  brain export; read back via `ActivityResultContracts.OpenDocument()`).
  Settings → General → *Export/Import settings*. Deliberately an **allowlist**
  of portable keys, not a denylist, so a forgotten future setting silently
  isn't exported rather than leaking device-local state — excludes caches,
  "have we asked" permission flags, the Wi-Fi hashing salt, timestamps, and
  the prediction engine's own signals (that has its own separate export
  already). The one setting stored as a device-local id — calendar exclusions,
  a `CalendarContract` row id — travels as display names and gets re-matched
  against the destination device's calendars on import; unmatched ones are
  reported to the owner, not silently dropped. No Google Drive SDK or any new
  dependency: export uses the normal Android share sheet (Drive already
  appears there as a target, like any file-sharing app), import uses the
  system document picker (browses into Drive/Downloads/etc. the same way).
  Schema-versioned (`"schema": 1` in the file) but no migration machinery
  built yet — nothing has needed one; the flat allowlisted-key-map shape is
  naturally forward/backward compatible for additions on its own.

## Current state (as of v1.19.0)

- **Toolchain**: JDK 25 (Temurin, `C:/dev/tools/jdk-25`, user `JAVA_HOME`) /
  Gradle 9.5.1 / AGP 8.13.2 / Kotlin 2.2.21. App still targets Java 17
  bytecode. Do not bump Gradle past 9.5.x or Kotlin past 2.2.x without also
  moving AGP — see `CLAUDE.md` for exactly why both ceilings exist.
- **APK size**: at the ~2 MB ceiling (rule #1 in `CLAUDE.md`: no third-party
  runtime deps, by design). Size-audit before adding anything non-trivial.
  v1.18.0's release APK is ~2,003,256 bytes — just over 2 MB now; watch this
  closely on the next feature.
- **Prediction engine**: Tier 0 of the roadmap is shipped and self-grading in
  Settings → Insights (live hit-rate + backtest scoreboard vs. frequency/
  recency baselines). Tier 2 priorities were re-ordered based on real-data
  backtest results, not guesses — read `docs/BRAIN_IDEAS.md` before changing
  any scoring weight.
- **Drag & drop**: fully unified across every draggable surface as of
  v1.15.0; the interaction contract is documented in detail in `CLAUDE.md`'s
  "Interaction contracts" section (search "Drag & drop, one model
  everywhere").

## Open threads (unresolved, not yet closed out)

- **Alarm display saga**: the status-line "next alarm" field has shown the
  wrong hour on the owner's Pixel three times across different fixes
  (pre-1.9.3 naive trust in the system string; v1.9.3 reconciliation logic;
  still reported 1h early afterward). v1.9.4 added a raw-diagnostics block
  to Settings → Insights (timezone, raw trigger time, the *owning package*
  of the alarm, the system's formatted string, and what we finally show).
  **Next step, still pending**: get the owner to open that Insights block and
  report what it says — the owning package is the key fact: if it isn't the
  stock clock app, another app (sleep tracker, bedtime schedule, wearable
  companion) owns the alarm slot and we need to filter or pick differently.
  This has not been followed up on since v1.9.4 shipped.
- **New-app spotlight rotation**: owner suspected multiple recently-installed
  apps weren't rotating through the spotlight slot. Code review (v1.17.0)
  found `showNextSpotApp()` structurally identical to the park slot's
  (stable index into a timestamp-sorted list, self-rescheduling `Runnable`
  every `SPOT_ROTATE_MS`) — no logic bug found. Shipped the coin-flip swap
  (much more visible than the old cross-fade) on the theory the rotation was
  actually firing but easy to miss; **unconfirmed** — if the owner still
  sees it stuck on one app with 2+ new installs pending, the next step is
  checking whether some other call to `updateNewAppSpot()` is firing more
  often than expected and resetting `spotIndex` before `SPOT_ROTATE_MS`
  (4.5 s) elapses.

## Environment quick facts (full detail in `CLAUDE.md`)

- Fresh shell already has `JAVA_HOME` pointed at Temurin 25; only a shell
  that predates 2026-09-08's setup needs a manual export.
- No Android emulator/device available here; all verification is
  build+test+lint+reasoning, with the owner confirming on-device afterward.
  Setting up an emulator was discussed but deferred — the owner said they'll
  handle disk cleanup for it "later," not now.
- Signing keystore is reused from the sibling `AdBlocker4Android` repo
  (gitignored, never printed). Release asset must be named exactly
  `LastLauncher.apk`, tag `vX.Y.Z` matching `versionName`.
