package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.ZoneId
import java.util.Locale

/**
 * A Spotify Home/Explore item. This is discovery metadata only; playback must
 * be resolved through BitChord's configured non-Spotify catalogue/source.
 */
data class SpotifyFeedItem(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val kind: String,
)

data class SpotifyFeedSection(
    val id: String,
    val title: String,
    val items: List<SpotifyFeedItem>,
)

/**
 * Reads the same authenticated Spotify web-player feeds used by the official
 * client. These Pathfinder/Browse endpoints are internal and can change; all
 * parsing is defensive, and HTTP/API failures remain errors rather than being
 * reported as a successful empty feed.
 */
object SpotifyDiscovery {
    private const val HOME_HASH = "63c412a34a2071adfd99b804ea2fe1d8e9c5fd7d248e29ca54cc97a7ca06b561"
    private const val HOME_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val BROWSE_URL = "https://spclient.wg.spotify.com/hubview-mobile-v1/browse"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun home(): List<SpotifyFeedSection> = withContext(Dispatchers.IO) {
        val headers = SpotifyLibrary.discoveryHeaders()
        val variables = buildJsonObject {
            put("timeZone", ZoneId.systemDefault().id)
        }
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", HOME_HASH)
            }
        }
        val url = HOME_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "home")
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val root = requestJson(url.toString(), headers)
        root["errors"].arrayOrNull()?.firstOrNull()?.objOrNull()?.string("message")?.let { message ->
            error(message)
        }
        val sections = root.obj("data")?.obj("home")?.obj("sectionContainer")
            ?.obj("sections")?.array("items")
            ?: error("Spotify Home feed response did not contain sections")
        sections.mapIndexedNotNull { index, element ->
            val section = element.objOrNull() ?: return@mapIndexedNotNull null
            val title = section.obj("data")?.obj("title")?.string("text")
                ?: section.obj("title")?.string("text")
                ?: "For you"
            val rawItems = section.obj("sectionItems")?.array("items").orEmpty()
            val items = rawItems.mapNotNull { parseHomeItem(it) }
            if (items.isEmpty()) null else SpotifyFeedSection(
                id = section.string("uri") ?: "home-$index",
                title = title,
                items = items.distinctBy { it.uri },
            )
        }
    }

    suspend fun explore(includePodcasts: Boolean = true): List<SpotifyFeedSection> =
        withContext(Dispatchers.IO) {
            val locale = Locale.getDefault()
            val url = BROWSE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("platform", "android")
                .addQueryParameter("client-timezone", ZoneId.systemDefault().id)
                .addQueryParameter("podcast", includePodcasts.toString())
                .addQueryParameter("locale", locale.toString())
                .build()
            val headers = SpotifyLibrary.discoveryHeaders().toMutableMap().apply {
                put("App-Platform", "Android")
                put("Accept", "application/json")
                put("Accept-Language", locale.toLanguageTag())
                put("User-Agent", "Spotify/8.9.96.476 Android/31 (Android SDK built for x86_64)")
            }
            val root = requestJson(url.toString(), headers)
            val body = root.array("body")
                ?: error("Spotify Explore response did not contain a feed")
            val result = mutableListOf<SpotifyFeedSection>()
            var currentId = "explore"
            var currentTitle = "Explore"
            var currentItems = mutableListOf<SpotifyFeedItem>()

            fun flush() {
                if (currentItems.isNotEmpty()) {
                    result += SpotifyFeedSection(currentId, currentTitle, currentItems.distinctBy { it.uri })
                    currentItems = mutableListOf()
                }
            }

            body.forEachIndexed { index, element ->
                val item = element.objOrNull() ?: return@forEachIndexed
                val componentId = item.obj("component")?.string("id").orEmpty()
                val title = item.obj("text")?.string("title")
                if (componentId.contains("sectionHeader", ignoreCase = true) && !title.isNullOrBlank()) {
                    flush()
                    currentTitle = title
                    currentId = item.string("id") ?: "explore-section-$index"
                    return@forEachIndexed
                }
                val category = item.obj("component")?.string("category").orEmpty()
                if (!componentId.contains("categoryCard", ignoreCase = true) &&
                    !componentId.endsWith(":card") && category != "card"
                ) return@forEachIndexed
                if (title.isNullOrBlank()) return@forEachIndexed
                val uri = item.obj("metadata")?.string("uri")
                    ?: item.obj("events")?.obj("click")?.obj("data")?.string("uri")
                    ?: return@forEachIndexed
                currentItems += SpotifyFeedItem(
                    uri = uri,
                    title = title,
                    subtitle = item.obj("text")?.string("subtitle"),
                    imageUrl = findImageUrl(item.obj("images")),
                    kind = "category",
                )
            }
            flush()
            result
        }

    private fun requestJson(url: String, headers: Map<String, String>): JsonObject {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val payload = Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Spotify discovery request failed (${response.code})")
            }
            if (body.isBlank()) error("Spotify discovery response was empty")
            body
        }
        return json.parseToJsonElement(payload).jsonObject
    }

    private fun parseHomeItem(wrapper: JsonElement): SpotifyFeedItem? {
        val row = wrapper.objOrNull() ?: return null
        val content = row.obj("content") ?: return null
        val entity = content.obj("data") ?: return null
        val uri = row.string("uri") ?: entity.string("uri") ?: entity.string("_uri") ?: return null
        val title = entity.string("name")
            ?: entity.obj("title")?.string("text")
            ?: return null
        val artistNames = entity.obj("artists")?.array("items").orEmpty()
            .mapNotNull { artist -> artist.objOrNull()?.obj("profile")?.string("name") }
        val owner = entity.obj("ownerV2")?.obj("data")?.string("name")
        val subtitle = owner ?: artistNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: entity.string("description")
        return SpotifyFeedItem(
            uri = uri,
            title = title,
            subtitle = subtitle,
            imageUrl = findImageUrl(entity),
            kind = content.string("__typename")?.removeSuffix("ResponseWrapper") ?: "item",
        )
    }

    private fun findImageUrl(element: JsonElement?): String? {
        element ?: return null
        val obj = element.objOrNull()
        if (obj != null) {
            // Prefer the image source list over any unrelated URL elsewhere in the object.
            obj["sources"]?.let { sources ->
                sources.arrayOrNull()?.forEach { source ->
                    val url = source.objOrNull()?.string("url")?.let(::normalizeImageUrl)
                    if (url != null) return url
                }
            }
            for (key in listOf("main", "background", "images", "coverArt", "sources", "items")) {
                obj[key]?.let { nested -> findImageUrl(nested)?.let { return it } }
            }
            obj["url"]?.let { it.stringValue()?.let(::normalizeImageUrl)?.let { url -> return url } }
            obj["uri"]?.let { it.stringValue()?.let(::normalizeImageUrl)?.let { url -> return url } }
            obj.values.forEach { nested -> findImageUrl(nested)?.let { return it } }
        } else {
            val array = element.arrayOrNull()
            array?.forEach { nested -> findImageUrl(nested)?.let { return it } }
            val value = element.stringValue()?.let(::normalizeImageUrl)
            if (value != null) return value
        }
        return null
    }

    private fun normalizeImageUrl(value: String): String? = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("spotify:image:") -> "https://i.scdn.co/image/${value.substringAfterLast(':')}"
        else -> null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key].objOrNull()
    private fun JsonObject.array(key: String): JsonArray? = this[key].arrayOrNull()
    private fun JsonObject.string(key: String): String? = this[key].stringValue()

    private fun JsonElement?.obj(): JsonObject? = this?.objOrNull()
    private fun JsonElement?.objOrNull(): JsonObject? =
        this?.takeUnless { it == JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonElement?.array(key: String): JsonArray? = this.obj()?.get(key).arrayOrNull()
    private fun JsonElement?.arrayOrNull(): JsonArray? =
        this?.takeUnless { it == JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonElement?.string(key: String): String? = this.obj()?.get(key).stringValue()
    private fun JsonElement?.stringValue(): String? =
        this?.takeUnless { it == JsonNull }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
