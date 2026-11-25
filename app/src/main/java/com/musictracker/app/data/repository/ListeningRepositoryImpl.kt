package com.musictracker.app.data.repository

import androidx.room.withTransaction
import com.musictracker.app.data.db.ListenEventDao
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackDao
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.normalization.MetadataNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ListeningRepositoryImpl(
    private val database: MusicTrackerDatabase,
    private val normalizer: MetadataNormalizer,
    private val trackDao: TrackDao = database.trackDao(),
    private val listenEventDao: ListenEventDao = database.listenEventDao()
) : ListeningRepository {

    override suspend fun recordCompletedSession(session: CompletedListenSession) {
        val normalized = normalizer.normalize(session.rawTitle, session.rawArtist)
        val cleanAlbum = session.album.trim()
        val cleanDuration = session.durationMs.coerceAtLeast(0L)
        val cleanArtwork = session.artworkUri?.trim()?.takeIf { it.isNotEmpty() }

        database.withTransaction {
            val candidate = TrackEntity(
                dedupKey = normalized.dedupKey,
                normalizedTitle = normalized.normalizedTitle,
                normalizedArtist = normalized.normalizedArtist,
                album = cleanAlbum,
                durationMs = cleanDuration,
                rawTitle = session.rawTitle,
                rawArtist = session.rawArtist,
                artworkUriOrHash = cleanArtwork
            )

            val insertedId = trackDao.insertIgnore(candidate)
            val trackId = if (insertedId != -1L) {
                insertedId
            } else {
                val existing = checkNotNull(trackDao.findByDedupKey(normalized.dedupKey)) {
                    "Expected existing track for dedupKey=${normalized.dedupKey}"
                }
                if (cleanAlbum.isNotEmpty() || cleanDuration > 0L || cleanArtwork != null) {
                    trackDao.patchMissingMetadata(
                        id = existing.id,
                        album = cleanAlbum,
                        durationMs = cleanDuration,
                        artworkUriOrHash = cleanArtwork
                    )
                }
                existing.id
            }

            val safeListenedMs = session.actualListenedMs.coerceAtLeast(0L)
            val safeRatio = session.completedRatio.takeIf { it.isFinite() && it >= 0f } ?: 0f

            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = session.sourcePackage,
                    startedAtEpoch = session.startedAtEpoch,
                    endedAtEpoch = session.endedAtEpoch,
                    actualListenedMs = safeListenedMs,
                    completedRatio = safeRatio,
                    wasSkipped = session.wasSkipped
                )
            )
        }
    }

    override fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary> =
        listenEventDao.observeSummaryRow(sinceEpochMs).map { row ->
            val skipRate = if (row.playCount == 0) {
                0f
            } else {
                row.skippedCount.toFloat() / row.playCount.toFloat()
            }
            ListeningSummary(
                totalListenedMs = row.totalListenedMs,
                playCount = row.playCount,
                skippedCount = row.skippedCount,
                skipRate = skipRate
            )
        }

    override fun observeRecentHistory(limit: Int): Flow<List<RecentListenItem>> =
        listenEventDao.observeRecentHistory(limit)

    override fun observeTopTracks(sinceEpochMs: Long, limit: Int): Flow<List<TopTrackStat>> =
        listenEventDao.observeTopTracks(sinceEpochMs, limit)

    override fun observeTopArtists(sinceEpochMs: Long, limit: Int): Flow<List<TopArtistStat>> =
        listenEventDao.observeTopArtists(sinceEpochMs, limit)

    override suspend fun renormalizeAllTracks(): Int = database.withTransaction {
        val allTracks = trackDao.getAll()
        if (allTracks.isEmpty()) return@withTransaction 0

        val recomputed = allTracks.associate { track ->
            track.id to normalizer.normalize(track.rawTitle, track.rawArtist)
        }

        val groupedByNewKey = allTracks.groupBy { track ->
            recomputed.getValue(track.id).dedupKey
        }

        var affectedCount = 0
        val survivorUpdates = mutableListOf<SurvivorUpdate>()

        // Phase 1: Merge colliding duplicates into the oldest (lowest ID) survivor
        for ((newDedupKey, groupTracks) in groupedByNewKey) {
            val survivor = groupTracks.first()
            val duplicates = groupTracks.drop(1)
            val norm = recomputed.getValue(survivor.id)

            var mergedAlbum = survivor.album
            var mergedDuration = survivor.durationMs
            var mergedArtwork = survivor.artworkUriOrHash

            for (dup in duplicates) {
                if (mergedAlbum.isEmpty() && dup.album.isNotEmpty()) {
                    mergedAlbum = dup.album
                }
                if (mergedDuration <= 0L && dup.durationMs > 0L) {
                    mergedDuration = dup.durationMs
                }
                if (mergedArtwork.isNullOrEmpty() && !dup.artworkUriOrHash.isNullOrEmpty()) {
                    mergedArtwork = dup.artworkUriOrHash
                }
                listenEventDao.reassignTrackId(oldTrackId = dup.id, newTrackId = survivor.id)
                trackDao.deleteById(dup.id)
                affectedCount++
            }

            val targetEntity = survivor.copy(
                dedupKey = newDedupKey,
                normalizedTitle = norm.normalizedTitle,
                normalizedArtist = norm.normalizedArtist,
                album = mergedAlbum,
                durationMs = mergedDuration,
                artworkUriOrHash = mergedArtwork
            )

            if (targetEntity != survivor) {
                survivorUpdates += SurvivorUpdate(
                    survivor = survivor,
                    target = targetEntity,
                    dedupKeyChanged = targetEntity.dedupKey != survivor.dedupKey
                )
            }
        }

        // Phase 2a: Assign temporary unique dedup_keys to avoid UNIQUE constraint swap collisions
        for (update in survivorUpdates) {
            if (update.dedupKeyChanged) {
                trackDao.updateDedupKey(
                    id = update.survivor.id,
                    dedupKey = "__renorm_tmp_${update.survivor.id}__"
                )
            }
        }

        // Phase 2b: Apply final normalized fields and dedup_keys
        for (update in survivorUpdates) {
            trackDao.update(update.target)
            affectedCount++
        }

        affectedCount
    }

    private data class SurvivorUpdate(
        val survivor: TrackEntity,
        val target: TrackEntity,
        val dedupKeyChanged: Boolean
    )
}
