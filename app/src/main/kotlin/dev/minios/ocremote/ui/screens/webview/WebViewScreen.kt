package dev.minios.ocremote.ui.screens.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.flow.SharedFlow

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
    LaunchedEffect(navigateUrlFlow) {
        navigateUrlFlow?.collect { newUrl ->
            Log.i("WebViewScreen", "Deep-link navigation received: $newUrl")
            webView?.let { wv ->
                val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                wv.loadUrl(newUrl, headers)
            }
        }
    }

    // Refresh handler
    fun refresh() {
        webView?.let { wv ->
            isRefreshing = true
            val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
            wv.loadUrl(serverUrl, headers)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing),
            onRefresh = { refresh() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()  // Add padding for status bar
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
                                Log.d("WebViewScreen", "Page started: $url")
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                Log.d("WebViewScreen", "Page finished: $url")
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
                                Log.e("WebViewScreen", "Error loading ${request?.url}: ${error?.description} (code=${error?.errorCode})")
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

                    // Load the full URL (with session path if deep-linked)
                    val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                    wv.loadUrl(fullUrl, headers)

                    webView = wv
                    wv
                },
                update = { /* WebView state is managed internally */ }
            )

            // Loading indicator overlay
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
    }
}
