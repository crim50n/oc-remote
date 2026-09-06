package dev.minios.ocremote.ui.screens.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import dev.minios.ocremote.logging.AppLogger as Log
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.flow.SharedFlow
import dev.minios.ocremote.ui.components.ServerConnectionBanner

/**
 * WebView Screen - loads the remote OpenCode Web UI
 *
 * This replaces all native Chat/Session screens with the full-featured
 * web UI served by the OpenCode server, while the Android foreground
 * service keeps the SSE connection alive in the background.
 *
 * Features:
 * - Pull-to-refresh gesture for page reload
 * - System back button navigates WebView history
 * - Full-screen (no top bar)
 * - Reacts to deep-link navigation events (navigateUrlFlow) even when
 *   the WebView is already open, by calling loadUrl() on the existing instance.
 */
@Composable
fun WebViewScreen(
    serverUrl: String,
    username: String,
    password: String,
    serverName: String,
    initialPath: String = "",
    navigateUrlFlow: SharedFlow<String>? = null,
    isServerConnected: Boolean = true,
    isServerConnecting: Boolean = false,
    onConnectServer: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    // Build the full URL: serverUrl + initialPath (for session deep-links)
    val fullUrl = remember(serverUrl, initialPath) {
        if (initialPath.isNotBlank()) {
            serverUrl.trimEnd('/') + initialPath
        } else {
            serverUrl
        }
    }
    
    Log.d("WebViewScreen", "Composable invoked: serverUrl=$serverUrl, initialPath=$initialPath, fullUrl=$fullUrl")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasLoadedAnyUrl by remember(fullUrl) { mutableStateOf(false) }
    var needsOnlineReload by remember(fullUrl) { mutableStateOf(true) }
    var reconnectUrl by remember(fullUrl) { mutableStateOf(fullUrl) }
    val currentServerConnected by rememberUpdatedState(isServerConnected)

    // File chooser support for <input type="file"> in WebView
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        Log.d("WebViewScreen", "File chooser result: ${uris.size} files selected")
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    // Build Basic Auth header
    val authHeader = remember(username, password) {
        if (username.isNotBlank()) {
            val credentials = "$username:$password"
            "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        } else {
            null
        }
    }
    
    // Listen for navigation events from deep-links (notification taps while WebView is open)
    LaunchedEffect(navigateUrlFlow, isServerConnected) {
        navigateUrlFlow?.collect { newUrl ->
            if (!isServerConnected) return@collect
            Log.i("WebViewScreen", "Deep-link navigation received: $newUrl")
            webView?.let { wv ->
                val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                reconnectUrl = newUrl
                needsOnlineReload = true
                wv.loadUrl(newUrl, headers)
                hasLoadedAnyUrl = true
            }
        }
    }

    // Refresh handler
    fun refresh() {
        if (!isServerConnected) return
        webView?.let { wv ->
            isRefreshing = true
            val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
            reconnectUrl = serverUrl
            needsOnlineReload = true
            wv.loadUrl(serverUrl, headers)
            hasLoadedAnyUrl = true
        }
    }

    LaunchedEffect(isServerConnected, webView, fullUrl, authHeader) {
        val currentWebView = webView ?: return@LaunchedEffect
        if (isServerConnected) {
            currentWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            currentWebView.resumeTimers()
            currentWebView.onResume()
            if (needsOnlineReload) {
                currentWebView.loadUrl(reconnectUrl, authHeader?.let { mapOf("Authorization" to it) }.orEmpty())
                hasLoadedAnyUrl = true
            }
        } else {
            if (!hasLoadedAnyUrl) {
                currentWebView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                currentWebView.loadUrl(fullUrl, authHeader?.let { mapOf("Authorization" to it) }.orEmpty())
                hasLoadedAnyUrl = true
            } else {
                currentWebView.stopLoading()
            }
            currentWebView.onPause()
            currentWebView.pauseTimers()
            isLoading = false
            isRefreshing = false
        }
    }

    // Handle system back button: go back in WebView history, or exit if at root
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        if (!isServerConnected) {
            ServerConnectionBanner(connecting = isServerConnecting, onConnect = onConnectServer)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing),
            onRefresh = { refresh() },
            swipeEnabled = isServerConnected,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()  // Add padding for navigation bar
                    .imePadding()  // Shrink when keyboard appears
            ) {
            // WebView
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val wv = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowContentAccess = true
                            allowFileAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            // Allow WebSocket connections
                            javaScriptCanOpenWindowsAutomatically = true
                            // Cache settings for better offline experience
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // User agent
                            userAgentString = "$userAgentString OpenCodeAndroid/1.0"
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                Log.d("WebViewScreen", "Page load started")
                                url?.let { reconnectUrl = it }
                                if (currentServerConnected) needsOnlineReload = true
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                Log.d("WebViewScreen", "Page load finished")
                                if (currentServerConnected) needsOnlineReload = false
                                isLoading = false
                                isRefreshing = false
                            }

                            override fun onReceivedHttpAuthRequest(
                                view: WebView?,
                                handler: HttpAuthHandler?,
                                host: String?,
                                realm: String?
                            ) {
                                Log.d("WebViewScreen", "HTTP Auth requested for host=$host, realm=$realm")
                                if (username.isNotBlank()) {
                                    handler?.proceed(username, password)
                                } else {
                                    handler?.cancel()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                Log.e("WebViewScreen", "Page load failed (code=${error?.errorCode})")
                                // Only handle main frame errors
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    isRefreshing = false
                                }
                            }

                            // Stay inside the WebView for same-origin navigation
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                // Stay in WebView for same-origin requests
                                if (requestUrl.startsWith(serverUrl)) {
                                    return false
                                }
                                // Also stay for relative URLs (they resolve to same origin)
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                isLoading = newProgress < 100
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?
                            ): Boolean {
                                Log.d("WebViewScreen", "onShowFileChooser: mode=${params?.mode}, acceptTypes=${params?.acceptTypes?.toList()}")
                                // Cancel any previous pending callback
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = callback

                                val mimeTypes = params?.acceptTypes
                                    ?.filter { it.isNotBlank() }
                                    ?.toTypedArray()
                                    ?: arrayOf("*/*")
                                if (mimeTypes.isEmpty()) {
                                    fileChooserLauncher.launch(arrayOf("*/*"))
                                } else {
                                    fileChooserLauncher.launch(mimeTypes)
                                }
                                return true
                            }
                        }
                    }

                    webView = wv
                    wv
                },
                update = { /* WebView state is managed internally */ }
            )

            if (!isServerConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }

            // Loading indicator overlay
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
    }
    }
}
