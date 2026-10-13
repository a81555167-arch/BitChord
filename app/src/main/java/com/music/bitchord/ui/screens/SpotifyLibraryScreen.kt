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
    var showSpotifyHome by remember(cookie) { mutableStateOf(cookie.isNotBlank()) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    var lastHandledRefresh by remember { mutableIntStateOf(0) }
    BackHandler(enabled = showLogin) { showLogin = false }
val cacheAccountKey = remember(cookie) {
    cookie.takeIf { it.isNotBlank() }?.let(::spotifyCacheAccountKey)
}
    
LaunchedEffect(cookie, cacheAccountKey, refreshCounter, showSpotifyHome) {
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

    if (cookie.isNotBlank() && showSpotifyHome) {
        SpotifyHomeScreen(
            sessionCookie = cookie,
            modifier = modifier,
            contentPadding = contentPadding,
            onOpenLibrary = { showSpotifyHome = false },
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
                if (cookie.isNotBlank()) {
                    TextButton(onClick = { showSpotifyHome = true }) {
                        Text("Home")
                    }
                }
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

@Composable
private fun SpotifyHomeScreen(
    sessionCookie: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    onOpenLibrary: () -> Unit,
) {
    var rendererGeneration by remember { mutableIntStateOf(0) }
    var homeWebView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = homeWebView?.canGoBack() == true) {
        homeWebView?.goBack()
    }
    Column(modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.spotify_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Spotify Home",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenLibrary) { Text("Your Library") }
        }
        key(rendererGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // Prefer locally cached page resources to downloading them again.
                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        settings.mediaPlaybackRequiresUserGesture = true
                        // Present as a regular mobile browser, not an embedded WebView,
                        // which Spotify's web player may otherwise reject.
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace(" Version/4.0", "")
                        if (Build.VERSION.SDK_INT >= 33) {
                            settings.isAlgorithmicDarkeningAllowed = false
                        } else if (Build.VERSION.SDK_INT >= 29) {
                            @Suppress("DEPRECATION")
                            settings.forceDark = WebSettings.FORCE_DARK_OFF
                        }
                        setBackgroundColor(0xFF121212.toInt())
                        homeWebView = this
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)
                        // Rehydrate the session cookie if the WebView cookie jar was cleared
                        // while BitChord still has the linked account saved.
                        cookies.setCookie(
                            "https://open.spotify.com",
                            "sp_dc=$sessionCookie; Domain=.spotify.com; Path=/; Secure; HttpOnly",
                        ) {
                            cookies.flush()
                            loadUrl("https://open.spotify.com/")
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !isLoginHost(request.url)

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                homeWebView = null
                                rendererGeneration += 1
                                return true
                            }
                        }
                    }
                },
                onRelease = {
                    if (homeWebView === it) homeWebView = null
                    it.stopLoading()
                    it.destroy()
                },
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SPOTIFY_COOKIE_URLS = listOf(
    "https://open.spotify.com",
    "https://accounts.spotify.com",
    "https://www.spotify.com",
    "https://spotify.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "spotify.com", "spotifycdn.com", "scdn.co",
    "google.com", "gstatic.com", "recaptcha.net",
    "facebook.com", "apple.com",
    // Spotify can hand login verification to third-party CAPTCHA providers.
    "arkoselabs.com", "funcaptcha.com", "hcaptcha.com",
    "captcha-delivery.com", "cloudflare.com",
)

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isSpotifyHost(uri: Uri?) = hostMatches(uri, listOf("spotify.com"))

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

/**
 * Signs the login WebView out of Spotify. Disconnecting only forgot the
 * stored cookie, and the WebView's own jar would have signed the next
 * "Sign in" straight back in. Only Spotify's cookies go — the same jar holds
 * the YouTube Music sign-in.
 */
fun clearSpotifyWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.spotify.com")
        }
    }
    cookies.flush()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLogin(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sent by remember { mutableStateOf(false) }
    var loginGeneration by remember { mutableIntStateOf(0) }
    key(loginGeneration) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                if (Build.VERSION.SDK_INT >= 33) {
                    settings.isAlgorithmicDarkeningAllowed = false
                } else if (Build.VERSION.SDK_INT >= 29) {
                    @Suppress("DEPRECATION")
                    settings.forceDark = WebSettings.FORCE_DARK_OFF
                }
                setBackgroundColor(0xFF121212.toInt())
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    // Spotify's own pages and the identity providers its sign-in
                    // hands off to; anything else is not part of logging in.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isLoginHost(request.url)

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        // A dead Chromium renderer must not take BitChord down with it.
                        // Recreate the login WebView on the next Compose frame.
                        sent = false
                        loginGeneration += 1
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (isSpotifyHost(url?.toUri())) view.evaluateJavascript(LOGIN_LAYOUT_FIX, null)
                        if (sent || url?.startsWith("https://open.spotify.com") != true) return
                        val raw = CookieManager.getInstance().getCookie("https://open.spotify.com") ?: return
                        val token = raw.split(";")
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("sp_dc=") }
                            ?.substringAfter("=")
                            ?.takeIf { it.isNotBlank() }
                            ?: return
                        sent = true
                        onConnected(token)
                    }
                }
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )
    }
}
private fun spotifyCacheAccountKey(cookie: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(cookie.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
