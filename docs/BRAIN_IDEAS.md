# Brain roadmap — prediction engine ideas (researched 2026-08-08)

Output of a multi-agent research pass (5 lenses × generator + adversarial critic,
validated against the codebase and its golden rules). Each idea below survived the
critique; amendments from the critics are folded in and MUST be respected — they fix
real bugs in the naive versions. Effort: S/M/L. Impact as rated by the critics.

Engine state at time of writing (v1.13.0): score = Σ over 60d of
`exp(-age/20d) · [1 + 1.2·hour±1 + 0.3·daytype + 2.5·prevPkg + 3.5·ctxEvent]`
`+ 0.35·min(notif,4) + boost(×1.35+1.0) − 1.5·(same formula over miss rows)`
`− just-used crush (×0.05 for 45min, 15s grace)`.
Triggers (single slot, 5-min window): bt_connected, headset_plugged, power_connected,
wifi:<hash>/wifi_joined/left, back_online/went_offline, started_moving.
Corrected-trio feedback writes miss rows + one reinforcement launch row.

---

## BACKTEST VERDICTS (owner brain export, run 2026-08-07 — READ FIRST)

Walk-forward replay of the owner's real export: 1534 launches, 27 days, 54 apps,
20% warmup, 1228 evaluated rows. Engine replicated exactly minus notification
bonus and boosts (not replayable; boosts were empty anyway). With n=1228,
differences under ~2pp are noise — paired win/loss tests used throughout.

**Feature-age caveat (critical):** the export spans every version since the
engine was born (07-11). bt/headset/power triggers ran the whole span, but
started_moving + wifi join/leave shipped 08-06 and SSID hashing + the
corrections rework 08-07 — the export day. Ctx coverage was 9% before the
last 36h and 31% inside them. Verdicts on TIME/FREQUENCY/PREV ideas rest on
full-span data and are solid; verdicts touching the NEW triggers are marked
below as insufficient-data, not failures.

Scoreboard: current engine hit@1 23.4% / hit@3 44.3% / hit@12 75.4%.
Decayed-MFU baseline: 23.1 / 43.2 / 73.5. LRU: 12.1 / 30.8 / 67.9.
**The whole signal blend nets ~1pp over plain decayed frequency**, and hit@3
equals the top-3 apps' launch share (43.7%) — the trio is effectively
decayed-MFU today. Cheating oracles put the realistic ceiling at ~48–55%,
and the strongest oracle (markov+hour, fit on ALL data) collapses to 40.0%
when honestly walk-forwarded (overfit, z=−3.74). Headroom is a few points, not tens.

Per-idea verdicts from the data:

- **Ablations / 2.8 retune:** removing ANY single term (hour, daytype, markov,
  trigger) costs ≤0.4pp hit@3; a 256-combo weight grid spans 43.5–44.3% total.
  (The trigger column of this is weak evidence — see feature-age caveat; the
  hour/daytype/markov flatness is full-span and solid.) There is nothing to
  retune → **2.8 demoted to last**; revisit only if the in-app backtester on
  richer data shows spread.
- **2.3 burst/fast kernel (both forms): FLAT** (W_FAST 0.5/1/2 within ±0.3pp;
  dual-kernel slightly worse). Not worth a release on current evidence.
- **2.4 session conditioning: NEGATIVE** — in the roadmap form AND in a fairer
  markov-×2-in-session-only form (win 3 / loss 10–11 paired). Falcon's finding
  does not transfer to this usage. Demote below 2.8; needs new evidence.
- **2.5 trigger lift: INSUFFICIENT DATA** (flat on this replay, but only 10.6%
  of rows carry any ctx and most trigger types are days old). Its premise is
  CONFIRMED: started_moving fired 19× in its first ~23h (~20/day) — precedence
  1.2 and an evidence cap will matter once this data accumulates. Re-test after
  a few weeks of v1.13+ history.
- **2.11 cadence, 2.12 hour-lift, 2.6 diverse trio: FLAT** (2.6 tested in three
  slot layouts: markov slot, hour slot — all z≈0; the motivating headset scenario
  occurred once in 27 days).
- **Just-used crush = the largest measurable lever.** 6.0% of evaluated launches
  are the crushed app relaunched inside the window. Fully lifting the crush:
  hit@3 44.3→45.6, hit@12 75.4→80.0 (win 45 / loss 29, z=+1.86 — borderline,
  but the only effect that approaches significance).
  **1.6 as specced does NOT work though:** (b) never fires — max observed
  rebound rate is 0.21 (Maps 0.18, UpUpUp 0.21) vs the 0.3 threshold — and the
  0.4-instead-of-0.05 remedy barely acts: factors 0.2/0.4 leave the TRIO
  identical to 0.05 (a crushed heavy app needs ~full score to re-enter the top
  3; 0.4 only lifts hit@12, i.e. paging depth). → Amend 1.6: drop (b), keep the
  reactive variant (a) with a FULL lift, not a softer factor.
- **Corrected-trio feedback: one event so far** — but the rework was hours old
  at export, so this measures novelty, not adoption. Still, a deliberate
  swipe-then-correct will always be lower-volume than typing past the trio →
  **2.2 (search-past-trio soft misses) stays promoted to the top of Tier 2**
  (still needs 2.1); judge the swipe-correction volume again in a few weeks.
- **1.1 premise CONFIRMED directionally:** bt_connected pools devices — YMusic
  lift 8× (earbuds/car) vs Eufy cam 3.7× vs WhatsApp 0.6× under the same token.
  Identity hashing splits 102 rows across devices, so expect sparsity at first.
- **NEW FINDING — signal health is invisible:** the export can't distinguish
  "signal too new" from "signal silently degraded". wifi:<hash> had shipped
  hours before the export (zero rows is expected), but NOTHING would surface it
  if ACCESS_FINE_LOCATION stays ungranted and every join falls back to generic
  wifi_joined forever; headset_plugged managed 1 row in 27 days without anyone
  noticing. → Add to Tier 1: an Insights "signal health" block (per signal:
  rows collected, first/last seen, and why it's degraded — permission missing,
  pref off). Effort S. Then check the wifi hash actually appears within days.
- Learning curve is non-monotonic (per-quartile hit@3: 38.8 / 45.0 / 49.8 /
  43.6%) — usage drifts; long-memory features would chase stale patterns.

Caveats: one user, 27 days, notification bonus excluded (real-life trio likely
does better on messengers than the replay shows), activeEvent at prediction time
approximated by the launch row's own ctx stamp (slightly flatters trigger terms).
None of this kills Tier 0/1 engineering items — it re-prices the Tier 2 bets.

Revised order: Tier 0 → Tier 1 (1.1–1.4, 1.7, amended 1.6a, + signal-health
Insights) → 2.1 → 2.2 → E.2/E.1 → re-run backtest on richer data before
touching 2.3/2.4/2.5/2.6/2.11/2.12 → 2.7 → 2.8.

---

## TIER 0 — MEASUREMENT (ship first; gatekeeper for everything else)

**STATUS: SHIPPED in v1.14.0** — `predict/ScoreMath.kt` (shared pure formula,
unit-tested), `predict/Backtester.kt` (walk-forward replay + LRU/MFU baselines,
auto-runs at the bottom of Insights), `predict/Calibration.kt` (global trio-hit
EMA β=0.02 + 5-bin top-slot reliability table, recorded in MainActivity.launchApp,
shown in Insights; observability only, MIN_CONFIDENCE untouched), and
`tools/backtest_brain.py` for the analyst side.

### 0.1 Walk-forward backtester — impact HIGH, effort M
- New `predict/Backtester.kt`, pure Kotlin, unit-testable, no Android deps.
- Replay usage.db chronologically: skip warmup (~first 20% of rows); for each launch
  row, compute the ranking using only earlier rows; record the launched app's rank.
  Metrics: hit@1, hit@3 (trio), hit@12 (cycle depth), MRR.
- O(n) via incremental state: per-app decayed accumulators (`acc *= exp(-dt/τ); acc += w`),
  Markov counts in a HashMap, 24-bin hour arrays, miss rows applied as they appear.
- Baselines in the same pass: pure-LRU and pure-MFU (published work always compares
  against these because they are embarrassingly strong).
- **Amendments (mandatory):** extract the per-row scoring math into ONE shared pure
  function used by both `PredictionEngine.score()` and the backtester + a golden
  parity unit test (incremental state at T must reproduce score() over the same
  prefix). Notification bonus and user boost cannot be replayed (no historical
  snapshots) — zero them and label them excluded in the report. Variant grids share
  state only for mixing-weight variants; decay-τ/kernel variants need own accumulators.
- Surface: Insights button + `tools/backtest_brain.py` runs the analyst's version
  against an exported brain JSON (baselines, ablations, paired z-tests, crush
  sweep, rebound rates, trigger lifts, signal-health table).

### 0.2 Hit-rate observability / calibration — impact HIGH, effort S
- Reliability table in Prefs (~5 coarse bins by predicted top-app share, EMA β=0.02)
  + one global EMA: "the trio contained the launched app N% of the time".
- Show in Insights. This is the acceptance metric for every idea below.
- **Amendment:** ship as pure observability. Do NOT touch MIN_CONFIDENCE yet — top-1
  share conflates dominance with evidence on near-zero data; revisit the gate only
  after the table has mass.

---

## TIER 1 — PRECISION WINS ON EXISTING SIGNALS (all S, ship as one release)

### 1.1 Bluetooth identity hash — impact HIGH, effort S
- bt_connected is the strongest trigger (×3.5) but pools car/earbuds/watch into one
  bucket, actively polluting predictions.
- On ACTION_ACL_CONNECTED read BluetoothDevice.EXTRA_DEVICE → device.name; record
  `bt:<sha256Prefix(ssidSalt + name)>` (reuse existing salt + sha256Prefix), gated
  behind a pref like ssid_signal; fall back to plain bt_connected when name null or
  pref off. BLUETOOTH_CONNECT already granted, covers the read.
- **Amendment:** do NOT build a class taxonomy (bt_car/bt_audio) — BluetoothClass is
  unreliable (head units report HANDSFREE, some cars UNCATEGORIZED). Identity only.
- If a prefix fallback compare ("bt*" at half weight for legacy rows) is added, it
  MUST be applied in BOTH the launch and miss scoring loops.

### 1.2 Trigger-slot precedence — effort S (bug fix)
- The single context slot gets clobbered: charger plug right after car-BT overwrites
  the better signal; started_moving (re-arms every 10 min) stomps everything.
- Rule: within the 5-min window, a rare/specific event (bt:*, wifi:*, headset_plugged,
  alarm_gone) is NOT overwritten by a common one (power_*, started_moving,
  long_idle_end, dnd_*). Implement as a specificity rank in recordEvent.

### 1.3 Disconnect edges — impact MEDIUM, effort S
- `bt_gone` on ACTION_ACL_DISCONNECTED (+ identity hash when enabled) and
  `headset_out` on ACTION_HEADSET_PLUG state==0. Captures commute/workout-end.
- (Replaces a killed media-session-ended idea whose API premise was wrong:
  session-changed listeners don't fire on playback-state transitions.)

### 1.4 Wake-up + long-idle triggers — impact MEDIUM, effort S
- `alarm_gone`: receiver for AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED (API 21+,
  no permission). Cache getNextAlarmClock()?.triggerTime; on broadcast, if the cached
  time ∈ [now−15min, now+30s] the alarm just fired → recordEvent. Else just refresh
  the cache (user edited the alarm).
- `long_idle_end`: runtime-register ACTION_SCREEN_OFF + ACTION_USER_PRESENT; on
  USER_PRESENT with gap ≥ 4h since screen-off → recordEvent.
- **Amendments:** do NOT add a 45-min "idle_end" tier (fires constantly, clobbers
  better triggers). Precedence (1.2) must keep long_idle_end from overwriting a
  fresh alarm_gone on alarm mornings.

### 1.5 Hour kernel + graded day-type (cliff fix) — impact MEDIUM, effort S
- A 07:55 habit currently contributes ZERO at 09:05 (circularHourDiff ≤ 1 cliff).
- Drop-in: `s += W_HOUR · w · K(dist)` with K = 1.0 / 0.6 / 0.25 for 0/1/2h, 0 beyond;
  day-type becomes graded D (1.0 same weekday, 0.5 same day-type, 0.2 otherwise) as
  `s += W_DAY_TYPE · w · D`.
- **Amendment:** NO normalization, NO multiplicative K·D coupling (changes the term's
  scale class and silently re-weights the whole blend). Mirror in the miss loop.
  Let the backtester decide if per-weekday granularity earns its noise (~8 samples
  per app-weekday on this history).

### 1.6 Adaptive just-used crush — impact HIGH, effort M
- JUST_USED_FACTOR=0.05 for 45 min is the engine's largest deliberate error for
  check-again apps (messengers while cooking): it hides the most-wanted app.
- (a) Reactive: if the user relaunches the crushed app via search/drawer (src≠TRIO,
  pkg==lastLaunchedPkg) twice within the window → lift the crush for the remainder.
- (b) Learned: during the existing ORDER BY ts scan, compute per-app reboundRate =
  fraction of launches followed by same-app relaunch within 45 min. reboundRate>0.3
  → JUST_USED_FACTOR 0.4 instead of 0.05. No new storage; Insights shows the rate.
- **Amendments:** require n ≥ 10 launches before departing from the default (2
  launches with 1 rebound = noise). Ignore same-pkg gaps < 5s in the scan —
  logTrioCorrection's reinforcement row shares the real row's timestamp and would
  read as a 0-minute rebound.

### 1.7 DB hygiene on uninstall — effort S (pure win)
- LauncherApp's package receiver (already has the "package" scheme + EXTRA_REPLACING
  guard): add ACTION_PACKAGE_FULLY_REMOVED → on predict executor DELETE FROM
  launches/misses WHERE pkg=?, remove from boosted/favorites/parked.
- Because the process can be dead during an uninstall: also sweep rows whose pkg is
  absent from the repo catalog during the existing prune().
- Dead rows currently squat in the 5000-row budget ~60 days and haunt Insights.

### 1.8 Passengers (low impact, near-zero cost — optional)
- Charging-kind split: on POWER_CONNECTED read sticky EXTRA_PLUGGED →
  power_ac/power_usb/power_wireless. If it reads 0/unknown, delayed ~500ms re-read
  before falling back to generic. NO ACTION_DOCK_EVENT (defunct). The split really
  distinguishes wireless pad vs cable vs computer-USB (car chargers report AC too).
- DND: NotifListener.onInterruptionFilterChanged → dnd_on/dnd_off (debounced). Skip
  ringer-mode entirely (Pixel: flip-to-shhh is DND, ringer flips ≈ never). Rank
  dnd_off below alarm_gone/long_idle_end in precedence. Remove later if rows stay
  scheduled-bedtime-only.
- cal_soon: NO schema change — at logLaunch, if activeEvent()==null and the cached
  next non-all-day event begins within [now−5min, now+15min], stamp ctxEvent="cal_soon"
  (CalendarFeed's resume cache already has the data). Note: silently absent when the
  agenda feature is off.
- Weekday rhythm (kept clean by critic, impact LOW): per-app DoubleArray(7) decayed
  dow histogram; add-only bonus `W_DOW_EXACT · max(0, 7·P(dowNow|app) − 1)` with
  Laplace α=1; keep the weekend/weekday term at half weight as fallback.

---

## TIER 2 — STRUCTURAL (one per release, each gated on a backtest win)

### 2.1 Launch-source + row-weight columns (foundation) — impact MEDIUM, effort M
- DB v3: `ALTER TABLE launches ADD COLUMN src TEXT` + `ADD COLUMN weight REAL NOT
  NULL DEFAULT 1.0` (weight on misses too). All launch paths already funnel through
  MainActivity.launchApp (~13 call sites): add a source enum param
  (TRIO_0/1/2, RESULTS, DRAWER, PARK, TICKER, SPOTLIGHT, WIDGET).
- Scoring: per-row contribution × r.weight × srcW(r.src); named constants (e.g.
  SRC_TRIO≈0.85 down-weights convenience clicks, SRC_RESULTS≈1.2 up-weights earned
  clicks). Insights: per-source histogram.
- **Caveat:** srcW multipliers interact with the absolute MIN_CONFIDENCE=1.0 — re-check
  the threshold (or re-normalize) so borderline habits don't fall to fillers.
- 2.2 and 2.6 depend on this.

### 2.2 Search-past-the-trio = soft miss — impact MEDIUM, effort S (needs 2.1)
- Highest-volume correction signal, currently thrown away: every typed launch of an
  app the trio didn't show is a prediction failure.
- Hook: launchApp with src==RESULTS, query length ≥ 2, launched pkg ∉ displayed trio,
  home resumed < 60s. Insert miss rows for the 3 shown apps with weight ≈ 0.2–0.25
  (NOT 0.5 — the critic showed 0.75 effective negative per search causes rank
  oscillation: a correct #1 tapped 3× and searched-past 5×/day nets negative).
- Rate-limit: per-pkg, one soft miss per ~3h (in-memory map on predict executor).
- No extra reinforcement row (typing is weaker evidence than swipe-then-launch).
- Protect swipe-corrections (weight 1.0) from prune churn: separate prune budgets or
  prune soft rows first.
- This ABSORBS the "shown-but-ignored" idea — do not also implement that.

### 2.3 Burst recency term (Hawkes-lite) — impact HIGH, effort S
- Usage is self-exciting: "opened 3× this morning" predicts a 4th; a single 20-day
  kernel can't express it.
- **Correct form (critic):** add fast kernel as a SEPARATE ADDITIVE BASE TERM ONLY:
  `s_base += W_FAST · exp(-age/6h)` — all hour/day/transition/context bonuses keep
  riding the 20-day kernel. Naive "replace the kernel" makes a context-matching
  launch from 1h ago contribute ~17 (pure LRU-twitch).
- Start W_FAST ≈ 0.5–1.0 (not 2.0). Ship only with backtest hit@3 win over BOTH the
  current engine AND pure-LRU; check trio churn against the 45-min crush behavior.
- Alternative blend (from another lens, pick ONE after backtesting):
  per-row `w' = 0.45·exp(-age/5d) + 0.55·exp(-age/30d)` used EVERYWHERE w is used
  (incl. miss loop), normalized so median top-10 score is unchanged (protects
  MIN_CONFIDENCE). Fixes both "forgets a 2-year habit after a 2-week holiday" and
  "keeps recommending a game abandoned 3 weeks ago".

### 2.4 Session conditioning (Falcon's trigger/follower split) — impact MEDIUM, effort S
- Published finding: first app of a session is driven by time/context; followers are
  driven by the previous app (~2× more predictive in-session, near-noise across
  session boundaries). Current W_TRANSITION=2.5 applies unconditionally with a prev
  that persists overnight in Prefs.
- Two multiplier sets: session-start = Markov ×0.5, temporal slightly up; in-session
  = Markov ×2.0, temporal ×0.5. Tuned via backtester.
- **Amendment:** define the boundary by SCREEN STATE, not launch-timestamp arithmetic
  (an app used > 180s would misclassify its follow-up as session-start). Runtime
  ACTION_SCREEN_OFF receiver (shares 1.4's registration): first home-resume after
  screen-off = session start. Backtest with ts-gap tagging labeled approximate.
- Also unwinds the "prev pollution" that killed the order-2 Markov idea — reconsider
  order-2 (count interpolation with Katz backoff λ=n2/(n2+3), NO normalized
  probabilities — scale bug) only after this ships and only with backtest lift.

### 2.5 Trigger lift with evidence cap — impact MEDIUM, effort M
- Problem is real mainly for started_moving (fires all day): flat 3.5× sprays bonus.
- `bonus = W_TRIGGER · min(N_ae_decayed, CAP) · ln(max(1, lift))` where
  lift = ((N_ae+α)/(N_a+αK)) / ((N_e+α)/(N+αK)), α=0.5, K=#events. The evidence cap
  is mandatory — unscaled ln(lift) gives a single co-occurrence bonus ≈ 8.
- Mirror in the miss loop. NO multi-slot schema (precedence 1.2 covers clobbering).

### 2.6 Rationale-diverse trio — impact MEDIUM, effort S (critic: clean keep)
- After headset-plug all three slots go podcast-flavored (W_TRIGGER dominates); one
  wrong hypothesis burns the whole trio.
- Pure re-rank of the computed score map: slot A = argmax habitScore (decay+temporal
  terms), slot B = argmax situationScore (markov+context+notif) excluding A — only
  when a situational signal actually fired — slot C = argmax blended. 12-pool stays
  blended for paging. Insights labels each slot's rationale.
- Directly A/B-able in the backtester (one-line variant).

### 2.7 Notification responsiveness — impact MEDIUM, effort M
- Promo-spam app and SMS get identical +0.35/notification today.
- Per-pkg EWMA responsiveness (Prefs JSON map): use dismissal REASON CODES from
  NotificationListenerService.onNotificationRemoved (API 26+ has reason int):
  REASON_CLICK → up; REASON_CANCEL / REASON_CANCEL_ALL (user swipe) → down; ALL other
  reasons → no signal (app self-cancel, read-on-desktop etc. would otherwise punish
  well-behaved messengers). Plus launch-while-counts>0 → up.
- Score: `W_NOTIFICATION · min(count,CAP) · (0.4 + 1.1·resp)`, resp starts 0.55.
- Flush map from MainActivity.onPause + onListenerDisconnected.

### 2.8 Offline weight retune — impact MEDIUM, effort M (needs 0.1)
- Insights button "retune from my history": walk-forward grid/coordinate-descent over
  the named weights (clamped [0.25×, 4×] of defaults), show before/after hit@1/hit@3
  and old-vs-new weights, one-tap revert. Persist as floats in Prefs.
- Online SGD was KILLED: the (shown, not-launched) labels mostly don't exist
  (corrections are rare), feature scales differ wildly (gradient slams into clamps),
  and it trains on the model's own ranking (selection-bias loop). Offline only.

### 2.9 Accidental-launch unlearning — impact LOW, effort S (needs 2.1's weight col)
- Fast return alone is NOT a mis-tap (quick-check apps live in <10s sessions; the
  code's own MISTAKE_WINDOW comment reads fast-exit as accidental EXIT).
- Classifier: fast return (<10s) FOLLOWED by launching a DIFFERENT app within ~30s →
  `UPDATE launches SET weight=0.5 WHERE id=?` (insertLaunch returns rowid; keep it
  executor-confined; guard against the reinforcement insert overwriting it).
- Unconditionally safe part: unwind the Markov chain on <10s returns (restore
  prefs.lastLaunchedPkg to the pre-mistap value via a prevPrev field).

### 2.10 Latency buckets — measurement only, effort S (rides 2.1's migration)
- INTEGER latency_bucket on launches (0: <1.5s reflex, 1: 1.5–6s, 2: >6s) from
  homeShownTs. Insights "hesitation map" (median bucket per hour / per ctxEvent).
- Do NOT weight by it (the home screen is deliberately information-rich; >6s often
  means "read the agenda first"). Revisit only if the map shows a stable pattern.

### 2.11 Cadence/hazard term ("this app is due") — impact MEDIUM, effort M
- Periodicity is orthogonal to every existing term: "opened every ~24h, last opened
  23h ago". One pass over ts-ordered rows: per-app inter-launch intervals into 12
  log2 buckets (decayed); bonus = W_CADENCE · concentration · recencySum when elapsed
  falls in the modal bucket ±1.
- **Amendments:** only award when the modal bucket ≥ ~2h (else it stacks a "due" bonus
  at minute 46, right as the crush expires — rich-get-richer); floors: ≥5 intervals
  AND concentration ≥ 0.4; the ×0.7 "too soon" damp applies to history-derived score
  only, never to notification/boost terms.
- (A Gaussian inter-arrival variant was killed as noise-wearing-a-formula; this
  bucketed version with floors is the survivable form. Backtest before shipping.)

### 2.12 Hour-histogram lift (ambitious version of 1.5) — impact MEDIUM, effort S
- Relative normalization: per-app 24-bin decayed histogram, triangular kernel around
  hourNow, `lift = (kernelMass+α)/(total+24α)·24`, bonus =
  `W_HOUR · recencySum · clamp01((lift−1)/(CAP−1))`, CAP≈4, α≈0.5.
- Fixes always-on apps collecting hour bonuses at every hour (relative, not absolute,
  concentration). MUST mirror in miss mass; re-validate MIN_CONFIDENCE (score scale
  shifts). Gate on 0.2's calibration metric. Ship 1.5 first; this replaces it only
  with a measured win.

---

## EFFICIENCY (the felt wins only — the engine is already fast)

Measured reality: full scan ≈ single-digit ms on a background thread, < 1s CPU/day.
KILLED as solving nonexistent costs: per-app lazy aggregates, day-bucket rollup
compaction (prev_pkg cardinality destroys the compression), cursor streaming + exp
LUT, WAL switch (Android P+ already applies compatibility WAL; single executor means
no reader/writer concurrency to unlock). Only transaction-wrapping logTrioCorrection's
4 inserts + prune's 2 DELETEs survives as trivial hygiene.

### E.1 Persisted last-ranking snapshot — impact MEDIUM, effort S
- After each computeSuggestions, persist {ranked 12 (pkg,label) pairs, ts, contextKey}
  to Prefs (~1KB). On cold start bind the trio at frame one from it, then
  stale-while-revalidate when the live ranking lands.
- **Amendments:** store (pkg,label) pairs — AppRepository's catalog is EMPTY at cold
  start (that's the real critical path, not the DB scan); load the 3 icons directly
  via PackageManager.getActivityIcon on the repo executor; validate against
  getLaunchIntentForPackage (not the unloaded catalog); filter hiddenApps from prefs.

### E.2 Ranking-delta gate — impact MEDIUM, effort S
- Every resume re-runs applySuggestions(animate=true): 3 rebinds + 220ms staggered
  animation even when the trio is byte-identical — battery-rule violation in spirit.
- In the computeSuggestions callback: compare new ranked.take(3) (ordered pkgs +
  badge counts) to the displayed page-0 trio; on match update suggestionPool /
  usageBoost silently, skip applySuggestions. (suggestionPage resets to 0 on refresh.)
- Do NOT add score hysteresis/STABILITY_MARGIN — pure decay is ranking-neutral
  (uniform multiplicative factor), so "decay jitter" doesn't exist; slots flip only
  on real input changes.

### E.3 Score memoization — impact LOW, effort S (optional)
- Cache score() keyed on (hourNow, weekend, prevPkg, activeEvent, dbGeneration,
  boost/hidden revision, HASH OF THE COUNTS MAP — not notification event counters,
  or messenger churn kills the hit rate). Executor-confined plain field. Surface the
  measured hit rate in Insights rather than assuming it.

---

## KILLED (do not resurrect without new evidence)

- Travel edges (airplane/timezone): exp(-age/20d) decay ⇒ a trip 60d ago contributes
  ~5%; a few-flights-a-year signal structurally never has live history.
- Solar day-phase: decay's ~3-week effective memory already tracks seasonal drift
  (~30-40min/3wk, inside the hour window); ph_day/ph_night span ~12h ⇒ double-counting.
- Media-session-ended: sessions-changed listener doesn't fire on playback-state
  transitions; replaced by 1.3 disconnect edges.
- Second-order trigram (as first proposed): per-row conjunction of hour+transition
  already disambiguates most of it; exact (prev2,prev1) matches too rare. Revisit as
  2.4's follow-up only.
- Exploration slot: premise factually wrong — being suggested earns punishment or
  nothing in this engine (reinforcement goes to the launched app, which was outside
  the trio); no rich-get-richer impression loop exists.
- Dwell-time weighting: wrong objective (predict opens, not time-in-app); dwell proxy
  measurement broken (recents switches, screen-off pockets).
- Slot-CTR inverse-propensity weighting: invalid without randomizing the trio;
  position and relevance perfectly confounded in a deterministic ranker.
- Shown-but-ignored passive misses: absorbed into 2.2 (drawer-sourced variant is
  noise: drawer use is habitual alternate-path access, not a correction).
- Online SGD weight learning: see 2.8.
- 45-min idle_end tier, ringer-mode receiver, ACTION_DOCK_EVENT, bt class taxonomy,
  ctxState multi-slot column (until a 2nd genuine state signal exists).

## RECOMMENDED ORDER

1. Tier 0 (backtester + hit-rate) — everything else becomes provable.
2. Tier 1 as one release (1.1–1.7 (+1.8 passengers)).
3. Tier 2 one idea per release, each gated on a backtest win, in rough order:
   2.1 → 2.2 → E.2/E.1 → 2.3 → 2.4 → 2.6 → 2.5 → 2.7 → 2.8 → rest.
4. The owner's exported brain JSON (Settings → Suggestions → Export) can pre-validate
   2.3/2.4/2.5/2.11 offline before building.

Constraints reminder for the next session: no third-party deps, battery is a feature
(push-based only, no polling/sensors), APK ≈ at the 2 MB ceiling (do a size audit
before large additions), keep everything explainable in Insights, strings EN+FR,
every change ships (commit → bump → release per CLAUDE.md recipe).
