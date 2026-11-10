package com.hungrysturdy

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.nicehttp.NiceResponse
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// --- Cloudflare Bypass Helper ---
private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val CF_CHALLENGE_TITLES = listOf(
    "just a moment", "just a moment...", "checking your browser",
    "attention required", "ddos-guard", "one more step"
)

private const val COOKIE_TTL_MS = 15L * 60 * 60 * 1000
private const val SOLVER_TIMEOUT_MS = 120_000L
private const val POLL_INTERVAL_MS = 1000L
private const val CURSOR_STEP_DP = 10f

object AnimeWorldCFStore {
    private const val KEY_CF_COOKIES = "ANIMEWORLD_CF_COOKIES"
    private const val KEY_CF_UA = "ANIMEWORLD_CF_USER_AGENT"
    private const val KEY_CF_HOST = "ANIMEWORLD_CF_COOKIE_HOST"
    private const val KEY_CF_TIMESTAMP = "ANIMEWORLD_CF_TIMESTAMP"

    @Volatile private var cachedCookies: String? = null
    @Volatile private var cachedUA: String? = null
    @Volatile private var cachedHost: String? = null
    @Volatile private var cachedTimestamp: Long = 0L
    @Volatile private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        try {
            cachedCookies = CloudStreamApp.getKey<String>(KEY_CF_COOKIES)
            cachedUA = CloudStreamApp.getKey<String>(KEY_CF_UA)
            cachedHost = CloudStreamApp.getKey<String>(KEY_CF_HOST)
            cachedTimestamp = CloudStreamApp.getKey<String>(KEY_CF_TIMESTAMP)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {}
    }

    fun getCookies(): String? {
        val cookies = cachedCookies
        if (cookies.isNullOrBlank()) return null
        if (System.currentTimeMillis() - cachedTimestamp > COOKIE_TTL_MS) {
            clear()
            return null
        }
        return cookies
    }

    fun getUserAgent(): String? = cachedUA?.takeIf { it.isNotBlank() }

    fun save(cookies: String, userAgent: String, host: String) {
        cachedCookies = cookies
        cachedUA = userAgent
        cachedHost = host
        cachedTimestamp = System.currentTimeMillis()
        try {
            CloudStreamApp.setKey(KEY_CF_COOKIES, cookies)
            CloudStreamApp.setKey(KEY_CF_UA, userAgent)
            CloudStreamApp.setKey(KEY_CF_HOST, host)
            CloudStreamApp.setKey(KEY_CF_TIMESTAMP, cachedTimestamp.toString())
        } catch (_: Exception) {}
    }

    fun clear() {
        cachedCookies = null
        cachedUA = null
        cachedHost = null
        cachedTimestamp = 0L
        try {
            CloudStreamApp.setKey(KEY_CF_COOKIES, "")
            CloudStreamApp.setKey(KEY_CF_UA, "")
            CloudStreamApp.setKey(KEY_CF_HOST, "")
            CloudStreamApp.setKey(KEY_CF_TIMESTAMP, "")
        } catch (_: Exception) {}
    }
}

fun isAnimeWorldCloudflareBlocked(response: NiceResponse): Boolean {
    val code = response.code
    if (code == 503) return true
    val body = try { response.text.lowercase() } catch (_: Exception) { "" }
    if (code == 403 || body.contains("just a moment") || body.contains("challenge-platform") || body.contains("cloudflare")) {
        return true
    }
    return false
}

private fun isChallengeTitle(title: String): Boolean {
    val lower = title.lowercase()
    return CF_CHALLENGE_TITLES.any { lower.contains(it) }
}

private val cfBypassMutex = Mutex()
private class CursorPosHolder { var x: Float = 0f; var y: Float = 0f }

@SuppressLint("InflateParams")
private class AnimeWorldCFDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private var dialog: AlertDialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { targetUrl }
    }

    private fun extractAndFinish() {
        if (resolved.get()) return
        try {
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            if (cookieStr.contains("cf_clearance")) {
                finishSuccess(cookieStr)
            }
        } catch (_: Exception) {}
    }

    private fun finishSuccess(cookieStr: String) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        val ua = webView?.settings?.userAgentString ?: ""
        AnimeWorldCFStore.save(cookieStr, ua, targetHost)
        try {
            webView?.destroy()
            (webView?.getTag() as? Dialog)?.dismiss()
        } catch (_: Exception) {}
        try { onFinished?.invoke(true) } catch (_: Exception) {}
    }

    private fun finishFailure() {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        try {
            webView?.destroy()
            dialog?.dismiss()
        } catch (_: Exception) {}
        try { onFinished?.invoke(false) } catch (_: Exception) {}
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (resolved.get() || dialog == null || dialog?.isShowing != true) return
            pollElapsedMs += POLL_INTERVAL_MS
            extractAndFinish()
            if (!resolved.get()) {
                if (pollElapsedMs >= SOLVER_TIMEOUT_MS) {
                    finishFailure()
                } else {
                    statusText?.text = "Waiting... (${pollElapsedMs / 1000}s)"
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dialogW = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()
        val dialogH = (screenH * 0.9f).toInt()
        val webViewHeight = (screenH * 0.65f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        container.addView(TextView(activity).apply {
            text = "Cloudflare Bypass"
            textSize = 16f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        val statusView = TextView(activity).apply {
            text = "Loading..."
            textSize = 12f; setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        statusText = statusView
        container.addView(statusView)

        val isTv = try { Globals.isLayout(Globals.TV) } catch (_: Throwable) { false }
        container.addView(TextView(activity).apply {
            text = if (isTv) "Use D-pad to move cursor, OK to click."
            else "Solve the CAPTCHA below, then tap Done."
            textSize = 11f; setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        container.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = (8 * dp).toInt() }
        })

        val webContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, webViewHeight)
            isFocusable = true; isFocusableInTouchMode = true
        }
        webView = buildWebView(activity)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

        if (isTv) {
            val cursorSize = (22 * dp).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * dp).toInt(), Color.WHITE)
                }
                elevation = 999f
            }
            webContainer.addView(cursor)

            val pos = CursorPosHolder()
            pos.x = webViewHeight / 2f; pos.y = webViewHeight / 2f
            cursor.translationX = pos.x - cursorSize / 2f
            cursor.translationY = pos.y - cursorSize / 2f

            webContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    webContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    pos.x = webContainer.width / 2f; pos.y = webContainer.height / 2f
                    cursor.translationX = pos.x - cursorSize / 2f
                    cursor.translationY = pos.y - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * dp
            webContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(pos, cursor, cursorSize, webContainer, 0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(pos, cursor, cursorSize, webContainer, -step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(pos, cursor, cursorSize, webContainer, step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(pos, webView); true }
                    else -> false
                }
            }
            webContainer.requestFocus()
        }
        container.addView(webContainer)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = (8 * dp).toInt() }
        }
        btnContainer.addView(Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractAndFinish()
                if (!resolved.get()) statusText?.text = "No cf_clearance found."
            }
        })
        btnContainer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finishFailure() }
        })
        container.addView(btnContainer)

        dialog = AlertDialog.Builder(activity).setView(container).setCancelable(false).create()
        webView?.setTag(dialog)
        dialog?.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { webView?.destroy() } catch (_: Exception) {}
                try { onFinished?.invoke(false) } catch (_: Exception) {}
            }
        }
        dialog?.show()
        dialog?.window?.apply {
            setLayout(dialogW, dialogH)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                setCookie(targetHost, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
        handler.postDelayed({ finishFailure() }, SOLVER_TIMEOUT_MS)
    }

    private fun moveCursor(pos: CursorPosHolder, cursorView: View, cursorSize: Int, container: View, dx: Float, dy: Float) {
        pos.x = (pos.x + dx).coerceIn(0f, container.width.toFloat())
        pos.y = (pos.y + dy).coerceIn(0f, container.height.toFloat())
        cursorView.translationX = pos.x - cursorSize / 2f
        cursorView.translationY = pos.y - cursorSize / 2f
    }

    private fun clickAtCursor(pos: CursorPosHolder, webView: WebView?) {
        val wv = webView ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, pos.x, pos.y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, pos.x, pos.y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (_: Exception) {} finally {
            down.recycle()
            up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        return WebView(context).apply {
            isFocusable = true; isFocusableInTouchMode = true; requestFocus()
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true; allowFileAccess = true; loadsImagesAutomatically = true
                userAgentString = BROWSER_UA
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading... $newProgress%"
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (resolved.get()) return
                    val title = view?.title ?: ""
                    if (isChallengeTitle(title)) {
                        statusText?.text = "Challenge active - solve the CAPTCHA"
                        extractAndFinish()
                        return
                    }
                    statusText?.text = "Page loaded - checking cookies..."
                    extractAndFinish()
                }
            }
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        try {
            webView?.apply { stopLoading(); destroy() }
        } catch (_: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }
}

suspend fun showAnimeWorldCFBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val cfDialog = AnimeWorldCFDialog(url) { success ->
            if (cont.isActive) cont.resume(success)
        }
        try { cfDialog.show(activity) } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { cfDialog.dismiss() }
    }
}

suspend fun animeWorldGet(url: String, headers: Map<String, String> = emptyMap()): NiceResponse {
    val targetHost = try {
        val uri = Uri.parse(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }

    fun buildHeaders(): Map<String, String> {
        val h = headers.toMutableMap()
        if (!h.containsKey("Accept")) h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        if (!h.containsKey("User-Agent")) {
            h["User-Agent"] = AnimeWorldCFStore.getUserAgent() ?: BROWSER_UA
        }
        AnimeWorldCFStore.getCookies()?.let { h["Cookie"] = it }
        return h
    }

    var response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
    if (!isAnimeWorldCloudflareBlocked(response)) return response

    cfBypassMutex.withLock {
        if (AnimeWorldCFStore.getCookies() != null) {
            response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
            if (!isAnimeWorldCloudflareBlocked(response)) return response
        }
        AnimeWorldCFStore.clear()
        val bypassSuccess = showAnimeWorldCFBypassDialogAndWait(targetHost)
        if (!bypassSuccess) return@withLock
        for (attempt in 1..2) {
            response = app.get(url, headers = buildHeaders(), timeout = 30_000L)
            if (!isAnimeWorldCloudflareBlocked(response)) return@withLock
        }
    }
    return response
}

fun initAnimeWorldCFBypass() {
    AnimeWorldCFStore.init()
}

// --- AnimeWorld Provider ---
class AnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.one"
    override var name = "AnimeIndian"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    @Volatile
    private var isUrlLoaded = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseConfig(
        @JsonProperty("animeworldindia_url") val animeworldindia_url: String? = null,
        @JsonProperty("animeworld_url") val animeworld_url: String? = null,
        @JsonProperty("awi_url") val awi_url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ZephyrixVideo(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<FirebaseConfig>(response)
            val url = config.animeworldindia_url ?: config.animeworld_url ?: config.awi_url
            if (!url.isNullOrBlank()) mainUrl = url.removeSuffix("/")
            isUrlLoaded = true
        } catch (_: Exception) {
            isUrlLoaded = true
        }
    }

    override val mainPage = mainPageOf(
        "home" to "Latest Series",
        "crunchyroll" to "Crunchyroll",
        "netflix" to "Netflix",
        "primevideo" to "Amazon Prime",
        "disney" to "Disney+ Hotstar",
        "hidive" to "HIDIVE",
        "franchise" to "Franchises",
        "trending" to "Trending Anime",
        "popular" to "Popular Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        return try {
            val targetUrl = when (request.data) {
                "crunchyroll" -> "$mainUrl/category/network/crunchyroll"
                "netflix" -> "$mainUrl/category/network/netflix"
                "primevideo" -> "$mainUrl/category/network/amazon-prime"
                "disney" -> "$mainUrl/category/network/disney-hotstar"
                "hidive" -> "$mainUrl/category/network/hidive"
                "franchise" -> "$mainUrl/category/franchise/"
                else -> mainUrl
            }

            val response = animeWorldGet(targetUrl)
            val doc: Document = Jsoup.parse(response.text)

            val allItems = doc.select("li.post, article.post").mapNotNull { el: Element ->
                val link = el.selectFirst("a[href*=/series/], a[href*=/movies/]")?.attr("href") ?: return@mapNotNull null
                if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                val title = el.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.startsWith="//")) "https:$src" else src
                }
                val tvType = if (link.contains("/movies/")) TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

            when (request.data) {
                "crunchyroll" -> newHomePageResponse("Crunchyroll", allItems, hasNext = false)
                "netflix" -> newHomePageResponse("Netflix", allItems, hasNext = false)
                "primevideo" -> newHomePageResponse("Amazon Prime", allItems, hasNext = false)
                "disney" -> newHomePageResponse("Disney+ Hotstar", allItems, hasNext = false)
                "hidive" -> newHomePageResponse("HIDIVE", allItems, hasNext = false)
                "franchise" -> newHomePageResponse("Franchises", allItems, hasNext = false)
                "home" -> newHomePageResponse("Latest Series", allItems.take(20), hasNext = false)
                "trending" -> newHomePageResponse("Trending Anime", allItems.shuffled().take(20), hasNext = false)
                "popular" -> newHomePageResponse("Popular Series", allItems.reversed().take(20), hasNext = false)
                else -> newHomePageResponse(request.name, emptyList())
            }
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        loadFirebaseUrl()
        val results = mutableListOf<SearchResponse>()
        return try {
            for (pageNum in 1..3) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val response = animeWorldGet("$mainUrl/page/$pageNum/?s=$encoded")
                val doc: Document = Jsoup.parse(response.text)
                val items = doc.select("li.post, article.post").mapNotNull { el: Element ->
                    val seriesLink = el.selectFirst("a[href*=/series/]")?.attr("href")
                    val movieLink = el.selectFirst("a[href*=/movies/]")?.attr("href")
                    val link = seriesLink ?: movieLink ?: return@mapNotNull null
                    if (link.endsWith("/series/") || link.endsWith("/movies/")) return@mapNotNull null
                    val title = el.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
                    val poster = el.selectFirst("img")?.let { img ->
                        val src = img.attr("data-src").ifBlank { img.attr("src") }
                        if (src.startsWith="//")) "https:$src" else src
                    }
                    val tvType = if (link.contains("/movies/")) TvType.AnimeMovie else TvType.Anime
                    newAnimeSearchResponse(title, link, tvType) { this.posterUrl = poster }
                }.distinctBy { it.url }
                if (items.isEmpty()) break
                results.addAll(items)
            }
            results.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(url)
            val doc: Document = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img, .post-thumbnail img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith="//")) "https:$src" else src
            }
            val backdrop = doc.selectFirst(".backdrop img, .bg img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.startsWith="//")) "https:$src" else src
            }
            val plot = doc.selectFirst(".description, .synopsis, .plot, p")?.text()?.trim()
            val genres = doc.select("a[href*=genre]").map { it.text().trim() }.filter { it.isNotBlank() }

            val isMovie = url.contains("/movies/")

            if (isMovie) {
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop ?: poster
                    this.plot = plot
                    this.tags = genres
                }
            }

            val postId = doc.select("[data-post]").firstOrNull()?.attr("data-post") ?: ""

            val seasonTabs = doc.select("[data-season]").mapNotNull { el ->
                val seasonNum = el.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                val seasonName = el.text()?.trim()?.ifBlank { "Season $seasonNum" } ?: "Season $seasonNum"
                Pair(seasonNum, seasonName)
            }.distinctBy { it.first }.sortedBy { it.first }

            val initialEpLinks = doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
            val initialSeasonNum = initialEpLinks.firstOrNull()?.let {
                Regex("""(\d+)x(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: (seasonTabs.firstOrNull()?.first ?: 1)

            val episodes = mutableListOf<Episode>()

            val allSeasons = seasonTabs.map { it.first }.ifEmpty { listOf(initialSeasonNum) }
            val fullSeasons = (1..(allSeasons.maxOrNull() ?: initialSeasonNum)).toList()

            for (season in fullSeasons) {
                val epLinks = if (season == initialSeasonNum && initialEpLinks.isNotEmpty()) {
                    initialEpLinks
                } else {
                    fetchSeasonEpisodes(postId, season)
                }
                for (epUrl in epLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.count { it.season == season } + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: season
                    val cleanName = cleanEpisodeName(epUrl)
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = cleanName
                    })
                }
            }

            if (episodes.isEmpty() && initialEpLinks.isNotEmpty()) {
                for (epUrl in initialEpLinks) {
                    val match = Regex("""(\d+)x(\d+)""").find(epUrl)
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.size + 1)
                    val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episodes.add(newEpisode(epUrl) {
                        this.season = seasonNum
                        this.episode = epNum
                        this.name = cleanEpisodeName(epUrl)
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.tags = genres
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanEpisodeName(epUrl: String): String {
        val raw = epUrl.substringAfter("/episode/").trimEnd('/')
        val match = Regex("""(\d+)x(\d+)""").find(raw)
        val epNum = match?.groupValues?.get(2)?.toIntOrNull()
        val withoutNum = raw.replace(Regex("""\d+x\d+"""), "").trim('-')
        val parts = withoutNum.split("-").filter { it.isNotBlank() && it != "ep" && it != "episode" }
        val name = parts.joinToString(" ")
            .replace(Regex("\\s+/\\s*"), " ")
            .replace("/", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (name.isNotBlank()) name else "Episode ${epNum ?: ""}".trim()
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
            val response = animeWorldGet(ajaxUrl, headers = mapOf(
                "Referer" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val doc: Document = Jsoup.parse(response.text)
            doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val response = animeWorldGet(data)
            val doc: Document = Jsoup.parse(response.text)

            val iframes = doc.select("iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            var found = false
            for (iframe in iframes) {
                if (iframe.contains("zephyrix") || iframe.contains("zephyrflick")) {
                    val resolved = resolveZephyrix(iframe, subtitleCallback, callback)
                    if (resolved) found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveZephyrix(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val videoId = Regex("/video/([a-f0-9]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false
            val playerBase = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false

            val response = app.post(
                "$playerBase/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to "$mainUrl/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to playerBase,
                    "Referer" to "$playerBase/video/$videoId",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                timeout = 30_000L
            )
            val video = parseJson<ZephyrixVideo>(response.text)
            val streamUrl = video.videoSource ?: video.securedLink ?: return false
            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playerBase + streamUrl

            val link = newExtractorLink(
                "Anime World India",
                "Anime World India",
                absoluteUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$playerBase/"
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
            }
            callback.invoke(link)
            true
        } catch (_: Exception) {
            false
        }
    }
}
