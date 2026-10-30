package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.browser.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    // Using a concurrent set to handle background threads pushing sources
    private val foundMediaSources = ConcurrentHashMap.newKeySet<MediaSource>()
    private var currentUrl = ""

    data class MediaSource(val url: String, val type: String, val headers: Map<String, String> = emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupUI()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Add JavascriptInterface for direct communication
            addJavascriptInterface(WebAppInterface(this@MainActivity), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    currentUrl = url ?: ""
                    injectScriptToDetectMedia(view)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.let {
                        val url = it.url.toString()
                        checkAndAddMediaSource(url, it.requestHeaders)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {}
        }
    }

    private fun setupUI() {
        binding.goButton.setOnClickListener { loadUrl() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl()
                true
            } else {
                false
            }
        }

        binding.closePlayerButton.setOnClickListener {
            hidePlayer()
        }

        binding.fabMediaSources.setOnClickListener {
            showSourcesDialog()
        }
    }

    private fun loadUrl() {
        var url = binding.urlInput.text.toString()
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            binding.urlInput.setText(url)
            foundMediaSources.clear()
            updateFabVisibility()
            binding.webView.loadUrl(url)

            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        }
    }

    private fun injectScriptToDetectMedia(view: WebView?) {
        val script = """
            (function() {
                function findMediaElements(root) {
                    var sources = [];
                    // Find video and audio tags
                    var mediaElements = root.querySelectorAll('video, audio');
                    mediaElements.forEach(function(el) {
                        if (el.src) sources.push(el.src);
                        var sourceTags = el.querySelectorAll('source');
                        sourceTags.forEach(function(source) {
                            if (source.src) sources.push(source.src);
                        });
                    });

                    // Send found sources back to Android
                    if (sources.length > 0) {
                        sources.forEach(function(src) {
                            if (window.Android && window.Android.onMediaFound) {
                                window.Android.onMediaFound(src);
                            }
                        });
                    }
                }

                // Observe DOM changes to catch dynamically added media
                var observer = new MutationObserver(function(mutations) {
                    findMediaElements(document);
                });

                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });

                // Initial find
                findMediaElements(document);

                // Deep search in iframes
                var iframes = document.querySelectorAll('iframe');
                iframes.forEach(function(iframe) {
                    try {
                        if (iframe.contentDocument) {
                            findMediaElements(iframe.contentDocument);

                            var iframeObserver = new MutationObserver(function(mutations) {
                                findMediaElements(iframe.contentDocument);
                            });

                            iframeObserver.observe(iframe.contentDocument.body, {
                                childList: true,
                                subtree: true
                            });
                        }
                    } catch(e) {
                        // Cross-origin iframe, we rely on shouldInterceptRequest for these
                    }
                });
            })();
        """.trimIndent()

        view?.evaluateJavascript(script, null)
    }

    // Called from JavascriptInterface or WebViewClient
    fun checkAndAddMediaSource(url: String, headers: Map<String, String>? = null) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return

        val lowerUrl = url.lowercase()
        // Common video extensions and streaming formats
        if (lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8") ||
            lowerUrl.contains(".webm") || lowerUrl.contains(".mkv") ||
            (lowerUrl.contains("/video/") && !lowerUrl.contains(".html")) ||
            lowerUrl.contains("googlevideo.com/videoplayback") ||
            lowerUrl.contains(".ts")) {

            val type = when {
                lowerUrl.contains(".m3u8") -> "HLS"
                lowerUrl.contains(".mp4") -> "MP4"
                lowerUrl.contains(".webm") -> "WEBM"
                else -> "VIDEO"
            }

            // Create headers map with standard referer if not present
            val requestHeaders = headers?.toMutableMap() ?: mutableMapOf()
            if (!requestHeaders.containsKey("Referer") && currentUrl.isNotEmpty()) {
                requestHeaders["Referer"] = currentUrl
            }
            if (!requestHeaders.containsKey("User-Agent")) {
                requestHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
            }

            val source = MediaSource(url, type, requestHeaders)
            runOnUiThread {
                if (foundMediaSources.add(source)) {
                    updateFabVisibility()
                }
            }
        }
    }

    private fun updateFabVisibility() {
        runOnUiThread {
            binding.fabMediaSources.visibility = if (foundMediaSources.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showSourcesDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sources, null)
        dialog.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.sourcesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        // Convert to list for the adapter
        recyclerView.adapter = SourcesAdapter(foundMediaSources.toList()) { source ->
            dialog.dismiss()
            playMedia(source)
        }

        dialog.show()
    }

    private fun playMedia(source: MediaSource) {
        binding.playerContainer.visibility = View.VISIBLE

        // Setup headers using DefaultHttpDataSource.Factory
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

        // Set user agent specifically if it exists, default if not
        source.headers["User-Agent"]?.let { httpDataSourceFactory.setUserAgent(it) }

        // Apply all headers to default request properties
        val defaultRequestProperties = mutableMapOf<String, String>()
        for ((key, value) in source.headers) {
            defaultRequestProperties[key] = value
        }
        httpDataSourceFactory.setDefaultRequestProperties(defaultRequestProperties)

        // Create MediaSourceFactory with the HttpDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        // Release old player if exists
        exoPlayer?.release()

        // Build new player with custom MediaSourceFactory
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        binding.playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(source.url)

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    private fun hidePlayer() {
        binding.playerContainer.visibility = View.GONE
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onBackPressed() {
        if (binding.playerContainer.visibility == View.VISIBLE) {
            hidePlayer()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JavascriptInterface for direct communication from WebView injected script
    private inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun onMediaFound(url: String) {
            // Forward to the main check logic, without initial headers
            checkAndAddMediaSource(url)
        }
    }
}

class SourcesAdapter(
    private val sources: List<MainActivity.MediaSource>,
    private val onSourceClick: (MainActivity.MediaSource) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val urlText: TextView = view.findViewById(R.id.sourceUrlText)
        val typeText: TextView = view.findViewById(R.id.sourceTypeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]

        // Try to get just the filename or end of URL for display
        val displayUrl = try {
            val path = URI(source.url).path
            if (path != null && path.contains('/')) {
                path.substring(path.lastIndexOf('/') + 1).takeIf { it.isNotEmpty() } ?: source.url
            } else {
                source.url
            }
        } catch (e: URISyntaxException) {
            source.url
        }

        holder.urlText.text = if (displayUrl.length > 50) displayUrl.substring(0, 47) + "..." else displayUrl
        holder.typeText.text = source.type
        holder.itemView.setOnClickListener { onSourceClick(source) }
    }

    override fun getItemCount() = sources.size
}
