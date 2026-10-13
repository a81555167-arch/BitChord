
package com.music.bitchord.data.spotify

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent cache for Spotify library metadata.
 * Authentication tokens and audio files are never stored here.
 */
object SpotifyLibraryCache {

    private const val DATABASE_NAME = "spotify_library_cache.db"
    private const val DATABASE_VERSION = 1
    private const val PLAYLIST_TTL_MS = 6L * 60 * 60 * 1000
    private const val TRACK_TTL_MS = 12L * 60 * 60 * 1000
    private const val TABLE = "cache_entries"
    private const val KEY_COLUMN = "cache_key"
    private const val VALUE_COLUMN = "cache_value"

    @Volatile
    private var helper: CacheDatabase? = null

    fun init(context: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) {
                    helper = CacheDatabase(context.applicationContext)
                }
            }
        }
    }

    private fun database(): SQLiteDatabase =
        checkNotNull(helper) {
            "SpotifyLibraryCache.init(context) must be called first"
        }.writableDatabase

    private fun read(key: String): String? {
        return database().query(
            TABLE,
            arrayOf(VALUE_COLUMN),
            "$KEY_COLUMN = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun write(key: String, value: String) {
        val values = ContentValues().apply {
            put(KEY_COLUMN, key)
            put(VALUE_COLUMN, value)
        }

        database().insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun savePlaylists(
        accountKey: String,
        playlists: List<SpotifyPlaylist>,
    ) {
        val array = JSONArray()

        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .putNullable("owner", playlist.owner)
                    .putNullable("imageUrl", playlist.imageUrl)
            )
        }

        write("playlists:$accountKey", array.toString())
        markFresh("playlists:$accountKey")
    }

    fun getPlaylists(accountKey: String): List<SpotifyPlaylist>? {
        val cached = read("playlists:$accountKey") ?: return null

        return runCatching {
            val array = JSONArray(cached)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)

                SpotifyPlaylist(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    owner = item.nullableString("owner"),
                    imageUrl = item.nullableString("imageUrl"),
                )
            }
        }.getOrNull()
    }

    fun saveTracks(
        accountKey: String,
        playlistId: String,
        tracks: List<SpotifyTrack>,
    ) {
        val array = JSONArray()

        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .putNullable("album", track.album)
                    .put("durationMs", track.durationMs)
                    .putNullable("imageUrl", track.imageUrl)
            )
        }

        write("tracks:$accountKey:$playlistId", array.toString())
        markFresh("tracks:$accountKey:$playlistId")
    }

    fun getTracks(
        accountKey: String,
        playlistId: String,
    ): List<SpotifyTrack>? {
        val cached = read("tracks:$accountKey:$playlistId") ?: return null

        return runCatching {
            val array = JSONArray(cached)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)

                SpotifyTrack(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    album = item.nullableString("album"),
                    durationMs = item.optInt("durationMs", 0),
                    imageUrl = item.nullableString("imageUrl"),
                )
            }
        }.getOrNull()
    }

    /** Avoid re-downloading an unchanged library on every screen visit. */
    fun arePlaylistsFresh(accountKey: String): Boolean =
        isFresh("playlists:$accountKey", PLAYLIST_TTL_MS)

    /** Track pages are larger, so keep a longer freshness window. */
    fun areTracksFresh(accountKey: String, playlistId: String): Boolean =
        isFresh("tracks:$accountKey:$playlistId", TRACK_TTL_MS)

    private fun markFresh(key: String) {
        write("updated:$key", System.currentTimeMillis().toString())
    }

    private fun isFresh(key: String, ttlMs: Long): Boolean {
        val updatedAt = read("updated:$key")?.toLongOrNull() ?: return false
        val age = System.currentTimeMillis() - updatedAt
        return age >= 0L && age < ttlMs
    }

    private class CacheDatabase(context: Context) :
        SQLiteOpenHelper(
            context,
            DATABASE_NAME,
            null,
            DATABASE_VERSION,
        ) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $KEY_COLUMN TEXT PRIMARY KEY NOT NULL,
                    $VALUE_COLUMN TEXT NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            // Preserve cached entries during upgrades.
        }
    }

    private fun JSONObject.putNullable(
        key: String,
        value: String?,
    ): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? {
        val value = opt(key)
        return if (value == null || value == JSONObject.NULL) {
            null
        } else {
            value as? String
        }
    }
}
