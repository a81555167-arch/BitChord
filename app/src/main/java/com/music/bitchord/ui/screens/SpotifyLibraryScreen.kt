package com.music.bitchord.ui.screens
import com.music.bitchord.data.spotify.SpotifyLibraryCache
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.Res
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack

private const val LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private const val LOGIN_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val LOGIN_LAYOUT_FIX = """
    (function () {
      var id = 'bitchord-login-layout-fix';
      if (document.getElementById(id)) return;
      var st = document.createElement('style');
      st.id = id;
      st.textContent =
        'html, body { height: auto !important; min-height: 100% !important; overflow: visible !important; }' +
        'body > div { height: auto !important; min-height: 100% !important; }' +
        'main { position: static !important; height: auto !important; min-height: 100dvh !important;' +
        ' max-height: none !important; overflow: visible !important; }';
      (document.head || document.documentElement).appendChild(st);
    })();
"""

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var showLogin by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    var lastHandledRefresh by remember { mutableIntStateOf(0) }
    BackHandler(enabled = showLogin) { showLogin = false }
val cacheAccountKey = remember(cookie) {
    cookie.takeIf { it.isNotBlank() }?.let(::spotifyCacheAccountKey)
}
    
LaunchedEffect(cookie, cacheAccountKey, refreshCounter) {
    val accountKey = cacheAccountKey

    if (cookie.isBlank() || accountKey == null) {
        playlists = emptyList()
        loading = false
        error = null
        return@LaunchedEffect
    }
    if (showSpotifyHome) {
        loading = false
        return@LaunchedEffect
    }

    // Show saved playlists immediately. A fresh cache is a complete answer:
    // opening this tab repeatedly should not spend mobile data on the same pages.
    // A deliberate refresh bypasses the TTL so external Spotify edits can be pulled in.
    val manualRefresh = refreshCounter != lastHandledRefresh
    lastHandledRefresh = refreshCounter
    val cachedPlaylists = SpotifyLibraryCache.getPlaylists(accountKey)
    playlists = cachedPlaylists.orEmpty()
    error = null
    if (!manualRefresh && cachedPlaylists != null && SpotifyLibraryCache.arePlaylistsFresh(accountKey)) {
        loading = false
        return@LaunchedEffect
    }
    // A failed refresh should not be retried on every visit while mobile data
    // is weak. Keep the cached library visible and retry after the short cooldown,
    // unless the user explicitly requests Refresh.
    if (!manualRefresh && cachedPlaylists != null &&
        SpotifyLibraryCache.isFeedFailureCoolingDown(accountKey, "playlists")
    ) {
        loading = false
        return@LaunchedEffect
    }

    loading = true

    try {
        // Refresh the cache with the latest Spotify library.
        val freshPlaylists = SpotifyLibrary.playlists()

        SpotifyLibraryCache.savePlaylists(accountKey, freshPlaylists)
        SpotifyLibraryCache.clearFeedFailure(accountKey, "playlists")
        playlists = freshPlaylists
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpotifyLibraryCache.markFeedFailure(accountKey, "playlists")
        // Keep cached choices visible if Spotify is offline or the session expired.
        if (playlists.isEmpty()) {
            error = e.message ?: "Could not load Spotify library"
        }
    } finally {
        loading = false
    }
}


    if (showLogin) {
        SpotifyLogin(
            modifier = modifier.padding(contentPadding),
            onConnected = { token ->
                AppSettings.setSpotifySpdcToken(token)
                showLogin = false
            },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        cacheAccountKey?.let { accountKey ->
                            playlists.forEach { SpotifyLibraryCache.invalidateTracks(accountKey, it.id) }
                        }
                        refreshCounter++
                    },
                    enabled = !loading,
                ) {
                    Text("Refresh")
                }
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showLogin = true }) {
                        Text(stringResource(R.string.spotify_sign_in))
                    }
                }
            }
        } else {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { item ->
                LibraryRow(
                    title = item.name,
                    subtitle = item.owner.orEmpty(),
                    imageUrl = item.imageUrl,
                    onClick = { onOpenPlaylist(item) },
                )
            }
        }
    }
}
