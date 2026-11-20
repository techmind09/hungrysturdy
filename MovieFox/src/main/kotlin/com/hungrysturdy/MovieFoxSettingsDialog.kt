package com.hungrysturdy

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object MovieFoxSettingsDialog {

    private const val PREFS_NAME = "moviefox_ultra_max_v9"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_AUDIO_PREFIX = "mf_audio_"
    private const val KEY_FAIL_PREFIX = "mf_fail_"
    private const val KEY_LAST_MS_PREFIX = "mf_ms_"
    private const val KEY_NUVIO_MANIFESTS = "mf_nuvio_manifests_json"
    private const val KEY_QUALITY = "mf_preferred_quality"
    private const val KEY_LOW_BUFFER = "mf_low_buffer_mode"
    private const val KEY_FAST_FETCH = "mf_fast_fetch_mode"
    private const val KEY_AUTO_DISABLE_ERR = "mf_auto_disable_err"
    private const val KEY_CONCURRENCY = "mf_concurrency"
    private const val KEY_TIMEOUT_SEC = "mf_timeout_sec"

    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cachedPrefs: SharedPreferences? = null
    private var cachedManifests: MutableList<NuvioManifestConfig>? = null

    data class ServerItem(
        val key: String,
        val name: String,
        val tag: String,
        val probeUrl: String,
        val accentHex: String
    )

    data class AudioOption(
        val key: String,
        val title: String,
        val subtitle: String,
        val defaultOn: Boolean = true
    )

    data class NuvioManifestConfig(
        val id: String,
        val label: String,
        val manifestUrl: String,
        var enabled: Boolean = true
    )

    data class CustomExternalSite(
        val id: String,
        val name: String,
        val baseUrl: String,
        var enabled: Boolean = true
    )

    val SERVERS = listOf(
        // Existing MovieFox & provider servers
        ServerItem("purstream", "Purstream", "API", "https://moviefox.pro/api/stream/purstream", "#38BDF8"),
        ServerItem("vegamovies", "VegaMovies", "SITE", "https://vegamovies.lease/", "#EC4899"),
        ServerItem("sflix", "Sflix", "SITE", "https://sflix.film/", "#F97316"),
        ServerItem("castle", "Castle", "API", "https://moviefox.pro/api/stream/castle", "#818CF8"),
        ServerItem("watchout", "Watchout", "API", "https://moviefox.pro/api/stream/watchout", "#2DD4BF"),
        ServerItem("prmovies", "Prmovies", "SITE", "https://prmovies.coupons/", "#F59E0B"),
        ServerItem("hindmoviez", "HindMoviez", "SITE", "https://v3.hindmovie.im/", "#14B8A6"),
        ServerItem("play4u", "Play4u", "HLS", "https://play4u.org/", "#FB7185"),
        ServerItem("nuvio", "Nuvio Addon", "MANIFEST", "https://nuvio.app/", "#A855F7"),
        ServerItem("moviesdrive", "MoviesDrive", "DRIVE", "https://new3.moviesdrive.cyou/", "#34D399"),

        // MovieBox API servers
        ServerItem("mb_apig", "MovieBox APIG", "API", "https://apig.inmoviebox.com", "#38BDF8"),
        ServerItem("mb_apii", "MovieBox APII", "API", "https://apii.inmoviebox.com", "#60A5FA"),
        ServerItem("mb_api3", "MovieBox API 3", "API", "https://api3.aoneroom.com", "#818CF8"),
        ServerItem("mb_api4", "MovieBox API 4", "API", "https://api4.aoneroom.com", "#A78BFA"),
        ServerItem("mb_api4sg", "MovieBox API 4 SG", "API", "https://api4sg.aoneroom.com", "#C084FC"),
        ServerItem("mb_api5", "MovieBox API 5", "API", "https://api5.aoneroom.com", "#F472B6"),
        ServerItem("mb_api6", "MovieBox API 6", "API", "https://api6.aoneroom.com", "#FB7185"),
        ServerItem("mb_api7", "MovieBox API 7", "API", "https://api7.aoneroom.com", "#F59E0B"),

        // MovieBox player and supporting services
        ServerItem("mb_player", "MovieBox Player", "PLAYER", "https://play.88player.top/", "#34D399"),
        ServerItem("mb_gslb", "MovieBox GSLB", "API", "https://gslb.shalltry.com/", "#2DD4BF"),
        ServerItem("mb_sport", "MovieBox Sport API", "API", "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff", "#FBBF24")
    )

    val AUDIO_TRACKS = listOf(
        AudioOption("original", "Original", "Original audio track", true),
        AudioOption("hindi", "Hindi Dub", "Hindi dubbed audio", true),
        AudioOption("english", "English Dub", "English audio track", true),
        AudioOption("korean", "Korean Dub", "Korean audio / dub", true),
        AudioOption("japanese", "Japanese Dub", "Japanese audio / dub", true),
        AudioOption("tamil", "Tamil Dub", "Tamil dubbed audio", true),
        AudioOption("telugu", "Telugu Dub", "Telugu dubbed audio", true),
        AudioOption("punjabi", "Punjabi Dub", "Punjabi dubbed audio", true),
        AudioOption("spanish", "Spanish Dub", "Spanish dubbed audio", false),
        AudioOption("french", "French Dub", "French dubbed audio", false),
        AudioOption("arabic", "Arabic Dub", "Arabic dubbed audio", false),
        AudioOption("bengali", "Bengali Dub", "Bengali dubbed audio", false)
    )

    val QUALITIES = listOf("AUTO", "2160p", "1080p", "720p", "480p")

    private fun prefs(ctx: Context): SharedPreferences =
        cachedPrefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }

    fun isServerEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled).apply()
    }

    fun reportServerResult(ctx: Context?, key: String, success: Boolean, latencyMs: Long = 0L) {
        if (ctx == null) return
        val k = key.lowercase().trim()
        val p = prefs(ctx)
        if (success) {
            p.edit()
                .putInt("$KEY_FAIL_PREFIX$k", 0)
                .putLong("$KEY_LAST_MS_PREFIX$k", latencyMs)
                .apply()
        } else {
            val fails = p.getInt("$KEY_FAIL_PREFIX$k", 0) + 1
            val editor = p.edit().putInt("$KEY_FAIL_PREFIX$k", fails)
            if (fails >= 2 && isAutoDisableErrorServers(ctx)) {
                editor.putBoolean("$KEY_SRV_PREFIX$k", false)
            }
            editor.apply()
        }
    }

    fun getServerFailCount(ctx: Context, key: String): Int =
        prefs(ctx).getInt("$KEY_FAIL_PREFIX${key.lowercase()}", 0)

    fun getServerLastMs(ctx: Context, key: String): Long =
        prefs(ctx).getLong("$KEY_LAST_MS_PREFIX${key.lowercase()}", -1L)

    fun resetAllHealthStats(ctx: Context) {
        val editor = prefs(ctx).edit()
        SERVERS.forEach { srv ->
            editor.remove("$KEY_FAIL_PREFIX${srv.key}")
            editor.remove("$KEY_LAST_MS_PREFIX${srv.key}")
        }
        editor.apply()
    }

    fun isAudioEnabled(ctx: Context, key: String): Boolean {
        val def = AUDIO_TRACKS.find { it.key == key }?.defaultOn ?: true
        return prefs(ctx).getBoolean("$KEY_AUDIO_PREFIX$key", def)
    }

    fun setAudioEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_AUDIO_PREFIX$key", enabled).apply()
    }

    fun getNuvioManifests(ctx: Context): MutableList<NuvioManifestConfig> {
        cachedManifests?.let { return it }
        val raw = prefs(ctx).getString(KEY_NUVIO_MANIFESTS, "[]") ?: "[]"
        val list = mutableListOf<NuvioManifestConfig>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("manifestUrl", "").trim()
                if (url.isNotBlank()) {
                    list.add(
                        NuvioManifestConfig(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            label = obj.optString("label", deriveManifestLabel(url)),
                            manifestUrl = url,
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }
        cachedManifests = list
        return list
    }

    fun saveNuvioManifests(ctx: Context, list: List<NuvioManifestConfig>) {
        cachedManifests = list.toMutableList()
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("manifestUrl", item.manifestUrl)
                    put("enabled", item.enabled)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_NUVIO_MANIFESTS, arr.toString()).apply()
    }

    fun getCustomSites(ctx: Context): List<CustomExternalSite> {
        return getNuvioManifests(ctx).map { m ->
            CustomExternalSite(
                id = m.id,
                name = m.label,
                baseUrl = m.manifestUrl,
                enabled = m.enabled
            )
        }
    }

    fun deriveManifestLabel(rawUrl: String): String {
        return try {
            val normalized = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            val host = URI(normalized).host.orEmpty().removePrefix("www.")
            host.replaceFirstChar { it.uppercase() }.ifBlank { "Nuvio Manifest" }
        } catch (_: Exception) {
            "Nuvio Manifest"
        }
    }

    fun getPreferredQuality(ctx: Context): String =
        prefs(ctx).getString(KEY_QUALITY, "AUTO") ?: "AUTO"

    fun setPreferredQuality(ctx: Context, quality: String) {
        prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()
    }

    fun isLowBufferMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)

    fun setLowBufferMode(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()
    }

    fun isFastFetchMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FAST_FETCH, true)

    fun setFastFetchMode(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FAST_FETCH, enabled).apply()
    }

    fun isAutoDisableErrorServers(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_DISABLE_ERR, true)

    fun setAutoDisableErrorServers(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DISABLE_ERR, enabled).apply()
    }

    fun isStreamOnly(ctx: Context): Boolean = isLowBufferMode(ctx)
    fun isDownloadOnly(ctx: Context): Boolean = false
    fun getParallelServers(ctx: Context): Int = getConcurrency(ctx)

    fun getConcurrency(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CONCURRENCY, 14)

    fun setConcurrency(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 24)).apply()
    }

    fun getTimeoutSeconds(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TIMEOUT_SEC, 5)

    fun setTimeoutSeconds(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(3, 20)).apply()
    }

    private fun dp(ctx: Context, px: Int): Int =
        (px * ctx.resources.displayMetrics.density).toInt()

    private fun cardBg(
        bgHex: String = "#141822",
        strokeHex: String = "#252E42",
        radius: Float = 22f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(bgHex))
        cornerRadius = radius
        setStroke(strokeWidth, Color.parseColor(strokeHex))
    }

    private fun pillBg(bgHex: String, radius: Float = 16f): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(bgHex))
        cornerRadius = radius
    }

    private suspend fun probeEndpoint(urlStr: String, timeoutMs: Int = 2500): Pair<Boolean, Long> =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val fixedUrl = if (urlStr.startsWith("http")) urlStr else "https://$urlStr"
                val conn = (URL(fixedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                }
                val code = conn.responseCode
                conn.disconnect()
                val elapsed = System.currentTimeMillis() - start
                (code in 200..499) to elapsed
            } catch (_: Exception) {
                false to (System.currentTimeMillis() - start)
            }
        }

    fun show(ctx: Context) {
        val nuvioList = getNuvioManifests(ctx).toMutableList()
        val serverSwitches = mutableMapOf<String, Switch>()
        val serverCards = mutableMapOf<String, LinearLayout>()
        val serverStatusBadges = mutableMapOf<String, TextView>()
        val failedServerKeys = mutableSetOf<String>()
        val audioSwitches = mutableMapOf<String, Switch>()
        val audioCards = mutableMapOf<String, LinearLayout>()

        var currentQuery = ""
        var selectedQuality = getPreferredQuality(ctx)
        var currentConcurrency = getConcurrency(ctx)
        var autoDisableErrors = isAutoDisableErrorServers(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#07090F", "#1E273B", 34f, 3)
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#0B192C"),
                    Color.parseColor("#1E153D"),
                    Color.parseColor("#08272E")
                )
            ).apply {
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#2E3D59"))
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX"
            textSize = 17f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        topRow.addView(TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            textSize = 9.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            background = pillBg("#0D2B22", 14f)
            setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4))
        })
        header.addView(topRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }

        fun statChip(label: String, colorHex: String, marginEndDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg("#0A0E17", "#26324A", 14f, 2)
                setPadding(dp(ctx, 9), dp(ctx, 6), dp(ctx, 9), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (marginEndDp > 0) marginEnd = dp(ctx, marginEndDp)
                }
            }
            val valTv = TextView(ctx).apply {
                text = "-"
                textSize = 13.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
            }
            val lblTv = TextView(ctx).apply {
                text = label
                textSize = 9f
                isSingleLine = true
                setTextColor(Color.parseColor("#94A3B8"))
            }
            box.addView(valTv)
            box.addView(lblTv)
            return box to valTv
        }

        val (srvChip, srvValTv) = statChip("SERVERS", "#38BDF8", 6)
        val (nuvioChip, nuvioValTv) = statChip("NUVIO", "#C084FC", 6)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)

        statsRow.addView(srvChip)
        statsRow.addView(nuvioChip)
        statsRow.addView(spdChip)
        header.addView(statsRow)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        )

        fun refreshStats() {
            val srvOn = if (serverSwitches.isEmpty()) SERVERS.count { isServerEnabled(ctx, it.key) }
            else serverSwitches.values.count { it.isChecked }
            val nuvioOn = nuvioList.count { it.enabled }
            srvValTv.text = "$srvOn/${SERVERS.size}"
            nuvioValTv.text = "$nuvioOn/${nuvioList.size}"
            spdValTv.text = "${currentConcurrency}x • $selectedQuality"
        }
        refreshStats()

        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 18f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }

        val tabTitles = listOf("Servers", "Nuvio Manifest", "Audio & Quality", "Buffer & Speed")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title
                textSize = 11.5f
                isAllCaps = false
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        tabScroll.addView(tabBar)
        root.addView(tabScroll)

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val searchBox = EditText(ctx).apply {
            hint = "🔍 Filter..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            background = cardBg("#10141E", "#253046", 15f)
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 6) }
        }

        val testAllBtn = Button(ctx).apply {
            text = "TEST ALL"
            textSize = 10f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#0284C7", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)).apply {
                marginEnd = dp(ctx, 5)
            }
        }

        val offErrorsBtn = Button(ctx).apply {
            text = "OFF ERRORS"
            textSize = 10f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#DC2626", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36))
        }

        actionRow.addView(searchBox)
        actionRow.addView(testAllBtn)
        actionRow.addView(offErrorsBtn)
        root.addView(actionRow)

        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 365)
            )
        }

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val serversPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nuvioPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val audioPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val speedPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        fun updateServerBadge(key: String, ok: Boolean, ms: Long) {
            val statusTv = serverStatusBadges[key] ?: return
            reportServerResult(ctx, key, ok, ms)
            if (ok) {
                failedServerKeys.remove(key)
                statusTv.text = "OK ${ms}ms"
                statusTv.setTextColor(Color.parseColor("#34D399"))
                statusTv.background = pillBg("#0D2B22", 10f)
            } else {
                failedServerKeys.add(key)
                statusTv.text = "ERROR"
                statusTv.setTextColor(Color.parseColor("#F87171"))
                statusTv.background = pillBg("#2B1216", 10f)
                if (autoDisableErrors) {
                    serverSwitches[key]?.isChecked = false
                    setServerEnabled(ctx, key, false)
                    refreshStats()
                }
            }
        }

        SERVERS.forEach { srv ->
            val fails = getServerFailCount(ctx, srv.key)
            val lastMs = getServerLastMs(ctx, srv.key)
            if (fails >= 1) failedServerKeys.add(srv.key)

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            topLine.addView(TextView(ctx).apply {
                text = srv.name
                textSize = 13.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })

            topLine.addView(TextView(ctx).apply {
                text = srv.tag
                textSize = 8.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(srv.accentHex))
                background = pillBg("#192236", 8f)
                setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 5) }
            })

            val statusTv = TextView(ctx).apply {
                text = when {
                    fails >= 2 -> "ERROR (${fails}x)"
                    fails == 1 -> "SLOW/ERR"
                    lastMs >= 0L -> "OK ${lastMs}ms"
                    else -> "READY"
                }
                textSize = 8.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    Color.parseColor(
                        when {
                            fails >= 1 -> "#F87171"
                            lastMs >= 0L -> "#34D399"
                            else -> "#94A3B8"
                        }
                    )
                )
                background = pillBg(
                    when {
                        fails >= 1 -> "#2B1216"
                        lastMs >= 0L -> "#0D2B22"
                        else -> "#1C2230"
                    },
                    8f
                )
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 5) }
            }
            topLine.addView(statusTv)
            serverStatusBadges[srv.key] = statusTv

            col.addView(topLine)
            col.addView(TextView(ctx).apply {
                text = srv.probeUrl
                textSize = 10f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#7C869C"))
                setPadding(0, dp(ctx, 2), dp(ctx, 6), 0)
            })

            val pingBtn = TextView(ctx).apply {
                text = "TEST"
                textSize = 9.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                background = pillBg("#0D2338", 12f)
                setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    statusTv.text = "..."
                    statusTv.setTextColor(Color.parseColor("#FBBF24"))
                    dialogScope.launch {
                        val (ok, ms) = probeEndpoint(srv.probeUrl)
                        updateServerBadge(srv.key, ok, ms)
                    }
                }
            }

            val sw = Switch(ctx).apply {
                isChecked = isServerEnabled(ctx, srv.key)
                setOnCheckedChangeListener { _, checked ->
                    setServerEnabled(ctx, srv.key, checked)
                    refreshStats()
                }
            }
            serverSwitches[srv.key] = sw
            serverCards[srv.key] = card

            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(pingBtn)
            card.addView(sw)
            serversPane.addView(card)
        }

        testAllBtn.setOnClickListener {
            Toast.makeText(ctx, "Fast testing all servers...", Toast.LENGTH_SHORT).show()
            SERVERS.forEach { srv ->
                serverStatusBadges[srv.key]?.apply {
                    text = "..."
                    setTextColor(Color.parseColor("#FBBF24"))
                }
            }
            dialogScope.launch {
                val jobs = SERVERS.map { srv ->
                    async(Dispatchers.IO) {
                        val (ok, ms) = probeEndpoint(srv.probeUrl)
                        Triple(srv.key, ok, ms)
                    }
                }
                val results = jobs.awaitAll()
                var okCount = 0
                var errCount = 0
                results.forEach { (key, ok, ms) ->
                    updateServerBadge(key, ok, ms)
                    if (ok) okCount++ else errCount++
                }
                Toast.makeText(
                    ctx,
                    "Done: $okCount Working, $errCount Error" + if (autoDisableErrors && errCount > 0) " (Auto-OFF)" else "",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        offErrorsBtn.setOnClickListener {
            if (failedServerKeys.isEmpty()) {
                Toast.makeText(ctx, "Pehle TEST ALL dabayein taaki error servers detect hon", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var disabledCount = 0
            failedServerKeys.forEach { key ->
                serverSwitches[key]?.isChecked = false
                setServerEnabled(ctx, key, false)
                serverCards[key]?.visibility = View.GONE
                disabledCount++
            }
            refreshStats()
            Toast.makeText(ctx, "$disabledCount error servers OFF & hide kar diye!", Toast.LENGTH_SHORT).show()
        }

        val nuvioAddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#131826", "#314161", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }

        nuvioAddBox.addView(TextView(ctx).apply {
            text = "+ Add Nuvio Manifest URL"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#C084FC"))
        })

        val labelInput = EditText(ctx).apply {
            hint = "Manifest Name (optional)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 8)
            }
        }

        val manifestInputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val manifestUrlInput = EditText(ctx).apply {
            hint = "https://.../manifest.json"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#323E57", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            }
        }

        val pasteManifestBtn = Button(ctx).apply {
            text = "PASTE"
            textSize = 10.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E9D5FF"))
            background = cardBg("#241738", "#9333EA", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 40))
            setOnClickListener {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                if (clip.isNotBlank()) {
                    manifestUrlInput.setText(clip)
                    manifestUrlInput.setSelection(clip.length)
                } else {
                    Toast.makeText(ctx, "Clipboard khali hai", Toast.LENGTH_SHORT).show()
                }
            }
        }

        manifestInputRow.addView(manifestUrlInput)
        manifestInputRow.addView(pasteManifestBtn)

        val nuvioListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun renderNuvioList() {
            nuvioListHolder.removeAllViews()
            if (nuvioList.isEmpty()) {
                nuvioListHolder.addView(TextView(ctx).apply {
                    text = "Koi Nuvio manifest add nahi hai."
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                })
                return
            }

            nuvioList.forEachIndexed { idx, item ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg("#121621", "#232C40", 20f)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 7) }
                }

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                row.addView(TextView(ctx).apply {
                    text = item.label
                    textSize = 13.5f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                val delBtn = TextView(ctx).apply {
                    text = "DELETE"
                    textSize = 9.5f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F87171"))
                    background = pillBg("#2A1519", 12f)
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(ctx, 8) }
                    setOnClickListener {
                        nuvioList.removeAt(idx)
                        saveNuvioManifests(ctx, nuvioList)
                        refreshStats()
                        renderNuvioList()
                    }
                }

                val sw = Switch(ctx).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        item.enabled = checked
                        saveNuvioManifests(ctx, nuvioList)
                        refreshStats()
                    }
                }

                row.addView(delBtn)
                row.addView(sw)
                card.addView(row)

                card.addView(TextView(ctx).apply {
                    text = item.manifestUrl
                    textSize = 10.5f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(ctx, 4), 0, 0)
                })
                nuvioListHolder.addView(card)
            }
        }

        val addManifestBtn = Button(ctx).apply {
            text = "SAVE NUVIO MANIFEST"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#9333EA", 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40))
            setOnClickListener {
                var rawUrl = manifestUrlInput.text.toString().trim()
                if (rawUrl.isBlank()) {
                    Toast.makeText(ctx, "Pehle Nuvio manifest URL dalein", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    rawUrl = "https://$rawUrl"
                }
                val customName = labelInput.text.toString().trim().ifEmpty { deriveManifestLabel(rawUrl) }
                nuvioList.add(
                    NuvioManifestConfig(
                        id = System.currentTimeMillis().toString(),
                        label = customName,
                        manifestUrl = rawUrl,
                        enabled = true
                    )
                )
                saveNuvioManifests(ctx, nuvioList)
                labelInput.setText("")
                manifestUrlInput.setText("")
                refreshStats()
                renderNuvioList()
                Toast.makeText(ctx, "$customName saved!", Toast.LENGTH_SHORT).show()
            }
        }

        nuvioAddBox.addView(labelInput)
        nuvioAddBox.addView(manifestInputRow)
        nuvioAddBox.addView(addManifestBtn)
        nuvioPane.addView(nuvioAddBox)
        nuvioPane.addView(nuvioListHolder)
        renderNuvioList()

        val qualityCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        qualityCard.addView(TextView(ctx).apply {
            text = "Preferred Quality"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val qScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }
        val qRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val qChips = mutableMapOf<String, TextView>()

        fun refreshQualityChips() {
            qChips.forEach { (q, tv) ->
                if (q == selectedQuality) {
                    tv.background = pillBg("#2563EB", 14f)
                    tv.setTextColor(Color.WHITE)
                } else {
                    tv.background = cardBg("#0B0E16", "#232C40", 14f)
                    tv.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        QUALITIES.forEach { q ->
            val chip = TextView(ctx).apply {
                text = q
                textSize = 11.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 13), dp(ctx, 6), dp(ctx, 13), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    selectedQuality = q
                    setPreferredQuality(ctx, q)
                    refreshQualityChips()
                    refreshStats()
                }
            }
            qChips[q] = chip
            qRow.addView(chip)
        }
        refreshQualityChips()
        qScroll.addView(qRow)
        qualityCard.addView(qScroll)
        audioPane.addView(qualityCard)

        AUDIO_TRACKS.forEach { aud ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 10), dp(ctx, 13), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = aud.title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = aud.subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#8B93A7"))
            })

            val sw = Switch(ctx).apply {
                isChecked = isAudioEnabled(ctx, aud.key)
                setOnCheckedChangeListener { _, checked ->
                    setAudioEnabled(ctx, aud.key, checked)
                }
            }
            audioSwitches[aud.key] = sw
            audioCards[aud.key] = card

            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(sw)
            audioPane.addView(card)
        }

        val lowBufferSw = Switch(ctx).apply {
            isChecked = isLowBufferMode(ctx)
            setOnCheckedChangeListener { _, v -> setLowBufferMode(ctx, v) }
        }
        val fastFetchSw = Switch(ctx).apply {
            isChecked = isFastFetchMode(ctx)
            setOnCheckedChangeListener { _, v -> setFastFetchMode(ctx, v) }
        }
        val autoDisableSw = Switch(ctx).apply {
            isChecked = autoDisableErrors
            setOnCheckedChangeListener { _, v ->
                autoDisableErrors = v
                setAutoDisableErrorServers(ctx, v)
            }
        }

        fun makeToggleCard(title: String, desc: String, sw: Switch) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(Color.parseColor("#8B93A7"))
            })
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(sw)
            speedPane.addView(card)
        }

        makeToggleCard("Auto-Disable Error Servers", "Lagataar 2 baar fail hone par server auto OFF", autoDisableSw)
        makeToggleCard("Zero-Buffer Mode", "Low-latency HLS/DASH streams pehle load hon", lowBufferSw)
        makeToggleCard("Turbo Fast Fetch", "Parallel lookups & fast timeout", fastFetchSw)

        fun makeStepper(
            title: String,
            desc: String,
            initialVal: Int,
            minVal: Int,
            maxVal: Int,
            step: Int,
            suffix: String,
            onChange: (Int) -> Unit
        ) {
            var v = initialVal
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(Color.parseColor("#8B93A7"))
            })

            val valTv = TextView(ctx).apply {
                text = "$v$suffix"
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(dp(ctx, 9), 0, dp(ctx, 9), 0)
            }

            val minusBtn = Button(ctx).apply {
                text = "−"
                setTextColor(Color.WHITE)
                background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener {
                    if (v - step >= minVal) {
                        v -= step
                        valTv.text = "$v$suffix"
                        onChange(v)
                    }
                }
            }
            val plusBtn = Button(ctx).apply {
                text = "+"
                setTextColor(Color.WHITE)
                background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener {
                    if (v + step <= maxVal) {
                        v += step
                        valTv.text = "$v$suffix"
                        onChange(v)
                    }
                }
            }
            card.addView(col)
            card.addView(minusBtn)
            card.addView(valTv)
            card.addView(plusBtn)
            speedPane.addView(card)
        }

        makeStepper("Parallel Workers", "Concurrent endpoint queries", currentConcurrency, 2, 24, 2, "x") {
            currentConcurrency = it
            setConcurrency(ctx, it)
            refreshStats()
        }
        makeStepper("Fast Timeout", "Dead server skip wait time (5s best)", getTimeoutSeconds(ctx), 3, 20, 1, "s") {
            setTimeoutSeconds(ctx, it)
        }

        val resetHealthBtn = Button(ctx).apply {
            text = "RESET ALL SERVER HEALTH & RE-ENABLE"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#334155", 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 40)
            ).apply { topMargin = dp(ctx, 4) }
            setOnClickListener {
                resetAllHealthStats(ctx)
                failedServerKeys.clear()
                SERVERS.forEach { srv ->
                    setServerEnabled(ctx, srv.key, true)
                    serverSwitches[srv.key]?.isChecked = true
                    serverCards[srv.key]?.visibility = View.VISIBLE
                    serverStatusBadges[srv.key]?.apply {
                        text = "READY"
                        setTextColor(Color.parseColor("#94A3B8"))
                        background = pillBg("#1C2230", 8f)
                    }
                }
                refreshStats()
                Toast.makeText(ctx, "Sabhi servers reset aur ON ho gaye!", Toast.LENGTH_SHORT).show()
            }
        }
        speedPane.addView(resetHealthBtn)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty().trim().lowercase()
                SERVERS.forEach { srv ->
                    val match = currentQuery.isEmpty() ||
                        srv.name.lowercase().contains(currentQuery) ||
                        srv.tag.lowercase().contains(currentQuery)
                    serverCards[srv.key]?.visibility = if (match) View.VISIBLE else View.GONE
                }
                AUDIO_TRACKS.forEach { aud ->
                    val match = currentQuery.isEmpty() ||
                        aud.title.lowercase().contains(currentQuery) ||
                        aud.subtitle.lowercase().contains(currentQuery)
                    audioCards[aud.key]?.visibility = if (match) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val panes = listOf(serversPane, nuvioPane, audioPane, speedPane)
        fun selectTab(idx: Int) {
            panes.forEachIndexed { i, pane ->
                pane.visibility = if (i == idx) View.VISIBLE else View.GONE
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg("#2563EB", 15f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(Color.parseColor("#8B93A7"))
                }
            }
        }

        tabButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { selectTab(index) }
        }
        selectTab(0)

        panes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = Button(ctx).apply {
            text = "SAVE & APPLY"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#2563EB", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveNuvioManifests(ctx, nuvioList)
                setPreferredQuality(ctx, selectedQuality)
                setConcurrency(ctx, currentConcurrency)
                Toast.makeText(ctx, "Settings applied!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        footer.addView(closeBtn)
        footer.addView(saveBtn)
        root.addView(footer)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
