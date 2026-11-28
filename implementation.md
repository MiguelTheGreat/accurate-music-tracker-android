# Collecto — Next-Session Implementation Plan (v2.6 Stable → v3.0 → v3.5)

> **Start here in your new conversation:** Read [`handoff.md`](./handoff.md) first for full repo context, standing user rules (targeted tests only, exclude `.agents/`/`.kotlin/`/`PROJECT.md`, release via `gh release create`), and platform quirks.

---

## Kickoff Checklist
1. Confirm `v2.6.0-preview.2` (`e13fb80`) sheet blur looks right on device (dock-matched `8.dp` AGSL blur via `LocalLiquidGlassSheetBackdrop`).
2. Decide whether to run **Track A** (Bug Explorer + `v2.6.0` stable) first or jump straight into **Track B (Phase 2 — v3.0)**.

---

## Track A: Pre-v3.0 Stabilization (`v2.6.0` Stable)
*Deferred from the Phase 1 pre-release run so we could ship fast first.*

- **Bug Explorer Pass:** Run a focused read-only bug exploration across v2.5 + v2.6 additions:
  - `DataImporterImpl` (malformed JSON/CSV, empty files, timezone/timestamp edge cases, ±5s dedup collisions).
  - `BlendCalculator` & `MainActivity` `COLLECTO1:` deep link (cold start vs warm `onNewIntent`, oversized payloads, invalid Base64/zlib).
  - `ListeningInsightsCalculator` (period-over-period delta edge cases when previous period is 0, rank arrows on ties, discovery tag boundaries).
  - `ShareCardRenderer` (Top 5 Artists with `< 5` artists, long names, missing avatars).
  - `LiquidGlassSheet` (`LocalLiquidGlassSheetBackdrop` alignment on different screen sizes / keyboard open).
- **Targeted Tests & Release:** Fix any critical bugs found, run targeted unit tests only, commit/push, and publish **`v2.6.0`** (non-prerelease).

---

## Track B: Phase 2 — Version 3.0 (Social Recap, Albums/Genres & Digest)
*DB Migration `v1 → v2` | New deps: `androidx.work:work-runtime-ktx` (`2.10.0`) + opt-in `POST_NOTIFICATIONS`.*

### 2.1 Database `v1 → v2` & Deezer Genre / Track-Count Cache
- **`TrackEntity.kt` & `MusicTrackerDatabase.kt`:**
  - Add `genre: String? = null` and `album_track_count: Int? = null` to `tracks`.
  - Add `ArtistMetadataEntity` (`normalized_artist` PK, `primary_genre`, `fetched_at_epoch_ms`).
  - Implement `MIGRATION_1_2` preserving all existing user data.
- **`OnlineArtworkFetcher.kt`:**
  - Extend existing Deezer lookups (respecting `onlineArtworkFallbackEnabled`) to cache `primary_genre` and album `nb_tracks` locally.

### 2.2 Album & Genre Views (4-Pill Ranked Pager)
- **`ListenEventDao.kt` & `StatsViewModel.kt`:**
  - Add `observeTopAlbums(sinceEpochMs, limit)` and genre listening-time aggregation.
- **`StatsScreen.kt`:**
  - Replace the 2-text pager header with a compact **4-pill switcher** (`Tracks` · `Artists` · `Albums` · `Genres`) fitting cleanly on one row, with a Compose Canvas donut chart on the `Genres` page.

### 2.3 Expanded Collection Badges (`TrophyCabinetSheet`)
- **`ListeningInsightsCalculator.kt` & `StatsScreen.kt`:**
  - Add **Album Completion**, **Genre Explorer**, and **Night Owl / Early Bird** badges.
  - Keep the compact streak/milestone strip on `StatsScreen` and add **"All Badges →"** to open `TrophyCabinetSheet` (`LiquidGlassSheet`).

### 2.4 Collecto Recap (Anytime Story Slides)
- **`ui/recap/RecapModels.kt` & `RecapScreen.kt`:**
  - Monthly (`This Month`, `Last Month`) & Yearly (`This Year`, `All Time`) swipeable story slides: Intro, Top Artist, Top Track, Rhythm & Genre, Discovery & Streak, Summary Poster.
  - Per-slide **Share / Save** via `ShareCardExporter`.
  - Accessible from the `StatsScreen` **Share** action sheet.

### 2.5 Weekly Digest Notification
- **`worker/WeeklyDigestWorker.kt`:**
  - Opt-in Sunday `PeriodicWorkRequest` posting a single summary notification (*"14h 20m listened · #1 Artist: ..."*) deep-linking to `StatsScreen`.
- **`AndroidManifest.xml`, `SettingsViewModel.kt`, `SettingsScreen.kt`:**
  - Add `POST_NOTIFICATIONS` permission (requested only when user enables Weekly Digest) and a **Weekly Digest** toggle in Settings.

---

## Track C: Phase 3 — Version 3.5 (Power Archive & Taste Picks)
*Zero DB migration (uses v2 schema).*

### 3.1 Activity Calendar Heatmap (`ActivityCalendarSheet`)
- **`ListenEventDao.kt`:** Add `observeDailyListeningTotals(sinceEpochMs)` and `getEventsForDay(dayStartMs, dayEndMs)`.
- **`ui/screens/stats/ActivityCalendarSheet.kt`:** 52-week × 7-day GitHub-style Canvas heatmap tucked behind **"Calendar →"** on `ListeningRhythmCard`; tapping any day shows that day's summary and tracks.

### 3.2 Local-First Recommendations (ListenBrainz / MusicBrainz + Offline Rediscover)
- **`data/recommendations/RecommendationRepository.kt` & `ui/screens/stats/RecommendationsSheet.kt`:**
  - **Online (No API Key):** Query MusicBrainz / ListenBrainz (`api.listenbrainz.org` / `musicbrainz.org`) using top local artists/genres, filter out tracks already in Room, and pair with Deezer for cover art + 30s audio preview.
  - **Offline Fallback:** Compute **"Forgotten Favorites / Rediscover"** purely from local `listen_events` (heavily played tracks not heard in 60+ days).
  - Triggered via a compact **"Discover →"** pill in `StatsScreen` / Drill-Down Sheet.

---

## Targeted Verification Commands (Per Phase)

```bash
# Track A (v2.6.0 stable)
./gradlew :app:testDebugUnitTest --tests "*DataImporterTest*" --tests "*BlendCalculatorTest*" --tests "*ListeningInsightsCalculatorTest*" --no-daemon

# Track B (v3.0)
./gradlew :app:testDebugUnitTest --tests "*MusicTrackerDatabaseMigrationTest*" --tests "*ListeningInsightsCalculatorTest*" --tests "*WeeklyDigestWorkerTest*" --no-daemon

# Track C (v3.5)
./gradlew :app:testDebugUnitTest --tests "*RecommendationRepositoryTest*" --tests "*ListenEventDao*" --no-daemon

# Quick UI compile check
./gradlew :app:compileDebugKotlin --no-daemon
```
