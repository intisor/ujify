package com.alify.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SubstituteSuggestion(
    val title: String,
    val artist: String,
    val spotifyUri: String? = null,
    val matchReason: String? = null
)

data class SpotifyTrackCandidate(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val isExplicit: Boolean
)

object VibeSubstituteService {

    private const val TAG = "VibeSubstitute"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Rolling memory of recently chosen sister tracks to prevent repeating the same songs
    private val recentSisterTrackKeys = java.util.Collections.synchronizedList(mutableListOf<String>())

    /**
     * Dynamically asks the AI (Gemini/Groq waterfall) to recommend diverse clean sister tracks that match
     * the exact genre, acoustic energy, rhythm, and vibe of the flagged track.
     * ZERO hardcoded songs or artists — entirely generated on the fly.
     */
    suspend fun findCleanSisterTrack(
        context: Context,
        flaggedTrack: String,
        flaggedArtist: String,
        flagReason: String,
        profileName: String,
        spotifyToken: String,
        filterEngine: FilterEngine,
        lrclibClient: LrclibClient = LrclibClient()
    ): SubstituteSuggestion? = withContext(Dispatchers.IO) {
        try {
            SanctuaryMemoryBank.init(context)
            val tasteContext = SanctuaryMemoryBank.buildTasteContext()
            val tastePrompt = if (tasteContext.isNotBlank()) "\nListener's personal verified taste context: $tasteContext\nPrefer clean sister tracks that align with this aesthetic if suitable.\n" else ""

            val recentSnapshot = synchronized(recentSisterTrackKeys) { recentSisterTrackKeys.takeLast(15) }
            val recentAvoidPrompt = if (recentSnapshot.isNotEmpty()) {
                val avoidList = recentSnapshot.joinToString(", ") { "\"$it\"" }
                "\nCRITICAL DIVERSITY RULE: DO NOT repeat any of these recently played clean tracks: $avoidList. Suggest completely fresh songs from different artists.\n"
            } else ""

            val prompt = """
                You are an expert music curator and musicologist.
                The following song was flagged by an ethical music sentinel for violating the $profileName listening ethos:
                Track: "$flaggedTrack" by "$flaggedArtist"
                Violation Reason: "$flagReason"
                $tastePrompt
                $recentAvoidPrompt
                Recommend 4 diverse, high-quality, real, verified alternative songs available on Spotify that:
                1. Match the EXACT SAME musical genre, acoustic energy, tempo/BPM, beat pattern, and vocal vibe.
                2. Have 100% CLEAN, positive, inspiring, or wholesome lyrics with ZERO explicit language, violence, or vulgarity.
                3. Are from DIFFERENT artists to provide rich musical variety.
                4. NEVER suggest "$flaggedTrack".

                Return ONLY a valid JSON array of objects with "title", "artist", and "matchReason", like this:
                [
                  {"title": "Track Name", "artist": "Artist Name", "matchReason": "Same Afrobeat groove and 108 BPM with uplifting grateful lyrics"}
                ]
            """.trimIndent()

            val aiKey = filterEngine.geminiApiKey
            val keys = FilterEngine.buildProviderKeyWaterfall(aiKey)

            var rawResponse: String? = null
            for (k in keys) {
                if (k.isNotBlank()) {
                    rawResponse = FilterEngine.executeLlmWaterfallRequest(prompt, k, filterEngine.customModel, filterEngine.customEndpoint)
                    if (!rawResponse.isNullOrBlank()) break
                }
            }

            if (rawResponse.isNullOrBlank()) {
                Log.w(TAG, "Empty response from AI waterfall for vibe substitution")
                return@withContext null
            }

            val suggestions = parseSuggestions(rawResponse)
            Log.d(TAG, "AI generated ${suggestions.size} clean substitute suggestions for '$flaggedTrack'")

            // Shuffle candidates so we don't always pick the first one, and filter out recent tracks
            val freshCandidates = suggestions.filterNot { candidate ->
                val k = "${candidate.title}::${candidate.artist}".lowercase()
                synchronized(recentSisterTrackKeys) { recentSisterTrackKeys.contains(k) }
            }.shuffled().ifEmpty { suggestions.shuffled() }

            var fallbackUnindexedCandidate: Pair<SubstituteSuggestion, SpotifyTrackCandidate>? = null

            // Resolve and strictly verify each candidate BEFORE accepting it
            for (candidate in freshCandidates) {
                val spotifyTrack = searchSpotifyTrackCandidate(candidate.title, candidate.artist, spotifyToken) ?: continue
                if (spotifyTrack.isExplicit || spotifyTrack.title.equals(flaggedTrack, ignoreCase = true)) {
                    Log.d(TAG, "Rejected explicit or duplicate sister candidate '${spotifyTrack.title}'")
                    continue
                }

                val cached = AlifyMonitorService.auditCache[spotifyTrack.id]
                if (cached != null) {
                    if (cached.first.isFlagged) {
                        Log.i(TAG, "Rejected cached flagged sister candidate '${spotifyTrack.title}' by '${spotifyTrack.artist}'")
                        continue
                    }
                    val verified = candidate.copy(
                        title = spotifyTrack.title,
                        artist = spotifyTrack.artist,
                        spotifyUri = spotifyTrack.uri
                    )
                    acceptVerifiedSister(context, verified, spotifyTrack)
                    return@withContext verified
                }

                // Actively retrieve lyrics & run FilterEngine verification before allowing into playback/memory
                val lyrics = LrclibClient.getCachedLyrics(spotifyTrack.artist, spotifyTrack.title)
                    ?: lrclibClient.getLyrics(spotifyTrack.artist, spotifyTrack.title, filterEngine.geminiApiKey)
                val evalResult = filterEngine.evaluate(spotifyTrack.title, spotifyTrack.artist, spotifyTrack.isExplicit, lyrics)
                AlifyMonitorService.auditCache[spotifyTrack.id] = Pair(evalResult, lyrics)

                if (evalResult.isFlagged) {
                    Log.i(TAG, "Rejected sister candidate '${spotifyTrack.title}' by '${spotifyTrack.artist}': ${evalResult.flagReason}")
                    continue
                }

                val verified = candidate.copy(
                    title = spotifyTrack.title,
                    artist = spotifyTrack.artist,
                    spotifyUri = spotifyTrack.uri
                )
                if (!lyrics.isNullOrBlank()) {
                    acceptVerifiedSister(context, verified, spotifyTrack)
                    return@withContext verified
                } else if (fallbackUnindexedCandidate == null) {
                    fallbackUnindexedCandidate = Pair(verified, spotifyTrack)
                }
            }

            if (fallbackUnindexedCandidate != null) {
                val (verified, spotifyTrack) = fallbackUnindexedCandidate
                acceptVerifiedSister(context, verified, spotifyTrack)
                return@withContext verified
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving dynamic vibe substitute: ${e.message}", e)
            null
        }
    }

    private fun acceptVerifiedSister(
        context: Context,
        verified: SubstituteSuggestion,
        spotifyTrack: SpotifyTrackCandidate
    ) {
        val key = "${spotifyTrack.title}::${spotifyTrack.artist}".lowercase()
        synchronized(recentSisterTrackKeys) {
            recentSisterTrackKeys.add(key)
            if (recentSisterTrackKeys.size > 30) {
                recentSisterTrackKeys.removeAt(0)
            }
        }
        Log.i(TAG, "Verified clean sister track: '${spotifyTrack.title}' by '${spotifyTrack.artist}' -> ${spotifyTrack.uri}")
        SanctuaryMemoryBank.recordCleanTrack(context, spotifyTrack.title, spotifyTrack.artist, spotifyTrack.uri, "vibe_swap")
    }

    suspend fun getVerifiedCleanSeedUris(
        context: Context,
        filterEngine: FilterEngine,
        lrclibClient: LrclibClient = LrclibClient(),
        excludeTitle: String?,
        limit: Int = 3
    ): List<String> = withContext(Dispatchers.IO) {
        SanctuaryMemoryBank.init(context)
        val seeds = SanctuaryMemoryBank.getRecentCleanSeeds(10)
        val verifiedUris = mutableListOf<String>()

        for (seed in seeds) {
            if (!excludeTitle.isNullOrBlank() && seed.title.equals(excludeTitle, ignoreCase = true)) {
                continue
            }
            val rawUri = seed.spotifyUri?.trim() ?: continue
            val (uri, trackId) = when {
                rawUri.startsWith("spotify:track:") -> Pair(rawUri, rawUri.removePrefix("spotify:track:"))
                !rawUri.contains("::") && rawUri.matches(Regex("^[A-Za-z0-9]{15,30}$")) -> Pair("spotify:track:$rawUri", rawUri)
                else -> continue
            }

            val cached = AlifyMonitorService.auditCache[trackId]
            if (cached != null) {
                if (!cached.first.isFlagged && !verifiedUris.contains(uri)) {
                    verifiedUris.add(uri)
                }
            } else {
                val lyrics = LrclibClient.getCachedLyrics(seed.artist, seed.title)
                    ?: lrclibClient.getLyrics(seed.artist, seed.title, filterEngine.geminiApiKey)
                val evalResult = filterEngine.evaluate(seed.title, seed.artist, false, lyrics)
                AlifyMonitorService.auditCache[trackId] = Pair(evalResult, lyrics)
                if (!evalResult.isFlagged && !verifiedUris.contains(uri)) {
                    verifiedUris.add(uri)
                }
            }

            if (verifiedUris.size >= limit) break
        }

        verifiedUris
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("""[\(\[][^\)\]]*[\)\]]"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun pickCleanTrackCandidate(
        items: JSONArray?,
        expectedTitle: String,
        expectedArtist: String,
        requireOverlap: Boolean
    ): SpotifyTrackCandidate? {
        if (items == null || items.length() == 0) return null
        val normExpTitle = normalizeForMatch(expectedTitle)
        val normExpArtist = normalizeForMatch(expectedArtist)

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val isExplicit = item.optBoolean("explicit", false)
            if (isExplicit) continue

            val id = item.optString("id").trim()
            val uri = item.optString("uri").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || uri.isBlank()) continue

            val artistsArray = item.optJSONArray("artists")
            val artistNames = mutableListOf<String>()
            if (artistsArray != null) {
                for (aIdx in 0 until artistsArray.length()) {
                    val aName = artistsArray.optJSONObject(aIdx)?.optString("name")?.trim().orEmpty()
                    if (aName.isNotBlank()) artistNames.add(aName)
                }
            }
            val primaryArtist = artistNames.firstOrNull() ?: "Unknown Artist"

            if (requireOverlap) {
                val normCandTitle = normalizeForMatch(name)
                val titleOverlap = normCandTitle.isNotEmpty() && normExpTitle.isNotEmpty() &&
                    (normCandTitle.contains(normExpTitle) || normExpTitle.contains(normCandTitle))
                val artistOverlap = artistNames.any { a ->
                    val ca = normalizeForMatch(a)
                    ca.isNotEmpty() && normExpArtist.isNotEmpty() && (ca.contains(normExpArtist) || normExpArtist.contains(ca))
                }
                if (!titleOverlap || !artistOverlap) continue
            }

            return SpotifyTrackCandidate(
                id = id,
                uri = uri,
                title = name,
                artist = primaryArtist,
                isExplicit = false
            )
        }
        return null
    }

    /**
     * Resolves a verified non-explicit Spotify Track candidate via Spotify's official Search API.
     */
    suspend fun searchSpotifyTrackCandidate(title: String, artist: String, token: String): SpotifyTrackCandidate? = withContext(Dispatchers.IO) {
        try {
            val query = "track:\"$title\" artist:\"$artist\""
            val url = "https://api.spotify.com/v1/search?q=${Uri.encode(query)}&type=track&limit=5"

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val items = json.optJSONObject("tracks")?.optJSONArray("items")
                    val exactMatch = pickCleanTrackCandidate(items, title, artist, requireOverlap = false)
                    if (exactMatch != null) return@withContext exactMatch
                }
            }

            // Fallback to looser query if exact quotes yielded no non-explicit results
            val looseQuery = "$title $artist"
            val fallbackUrl = "https://api.spotify.com/v1/search?q=${Uri.encode(looseQuery)}&type=track&limit=5"
            val fallbackReq = Request.Builder()
                .url(fallbackUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(fallbackReq).execute().use { fallbackResp ->
                if (!fallbackResp.isSuccessful) return@withContext null
                val json = JSONObject(fallbackResp.body?.string() ?: "{}")
                val items = json.optJSONObject("tracks")?.optJSONArray("items")
                return@withContext pickCleanTrackCandidate(items, title, artist, requireOverlap = true)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Spotify search failed for '$title' by '$artist': ${e.message}")
            null
        }
    }

    /**
     * Resolves a real Spotify Track URI via Spotify's official Search API.
     * GET /v1/search?q=track:"..." artist:"..."&type=track&limit=5
     */
    suspend fun searchSpotifyTrack(title: String, artist: String, token: String): String? {
        return searchSpotifyTrackCandidate(title, artist, token)?.uri
    }

    /**
     * Enqueues a track URI directly into the user's active Spotify playback queue.
     * POST /v1/me/player/queue?uri=spotify:track:...
     */
    suspend fun enqueueTrack(trackUri: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.spotify.com/v1/me/player/queue?uri=${Uri.encode(trackUri)}"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                Log.i(TAG, "Enqueued track $trackUri into Spotify queue: code=${resp.code}")
                resp.isSuccessful || resp.code == 204
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue track $trackUri: ${e.message}")
            false
        }
    }

    /**
     * Immediately starts playing the clean sister track, delegating to synchronized playback.
     */
    @Deprecated("Use QueueSyncManager.executeSynchronizedSwap to preserve upcoming queue tracks")
    suspend fun playTrackImmediately(trackUri: String, token: String): Boolean {
        return QueueSyncManager.playSynchronizedQueue(listOf(trackUri), token)
    }

    private fun parseSuggestions(raw: String): List<SubstituteSuggestion> {
        val list = mutableListOf<SubstituteSuggestion>()
        try {
            // Find JSON array bounds
            val startIdx = raw.indexOf('[')
            val endIdx = raw.lastIndexOf(']')
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                val jsonStr = raw.substring(startIdx, endIdx + 1)
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val artist = obj.optString("artist").trim()
                    val matchReason = obj.optString("matchReason").trim()
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        list.add(SubstituteSuggestion(title, artist, matchReason = matchReason))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse vibe substitute JSON array: ${e.message}")
        }
        return list
    }
}
