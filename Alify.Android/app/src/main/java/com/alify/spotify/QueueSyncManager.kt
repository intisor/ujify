package com.alify.spotify

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SyncedQueueTrack(
    val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val isExplicit: Boolean
)

/**
 * Coordinates Spotify multi-queue synchronization so Ujify never distorts the user's queue,
 * never loses upcoming queue context across consecutive skips, and never leaves Spotify
 * paused on an empty queue.
 */
object QueueSyncManager {

    private const val TAG = "QueueSyncManager"
    private const val MAX_PRESERVED_TRACKS = 25
    private const val MAX_PLAYED_HISTORY = 100

    private val queueMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    var lastKnownContextUri: String? = null

    @Volatile
    var lastUjifySkipTimestampMs: Long = 0L

    private val preservedUpcomingTracks = Collections.synchronizedList(mutableListOf<SyncedQueueTrack>())
    private val recentlyPlayedOrFlaggedIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val preResolvedSisterMap = ConcurrentHashMap<String, SubstituteSuggestion>()

    fun recordPlayerSnapshot(contextUri: String?, currentTrackId: String?, isPlaying: Boolean) {
        if (!contextUri.isNullOrBlank()) {
            lastKnownContextUri = contextUri
        }
        if (!currentTrackId.isNullOrBlank()) {
            addRecentTrackId(currentTrackId)
            synchronized(preservedUpcomingTracks) {
                preservedUpcomingTracks.removeAll { it.id == currentTrackId }
            }
        }
        if (isPlaying) {
            Log.v(TAG, "Player snapshot recorded: track=$currentTrackId context=$contextUri")
        }
    }

    private fun addRecentTrackId(trackId: String) {
        if (trackId.isBlank()) return
        synchronized(recentlyPlayedOrFlaggedIds) {
            if (recentlyPlayedOrFlaggedIds.size >= MAX_PLAYED_HISTORY) {
                recentlyPlayedOrFlaggedIds.clear()
            }
            recentlyPlayedOrFlaggedIds.add(trackId)
        }
    }

    suspend fun fetchLiveQueue(token: String): List<SyncedQueueTrack> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/queue")
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val queueArray = json.optJSONArray("queue") ?: return@withContext emptyList()

                val parsed = mutableListOf<SyncedQueueTrack>()
                for (i in 0 until queueArray.length()) {
                    val item = queueArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val uri = item.optString("uri").trim()
                    val name = item.optString("name").trim()
                    val isExplicit = item.optBoolean("explicit", false)
                    val artists = item.optJSONArray("artists")
                    val artistName = if (artists != null && artists.length() > 0) {
                        artists.getJSONObject(0).optString("name").trim().ifBlank { "Unknown Artist" }
                    } else {
                        "Unknown Artist"
                    }

                    if (id.isNotBlank() && uri.isNotBlank()) {
                        parsed.add(
                            SyncedQueueTrack(
                                id = id,
                                uri = uri,
                                name = name,
                                artist = artistName,
                                isExplicit = isExplicit
                            )
                        )
                    }
                }

                syncPreservedQueue(parsed)
                parsed
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch live Spotify queue: ${e.message}")
            emptyList()
        }
    }

    private fun syncPreservedQueue(liveTracks: List<SyncedQueueTrack>) {
        if (liveTracks.isEmpty()) return

        val seenUris = LinkedHashSet<String>()
        val updated = mutableListOf<SyncedQueueTrack>()

        for (track in liveTracks) {
            val isFlaggedInCache = AlifyMonitorService.auditCache[track.id]?.first?.isFlagged == true
            val isAlreadyPlayed = synchronized(recentlyPlayedOrFlaggedIds) {
                recentlyPlayedOrFlaggedIds.contains(track.id)
            }
            if (!isFlaggedInCache && !isAlreadyPlayed && seenUris.add(track.uri)) {
                updated.add(track)
                if (updated.size >= MAX_PRESERVED_TRACKS) break
            }
        }

        if (updated.isNotEmpty()) {
            synchronized(preservedUpcomingTracks) {
                preservedUpcomingTracks.clear()
                preservedUpcomingTracks.addAll(updated)
            }
        }
    }

    fun putPreResolvedSister(flaggedTrackId: String, sister: SubstituteSuggestion) {
        if (flaggedTrackId.isBlank()) return
        preResolvedSisterMap[flaggedTrackId] = sister
    }

    fun consumePreResolvedSister(flaggedTrackId: String): SubstituteSuggestion? {
        if (flaggedTrackId.isBlank()) return null
        return preResolvedSisterMap.remove(flaggedTrackId)
    }

    fun hasPreResolvedSister(flaggedTrackId: String): Boolean {
        if (flaggedTrackId.isBlank()) return false
        return preResolvedSisterMap.containsKey(flaggedTrackId)
    }

    fun markTrackFlagged(trackId: String?) {
        if (trackId.isNullOrBlank()) return
        addRecentTrackId(trackId)
        synchronized(preservedUpcomingTracks) {
            preservedUpcomingTracks.removeAll { it.id == trackId }
        }
    }

    suspend fun playSynchronizedQueue(uris: List<String>, token: String): Boolean = withContext(Dispatchers.IO) {
        val cleanUris = uris.asSequence()
            .map { it.trim() }
            .filter { it.startsWith("spotify:track:") }
            .distinct()
            .take(MAX_PRESERVED_TRACKS)
            .toList()

        if (cleanUris.isEmpty()) return@withContext false

        try {
            val uriArray = JSONArray()
            for (u in cleanUris) {
                uriArray.put(u)
            }
            val payload = JSONObject().apply {
                put("uris", uriArray)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/play")
                .header("Authorization", "Bearer $token")
                .put(payload)
                .build()

            httpClient.newCall(request).execute().use { resp ->
                Log.i(TAG, "playSynchronizedQueue (${cleanUris.size} URIs) returned code=${resp.code}")
                resp.isSuccessful || resp.code == 204
            }
        } catch (e: Exception) {
            Log.w(TAG, "playSynchronizedQueue failed: ${e.message}")
            false
        }
    }

    suspend fun executeSynchronizedSwap(
        token: String,
        flaggedTrackId: String?,
        sisterTrack: SubstituteSuggestion?,
        fallbackCleanSeeds: List<String>
    ): Boolean = queueMutex.withLock {
        markTrackFlagged(flaggedTrackId)

        val sisterUri = sisterTrack?.spotifyUri?.takeIf { it.startsWith("spotify:track:") }
        val liveQueue = fetchLiveQueue(token)

        val excludedIds = synchronized(recentlyPlayedOrFlaggedIds) {
            recentlyPlayedOrFlaggedIds.toHashSet()
        }
        if (!flaggedTrackId.isNullOrBlank()) {
            excludedIds.add(flaggedTrackId)
        }

        fun filterCleanUris(source: List<SyncedQueueTrack>): MutableList<String> {
            val result = mutableListOf<String>()
            val seen = HashSet<String>()
            for (t in source) {
                if (excludedIds.contains(t.id)) continue
                if (AlifyMonitorService.auditCache[t.id]?.first?.isFlagged == true) continue
                if (sisterUri != null && t.uri == sisterUri) continue
                if (seen.add(t.uri)) {
                    result.add(t.uri)
                }
            }
            return result
        }

        val cleanRemainingUris = filterCleanUris(liveQueue)

        // If Spotify returned an empty queue (e.g. after previous URI-array swap), rescue from preserved tracks
        if (cleanRemainingUris.isEmpty()) {
            val preservedCopy = synchronized(preservedUpcomingTracks) { preservedUpcomingTracks.toList() }
            cleanRemainingUris.addAll(filterCleanUris(preservedCopy))
        }

        // Ensure there are always at least 2 upcoming tracks so consecutive skips never drain to a paused state
        if (cleanRemainingUris.size < 2) {
            var addedSeeds = 0
            for (seedUri in fallbackCleanSeeds) {
                if (!seedUri.startsWith("spotify:track:")) continue
                if (sisterUri != null && seedUri == sisterUri) continue
                if (!cleanRemainingUris.contains(seedUri)) {
                    cleanRemainingUris.add(seedUri)
                    addedSeeds++
                    if (addedSeeds >= 2) break
                }
            }
        }

        val orderedUris = mutableListOf<String>()
        if (sisterUri != null) {
            orderedUris.add(sisterUri)
        }
        for (u in cleanRemainingUris) {
            if (!orderedUris.contains(u)) {
                orderedUris.add(u)
            }
        }

        if (orderedUris.isEmpty()) {
            return@withLock false
        }

        lastUjifySkipTimestampMs = System.currentTimeMillis()
        val ok = playSynchronizedQueue(orderedUris, token)
        if (ok) {
            val headUri = orderedUris.first()
            synchronized(preservedUpcomingTracks) {
                preservedUpcomingTracks.removeAll { it.uri == headUri }
            }
            Log.i(TAG, "Synchronized swap succeeded with ${orderedUris.size} queued tracks (sister=${sisterTrack?.title ?: "none"})")
        } else {
            Log.w(TAG, "Synchronized swap rejected by Spotify API; caller may fall back to standard skip")
        }
        ok
    }

    suspend fun recoverFromEmptyQueuePause(
        token: String,
        fallbackCleanUris: List<String>
    ): Boolean = queueMutex.withLock {
        lastUjifySkipTimestampMs = 0L

        val excludedIds = synchronized(recentlyPlayedOrFlaggedIds) {
            recentlyPlayedOrFlaggedIds.toHashSet()
        }
        val preservedCopy = synchronized(preservedUpcomingTracks) { preservedUpcomingTracks.toList() }

        val recoveryUris = mutableListOf<String>()
        for (t in preservedCopy) {
            if (excludedIds.contains(t.id)) continue
            if (AlifyMonitorService.auditCache[t.id]?.first?.isFlagged == true) continue
            if (!recoveryUris.contains(t.uri)) {
                recoveryUris.add(t.uri)
            }
        }

        for (seedUri in fallbackCleanUris) {
            if (seedUri.startsWith("spotify:track:") && !recoveryUris.contains(seedUri)) {
                recoveryUris.add(seedUri)
            }
        }

        if (recoveryUris.isEmpty()) {
            Log.d(TAG, "Empty-queue pause recovery aborted: no preserved or clean seed tracks available")
            return@withLock false
        }

        val ok = playSynchronizedQueue(recoveryUris, token)
        if (ok) {
            val headUri = recoveryUris.first()
            synchronized(preservedUpcomingTracks) {
                preservedUpcomingTracks.removeAll { it.uri == headUri }
            }
            Log.i(TAG, "Recovered paused Spotify queue with ${recoveryUris.size} synchronized clean tracks")
        } else {
            Log.w(TAG, "Empty-queue pause recovery play call failed")
        }
        ok
    }

    fun reset() {
        preResolvedSisterMap.clear()
        Log.d(TAG, "Pre-resolved sister track cache reset")
    }
}
