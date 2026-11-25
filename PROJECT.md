# Project: Local-First Android Music Tracker

Native Android application in **Kotlin** (Coroutines + Flow), **Jetpack Compose + Material 3** (styled strictly using the `liquid-glass-design` skill), and **Room (SQLite)** that accurately tracks true listening time across YouTube Music and any other allowlisted Android music player — without root, Accessibility Services, or modifying any other app's files.

---

## Architecture

- **Module Structure**: Single `:app` Android module (`com.musictracker.app`) with strict package-level boundaries:
  - `domain/` — **Pure Kotlin** (zero `android.*` imports): `TrackerClock`, immutable models, `MetadataNormalizer`, `PlaySessionStateMachine`.
  - `data/` — Room SQLite database (`music_tracker.db`), DataStore preferences (`AllowlistStore`, accessibility prefs), `ListeningRepository`, SAF `DataExporter` (JSON + WAL-checkpointed SQLite backup).
  - `tracking/` — Android `MusicNotificationListenerService`, `MediaSessionCoordinator`, `SystemPermissionHelper`.
  - `di/` — Clean manual DI (`AppContainer`) attached to `MusicTrackerApplication` (faster builds, simpler JVM/Robolectric testing, zero extra KSP/bytecode plugin friction).
  - `ui/` — Liquid Glass Design System (`ui/theme/`, `ui/components/glass/`, `ui/components/content/`), `ui/navigation/`, and UDF ViewModels + stateless Compose screens (`ui/screens/home/`, `ui/screens/stats/`, `ui/screens/settings/`).
- **Toolchain & Environment** (verified on this VM):
  - JDK: OpenJDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`)
  - Android SDK: `/opt/android-sdk` (`platforms;android-34/35/36`, `build-tools;34.0.0/35.0.0`, all licenses accepted)
  - Targets: `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`
  - Recommended Versions: Gradle Wrapper `8.11.1`, AGP `8.9.1` (or `8.7.3`), Kotlin `2.1.21`, KSP `2.1.21-2.0.1`, Room `2.7.1`, Compose BOM `2025.05.00`, DataStore `1.1.7`, `kotlinx.serialization` `1.8.1`, `kotlinx.coroutines` `1.10.2`, Robolectric `4.16.1` (`@Config(sdk = [34])`), Turbine `1.2.0`.
  - Gradle JVM args (`gradle.properties`): `org.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=768m`
  - **Testing**: No emulator/KVM in VM. All automated tests run on JVM via `./gradlew :app:testDebugUnitTest` (JUnit 4 + Coroutines Test + Turbine + Robolectric with `isIncludeAndroidResources = true` for Room in-memory DB, Service/MediaSession integration, and Compose UI `createComposeRule()`), plus `./gradlew :app:assembleDebug` and `./gradlew :app:lintDebug`.

---

## Code Layout

```text
accurate-music-tracker-android/
├── .gitignore                                  # Includes .agents/, build/, .gradle/, local.properties
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/                                # gradlew + gradle-wrapper.jar/.properties
└── app/
    ├── build.gradle.kts
    ├── schemas/                                # Room exported schemas (KSP room.schemaLocation)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml             # MusicNotificationListenerService, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, <queries>
        │   ├── res/                            # strings.xml, themes.xml, icons
        │   └── java/com/musictracker/app/
        │       ├── MusicTrackerApplication.kt  # Holds AppContainer
        │       ├── MainActivity.kt             # enableEdgeToEdge(), MusicTrackerTheme, MusicTrackerNavHost
        │       ├── di/AppContainer.kt
        │       ├── domain/
        │       │   ├── clock/TrackerClock.kt
        │       │   ├── model/                  # TrackIdentity, MediaMetadataSnapshot, PlaybackStateSnapshot, PlaybackEvent, NowTrackingState, CompletedListenSession, StatsTimeframe, ListeningSummary, RecentListenItem, TopTrackStat, TopArtistStat, ExportResult
        │       │   ├── normalization/MetadataNormalizer.kt
        │       │   └── tracking/PlaySessionStateMachine.kt
        │       ├── data/
        │       │   ├── db/                     # MusicTrackerDatabase, TrackEntity, ListenEventEntity, TrackDao, ListenEventDao
        │       │   ├── preferences/            # AllowlistStore, DefaultAllowlist, AccessibilityPreferencesStore
        │       │   ├── repository/             # ListeningRepository, ListeningRepositoryImpl
        │       │   └── export/                 # DataExporter, DataExporterImpl
        │       ├── tracking/
        │       │   ├── MusicNotificationListenerService.kt
        │       │   ├── MediaSessionCoordinator.kt
        │       │   └── SystemPermissionHelper.kt
        │       └── ui/
        │           ├── theme/                  # LiquidGlassTokens, LiquidGlassShapes, LiquidGlassAccessibility, Typography, Theme
        │           ├── components/
        │           │   ├── glass/              # LiquidGlassBackdropHost, LiquidGlassSurface, LiquidGlassNavBar, LiquidGlassButton, LiquidGlassChip, LiquidGlassSegmentedControl, LiquidGlassSwitch, LiquidGlassBanner, LiquidGlassSheet, ScrollEdgeContainer, GlassModifiers
        │           │   └── content/            # StandardMaterialCard, ArtworkThumbnail, EmptyStateView
        │           ├── navigation/             # AppDestination, MusicTrackerNavHost, TestTags
        │           └── screens/
        │               ├── home/               # HomeViewModel, HomeUiState, HomeScreen
        │               ├── stats/              # StatsViewModel, StatsUiState, StatsScreen
        │               └── settings/           # SettingsViewModel, SettingsUiState, SettingsScreen
        └── test/
            └── java/com/musictracker/app/
                ├── data/                       # M1 unit/Robolectric tests (Room DAOs, AllowlistStore, DataExporter)
                ├── domain/                     # M2 pure JVM unit tests (MetadataNormalizer, PlaySessionStateMachine)
                ├── tracking/                   # M3 Robolectric integration tests (Service, Coordinator, Permissions)
                ├── ui/                         # M4 ViewModel & Robolectric Compose UI tests
                └── e2e/                        # E2E Testing Track (Tiers 1–4) & Tier 5 Adversarial tests
```

---

## Feature Inventory

Every Survey feature is assigned a Milestone (zero unassigned):

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F00 | Google Drive Skill Sync & Project Scaffold | Sync `liquid-glass-design-SKILL.md` (Drive ID `1qeLJf7jnPZjzI3UpMkOzCvMtOAZACR7x`) to `/home/antigravity/.gemini/skills/liquid-glass-design/SKILL.md`; scaffold Gradle/KSP/Compose/Room/DataStore/Robolectric project & `.gitignore` | M1 | Survey 1, ORIGINAL_REQUEST |
| F01 | Notification Access Detection | Check `NotificationManager.isNotificationListenerAccessGranted` (API 27+) / `NotificationManagerCompat.getEnabledListenerPackages` (API 26) | M3 | Survey 2, 3 |
| F02 | Notification Access Settings Deep-Link | Open `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` with `ActivityNotFoundException` fallback to `ACTION_SETTINGS` | M3 | Survey 2, 3 |
| F03 | Battery Optimization Status & Exemption | Check `PowerManager.isIgnoringBatteryOptimizations(pkg)` and launch `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with fallback to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | M3 | Survey 2, 3 |
| F04 | Persistent `NotificationListenerService` Lifecycle | Bind/unbind, `onListenerConnected`/`onListenerDisconnected` (`requestRebind`), `SecurityException` handling, non-cancellable active session flush on disconnect/destroy | M3 | Survey 2 |
| F05 | Active Media Session Discovery | `MediaSessionManager.getActiveSessions(componentName)`, `OnActiveSessionsChangedListener`, per-token `MediaController.Callback` diffing & initial metadata/state seeding | M3 | Survey 2 |
| F06 | Multi-Session Arbitration | Track actively `STATE_PLAYING` allowlisted controller (priority index 0); ignore non-allowlisted sessions; switch focus & finalize previous session cleanly | M3 | Survey 2 |
| F07 | Default Music App Allowlist | Pre-seed DataStore allowlist with popular streaming & local music players (YT Music, Spotify, Apple Music, TIDAL, Amazon Music, SoundCloud, Deezer, Bandcamp, Poweramp, Musicolet, Oto Music, VLC, Retro, Pulsar, Samsung Music, foobar2000) | M1 | Survey 2, 3 |
| F08 | Installed Apps Discovery & Toggle | Query `MAIN/LAUNCHER` apps via `<queries>` manifest declaration; persist toggle changes in DataStore; dynamically re-filter active sessions | M3 | Survey 2, 3 |
| F09 | Wall-Clock Play Time Accumulation | Pure Kotlin `PlaySessionStateMachine` with injectable `TrackerClock`; accumulate `elapsedRealtimeMs` strictly while `STATE_PLAYING` | M2 | Survey 2 |
| F10 | Session Debouncing (~1.5s) | `PENDING` → `ACTIVE` only after ≥1500 ms continuous `STATE_PLAYING` on same track identity; discard shorter transient/churn sessions | M2 | Survey 2 |
| F11 | Late-Arriving Metadata / Duration Patching | Patch `durationMs`, `album`, `artworkUri` on same track identity without resetting `startedAtEpoch` or `actualListenedMs`; reject blank titles | M2 | Survey 2 |
| F12 | Single-Track Loop Detection | Detect repeat-one via `state.position` drop from near end (≥`dur-5s` or ≥90%) to ≤3s with min listened guard; finalize completed session and start new session | M2 | Survey 2 |
| F13 | Scrubbing / Seeking Handling | Preserve wall-clock accumulation across seeks; distinguish mid-track seek-back from end-of-track loop so no spurious split occurs | M2 | Survey 2 |
| F14 | Completion Ratio & Skip Classification | Compute `completed_ratio` (`0f` if unknown duration, raw `actual/duration` otherwise) and `was_skipped` (`<0.50f` when duration known & not loop; `<30s` when duration unknown); idle pause timeout (30 min) | M2 | Survey 2 |
| F15 | Live "Now Tracking" State Emission | Emit `StateFlow<NowTrackingState?>` with `accumulatedMs`, `isPlaying`, `segmentStartElapsedMs` so Home ticks 1Hz without DB write churn | M2 | Survey 2, 3 |
| F16 | Artist & Title Noise Stripping | Strip `" - Topic"`, `"(Official Audio)"`, `"(Official Video)"`, `"(Official Music Video)"`, `"[Lyrics]"`, `"(Explicit)"`, etc.; preserve `(feat. ...)`; non-empty fallback | M2 | Survey 2 |
| F17 | `"Artist - Title"` Unpacking | Split spaced dash (`" - "`, `" – "`, `" — "`) when artist is blank/generic; preserve internal hyphens (`"Jay-Z"`, `"Spider-Man"`) | M2 | Survey 2 |
| F18 | Raw Metadata Preservation & Re-Normalization | Store `raw_title`/`raw_artist` verbatim; canonical `dedup_key`; transactional `renormalizeAllTracks()` merging colliding tracks & reassigning `listen_events.track_id` | M1 (schema) + M2 (logic) | Survey 2 |
| F19 | Room Schema (`tracks` & `listen_events`) | `TrackEntity` (unique `dedup_key`, artist index) & `ListenEventEntity` (FK `ON DELETE CASCADE`, `track_id` & `started_at_epoch` indices) in `music_tracker.db` | M1 | Survey 2 |
| F20 | Home & Stats Reactive Queries | Reactive Room `Flow` queries for Today's summary (total time, plays, skip rate), Recent History, and Timeframe Stats (`Today`/`7D`/`30D`/`All Time`) Top Tracks & Top Artists ranked by `SUM(actual_listened_ms) DESC` | M1 | Survey 2 |
| F21 | JSON History Export (SAF) | Export schema version + `tracks` + `listen_events` as pretty UTF-8 JSON to `OutputStream` / SAF `CreateDocument("application/json")` | M1 (exporter) + M4 (UI SAF) | Survey 2, 3 |
| F22 | Raw SQLite DB Backup (SAF) | Execute `PRAGMA wal_checkpoint(TRUNCATE)` via `query()`, stream `music_tracker.db` to `OutputStream` / SAF `CreateDocument` | M1 (exporter) + M4 (UI SAF) | Survey 2, 3 |
| F23 | Liquid Glass Design System (Compose + M3) | Strict content-vs-chrome layering, API 31+ `rememberGraphicsLayer()`/`RenderEffect` blur (`>0.5f` guard) + API 26–30 frosted fallback, `Regular`/`Clear` (35% dim scrim over bright media)/`Prominent`, concentric radii, Scroll Edge Effects, materialization/bounce/touch bloom, Temporary Lift thumbs, floating capsule nav with scroll minimization, and all 3 accessibility modes (Reduced Transparency, High Contrast, Reduced Motion) | M4 | Survey 3 |
| F24 | Home / Dashboard Screen | Non-intrusive Notification Access glass banner, live "Now Tracking" glass card with 1s tabular-numeral ticking counter, Today's Summary 3-stat Standard Material bento, Recent Listening History list | M4 | Survey 3 |
| F25 | Stats / Insights Screen | Timeframe `LiquidGlassSegmentedControl` (`Today`, `7 Days`, `30 Days`, `All Time`), overview strip, Top Tracks & Top Artists ranked by actual time listened with proportional bars | M4 | Survey 3 |
| F26 | Settings Screen | Notification Access & Battery Optimization cards (`ON_RESUME` refresh), searchable App Allowlist manager with `LiquidGlassSwitch`, one-tap JSON & SQLite SAF export with progress/feedback, Reduce Transparency accessibility toggle | M4 | Survey 3 |
| F27 | E2E Test Suite (Tiers 1–4) & Tier 5 Hardening | Independent opaque-box 4-tier E2E suite (`TEST_INFRA.md` → `TEST_READY.md`) + 100% pass & Tier 5 white-box adversarial coverage hardening | E2E Track + M5 | Project Pattern |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|--------------|--------|
| M1 | Project Scaffold, Room DB, DAOs, Preferences & Data Exporter | F00, F07, F18 (schema), F19, F20, F21 (exporter), F22 (exporter) + Robolectric Room/Export tests | none | IN_PROGRESS (`4cb6a1f04b26f655c2877fd7ebfc4748`) |
| M2 | Play-Session State Machine & Metadata Normalizer | F09, F10, F11, F12, F13, F14, F15, F16, F17, F18 (logic) + exhaustive JVM unit tests | M1 | PLANNED |
| M3 | Core Tracking Engine (`NotificationListenerService` + `MediaSessionManager`) | F01, F02, F03, F04, F05, F06, F08 + Robolectric service/coordinator/DB integration tests | M1, M2 | PLANNED |
| M4 | Liquid Glass Design System & Jetpack Compose UI | F21 (UI SAF), F22 (UI SAF), F23, F24, F25, F26 + ViewModel & Robolectric Compose UI tests | M1, M2, M3 | PLANNED |
| E2E | E2E Testing Track (Opaque-Box Tiers 1–4) | Independent requirement-driven 4-tier test suite (`TEST_INFRA.md`, `app/src/test/java/com/musictracker/app/e2e/`, `TEST_READY.md`) | M1 (for Gradle execution; design starts immediately) | IN_PROGRESS (`f51ede0a5e42f722a5aa842dc902dfe2`) |
| M5 | Final Milestone (E2E Pass + Tier 5 Hardening) | Phase 1: pass 100% E2E Tiers 1–4; Phase 2: Tier 5 adversarial white-box hardening | M4, E2E (`TEST_READY.md`) | PLANNED |

---

## Interface Contracts

### `domain/clock` ↔ `domain/tracking` & `ui`
```kotlin
interface TrackerClock {
    fun elapsedRealtimeMs(): Long
    fun currentTimeMillis(): Long
}
```

### `domain/normalization` ↔ `data/repository` & `domain/tracking`
```kotlin
data class NormalizedTrack(
    val normalizedTitle: String,
    val normalizedArtist: String,
    val dedupKey: String
)

interface MetadataNormalizer {
    fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack
}
```

### `domain/tracking` ↔ `tracking/` & `ui/screens/home`
```kotlin
data class NowTrackingState(
    val sourcePackage: String,
    val rawTitle: String,
    val rawArtist: String,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val accumulatedMs: Long,
    val segmentStartElapsedMs: Long?,
    val isDebouncing: Boolean
)

data class CompletedListenSession(
    val sourcePackage: String,
    val rawTitle: String,
    val rawArtist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val startedAtEpoch: Long,
    val endedAtEpoch: Long,
    val actualListenedMs: Long,
    val completedRatio: Float,
    val wasSkipped: Boolean
)

interface PlaySessionEngine {
    val nowTracking: StateFlow<NowTrackingState?>
    fun onPlaybackEvent(event: PlaybackEvent): List<CompletedListenSession>
    fun onSessionEnded(sourcePackage: String): List<CompletedListenSession>
    fun flushActiveSession(): List<CompletedListenSession>
}
```

### `data/repository` ↔ `tracking/` & `ui/`
```kotlin
interface ListeningRepository {
    suspend fun recordCompletedSession(session: CompletedListenSession)
    fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary>
    fun observeRecentHistory(limit: Int = 50): Flow<List<RecentListenItem>>
    fun observeTopTracks(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopTrackStat>>
    fun observeTopArtists(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopArtistStat>>
    suspend fun renormalizeAllTracks(): Int
}

interface AllowlistRepository {
    val allowedPackages: Flow<Set<String>>
    suspend fun setPackageAllowed(packageName: String, allowed: Boolean)
    suspend fun resetToDefaults()
}

interface DataExporter {
    suspend fun exportJson(outputStream: OutputStream): ExportResult
    suspend fun exportSqliteBackup(outputStream: OutputStream): ExportResult
}
```

### Room Schema (`music_tracker.db`, version 1)
- **`tracks`**: `id` (PK auto), `dedup_key` (UNIQUE INDEX), `normalized_title`, `normalized_artist` (INDEX), `album`, `duration_ms`, `raw_title`, `raw_artist`, `artwork_uri_or_hash` (nullable).
- **`listen_events`**: `id` (PK auto), `track_id` (FK → `tracks.id` `ON DELETE CASCADE`, INDEX), `source_package`, `started_at_epoch` (INDEX), `ended_at_epoch`, `actual_listened_ms`, `completed_ratio`, `was_skipped`.

### UI `TestTags` Contract (`ui/navigation/TestTags.kt`)
- Navigation: `NAV_BAR`, `NAV_TAB_HOME`, `NAV_TAB_STATS`, `NAV_TAB_SETTINGS`
- Home: `HOME_PERMISSION_BANNER`, `HOME_PERMISSION_ENABLE_BTN`, `HOME_NOW_TRACKING_CARD`, `HOME_LIVE_COUNTER`, `HOME_STAT_TIME`, `HOME_STAT_PLAYS`, `HOME_STAT_SKIP_RATE`, `HOME_RECENT_LIST`, `HOME_RECENT_ITEM_<id>`
- Stats: `STATS_TIMEFRAME_CONTROL`, `STATS_TIMEFRAME_<TODAY|7D|30D|ALL>`, `STATS_TOP_TRACKS_LIST`, `STATS_TOP_ARTISTS_LIST`, `STATS_TRACK_ITEM_<id>`, `STATS_ARTIST_ITEM_<index>`
- Settings: `SETTINGS_NOTIF_STATUS`, `SETTINGS_NOTIF_BTN`, `SETTINGS_BATTERY_STATUS`, `SETTINGS_BATTERY_BTN`, `SETTINGS_ALLOWLIST_SEARCH`, `SETTINGS_ALLOWLIST_SWITCH_<pkg>`, `SETTINGS_EXPORT_JSON_BTN`, `SETTINGS_EXPORT_SQLITE_BTN`, `SETTINGS_REDUCE_TRANSPARENCY_SWITCH`, `SETTINGS_EXPORT_FEEDBACK`

---

## Canonical Build & Verification Commands
- Unit & Robolectric Tests: `./gradlew :app:testDebugUnitTest`
- Debug APK Build: `./gradlew :app:assembleDebug`
- Android Lint: `./gradlew :app:lintDebug`
