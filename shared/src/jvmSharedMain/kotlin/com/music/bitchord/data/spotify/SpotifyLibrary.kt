package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    // Current + previous Spotify Web Player hashes, verified against Meld's
    // public registry on 2026-10-14. Spotify rotates persisted-query hashes;
    // gql() retries the previous hash and refreshes this registry on rejection.
    private const val LIBRARY = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    private const val PLAYLIST = "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a"
    private const val SEARCH_TRACKS = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    private const val SEARCH_DESKTOP = "db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345"
    private const val ADD_TO_PLAYLIST = "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a"
    private const val ADD_TO_LIBRARY = "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    private const val HASH_REGISTRY_URL = "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"

    private data class GqlHashPair(val current: String, val previous: String? = null)
    private val knownHashes = mapOf(
        "libraryV3" to GqlHashPair(LIBRARY, "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"),
        "fetchPlaylist" to GqlHashPair(PLAYLIST, "243c0ba2736f16da721e3a227004bbcdb8df6c846f198bd478172e00aa1faf42"),
        "fetchLibraryTracks" to GqlHashPair(LIKED),
        "searchDesktop" to GqlHashPair(SEARCH_DESKTOP, "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49"),
        "addToPlaylist" to GqlHashPair(ADD_TO_PLAYLIST, "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990"),
        "removeFromPlaylist" to GqlHashPair(ADD_TO_PLAYLIST, "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990"),
        "moveItemsInPlaylist" to GqlHashPair(ADD_TO_PLAYLIST, "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990"),
        "addToLibrary" to GqlHashPair(ADD_TO_LIBRARY, "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"),
        "removeFromLibrary" to GqlHashPair(ADD_TO_LIBRARY, "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a"),
    )
    @Volatile private var remoteHashes: Map<String, GqlHashPair> = emptyMap()
    @Volatile private var remoteHashesFetchedAt: Long = 0L
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** User-specific listening taste from Spotify's Web API; callers cache this for hours. */
    suspend fun topTracks(limit: Int = 6): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        webApiTracks(
            "https://api.spotify.com/v1/me/top/tracks?limit=${limit.coerceIn(1, 10)}&time_range=medium_term",
            headers,
            "items",
        )
    }

    /** Spotify's personalized recommendations, seeded from the listener's own top tracks. */
    suspend fun recommendations(seedTrackIds: List<String>, limit: Int = 6): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val seeds = seedTrackIds.filter { it.matches(Regex("[A-Za-z0-9]{22}")) }.distinct().take(5)
            if (seeds.isEmpty()) return@withContext emptyList()
            val headers = authHeaders()
            val encodedSeeds = java.net.URLEncoder.encode(seeds.joinToString(","), Charsets.UTF_8.name())
            webApiTracks(
                "https://api.spotify.com/v1/recommendations?limit=${limit.coerceIn(1, 10)}&seed_tracks=$encodedSeeds",
                headers,
                "tracks",
            )
        }

    private fun webApiTracks(
        url: String,
        headers: Map<String, String>,
        itemsKey: String,
    ): List<SpotifyTrack> {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val payload = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Spotify personalized feed unavailable (" + response.code + ")")
            }
            response.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(payload).jsonObject
        return root.arr(itemsKey).orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val id = item.str("id") ?: return@mapNotNull null
            val title = item.str("name").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val album = item.obj("album")
            val artists = item.arr("artists").orEmpty().mapNotNull { it.jsonObject.str("name") }
            val images = album?.arr("images").orEmpty()
            val artwork = images.mapNotNull { it.jsonObject.str("url") }.firstOrNull()
            SpotifyTrack(
                id = id,
                title = title,
                artist = artists.joinToString(", "),
                album = album?.str("name"),
                durationMs = item.int("duration_ms") ?: 0,
                imageUrl = artwork,
            )
        }
    }

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val (items, total, raw) = playlistPage(headers, offset)
            collected += items
            offset += raw
            if (raw == 0 || offset >= total) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = if (playlistId == LIKED_ID) {
                likedPage(headers, offset)
            } else {
                trackPage(headers, playlistId, offset)
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    }

    /**
     * Save/remove a track in Spotify's Liked Songs through the Web API.
     *
     * The current Web Player session may not carry the required library-modify
     * scope; in that case this throws a useful error instead of pretending the
     * heart synced successfully.
     */
    /** Save/remove a track in Spotify's Liked Songs using the Web Player mutation. */
    suspend fun setLiked(trackId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        require(trackId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify track id" }
        val headers = authHeaders()
        val operation = if (liked) "addToLibrary" else "removeFromLibrary"
        val variables = buildJsonObject {
            putJsonArray("libraryItemUris") { add("spotify:track:$trackId") }
        }
        gql(operation, ADD_TO_LIBRARY, variables, headers)
        Unit
    }

    /** Resolve a BitChord song to Spotify's catalogue when needed, then save it to Liked Songs. */
    suspend fun setLikedTrack(
        spotifyTrackId: String?,
        title: String,
        artist: String,
        liked: Boolean,
    ) = withContext(Dispatchers.IO) {
        val headers = authHeaders()
        val trackId = spotifyTrackId?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
            ?: searchTrackId(title, artist, headers)
            ?: throw IllegalStateException("Could not find this song in Spotify's catalogue")
        val operation = if (liked) "addToLibrary" else "removeFromLibrary"
        val variables = buildJsonObject {
            putJsonArray("libraryItemUris") { add("spotify:track:$trackId") }
        }
        gql(operation, ADD_TO_LIBRARY, variables, headers)
        trackId
    }

    /** Resolve a song once so every Spotify save/add entry point uses the same catalogue identity. */
    suspend fun resolveTrackId(spotifyTrackId: String?, title: String, artist: String): String =
        withContext(Dispatchers.IO) {
            spotifyTrackId?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
                ?: searchTrackId(title, artist, authHeaders())
                ?: throw IllegalStateException("Could not find this song in Spotify's catalogue")
        }

    /** Add a catalogue track to a Spotify playlist using the Web Player mutation. */
    suspend fun addTrackToPlaylist(
        playlistId: String,
        spotifyTrackId: String?,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify playlist id" }
        val headers = authHeaders()
        val trackId = spotifyTrackId?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
            ?: searchTrackId(title, artist, headers)
            ?: throw IllegalStateException("Could not find this song in Spotify's catalogue")
        val variables = buildJsonObject {
            put("playlistUri", "spotify:playlist:$playlistId")
            putJsonArray("playlistItemUris") { add("spotify:track:$trackId") }
            putJsonObject("newPosition") {
                put("moveType", "BOTTOM_OF_PLAYLIST")
                put("fromUid", JsonNull)
            }
        }
        gql("addToPlaylist", ADD_TO_PLAYLIST, variables, headers)
        trackId
    }

    /** Create a private playlist in the connected Spotify account. */
    suspend fun createPlaylist(title: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val name = title.trim()
        require(name.isNotEmpty()) { "Playlist name cannot be empty" }
        val headers = authHeaders()
        val profileRequest = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .get()
            .build()
        val profilePayload = Http.client.newCall(profileRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Spotify could not identify the connected account (" + response.code + ")")
            }
            text
        }
        val userId = json.parseToJsonElement(profilePayload).jsonObject.str("id")
            ?: throw IllegalStateException("Spotify did not return the connected account id")
        val body = buildJsonObject {
            put("name", name)
            put("public", false)
            put("collaborative", false)
            put("description", "Created with MyBit")
        }.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/users/$userId/playlists")
            .apply {
                headers.forEach { (key, value) -> header(key, value) }
                post(body)
            }
            .build()
        val payload = Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Spotify could not create this playlist (" + response.code + "). The connected session may not allow playlist creation."
                )
            }
            text
        }
        val item = json.parseToJsonElement(payload).jsonObject
        val id = item.str("id") ?: throw IllegalStateException("Spotify created a playlist but returned no id")
        SpotifyPlaylist(
            id = id,
            name = item.str("name") ?: name,
            owner = item.obj("owner")?.str("display_name"),
            imageUrl = item.obj("images")?.arr("items")?.firstOrNull()?.jsonObject?.str("url")
                ?: item.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
        )
    }

    /** Remove a playlist from the connected Spotify account's library. For owned playlists this deletes it for the owner. */
    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify playlist id" }
        val headers = authHeaders()
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId/followers")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .delete()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Spotify could not delete this playlist (" + response.code + "). Only playlists you own can be deleted."
                )
            }
        }
        Unit
    }

    /** Rename a Spotify playlist when the connected session permits playlist edits. */
    suspend fun renamePlaylist(playlistId: String, title: String) = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify playlist id" }
        require(title.isNotBlank()) { "Playlist name cannot be empty" }
        val headers = authHeaders()
        val body = buildJsonObject { put("name", title.trim()) }.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                method("PUT", body)
            }
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Spotify could not rename this playlist (" + response.code + "). The connected session may not allow playlist edits."
                )
            }
        }
    }

    /** Move one item in a Spotify playlist to a new zero-based position. */
    suspend fun reorderPlaylist(
        playlistId: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int = 1,
    ) = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify playlist id" }
        require(rangeStart >= 0 && insertBefore >= 0 && rangeLength > 0) { "Invalid Spotify playlist positions" }
        val headers = authHeaders()
        val body = buildJsonObject {
            put("range_start", rangeStart)
            put("insert_before", insertBefore)
            put("range_length", rangeLength)
        }.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                method("PUT", body)
            }
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Spotify could not reorder this playlist (" + response.code + "). The connected session may not allow playlist edits."
                )
            }
        }
    }

    /** Remove one track identity from a Spotify playlist. */
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify playlist id" }
        require(trackId.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify track id" }
        val headers = authHeaders()
        val body = buildJsonObject {
            putJsonArray("tracks") {
                add(buildJsonObject { put("uri", "spotify:track:$trackId") })
            }
        }.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                delete(body)
            }
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Spotify could not remove this track (" + response.code + "). The connected session may not allow playlist edits."
                )
            }
        }
    }

    /**
     * Resolve a YouTube/search result to Spotify's catalogue. The public REST
     * search endpoint rejects Web Player session tokens on some sessions, so
     * search through the same Pathfinder API used by Spotify itself first.
     * Older query hashes are tried as a fallback because Spotify rotates them.
     */
    private fun searchTrackId(title: String, artist: String, headers: Map<String, String>): String? {
        val term = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (term.isBlank()) return null

        fun scoreSearchResults(root: JsonObject): String? {
            val search = root.obj("data")?.obj("searchV2")
            val items = search?.obj("tracksV2")?.arr("items")
                ?: search?.obj("tracks")?.arr("items")
                ?: root.obj("data")?.obj("search")?.obj("tracks")?.arr("items")
                ?: root.obj("tracks")?.arr("items")
                ?: return null
            fun normalize(value: String) = value.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]"), "")
            val wantedTitle = normalize(title)
            val wantedArtist = normalize(artist)
            val candidates = items.mapNotNull { element ->
                val item = element.jsonObject
                val data = item.obj("data") ?: item.obj("item")?.obj("data") ?: item
                val uri = data.str("uri") ?: data.str("_uri") ?: item.str("_uri")
                val id = uri?.substringAfterLast(":")
                    ?: data.str("id")
                    ?: return@mapNotNull null
                if (!id.matches(Regex("[A-Za-z0-9]{22}"))) return@mapNotNull null
                val candidateTitle = data.str("name") ?: data.str("title").orEmpty()
                val artistNames = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artistItem ->
                    val obj = artistItem.jsonObject
                    obj.obj("profile")?.str("name") ?: obj.obj("data")?.str("name") ?: obj.str("name")
                } + data.arr("artists").orEmpty().mapNotNull { artistItem ->
                    artistItem.jsonObject.str("name")
                }
                val candidateArtist = artistNames.joinToString(" ")
                val normalizedTitle = normalize(candidateTitle)
                val normalizedArtist = normalize(candidateArtist)
                val titleMatches = wantedTitle.isNotBlank() &&
                    (normalizedTitle == wantedTitle || normalizedTitle.contains(wantedTitle) || wantedTitle.contains(normalizedTitle))
                val artistMatches = wantedArtist.isBlank() ||
                    normalizedArtist.contains(wantedArtist) || wantedArtist.contains(normalizedArtist)
                val score = (if (normalizedTitle == wantedTitle) 4 else if (titleMatches) 2 else 0) +
                    (if (artistMatches) 3 else 0)
                Triple(id, score, titleMatches && artistMatches)
            }
            return candidates.filter { it.third }.maxByOrNull { it.second }?.first
        }

        val variables = buildJsonObject {
            put("searchTerm", term)
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 10)
            put("includeAudiobooks", true)
            put("includePreReleases", false)
        }
        val desktopVariables = buildJsonObject {
            put("searchTerm", term)
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includeArtistHasConcertsField", false)
            put("includePreReleases", false)
            put("includeLocalConcertsField", false)
            put("includeAuthors", false)
        }
        // Prefer the current Web Player operation hash; try the alternate operation
        // for clients where Spotify still exposes searchTracks.
        runCatching { scoreSearchResults(gql("searchDesktop", SEARCH_DESKTOP, desktopVariables, headers)) }
            .getOrNull()?.let { return it }
        runCatching { scoreSearchResults(gql("searchTracks", SEARCH_TRACKS, variables, headers)) }
            .getOrNull()?.let { return it }

        // Last-resort compatibility path for a valid OAuth-backed session.
        val query = java.net.URLEncoder.encode("track:$title artist:$artist", Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$query&type=track&limit=10&market=from_token")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val payload = runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                response.body?.string().orEmpty()
            }
        }.getOrNull().orEmpty()
        if (payload.isBlank()) return null
        val items = runCatching {
            json.parseToJsonElement(payload).jsonObject.obj("tracks")?.arr("items").orEmpty()
        }.getOrDefault(emptyList())
        val wantedTitle = title.trim().lowercase()
        val wantedArtist = artist.trim().lowercase()
        return items.mapNotNull { element ->
            val item = element.jsonObject
            val id = item.str("id") ?: return@mapNotNull null
            val name = item.str("name").orEmpty().trim().lowercase()
            val artists = item.arr("artists").orEmpty().mapNotNull { it.jsonObject.str("name") }
                .joinToString(" ").lowercase()
            val titleMatches = name == wantedTitle || name.contains(wantedTitle) || wantedTitle.contains(name)
            val artistMatches = wantedArtist.isBlank() || artists.contains(wantedArtist) || wantedArtist.contains(artists)
            id.takeIf { titleMatches && artistMatches }
        }.firstOrNull()
    }

    private fun playlistPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return parsePlaylistPage(gql("libraryV3", LIBRARY, variables, headers))
    }

    private fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", PLAYLIST, variables, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", PLAYLIST, variables, authHeaders())
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    }

    private fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", LIKED, variables, headers))
    }

    /**
     * Poll Spotify's own playlist query after a write. Playlist additions are
     * appended at the end, so checking the last page avoids downloading a
     * whole large playlist just to confirm one mutation.
     */
    private suspend fun verifyPlaylistTrack(
        headers: Map<String, String>,
        playlistId: String,
        trackId: String,
    ) {
        repeat(3) { attempt ->
            val firstPage = trackPage(headers, playlistId, 0)
            val lastOffset = if (firstPage.second <= 0) 0 else ((firstPage.second - 1) / 100) * 100
            val lastPage = if (lastOffset == 0) firstPage else trackPage(headers, playlistId, lastOffset)
            if (firstPage.first.any { it.id == trackId } || lastPage.first.any { it.id == trackId }) return
            if (attempt < 2) delay(400)
        }
        throw IllegalStateException(
            "Spotify did not confirm that the song was added to this playlist. Refresh Spotify and try again."
        )
    }

    /** Confirm a newly liked song is actually present in Spotify's Liked Songs library. */
    private suspend fun verifyLikedTrack(headers: Map<String, String>, trackId: String) {
        repeat(3) { attempt ->
            val firstPage = likedPage(headers, 0)
            val lastOffset = if (firstPage.second <= 0) 0 else ((firstPage.second - 1) / 100) * 100
            val lastPage = if (lastOffset == 0) firstPage else likedPage(headers, lastOffset)
            if (firstPage.first.any { it.id == trackId } || lastPage.first.any { it.id == trackId }) return
            if (attempt < 2) delay(400)
        }
        throw IllegalStateException(
            "Spotify did not confirm that the song was added to Liked Songs. Refresh Spotify and try again."
        )
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw IllegalStateException("Spotify sign-in expired")
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
        }
    }

    private fun gql(operation: String, hash: String, variables: JsonObject, headers: Map<String, String>): JsonObject {
        fun attempt(candidate: String): JsonObject? {
            val body = buildJsonObject {
                put("variables", variables)
                put("operationName", operation)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", candidate)
                    }
                }
            }
            val response = Http.client.newCall(
                Request.Builder()
                    .url(GQL)
                    .post(body.toString().toRequestBody(mediaType))
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build(),
            ).execute()
            val payload = response.use { it.body?.string().orEmpty() }
            if (response.code == 412 || payload.contains("PersistedQueryNotFound", ignoreCase = true)) return null
            if (!response.isSuccessful) {
                throw IllegalStateException("Spotify $operation failed (${response.code}): ${payload.take(240)}")
            }
            val root = json.parseToJsonElement(payload).jsonObject
            val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
            if (!error.isNullOrBlank()) {
                if (error.contains("PersistedQueryNotFound", ignoreCase = true)) return null
                throw IllegalStateException(error)
            }
            return root
        }

        fun candidates(pairs: List<GqlHashPair>): List<String> =
            (pairs.flatMap { listOfNotNull(it.current, it.previous) } + hash).distinct()

        val localPair = knownHashes[operation]
        for (candidate in candidates(listOfNotNull(remoteHashes[operation], localPair))) {
            attempt(candidate)?.let { return it }
        }

        // All bundled hashes were rejected. Refresh once from Meld's maintained
        // registry, then retry the newly published hash and its previous value.
        val refreshed = fetchRemoteHashes(force = true)
        for (candidate in candidates(listOfNotNull(refreshed[operation], remoteHashes[operation], localPair))) {
            attempt(candidate)?.let { return it }
        }
        throw IllegalStateException("Spotify rejected every known persisted-query hash for $operation")
    }

    private fun fetchRemoteHashes(force: Boolean = false): Map<String, GqlHashPair> {
        val now = System.currentTimeMillis()
        if (!force && remoteHashes.isNotEmpty() && now - remoteHashesFetchedAt < 6 * 60 * 60 * 1000L) return remoteHashes
        return try {
            val request = Request.Builder().url(HASH_REGISTRY_URL).header("Accept", "application/json").get().build()
            val payload = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Hash registry HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val operations = json.parseToJsonElement(payload).jsonObject.obj("operations") ?: return remoteHashes
            val parsed = operations.mapNotNull { (name, value) ->
                val item = value.jsonObject
                val current = item.str("hash")?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) } ?: return@mapNotNull null
                val previous = item.str("previous_hash")?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }
                name to GqlHashPair(current, previous)
            }.toMap()
            if (parsed.isNotEmpty()) {
                remoteHashes = parsed
                remoteHashesFetchedAt = now
            }
            remoteHashes
        } catch (_: Exception) {
            remoteHashes
        }
    }
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
