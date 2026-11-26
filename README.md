# Collecto (v2.0.0) — Local-First Android Music Tracker

**Collecto** is a **local-first** native Android application built with **Kotlin**, **Jetpack Compose**, and **Room (SQLite)** that accurately tracks your true music listening time across YouTube Music, Spotify, and any other Android music player — **without root, Accessibility Services, cloud accounts, or modifying other apps**.

> **Seamless Upgrade Continuity:** Collecto `v2.0.0` retains `applicationId = "com.musictracker.app"`, Room database `music_tracker.db` (schema version 1), DataStore preferences, and the `MusicNotificationListenerService` component name so existing `v1.0.x` installs update in place without losing history or permissions.

---

## What's New in Collecto 2.0

- **Rebranded to Collecto:** Unified branding across app launcher, adaptive Liquid Glass icon, UI headers, widgets, exports, and share cards.
- **Interactive Liquid Glass Bottom Bar & Directional Transitions:** Tap or press-and-drag horizontally across the floating Liquid Glass navigation bar to glide the lens between tabs with subtle haptic ticks (respecting Reduced Motion), paired with directional horizontal slide transitions between tabs.
- **Full Light, Dark & System Mode:** Persisted theme preference in Settings with reactive edge-to-edge status/navigation bar contrast and high-contrast Light & Dark Liquid Glass palettes.
- **Stronger Liquid Glass Optical Refractions:** On Android 13+ (API 33+), memoized AGSL `RuntimeShader` effects deliver convex edge-bending refraction, subtle chromatic dispersion, and Fresnel specular highlights; API 26–32 uses enhanced multi-layer Fresnel/caustic frosted glass — with zero idle per-frame redraws.
- **Offline-First Cover Art & Artist Portraits:** Captures `MediaMetadata` album art into a local compressed WebP cache (`filesDir/artwork/`) with an optional online fallback (Deezer Public API artist portraits & Deezer/iTunes album covers, cached locally for offline use).
- **Native Share Cards:** Share **Top Artist**, **Top Music** (Top Track / Top 5), or **Listening Summary** cards in **9:16 Story** or **4:5 Post** aspect ratios with AndroidX Palette ambient backdrops — save directly to `Pictures/Collecto`, share straight to **Instagram Stories**, or send via the Android share sheet.
- **Collecto Insights:** Tap any artist or track on Stats for an **Artist & Track Drill-Down Sheet** (play count, total time, skip rate, first/last heard, top tracks, direct Share), plus **Listening Streaks & Collector Milestones** and a 24-hour **Listening Rhythm ("Peak Hours")** chart.
- **Tracking Accuracy, Ad Filtering & Library Data Cleaner:** Cross-checks `PlaybackState` telemetry (`lastPositionUpdateTime`, `playbackSpeed`, duration overrun ceilings) to prevent runaway wall-clock inflation, filters streaming audio ads and non-song placeholders without false-positiving real tracks, deduplicates artists (`- Topic`, `VEVO`, `Official`, case/whitespace), and includes a transactional **Clean & Re-normalize Library** tool in Settings.
- **Jetpack Glance Home Screen Widget:** Lightweight Liquid Glass widget showing live **Now Tracking** playback or your current **#1 Artist & weekly listening time** — updated strictly on discrete playback/DB events with zero periodic polling.
- **Power-Efficiency Upgrades:** Background `HandlerThread` for media callbacks, API 31+ `migrateNotificationFilter`, lifecycle-scoped live ticker (`repeatOnLifecycle(STARTED)`), memoized glass shaders/brushes, and compressed local WebP artwork caching.

---

## Core Features

### 1. Core Tracking Engine (`NotificationListenerService` + `MediaSessionManager`)
- **Zero-Hack System Integration:** Taps into Android's standard `MediaSessionManager.getActiveSessions()` and `OnActiveSessionsChangedListener` via a persistent `NotificationListenerService`.
- **Multi-Session Arbitration:** Attaches per-token `MediaController.Callback` listeners to active players, tracks the focused playing controller, and finalizes previous sessions cleanly on app switches or disconnects.
- **App Allowlist Manager:** Pre-seeded with popular streaming and local music players (YouTube Music, Spotify, Apple Music, TIDAL, Amazon Music, SoundCloud, Deezer, Bandcamp, Poweramp, Musicolet, Oto Music, VLC, Retro Music, Pulsar, Samsung Music, foobar2000). Toggle any installed launchable app on/off in Settings so non-music media (YouTube videos, Reels, browser tabs) is ignored.

### 2. Accurate Play-Session State Machine
Instead of crude "50% played" scrobble heuristics, every play session tracks **true listening time**:
- **Wall-Clock Accumulation & Anti-Inflation Guards:** Uses monotonic `SystemClock.elapsedRealtime()` to accumulate exact milliseconds spent in `STATE_PLAYING`, cross-checked against player telemetry (`lastPositionUpdateTime`, `playbackSpeed`, stall detection, and projected track overrun ceilings) with a 3-minute idle pause timeout.
- **Session Debouncing (~1.5s):** Streaming players emit rapid partial metadata updates during track loading and skipping. New sessions remain pending until 1.5 seconds of continuous playback before promoting to active.
- **Single-Track Loop Detection:** Detects near-end position resets back to `~0 ms` on repeat-one, finalizing the completed play and starting a fresh session.
- **Scrubbing & Seeking Accuracy:** Wall-clock accumulation ensures replaying a favorite chorus or scrubbing backward counts the real seconds listened without false loop splits.
- **Late-Arriving Metadata Patching:** Safely patches late-arriving `DURATION`, `ALBUM`, and artwork URIs into the active session without resetting start time or accumulated duration.

### 3. Metadata Normalization, Ad Filtering & Data Cleaner
- **Noise Stripping & Artist Deduplication:** Cleans common streaming suffixes (`" - Topic"`, `"VEVO"`, `"Official"`, `"(Official Audio)"`, `"(Official Video)"`, `"[Lyrics]"`, `"(Explicit)"`, etc.) while preserving meaningful credits like `(feat. ...)`.
- **`"Artist - Title"` Unpacking & Placeholder Hygiene:** Automatically unpacks combined titles when the artist field is blank or generic (`"Unknown"`, `"YouTube Music"`, `"Recommended for you"`, etc.) while preserving hyphenated names (`"Jay-Z"`, `"Spider-Man"`).
- **Context-Aware Ad Filtering:** Drops streaming audio ads and placeholders (`"Advertisement"`, `"Audio Ad"`, `"Ad · 1 of 2"`, `"Recommended for you"`) while protecting real songs (`"Ad Astra"`, `"Loading"`, `"Up Next"`, `"Advertisement for Myself"`).
- **Raw Data Preservation & Library Cleaner:** Stores original `raw_title` / `raw_artist` verbatim in SQLite, supporting transactional library cleanup and duplicate merging in Settings without data loss.

### 4. Local-First Storage & Data Ownership
- **Room (SQLite) Database (`music_tracker.db`):** All listening history stays strictly on your device.
- **One-Tap Storage Access Framework (SAF) Export:**
  - **JSON Export:** Clean, pretty-printed UTF-8 JSON export containing schema metadata, all `tracks`, and all `listen_events`.
  - **Raw SQLite Backup:** Executes `PRAGMA wal_checkpoint(TRUNCATE)` and streams a complete, self-contained `.db` backup file to any location you choose.

### 5. Liquid Glass UI & Accessibility
- **Home / Dashboard:** Live **"Now Tracking"** glass card with a real-time 1 Hz tabular-numeral counter, Today's Summary bento (total listening time, plays, skip rate), non-intrusive Notification Access prompt banner, and Recent Listening History (10-track preview with scroll-to-unlock full history).
- **Stats / Insights:** Filterable timeframes (**Today**, **7 Days**, **30 Days**, **All Time**), swipeable Top 5 Tracks / Top 5 Artists pager ranked by **actual time listened**, Artist & Track Drill-Down sheets, Listening Streaks & Milestones, Peak Hours chart, and Share Card studio.
- **Settings:** Notification Access & Battery Optimization controls, Theme Mode (**System / Light / Dark**), Online Artwork toggle, **Clean & Re-normalize Library**, searchable App Allowlist manager, one-tap JSON / SQLite export, and **Reduce Transparency** toggle.
- **Accessibility Modes:** Full support for **Reduced Transparency** (opaque frosted fallback with blur/refraction disabled), **High Contrast**, and **Reduced Motion**, respecting Android system accessibility settings automatically.

---

## Architecture & Tech Stack

### Package Structure (`:app` — `com.musictracker.app`)
```text
app/src/main/java/com/musictracker/app/
├── domain/            # Pure Kotlin (zero android.* imports)
│   ├── clock/         # TrackerClock abstraction for deterministic testing
│   ├── model/         # Immutable playback, stats, insights, and export models
│   ├── normalization/ # MusicMetadataNormalizer & AdAndPlaceholderFilter
│   ├── stats/         # ListeningInsightsCalculator (streaks, milestones, peak hours)
│   └── tracking/      # PlaySessionStateMachine (wall-clock, debounce, loop, anti-inflation)
├── data/              # Local persistence, artwork cache & export
│   ├── artwork/       # ArtworkCacheManager (WebP), OnlineArtworkFetcher (Deezer/iTunes), ArtworkRepository
│   ├── db/            # Room MusicTrackerDatabase, TrackEntity, ListenEventEntity, DAOs
│   ├── preferences/   # Jetpack DataStore (Allowlist, Accessibility, Theme, Artwork prefs)
│   ├── repository/    # ListeningRepositoryImpl (atomic upsert, stats queries, Data Cleaner)
│   └── export/        # DataExporterImpl (JSON + WAL-checkpointed SQLite SAF export)
├── tracking/          # Android MediaSession & NotificationListenerService integration
│   ├── MusicNotificationListenerService.kt
│   ├── MediaSessionCoordinator.kt
│   └── SystemPermissionHelper.kt
├── widget/            # Jetpack Glance Home Screen Widget (event-driven, zero polling)
│   ├── CollectoGlanceWidget.kt
│   ├── CollectoWidgetReceiver.kt
│   ├── CollectoWidgetState.kt
│   └── CollectoWidgetUpdater.kt
├── di/                # Lightweight manual dependency injection (AppContainer)
└── ui/                # Jetpack Compose + Material 3 + Liquid Glass Design System
    ├── theme/         # LiquidGlassTokens (Light/Dark), Shapes, Typography, Accessibility
    ├── components/    # glass/ (AGSL refraction, nav bar, surfaces) & content/ (cards, artwork)
    ├── navigation/    # Interactive LiquidGlassNavBar & directional slide NavHost
    ├── share/         # ShareCardRenderer (9:16 & 4:5), ShareCardExporter, ShareCardSheet
    └── screens/       # home/, stats/ (DrillDownSheet, InsightsCards), settings/
```

### Room Database Schema (`music_tracker.db`, version 1)
- **`tracks`**: `id` (PK), `dedup_key` (UNIQUE INDEX), `normalized_title`, `normalized_artist` (INDEX), `album`, `duration_ms`, `raw_title`, `raw_artist`, `artwork_uri_or_hash`
- **`listen_events`**: `id` (PK), `track_id` (FK → `tracks.id` `ON DELETE CASCADE`, INDEX), `source_package`, `started_at_epoch` (INDEX), `ended_at_epoch`, `actual_listened_ms`, `completed_ratio`, `was_skipped`

### Tech Stack
| Layer | Technology |
|---|---|
| Language | Kotlin `2.1.21` (Coroutines `1.10.2` + StateFlow / Flow) |
| UI & Graphics | Jetpack Compose (BOM `2025.05.00`), Material 3, Coil, AndroidX Palette KTX `1.0.0`, AGSL `RuntimeShader` (API 33+) |
| Widget | Jetpack Glance AppWidget `1.1.1` |
| Persistence | Room `2.7.1` (SQLite, WAL mode) + Jetpack DataStore `1.1.7` |
| Serialization | `kotlinx.serialization` `1.8.1` |
| Android SDK | `minSdk = 26` (Android 8.0), `compileSdk = 35`, `targetSdk = 35` |
| Testing | JUnit 4, Coroutines Test, Turbine, Robolectric `4.16.1`, Compose UI Test |

---

## Installation & Sideloading (Android 13+)

Download the signed release APK (`accurate-music-tracker-v2.0.0.apk`) from the repository's **GitHub Releases** page or build it locally from source (`./gradlew :app:assembleDebug`).

### Important Note for Android 13+ Sideloading
Because Collecto declares `BIND_NOTIFICATION_LISTENER_SERVICE` so it can read active system media sessions locally without root, Android 13+ applies two extra security gates to sideloaded APKs:

1. **Google Play Protect — Enhanced Fraud Protection:**
   Sideloading the APK directly from a mobile browser or file manager on Android 13+ may be hard-blocked by Google Play Protect's Enhanced Fraud Protection due to `BIND_NOTIFICATION_LISTENER_SERVICE`. Use either workaround:
   - **Recommended — Install via ADB:**
     ```bash
     adb install accurate-music-tracker-v2.0.0.apk
     ```
   - **Alternative — Temporarily Pause Play Protect:** Open **Google Play Store → Profile icon → Play Protect → Settings (gear icon)**, temporarily turn off **Scan apps with Play Protect**, install the APK, and turn Play Protect right back on afterward.

2. **Allow Restricted Settings (Android 13+):**
   Before Android lets you toggle Notification Access on for a sideloaded app:
   1. Open **Settings → Apps → Collecto**.
   2. Tap the **⋮ (three-dot menu)** in the top-right corner.
   3. Select **Allow restricted settings** and authenticate with your PIN/biometrics.
   4. Return to Collecto (or open **Settings → Notification access**) and enable **Collecto**.

3. **Optional — Battery Optimization Exemption:**
   In Collecto's **Settings** tab, tap **Exempt App** under **Battery Optimization** so Android does not kill the background `NotificationListenerService` during long listening sessions.

---

## Building & Testing

### Prerequisites
- **JDK 21** (`JAVA_HOME` configured)
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`

### Canonical Commands
```bash
# Build debug & release APKs
./gradlew :app:assembleDebug :app:assembleRelease

# Run the entire automated unit, Robolectric, and E2E test suite
./gradlew :app:testDebugUnitTest

# Run Android Lint checks
./gradlew :app:lintDebug
```

---

## Future Improvements / Roadmap

- **JSON / SQLite Backup Restore (Import):** One-tap restore from exported Collecto JSON or SQLite backups when migrating to a new device.
- **Custom Listening Goals & Year-in-Review Cards:** Personal weekly listening goals and annual Collecto Wrapped-style multi-slide summary exports.
- **Per-App Listening Breakdown:** Source-app analytics showing listening share across YouTube Music, Spotify, and local players.
