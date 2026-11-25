# E2E Test Infrastructure & 4-Tier Specification (`TEST_INFRA.md`)

**Project:** Local-First Android Music Tracker (`com.musictracker.app`)  
**Testing Methodology:** Opaque-Box, Requirement-Driven 4-Tier E2E Architecture  
**Authoritative Sources:** `ORIGINAL_REQUEST.md`, `PROJECT.md` (Features F00–F26, Interface Contracts, Room Schema, `TestTags`)

---

## 1. Execution Environment & Harness Architecture

Because this Linux VM has **no Android emulator, no `/dev/kvm`, and 0 attached ADB devices**, **100% of E2E tests execute deterministically on the JVM** via Gradle + JUnit 4 + Kotlin Coroutines Test + Robolectric (`@Config(sdk = [34])`, `isIncludeAndroidResources = true`) + Jetpack Compose UI Testing (`createComposeRule()`).

### Canonical E2E Execution Command
```bash
./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.*"
```

### Target-Scoped Tier Commands
- Tier 1 (Feature Coverage): `./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.tier1.*"`
- Tier 2 (Boundary & Corner Cases): `./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.tier2.*"`
- Tier 3 (Cross-Feature Combinations): `./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.tier3.*"`
- Tier 4 (Real-World Application Scenarios): `./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.tier4.*"`

### Directory Layout (`app/src/test/java/com/musictracker/app/e2e/`)
```text
app/src/test/java/com/musictracker/app/e2e/
├── harness/
│   ├── FakeTrackerClock.kt                  # Controllable monotonic elapsedRealtimeMs + wall-clock epoch
│   ├── E2eTestEnvironment.kt                # Wires real in-memory Room DB, real DataStore (temp file), real Normalizer, real PlaySessionStateMachine, real ListeningRepository, real DataExporter
│   ├── FakeMediaControllerDriver.kt         # Drives MediaSessionCoordinator / NotificationListenerService with synthetic controller lists, metadata, and playback states
│   └── ComposeE2eHarness.kt                 # Sets up createComposeRule() with MusicTrackerTheme, LocalLiquidGlassAccessibility, LocalActivityResultRegistryOwner (SAF fake)
├── tier1/
│   ├── T1MetadataNormalizationE2eTest.kt    # Area A: F16, F17, F18 (pure JVM)
│   ├── T1PlaySessionStateMachineE2eTest.kt  # Area B: F09, F10, F11, F12, F13, F14, F15 (pure JVM)
│   ├── T1DatabaseAndExportE2eTest.kt        # Area C: F07, F18, F19, F20, F21, F22 (Robolectric + in-memory Room)
│   ├── T1TrackingEngineE2eTest.kt           # Area D: F01, F02, F03, F04, F05, F06, F08 (Robolectric Service/Shadow)
│   └── T1LiquidGlassUiE2eTest.kt            # Area E: F23, F24, F25, F26 (Robolectric + createComposeRule)
├── tier2/
│   ├── T2NormalizationBoundariesE2eTest.kt  # Area A boundary/error/negative tests
│   ├── T2StateMachineBoundariesE2eTest.kt   # Area B boundary/error/negative tests
│   ├── T2DatabaseExportBoundariesE2eTest.kt # Area C boundary/error/negative tests
│   ├── T2TrackingBoundariesE2eTest.kt       # Area D boundary/error/negative tests
│   └── T2UiAccessibilityBoundariesE2eTest.kt# Area E boundary/error/negative tests
├── tier3/
│   ├── T3PipelineToStatsExportE2eTest.kt    # Normalizer + StateMachine + Room + Stats + JSON/SQLite Export
│   ├── T3AllowlistToLiveUiE2eTest.kt        # Allowlist toggle + MediaSessionCoordinator + NowTracking + Home/Recent
│   ├── T3MultiAppDedupAndRenormE2eTest.kt   # Multi-app YT Music/Spotify dedup + Re-normalization + Stats
│   └── T3PermissionsNavAndGlassE2eTest.kt   # Permission lifecycle + Navigation state + Accessibility modes
└── tier4/
    ├── T4Scenario1YouTubeMusicAlbumTest.kt  # Real-world YT Music album session with scrubbing, late duration & repeat-one
    ├── T4Scenario2MultiAppSwitchingTest.kt  # Real-world Spotify + YT Music + ignored YouTube video + live allowlist toggle
    ├── T4Scenario3CommuteStatsExportTest.kt # Real-world multi-day commute, skips, Stats timeframes & JSON/SQLite round-trip
    ├── T4Scenario4OnboardingAndA11yTest.kt  # Real-world fresh install permission grant, battery exemption & Liquid Glass a11y
    └── T4Scenario5ResilienceAndRenormTest.kt# Real-world service disconnect flush, idle timeout & transactional re-normalization
```

### Opaque-Box Discipline
- Tests interact **exclusively** with the public contracts defined in `PROJECT.md`:
  - `TrackerClock`, `MetadataNormalizer`, `PlaySessionEngine`, `NowTrackingState`, `CompletedListenSession`
  - `ListeningRepository`, `AllowlistRepository`, `DataExporter`, `MusicTrackerDatabase` (in-memory & file-backed temp DB for SQLite backup verification)
  - `MusicNotificationListenerService`, `MediaSessionCoordinator`, `SystemPermissionHelper`
  - Compose UI via `TestTags` (`NAV_*`, `HOME_*`, `STATS_*`, `SETTINGS_*`), `LocalLiquidGlassAccessibility`, and `LocalActivityResultRegistryOwner`
- Tests NEVER assert on private implementation fields or internal helper methods.
- Progressive Testability: each test uses the simplest channel that genuinely exercises the contract (pure JVM for domain, Robolectric in-memory Room for persistence, Robolectric Shadows for Android services/intents, Robolectric Compose Rule for UI).

---

## 2. Feature Coverage Matrix (F00–F26)

| Feature | Description | Area | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---------|-------------|------|--------|--------|--------|--------|
| F00 | Project Scaffold & Skill Sync | Build/All | Implicit (all suites compile & run) | — | — | — |
| F01 | Notification Access Detection | D | T1-D1 | T2-D1 | T3-4 | S4 |
| F02 | Notification Access Settings Deep-Link | D | T1-D2 | T2-D2 | T3-4 | S4 |
| F03 | Battery Optimization Status & Exemption | D | T1-D3 | T2-D3 | T3-4 | S4 |
| F04 | Persistent `NotificationListenerService` Lifecycle | D | T1-D4 | T2-D4 | T3-2 | S5 |
| F05 | Active Media Session Discovery | D | T1-D5 | T2-D5 | T3-2 | S2 |
| F06 | Multi-Session Arbitration | D | T1-D6 | T2-D6 | T3-2, T3-3 | S2 |
| F07 | Default Music App Allowlist | C | T1-C1 | T2-C1 | T3-2 | S2 |
| F08 | Installed Apps Discovery & Toggle | D | T1-D7 | T2-D7 | T3-2 | S2 |
| F09 | Wall-Clock Play Time Accumulation | B | T1-B1 | T2-B1 | T3-1, T3-2 | S1, S3 |
| F10 | Session Debouncing (~1.5s) | B | T1-B2 | T2-B2 | T3-1 | S1, S3 |
| F11 | Late-Arriving Metadata / Duration Patching | B | T1-B3 | T2-B3 | T3-1 | S1 |
| F12 | Single-Track Loop Detection | B | T1-B4 | T2-B4 | T3-1 | S1 |
| F13 | Scrubbing / Seeking Handling | B | T1-B5 | T2-B5 | T3-1 | S1 |
| F14 | Completion Ratio & Skip Classification | B | T1-B6 | T2-B6 | T3-1 | S3, S5 |
| F15 | Live "Now Tracking" State Emission | B | T1-B7 | T2-B7 | T3-2 | S1, S2 |
| F16 | Artist & Title Noise Stripping | A | T1-A1, T1-A2 | T2-A1, T2-A2 | T3-1, T3-3 | S1, S2 |
| F17 | `"Artist - Title"` Unpacking | A | T1-A3, T1-A4 | T2-A3, T2-A4 | T3-3 | S2 |
| F18 | Raw Metadata Preservation & Re-Normalization | A, C | T1-A5, T1-C2 | T2-A5, T2-C2 | T3-3 | S5 |
| F19 | Room Schema (`tracks` & `listen_events`) | C | T1-C3 | T2-C3 | T3-1, T3-3 | S1–S5 |
| F20 | Home & Stats Reactive Queries | C | T1-C4, T1-C5 | T2-C4 | T3-1, T3-3 | S1, S3 |
| F21 | JSON History Export (SAF) | C, E | T1-C6 | T2-C5 | T3-1 | S3 |
| F22 | Raw SQLite DB Backup (SAF) | C, E | T1-C7 | T2-C6 | T3-1 | S3 |
| F23 | Liquid Glass Design System (Compose + M3) | E | T1-E1, T1-E2 | T2-E1, T2-E2, T2-E3 | T3-4 | S4 |
| F24 | Home / Dashboard Screen | E | T1-E3, T1-E4 | T2-E4 | T3-2, T3-4 | S1, S4 |
| F25 | Stats / Insights Screen | E | T1-E5 | T2-E5 | T3-1, T3-4 | S3 |
| F26 | Settings Screen | E | T1-E6, T1-E7 | T2-E6, T2-E7 | T3-2, T3-4 | S2, S3, S4 |

---

## 3. Tier 1 — Feature Coverage (Isolated Happy-Path, >= 5 per Area)

### Area A: Metadata Normalization (`T1MetadataNormalizationE2eTest` — Pure JVM)
- **T1-A1 (F16 — Artist Topic Suffix):** `"Radiohead - Topic"` → normalizedArtist `"Radiohead"`.
- **T1-A2 (F16 — Title Noise Tags):** `"Creep (Official Music Video) [Lyrics] (Explicit)"` → normalizedTitle `"Creep"`; featured artist `"Song (feat. Daft Punk) (Official Audio)"` preserves `"(feat. Daft Punk)"`.
- **T1-A3 (F17 — Unpack Empty Artist):** `rawArtist = ""`, `rawTitle = "Daft Punk - Get Lucky (Official Audio)"` → artist `"Daft Punk"`, title `"Get Lucky"`.
- **T1-A4 (F17 — Unpack Generic Artist & Unicode Dashes):** `rawArtist = "Unknown Artist"`, `rawTitle = "Tame Impala – Borderline"` → artist `"Tame Impala"`, title `"Borderline"`.
- **T1-A5 (F18 — Cross-App Canonical `dedupKey`):** YT Music (`"The Weeknd - Topic"`, `"Blinding Lights (Official Audio)"`) and Spotify (`"The Weeknd"`, `"Blinding Lights"`) yield identical `dedupKey`.

### Area B: Play-Session State Machine (`T1PlaySessionStateMachineE2eTest` — Pure JVM)
- **T1-B1 (F09 — Wall-Clock Accumulation with Pause/Resume):** Play 20s → Pause 60s → Play 15s → Track Change → persisted `actualListenedMs == 35_000L` (paused 60s excluded).
- **T1-B2 (F10 — Debounce Promotion at 1.5s):** Continuous `STATE_PLAYING` for 2_000ms promotes `PENDING` → `ACTIVE` (`isDebouncing == false`) and persists on finalization.
- **T1-B3 (F11 — Late-Arriving Duration & Album Patch):** Start playing with `durationMs = 0`, `album = ""`; patch at +900ms with `durationMs = 210_000L`, `album = "Discovery"`; verify `startedAtEpoch` and `actualListenedMs` are preserved and finalized session carries `durationMs = 210_000L`.
- **T1-B4 (F12 — Single-Track Loop Detection):** 200s track plays to position 196s (`>= dur - 5s`), then position drops to 300ms while `STATE_PLAYING` → emits 1 completed session (`wasSkipped == false`) and immediately starts a new session at 0ms accumulated.
- **T1-B5 (F13 — Scrubbing / Seeking Wall-Clock Accuracy):** 200s track plays 40s, user scrubs back from 40s to 10s and plays another 50s → single session (no split), `actualListenedMs == 90_000L`.
- **T1-B6 (F14 — Completion Ratio & Skip Classification):** 200s track played 180s → `completedRatio == 0.90f`, `wasSkipped == false`; 200s track played 30s → `completedRatio == 0.15f`, `wasSkipped == true`.
- **T1-B7 (F15 — Live `NowTrackingState` Emission):** Playing emits non-null `NowTrackingState(isPlaying = true, segmentStartElapsedMs != null)`; pause sets `isPlaying = false, segmentStartElapsedMs = null` with frozen `accumulatedMs`; stop/session end emits `null`.

### Area C: Room Database, Allowlist & Data Export (`T1DatabaseAndExportE2eTest` — Robolectric + In-Memory Room)
- **T1-C1 (F07 — Default Music App Allowlist):** Fresh `AllowlistStore` pre-seeds YT Music (`com.google.android.apps.youtube.music`), Spotify (`com.spotify.music`), and local players; excludes `com.google.android.youtube` and `com.android.chrome`.
- **T1-C2 (F18 — Raw Metadata Preservation & Dedup Upsert):** Recording a session stores both `normalized_*` and verbatim `raw_title`/`raw_artist`; a second listen with the same `dedup_key` reuses the existing `tracks.id`.
- **T1-C3 (F19 — Foreign Key & Cascade Integrity):** Deleting a `TrackEntity` cascades and removes its `ListenEventEntity` rows; `dedup_key` uniqueness is enforced.
- **T1-C4 (F20 — Today's Summary & Recent History Flows):** Recording 3 listens (1 skipped, 2 completed) updates `observeSummary(todayMidnight)` (`totalListenedMs`, `playCount = 3`, `skipRate = 1/3`) and `observeRecentHistory()` in descending `started_at_epoch` order.
- **T1-C5 (F20 — Timeframe Top Tracks & Top Artists Ranked by Actual Time):** Track A (1 play × 300s = 300s) ranks **above** Track B (4 plays × 30s = 120s) in `observeTopTracks` and `observeTopArtists`; timeframe filter (`Today` vs `7D` vs `30D` vs `All Time`) accurately slices by `started_at_epoch`.
- **T1-C6 (F21 — JSON Export):** `exportJson(outputStream)` writes valid UTF-8 JSON containing `schemaVersion`, `tracks`, and `listenEvents` matching every DB row and returns `ExportResult.Success`.
- **T1-C7 (F22 — SQLite Backup Export):** `exportSqliteBackup(outputStream)` checkpoints WAL (`PRAGMA wal_checkpoint(TRUNCATE)`) and writes a valid SQLite header (`"SQLite format 3\u0000"`) readable by `SQLiteDatabase.openDatabase` with all `tracks` and `listen_events` intact.

### Area D: Core Tracking Engine & Permissions (`T1TrackingEngineE2eTest` — Robolectric)
- **T1-D1 (F01 — Notification Access Detection):** Returns `false` when listener is disabled, `true` when component/package is enabled in `ShadowNotificationManager` / `Settings.Secure`.
- **T1-D2 (F02 — Notification Access Deep-Link Intent):** Builds/launches `Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)`.
- **T1-D3 (F03 — Battery Optimization Status & Exemption Intent):** Detects `PowerManager.isIgnoringBatteryOptimizations` and launches `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:<pkg>` URI.
- **T1-D4 (F04 — Service Lifecycle Connect & Disconnect):** `MusicNotificationListenerService.onListenerConnected()` attaches to `MediaSessionManager` and seeds active controllers; `onListenerDisconnected()` unregisters callbacks and flushes active session.
- **T1-D5 (F05 — Active Media Session Discovery & Seeding):** Already-playing allowlisted controller discovered via `getActiveSessions()` immediately seeds metadata + playback state into `PlaySessionEngine`.
- **T1-D6 (F06 — Multi-Session Arbitration):** Allowlisted controller A (`STATE_PLAYING`) is tracked while controller B (`STATE_PAUSED`) is ignored; when B starts `STATE_PLAYING`, A finalizes and B becomes focused.
- **T1-D7 (F08 — Installed Apps Discovery & Toggle):** `SystemPermissionHelper` discovers launchable apps (excluding self) and toggling an app in `AllowlistRepository` persists across reads.

### Area E: Liquid Glass Design System & Compose UI (`T1LiquidGlassUiE2eTest` — Robolectric + `createComposeRule`)
- **T1-E1 (F23 — Concentric Corner Radius Math & Accessibility CompositionLocal):** `concentricCornerRadius(28.dp, 12.dp) == 16.dp`; `LocalLiquidGlassAccessibility` propagates all 3 modes.
- **T1-E2 (F23 — Navigation Capsule & Tab Switching):** `NAV_BAR` renders `NAV_TAB_HOME`, `NAV_TAB_STATS`, `NAV_TAB_SETTINGS`; tapping each tab navigates to the respective screen.
- **T1-E3 (F24 — Home Live "Now Tracking" Card & 1s Ticking Counter):** With active playing session, `HOME_NOW_TRACKING_CARD` shows normalized title, artist, source app, and `HOME_LIVE_COUNTER` advances by +1s when clock advances 1_000ms.
- **T1-E4 (F24 — Home Today's Summary & Recent History):** `HOME_STAT_TIME`, `HOME_STAT_PLAYS`, `HOME_STAT_SKIP_RATE`, and `HOME_RECENT_LIST` render reactive data from `HomeUiState`.
- **T1-E5 (F25 — Stats Timeframe Switcher & Ranked Lists):** Tapping `STATS_TIMEFRAME_TODAY`, `STATS_TIMEFRAME_7D`, `STATS_TIMEFRAME_30D`, `STATS_TIMEFRAME_ALL` updates `STATS_TOP_TRACKS_LIST` and `STATS_TOP_ARTISTS_LIST` ranked by actual time.
- **T1-E6 (F26 — Settings Permissions & Allowlist Search/Toggle):** `SETTINGS_NOTIF_STATUS`, `SETTINGS_BATTERY_STATUS`, `SETTINGS_ALLOWLIST_SEARCH` filtering, and `SETTINGS_ALLOWLIST_SWITCH_<pkg>` toggling work cleanly.
- **T1-E7 (F26 — Settings SAF JSON & SQLite Export Triggers):** Tapping `SETTINGS_EXPORT_JSON_BTN` and `SETTINGS_EXPORT_SQLITE_BTN` with fake `LocalActivityResultRegistryOwner` invokes export callbacks and surfaces `SETTINGS_EXPORT_FEEDBACK`.

---

## 4. Tier 2 — Boundary, Error & Corner Cases (>= 5 per Area)

### Area A: Normalization Boundaries (`T2NormalizationBoundariesE2eTest`)
- **T2-A1 (E13 — All-Noise Title Fallback):** `rawTitle = "(Official Video)"` never normalizes to blank; falls back to trimmed raw title.
- **T2-A2 (E15 — Hyphenated Artist & Title Preservation):** `rawArtist = ""`, `rawTitle = "Jay-Z - 99 Problems"` splits only on spaced `" - "` → artist `"Jay-Z"`, title `"99 Problems"`; `"Spider-Man"` without spaced dash is not split.
- **T2-A3 (E16 — Valid Artist Prevents Unpacking):** `rawArtist = "The Weeknd"`, `rawTitle = "Save Your Tears - Remix"` keeps artist `"The Weeknd"` and does not unpack `"Save Your Tears"` as artist.
- **T2-A4 (Unicode, Diacritics, Emoji & Repeated Whitespace):** Multiple tabs/spaces, stacked noise tags `[Lyrics] (HD) | Official Audio`, and non-ASCII scripts (`"宇多田ヒカル - Topic"`) normalize deterministically without stripping musical titles.
- **T2-A5 (Blank Artist After Failed Unpack):** `rawArtist = "   "`, `rawTitle = "Instrumental"` yields `"Unknown Artist"` and valid non-empty `dedupKey`.

### Area B: State Machine Boundaries (`T2StateMachineBoundariesE2eTest`)
- **T2-B1 (E01 — Sub-Threshold Debounce Discard at 1499ms vs 1500ms):** Track playing 1_499ms then skipped/stopped produces 0 persisted sessions; 1_500ms produces 1 persisted session.
- **T2-B2 (E03 — Blank Title Rejection):** `onPlaybackEvent` with `rawTitle = ""` or `"   "` is ignored and does not overwrite or corrupt an active session.
- **T2-B3 (E05 — Mid-Track Seek-Back Is NOT a Loop):** 200s track at position 90s (45%) seeks back to 0s → no loop split; wall-clock continues accumulating in the same session.
- **T2-B4 (E07 & E08 — Short Interlude Loop & Unknown Duration Loop Guard):** 12s interlude looping 11s → 0s after ≥6s listened triggers loop split; track with `durationMs == 0` jumping to 0s does NOT trigger loop split.
- **T2-B5 (E09 — 30-Minute Idle Pause Timeout & Negative Clock Delta Clamp):** Backward monotonic clock delta clamps to `0L`; pausing ≥ 30 minutes (`1_800_000L`) finalizes the session on next event/tick so resuming starts a fresh session.
- **T2-B6 (E17 & E18 — Unknown Duration Ratio & Replay >100% Ratio):** `durationMs == 0` → `completedRatio == 0f` (never `NaN`/`Infinity`), `wasSkipped = actualListenedMs < 30_000L`; listening 300s of a 200s track via scrub-back → `completedRatio == 1.5f`, `wasSkipped == false`.
- **T2-B7 (Duplicate `STATE_PLAYING` Idempotency):** Repeated `STATE_PLAYING` callbacks do not reset `segmentStartElapsedMs` or double-count elapsed time.

### Area C: Database, Allowlist & Export Boundaries (`T2DatabaseExportBoundariesE2eTest`)
- **T2-C1 (Empty Allowlist Persistence):** User explicitly disables all default apps → `allowlist_initialized` prevents defaults from being silently re-added on reload.
- **T2-C2 (Re-Normalization Collision Merge):** Two tracks initially stored with distinct `dedup_key`s that collide after re-normalization merge into 1 track while 100% of `listen_events` are reassigned to the surviving `track_id` with zero orphaned rows.
- **T2-C3 (Empty Database Zero-Division Guard):** `observeSummary(0L)` on empty DB emits `totalListenedMs = 0L`, `playCount = 0`, `skipRate = 0f` (no `NaN` or SQL NULL crash); `observeTopTracks`/`observeTopArtists` emit empty lists.
- **T2-C4 (Timeframe Boundary Inclusivity):** Event with `started_at_epoch == sinceEpochMs` is included; `started_at_epoch == sinceEpochMs - 1` is excluded.
- **T2-C5 (JSON Export on Empty DB & I/O Error):** Empty DB exports valid JSON with `"tracks": []`, `"listenEvents": []`; failing `OutputStream` returns `ExportResult.Error` cleanly without throwing uncaught exceptions.
- **T2-C6 (E20 — SQLite Export with Active WAL Writes):** Writing new events immediately before `exportSqliteBackup` without closing Room still includes 100% of newly written rows in the exported `.db` file after `PRAGMA wal_checkpoint(TRUNCATE)`.

### Area D: Tracking Engine Boundaries (`T2TrackingBoundariesE2eTest`)
- **T2-D1 (Revoked Permission / Null Secure Setting):** `isNotificationAccessGranted` returns `false` without throwing when setting is null/empty.
- **T2-D2 (OEM `ActivityNotFoundException` Fallback — Notification Settings):** Falls back to `Settings.ACTION_SETTINGS` when `ACTION_NOTIFICATION_LISTENER_SETTINGS` cannot be resolved.
- **T2-D3 (OEM `ActivityNotFoundException` / `SecurityException` Fallback — Battery Optimization):** Falls back to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
- **T2-D4 (E19 — `SecurityException` on `getActiveSessions` & Unclean `onDestroy` Flush):** `SecurityException` is caught gracefully without crash; `onDestroy()` while `ACTIVE` flushes the session to Room.
- **T2-D5 (Null/Empty Active Sessions List & Unregistered Removed Tokens):** Passing `null` or `emptyList()` to `onActiveSessionsChanged` detaches stale controllers and ends active tracking cleanly.
- **T2-D6 (E11 — Non-Allowlisted App Ignored While Allowlisted Pauses):** YouTube (`com.google.android.youtube`) playing while Spotify pauses does NOT track YouTube and stops Spotify's wall-clock accumulation.
- **T2-D7 (Disabling Currently Playing App in Allowlist):** Toggling off the active playing app mid-song immediately detaches its controller and finalizes its session.

### Area E: UI & Liquid Glass Boundaries (`T2UiAccessibilityBoundariesE2eTest`)
- **T2-E1 (`BlurEffect(0f, 0f)` Zero-Radius Guard):** Rendering `LiquidGlassSurface` with `0.dp` blur (e.g. during materialization start or Reduced Transparency) never throws `IllegalArgumentException`/runtime crash.
- **T2-E2 (`Clear` Glass 35% Dimming Scrim Over Bright Artwork):** Bright media artwork (`luminance > 0.55f`) engages the mandatory 35% dark dimming scrim (`alpha = 0.35f`) for legibility.
- **T2-E3 (All 3 Accessibility Modes Active Simultaneously):** Reduced Transparency + High Contrast + Reduced Motion renders Home, Stats, and Settings without crash or clipped text.
- **T2-E4 (Home Zero-Plays Skip Rate & Pause Freeze):** `tracksPlayed == 0` renders `"—"` (never `"NaN%"`); pausing active playback freezes `HOME_LIVE_COUNTER` across clock advances.
- **T2-E5 (Stats Empty Timeframe & Extreme Long Track Titles):** Empty timeframe shows clean empty state; 120-character track/artist title ellipsizes cleanly without crashing layout.
- **T2-E6 (Settings SAF Picker Cancellation):** Returning `null` `Uri` from `CreateDocument` leaves export state `Idle` with no error feedback.
- **T2-E7 (Settings Allowlist Empty Search Match):** Searching `"zzz_nonexistent_pkg"` displays empty-match message cleanly.

---

## 5. Tier 3 — Cross-Feature Combinations

- **T3-1 (`T3PipelineToStatsExportE2eTest`):** Noisy YT Music metadata + late-arriving duration + scrubbing + repeat-one loop → `PlaySessionStateMachine` → `ListeningRepository` (Room dedup) → `StatsViewModel` (Top Tracks & Top Artists ranked by actual time) → `DataExporter` (JSON + WAL-checkpointed SQLite) → verify every layer agrees on exact `actualListenedMs`, `playCount`, `normalizedTitle`, `rawTitle`.
- **T3-2 (`T3AllowlistToLiveUiE2eTest`):** `AllowlistStore` + `MediaSessionCoordinator` + `PlaySessionStateMachine` + `HomeViewModel` + `HomeScreen` + `SettingsScreen`: start playback on Spotify → verify `HOME_NOW_TRACKING_CARD` & ticking `HOME_LIVE_COUNTER` → disable Spotify in Settings allowlist → verify coordinator finalizes session, `HOME_NOW_TRACKING_CARD` returns to idle, and `HOME_RECENT_LIST` shows the finalized Spotify listen.
- **T3-3 (`T3MultiAppDedupAndRenormE2eTest`):** Same song played on YouTube Music (`"Artist - Topic"` / `"Song (Official Video)"`) and Spotify (`"Artist"` / `"Song"`) plus a pre-existing legacy unnormalized row → verify cross-app dedup into 1 `tracks` row, run `renormalizeAllTracks()` to merge legacy row, and verify `Stats` Top Tracks & Top Artists aggregate all `listen_events` seamlessly.
- **T3-4 (`T3PermissionsNavAndGlassE2eTest`):** Start app without Notification Access → `HOME_PERMISSION_BANNER` visible → navigate Home → Stats (select `7 Days`) → Settings (toggle `SETTINGS_REDUCE_TRANSPARENCY_SWITCH`) → grant Notification Access & trigger resume → navigate back to Home (banner gone) and Stats (`7 Days` preserved) under Reduced Transparency mode.

---

## 6. Tier 4 — Real-World Application Scenarios (>= 5 End-User Sessions)

1. **Scenario 1 (`T4Scenario1YouTubeMusicAlbumTest`) — YouTube Music Album Listen with Scrubbing & Repeat-One:**
   - User plays a 3-track YouTube Music album (`com.google.android.apps.youtube.music`) with noisy metadata (`"Daft Punk - Topic"`, `"Instant Crush (feat. Julian Casablancas) (Official Video)"`), partial metadata arriving before `DURATION`, a mid-track scrub-back to replay a chorus (wall-clock > track duration), a 1.0s accidental skip (discarded by 1.5s debounce), and Track 3 on Repeat-One looping twice.
   - Asserts: 4 persisted `listen_events` (not 5), 3 `tracks` rows with raw metadata preserved, Home `HOME_RECENT_LIST` & `HOME_STAT_PLAYS` & `HOME_STAT_TIME` match exact wall-clock sums, Stats ranks Track 3 #1 by actual time listened.
2. **Scenario 2 (`T4Scenario2MultiAppSwitchingTest`) — Multi-App Switching (Spotify + YouTube Music + Ignored YouTube Video):**
   - User listens to `"Blinding Lights"` on YouTube Music, switches to `"Blinding Lights"` on Spotify, pauses Spotify to watch a 5-minute video on main YouTube (`com.google.android.youtube`, not allowlisted), then enables VLC in Settings allowlist and plays a local FLAC track.
   - Asserts: YouTube video produces 0 DB rows; YT Music + Spotify share 1 deduplicated `tracks` row with 2 `listen_events`; VLC is tracked immediately after allowlist toggle without restarting the service.
3. **Scenario 3 (`T4Scenario3CommuteStatsExportTest`) — Full-Day & Multi-Week Commute, Skips, Timeframe Comparison & Data Export Round-Trip:**
   - Simulates listening sessions across 40 days ago, 15 days ago, 3 days ago, and Today, including short skips (`< 50%`) and long deep-listens (1 track played once for 8 minutes vs. another track skipped 5 times for 20s each = 1m40s).
   - Verifies Stats `Today`, `7 Days`, `30 Days`, `All Time` switch reactively and rank the 8-minute single-play track **above** the 5-play skipped track.
   - Triggers both JSON export and SQLite backup export via SAF in Settings; parses the exported JSON and opens the exported `.db` file with SQLite to assert 100% row-for-row fidelity.
4. **Scenario 4 (`T4Scenario4OnboardingAndA11yTest`) — Fresh Install Onboarding, Permission Grant Flow & Liquid Glass Accessibility:**
   - Fresh app launch with Notification Access revoked and Battery Optimization active → Home displays `HOME_PERMISSION_BANNER` and `"Nothing Playing"` idle card; Today's skip rate shows `"—"`.
   - User navigates to Settings, taps `SETTINGS_NOTIF_BTN` (verifies `ACTION_NOTIFICATION_LISTENER_SETTINGS` intent) and `SETTINGS_BATTERY_BTN` (verifies exemption intent + OEM fallback), grants permissions, returns (`ON_RESUME`) → Home banner auto-dismisses, Settings badges show Active/Unrestricted.
   - User toggles `SETTINGS_REDUCE_TRANSPARENCY_SWITCH` and tests High Contrast + Reduced Motion across all 3 screens cleanly.
5. **Scenario 5 (`T4Scenario5ResilienceAndRenormTest`) — Background Service Resilience, Idle Pause Timeout & Re-Normalization:**
   - User starts listening to a track, OS disconnects/destroys `MusicNotificationListenerService` mid-track → active session flushes non-cancellably to Room so 0 seconds are lost.
   - Later, user pauses a track for 35 minutes (> 30 min idle timeout) → session finalizes cleanly; resuming starts a fresh session.
   - Finally, runs `renormalizeAllTracks()` across stored tracks with raw metadata variants and verifies all `listen_events` foreign keys and aggregate Stats totals remain 100% intact.

---

## 7. Verification & Acceptance Criteria (Phase B)
- All tests under `app/src/test/java/com/musictracker/app/e2e/` compile cleanly against `PROJECT.md` public contracts.
- Zero modifications to `app/src/main/**` by the E2E Testing Track.
- Independent verification wave (2 Reviewers + 2 Challengers + 1 Forensic Auditor with sub-scope `app/src/test/java/com/musictracker/app/e2e/`) approves all 4 tiers with `CLEAN` integrity verdict before publishing `TEST_READY.md`.
