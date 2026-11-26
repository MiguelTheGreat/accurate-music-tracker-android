# Accurate Music Tracker for Android

A **local-first** native Android application built with **Kotlin**, **Jetpack Compose**, and **Room (SQLite)** that accurately tracks your true music listening time across YouTube Music, Spotify, and any other Android music player — **without root, Accessibility Services, cloud accounts, or modifying other apps**.

---

## Features

### 1. Core Tracking Engine (`NotificationListenerService` + `MediaSessionManager`)
- **Zero-Hack System Integration:** Taps into Android's standard `MediaSessionManager.getActiveSessions()` and `OnActiveSessionsChangedListener` via a persistent `NotificationListenerService`.
- **Multi-Session Arbitration:** Attaches per-token `MediaController.Callback` listeners to active players, tracks the focused playing controller, and finalizes previous sessions cleanly on app switches or disconnects.
- **App Allowlist Manager:** Pre-seeded with popular streaming and local music players (YouTube Music, Spotify, Apple Music, TIDAL, Amazon Music, SoundCloud, Deezer, Bandcamp, Poweramp, Musicolet, Oto Music, VLC, Retro Music, Pulsar, Samsung Music, foobar2000). Toggle any installed launchable app on/off in Settings so non-music media (YouTube videos, Reels, browser tabs) is ignored.

### 2. Accurate Play-Session State Machine
Instead of crude "50% played" scrobble heuristics, every play session tracks **true listening time**:
- **Wall-Clock Accumulation:** Uses monotonic `SystemClock.elapsedRealtime()` to accumulate exact milliseconds spent in `STATE_PLAYING`, pausing cleanly on `STATE_PAUSED`/`STATE_BUFFERING` and finalizing on track change, stop, or 30-minute idle pause timeout.
- **Session Debouncing (~1.5s):** Streaming players like YouTube Music emit rapid partial metadata updates during track loading and rapid skipping. New sessions remain pending until 1.5 seconds of continuous playback before promoting to active.
- **Single-Track Loop Detection:** Because Android media sessions do not re-emit `onMetadataChanged` on repeat-one, the state machine detects near-end position resets back to `~0 ms`, finalizes the completed play, and begins a fresh session.
- **Scrubbing & Seeking Accuracy:** Wall-clock accumulation ensures replaying a favorite chorus or scrubbing backward still counts the real seconds you listened without false loop splits.
- **Late-Arriving Metadata Patching:** Safely patches late-arriving `DURATION`, `ALBUM`, and artwork URIs into the active session without resetting start time or accumulated duration.

### 3. YouTube Music Metadata Normalization
- **Noise Stripping:** Cleans common streaming suffixes such as `" - Topic"`, `"(Official Audio)"`, `"(Official Video)"`, `"(Official Music Video)"`, `"[Lyrics]"`, `"(Explicit)"`, and `"| Official Visualizer"` while preserving meaningful credits like `(feat. ...)`.
- **`"Artist - Title"` Unpacking:** Automatically unpacks combined titles when the artist field is blank or generic (`"Unknown"`, `"Various Artists"`, `"YouTube Music"`, etc.) while preserving hyphenated names (`"Jay-Z"`, `"Spider-Man"`).
- **Raw Data Preservation & Re-Normalization:** Stores both normalized fields and original `raw_title` / `raw_artist` verbatim in SQLite, supporting transactional re-normalization and duplicate track merging without data loss.

### 4. Local-First Storage & Data Ownership
- **Room (SQLite) Database (`music_tracker.db`):** All listening history stays strictly on your device.
- **One-Tap Storage Access Framework (SAF) Export:**
  - **JSON Export:** Clean, pretty-printed UTF-8 JSON export containing schema metadata, all `tracks`, and all `listen_events`.
  - **Raw SQLite Backup:** Executes `PRAGMA wal_checkpoint(TRUNCATE)` and streams a complete, self-contained `.db` backup file to any location you choose.

### 5. Liquid Glass UI & Accessibility
- **Home / Dashboard:** Live **"Now Tracking"** glass card with a real-time 1 Hz tabular-numeral counter, Today's Summary bento (total listening time, plays, skip rate), non-intrusive Notification Access prompt banner, and Recent Listening History.
- **Stats / Insights:** Filterable timeframes (**Today**, **7 Days**, **30 Days**, **All Time**) with Top Tracks and Top Artists ranked strictly by **actual time listened** (not just play count).
- **Settings:** Notification Access & Battery Optimization status controls, searchable App Allowlist manager, one-tap JSON / SQLite export with status feedback, and a **Reduce Transparency** toggle.
- **Accessibility Modes:** Full support for **Reduced Transparency** (opaque frosted fallback with blur disabled), **High Contrast** (stronger borders and contrast), and **Reduced Motion** (disables bounce/bloom and navigation bar scroll minimization), respecting Android system accessibility settings automatically.

---

## Architecture & Tech Stack

### Package Structure (`:app` — `com.musictracker.app`)
```text
app/src/main/java/com/musictracker/app/
├── domain/          # Pure Kotlin (zero android.* imports)
│   ├── clock/       # TrackerClock abstraction for deterministic testing
│   ├── model/       # Immutable playback, stats, and export domain models
│   ├── normalization/ # MusicMetadataNormalizer & canonical dedup key generation
│   └── tracking/    # PlaySessionStateMachine (wall-clock, debounce, loop, scrub)
├── data/            # Local persistence & export
│   ├── db/          # Room MusicTrackerDatabase, TrackEntity, ListenEventEntity, DAOs
│   ├── preferences/ # Jetpack DataStore (AllowlistStore, AccessibilityPreferencesStore)
│   ├── repository/  # ListeningRepositoryImpl (atomic upsert, stats queries, re-normalization)
│   └── export/      # DataExporterImpl (JSON + WAL-checkpointed SQLite SAF export)
├── tracking/        # Android MediaSession & NotificationListenerService integration
│   ├── MusicNotificationListenerService.kt
│   ├── MediaSessionCoordinator.kt
│   └── SystemPermissionHelper.kt
├── di/              # Lightweight manual dependency injection (AppContainer)
└── ui/              # Jetpack Compose + Material 3 + Liquid Glass Design System
    ├── theme/       # LiquidGlassTokens, Shapes, Typography, Accessibility
    ├── components/  # glass/ chrome primitives & content/ Standard Material cards
    ├── navigation/  # Floating LiquidGlassNavBar & NavHost
    └── screens/     # home/, stats/, settings/ (UDF ViewModels + stateless UI)
```

### Room Database Schema (`music_tracker.db`, version 1)
- **`tracks`**: `id` (PK), `dedup_key` (UNIQUE INDEX), `normalized_title`, `normalized_artist` (INDEX), `album`, `duration_ms`, `raw_title`, `raw_artist`, `artwork_uri_or_hash`
- **`listen_events`**: `id` (PK), `track_id` (FK → `tracks.id` `ON DELETE CASCADE`, INDEX), `source_package`, `started_at_epoch` (INDEX), `ended_at_epoch`, `actual_listened_ms`, `completed_ratio`, `was_skipped`

### Tech Stack
| Layer | Technology |
|---|---|
| Language | Kotlin `2.1.21` (Coroutines `1.10.2` + StateFlow / Flow) |
| UI | Jetpack Compose (BOM `2025.05.00`) + Material 3 + Coil |
| Persistence | Room `2.7.1` (SQLite, WAL mode) + Jetpack DataStore `1.1.7` |
| Serialization | `kotlinx.serialization` `1.8.1` |
| Android SDK | `minSdk = 26` (Android 8.0), `compileSdk = 35`, `targetSdk = 35` |
| Testing | JUnit 4, Coroutines Test, Turbine, Robolectric `4.16.1`, Compose UI Test |

---

## Installation & Sideloading (Android 13+)

You can download the prebuilt APK from the repository's **GitHub Releases** page or build it locally from source (`./gradlew :app:assembleDebug`).

### Important Note for Android 13+ Sideloading
Because this app declares `BIND_NOTIFICATION_LISTENER_SERVICE` so it can read active system media sessions locally without root, Android 13+ applies two extra security gates to sideloaded APKs:

1. **Google Play Protect — Enhanced Fraud Protection:**
   Sideloading the APK directly from a mobile browser or file manager on Android 13+ may be hard-blocked by Google Play Protect's Enhanced Fraud Protection due to the `BIND_NOTIFICATION_LISTENER_SERVICE` permission. Use either of these clean workarounds:
   - **Recommended — Install via ADB:**
     ```bash
     adb install app-debug.apk
     ```
   - **Alternative — Temporarily Pause Play Protect:** Open **Google Play Store → Profile icon → Play Protect → Settings (gear icon)**, temporarily turn off **Scan apps with Play Protect**, install the APK, and turn Play Protect right back on afterward.

2. **Allow Restricted Settings (Android 13+):**
   Before Android will let you toggle Notification Access on for a sideloaded app:
   1. Open **Settings → Apps → Music Tracker**.
   2. Tap the **⋮ (three-dot menu)** in the top-right corner.
   3. Select **Allow restricted settings** and authenticate with your PIN/biometrics.
   4. Return to the app (or open **Settings → Notification access**) and enable **Music Tracker**.

3. **Optional — Battery Optimization Exemption:**
   In the app's **Settings** tab, tap **Exempt App** under **Battery Optimization** so Android does not kill the background `NotificationListenerService` during long listening sessions.

---

## Building & Testing

### Prerequisites
- **JDK 21** (`JAVA_HOME` configured)
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`

### Canonical Commands
```bash
# Build the debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug

# Run the entire automated test suite (Unit + Robolectric + E2E Tiers 1–5)
./gradlew :app:testDebugUnitTest --rerun

# Run Android Lint checks
./gradlew :app:lintDebug

# Run only the E2E suite (Tiers 1–5)
./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.*" --rerun

# Run only Tier 5 white-box adversarial hardening tests
./gradlew :app:testDebugUnitTest --tests "com.musictracker.app.e2e.tier5.*" --rerun
```

### Verified Automated Test Coverage
All **204 automated tests across 38 suites** run deterministically on JVM + Robolectric (`@Config(sdk = [34])`) with **0 failures, 0 errors, 0 skipped**, and **0 Android Lint errors**:
- **Unit & Robolectric Component Tests (`data/`, `domain/`, `tracking/`, `ui/`):** 17 suites, **110 tests**
- **E2E Opaque-Box Suite (`e2e/tier1..tier4/`):** 19 suites, **74 tests**
  - Tier 1 Feature Coverage: 33 tests
  - Tier 2 Boundary & Corner Cases: 32 tests
  - Tier 3 Cross-Feature Integration: 4 tests
  - Tier 4 Real-World End-User Scenarios: 5 tests
- **Tier 5 White-Box Adversarial Hardening (`e2e/tier5/`):** 2 suites, **20 tests** (`T5CoreAdversarialTest`: 12 tests, `T5UiAdversarialTest`: 8 tests)

---

## Future Improvements / Roadmap

The following enhancements are planned for future iterations:
- **Richer Apple-Style Liquid Glass Animations & Interactive Transitions:** Adding gel-like morphing transitions, touch-point illumination blooms, and dynamic optical specular/refraction dynamics across floating chrome surfaces.
- **Further Battery & Power-Efficiency Optimizations:** Minimizing background wakeups, reducing GPU/blur overdraw from glass rendering layers, and adding smarter idle throttling so background tracking and glass UI effects stay lightweight and low-power over all-day use.
