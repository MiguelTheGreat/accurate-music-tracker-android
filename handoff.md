# Collecto — Conversation Handoff

> **Last updated:** 2026-11-28  
> **Repository:** `MiguelTheGreat/accurate-music-tracker-android` (`main`)  
> **Latest Commit:** `e13fb80`  
> **Latest Prerelease:** [`v2.6.0-preview.2`](https://github.com/MiguelTheGreat/accurate-music-tracker-android/releases/tag/v2.6.0-preview.2)  
> **Latest Stable Release:** [`v2.0.0`](https://github.com/MiguelTheGreat/accurate-music-tracker-android/releases/tag/v2.0.0)

---

## 1. Product Identity & Setup
- **App Name:** Collecto (rebranded from "Music Tracker").
- **Application ID:** `com.musictracker.app` (**do not change** — keeps in-place APK upgrades working).
- **Architecture:** Local-first native Android app (Kotlin + Jetpack Compose + Room + DataStore + Glance widget), tracking listening via `NotificationListenerService` + `MediaSessionManager` with zero root, zero Accessibility Service, and zero modification of other apps.
- **Persistent Release Signing:** Configured via GitHub Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS = musictracker`, `KEY_PASSWORD`) and `versionCode = git rev-list --count HEAD`.
- **Release Workflow:** Publishing any GitHub Release (`gh release create <tag> --target main ...`) triggers `.github/workflows/release-apk.yml`, which builds and attaches `accurate-music-tracker-<tag>.apk` (~3–4 min). Note: `gh release upload` from the VM returns HTTP 401 against `uploads.github.com`, so always let the workflow attach APKs.

---

## 2. Standing User Preferences & Constraints
- **Targeted unit tests only:** Always run targeted tests (`./gradlew :app:testDebugUnitTest --tests "*ClassName*" --no-daemon`). Do **not** rerun the full ~265-test suite unless explicitly requested — it wastes time and tokens.
- **Small UI changes:** Skip unit tests and just compile with `./gradlew :app:compileDebugKotlin --no-daemon`.
- **Lean tests:** Do not add bloated E2E or UI test suites; keep tests focused on tracking accuracy, importers, and domain logic.
- **Git hygiene:** Commit and push to `origin/main` after each milestone/change. **Never commit** `.agents/`, `.kotlin/`, or `PROJECT.md`.
- **No Gemini image generation:** All icons and drawables remain pure vector XML / Compose Canvas.
- **Play Protect sideload note:** Already documented in `README.md` (Play Protect Enhanced Fraud Protection can flag sideloaded `NOTIFICATION_LISTENER` apps; workarounds are `adb install` or temporarily disabling Play Protect scanning).

---

## 3. Current Architecture & Shipped Features (through `v2.6.0-preview.2`)

### A. Tracking, Normalization & Ad Filtering
- `tracking/MusicNotificationListenerService.kt` + `tracking/MediaSessionCoordinator.kt`:
  - Re-checks focused controller live playback state on session sync; `onNotificationRemoved` triggers session sync; `HomeViewModel.refreshPermissionAndDay()` requests active session sync.
  - Reads Android's standard `"android.media.metadata.ADVERTISEMENT"` (`Long > 0L`) key and checks `DISPLAY_SUBTITLE` for ad labels before stripping placeholder subtitles (`"Recommended for you"`).
  - Skips artwork caching for ads and finalizes previous focused songs on cross-package focus switches to ad/blank controllers.
  - Emits `StateFlow<SuggestedPlayer?>` for active non-allowlisted players (persisted dismissals in `AllowlistStore`).
- `domain/tracking/PlaySessionStateMachine.kt`:
  - 3-minute idle pause timeout, paused-session stall guard, stale track-A updateTime protection on auto-advance, and `metadata.isAdvertisement` checked before blank-title guards.
- `domain/normalization/AdAndPlaceholderFilter.kt`:
  - Hardened across `isAdvertisement`, `title`, `artist`, `album`, and `subtitle` (`Spotify` / `Advertisement • 1 of 2`, `Ad · 0:15`, `Video will play after the ad`, `Paid Promotion`, etc.) with 0 false positives on real songs (`Ad Astra`, `Advertisement for Myself`, `Sponsored by Destiny`).
- `data/repository/ListeningRepositoryImpl.kt`:
  - Both `recordCompletedSession()` and `cleanAndRenormalizeLibrary()` (Settings → **Clean & Re-normalize Library**) filter ads and merge duplicate artists/tracks.

### B. True Apple iOS 26 Liquid Glass UI
- `ui/theme/LiquidGlassTokens.kt` & `ui/theme/Theme.kt`:
  - 3 palettes: **Light**, **Dark**, and **AMOLED Black** (`#000000` background, `#0A0A0D` obsidian cards) selectable via `System / Light / Dark / AMOLED` in Settings.
  - Tokenized optical parameters in `LiquidGlassOpticsTokens` (`Regular`, `Clear`, `NavLens`, `ThumbLens`, `Sheet`).
- `ui/components/glass/LiquidGlassRefraction.kt`:
  - Single-pass API 33+ AGSL `RuntimeShader` (`LIQUID_GLASS_LENS_AGSL`) with rounded-rect/capsule SDF, analytic outward normal, thick-rim refraction, subtle R/B chromatic dispersion (3 texture samples total, no loops), luminance adaptation, and razor-thin outer bevel specular.
  - Chained: light blur → AGSL lens shader → saturation. Cached by value equality in `LiquidGlassEffectBuilder` so idle surfaces never rebuild or redraw.
  - Fallback tiers: API 33+ full AGSL refraction; API 31–32 light blur + saturation; API 26–30 / Reduced Transparency opaque fallback.
- `ui/components/glass/LiquidGlassLight.kt`:
  - Ref-counted, lifecycle-aware (`STARTED`), throttled (~15 Hz) `TYPE_GAME_ROTATION_VECTOR` tilt sensor driving specular light direction (disabled under Reduced Motion).
- `ui/components/glass/LiquidGlassBackdropHost.kt` & `LiquidGlassSheet.kt`:
  - Exposes `LocalLiquidGlassSheetBackdrop` so separate-window `ModalBottomSheet`s run the exact same real AGSL dock-matched blur (`8.dp`) + refraction as the bottom dock, never snapping to opaque on expand.
- `ui/components/glass/LiquidGlassNavBar.kt`, `LiquidGlassSegmentedControl.kt`, `LiquidLensContent.kt`:
  - Both the bottom dock and segmented controls support **press-and-drag** sliding with haptic ticks, travel stretch, and AGSL lens magnification **only while pressing or gliding** (100% razor-sharp text at rest).
  - Hardware `.shadow()` is omitted on translucent dark pills to prevent Android HWUI elevation shadow punch-out rectangles behind text.
- `widget/CollectoGlanceWidget.kt`:
  - Multi-layer Liquid Glass XML drawables (`widget_liquid_glass_bg.xml` + `widget_liquid_glass_pill.xml` for day/night).

### C. Phase 1 (`v2.5`) + `v2.6` Social & Stats Features
- **Smart Allowlist Suggestions:** `LiquidGlassBanner` (*"[App] is playing. Track it?"* with **Track** / **Dismiss**) on `HomeScreen`.
- **Period-over-Period Deltas & Rank Arrows:** Inline delta pills on `StatsOverviewStrip` and rank arrows (`▲`, `▼`, `NEW`) on Top 5 Tracks / Artists.
- **Drill-Down Discovery Tags:** All-time `First Discovered` date and `DiscoveryTag` (`Obsession`, `Loyalist`, `New Find`) in `DrillDownSheet`.
- **Listening Rhythm Chart:** 24-hour bar chart in `InsightsCards.kt` with exact 6-hour quarter x-axis alignment (`12a`, `6a`, `12p`, `6p`, `12a`).
- **Backup & Restore + Importers:** `DataImporterImpl` supporting Collecto JSON, Last.fm CSV/JSON, and Spotify Extended Streaming History (±5s dedup in Room transaction).
- **Blend Card (QR / Share Code):** `BlendCalculator` (`COLLECTO1:` compressed Base64 + ZXing QR), `BlendCardRenderer` (9:16 & 4:5), `BlendSheet`, and `COLLECTO1` / `collecto1` `ACTION_VIEW` deep link in `AndroidManifest.xml` + `MainActivity.kt` so Samsung/Android QR scanners open Collecto straight into **Stats → Blend → Compare** and auto-compare.
- **Top 5 Artists Share Card:** General Share Card **Top Artists** mode now renders the ranked **Top 5 Artists** list (with circular artist avatars, plays/tracks, duration, and proportional bars), while tapping an individual artist from the Top 5 list still shares that artist's spotlight card.

---

## 4. Known Platform Quirks Already Solved (Do Not Regress)
1. **Samsung One UI `FLAG_BLUR_BEHIND`:** Ignored on `ModalBottomSheet` dialogs — solved by feeding `LocalLiquidGlassSheetBackdrop` into `LiquidGlassSheet`.
2. **Samsung Camera QR Scanner:** Treats `COLLECTO1:` as a URI scheme and fails unless `MainActivity` declares `<data android:scheme="collecto1" />` and `<data android:scheme="COLLECTO1" />` with `ACTION_VIEW` + `DEFAULT` + `BROWSABLE`.
3. **HWUI Translucent Pill Shadow Artifact:** Calling `.shadow()` on low-alpha dark rounded pills draws a visible inner rectangle behind text — keep `.shadow()` light-mode-only on translucent pills.
4. **Resting Lens Distortion:** Only engage `liquidLensContent` while pressing/dragging (`glow >= 0.02f || travelDelta >= 0.015f`) so stationary tab/segment labels stay razor-sharp.

---

## 5. Recent Commit Log
- `e13fb80` `fix(ui): feed recorded backdrop into LiquidGlassSheet so sheets run real AGSL dock-matched blur`
- `2766099` `fix(ui): add real window blur-behind (28dp) and balanced Sheet optics to LiquidGlassSheet`
- `8ebf9a3` `fix(ui): keep LiquidGlassSheet in true Liquid Glass without snapping to opaque on expand`
- `3aaf11d` `feat(share): show Top 5 Artists list on general Top Artists share card`
- `bb433b3` `fix(blend): register COLLECTO1 deep link so Samsung/Android QR scanners open Collecto Blend directly`
- `c5ebde1` `feat(v2.5): Phase 1 — Smart Allowlist, Period Deltas, Discovery Tags, Importers & Blend Card`
- `0bc2f25` `fix(tracking): harden streaming ad filtering across artist, subtitle, and Android metadata flag`
- `b764352` `fix(ui): remove HWUI shadow box behind segmented text and add AMOLED black theme`
- `7894a07` `feat(ui): make segmented control draggable and fix 24h Listening Rhythm x-axis alignment`
- `8b396ae` `fix(ui): refine Liquid Glass for eye comfort, crisp resting text, and apply to Glance widget`
