package com.hungrysturdy

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object MovieFoxSettingsDialog {

    private const val PREFS_NAME = "moviefox_studio_v4"
    private const val PREF_SERVER_PREFIX = "srv_"
    private const val PREF_CUSTOM_SITES = "custom_sites_json"
    private const val PREF_STREAM_ONLY = "stream_only"
    private const val PREF_DOWNLOAD_ONLY = "download_only"
    private const val PREF_CONCURRENCY = "parallel_servers"
    private const val PREF_TIMEOUT_SEC = "request_timeout_sec"

    // In-memory cache for 0ms lag reads
    private var cachedPrefs: SharedPreferences? = null
    private var cachedSites: MutableList<CustomExternalSite>? = null

    data class ServerConfig(
        val key: String,
        val title: String,
        val category: String,
        val badgeColor: String,
        val subtitle: String,
        val defaultEnabled: Boolean = true
    )

    data class CustomExternalSite(
        val id: String,
        val name: String,
        val baseUrl: String,
        var enabled: Boolean = true
    )

    val BUILTIN_SERVERS = listOf(
        ServerConfig("purstream", "Purstream", "API", "#3B82F6", "Primary high-speed JSON stream API"),
        ServerConfig("castle", "Castle", "API", "#3B82F6", "Secondary multi-quality JSON API"),
        ServerConfig("watchout", "Watchout", "API", "#3B82F6", "Multi-source resolver endpoint"),
        ServerConfig("direct", "Direct Server", "Direct", "#10B981", "Direct MP4 / HLS stream source"),
        ServerConfig("debrid", "Debrid", "Cloud", "#8B5CF6", "Cached high-bitrate stream links"),
        ServerConfig("vidsrc", "VidSrc", "Embed", "#F59E0B", "TMDB/IMDb ID-based embed source"),
        ServerConfig("autoembed", "AutoEmbed", "Embed", "#F59E0B", "Automated multi-host embed source"),
        ServerConfig("hls", "HLS / Workers / Nitrox", "HLS", "#06B6D4", "Adaptive .m3u8 & edge worker streams"),
        ServerConfig("fsl", "FSL Server", "Direct", "#10B981", "FSL direct media endpoint"),
        ServerConfig("fsl2", "FSL2 Server", "Direct", "#10B981", "FSL2 backup mirror endpoint"),
        ServerConfig("gdrive", "GDrive Server", "Cloud", "#8B5CF6", "Google Drive direct media source"),
        ServerConfig("embeds", "Embed Hosts", "Extractors", "#EC4899", "Voe, StreamTape, Vidara, Lulu, Pixeldrain, VidPrime")
    )

    private fun prefs(ctx: Context): SharedPreferences {
        return cachedPrefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
    }

    fun isServerEnabled(ctx: Context, key: String): Boolean {
        val def = BUILTIN_SERVERS.find { it.key == key }?.defaultEnabled ?: true
        return prefs(ctx).getBoolean("$PREF_SERVER_PREFIX$key", def)
    }

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$PREF_SERVER_PREFIX$key", enabled).apply()
    }

    fun getCustomSites(ctx: Context): MutableList<CustomExternalSite> {
        cachedSites?.let { return it }
        val raw = prefs(ctx).getString(PREF_CUSTOM_SITES, "[]") ?: "[]"
        val list = mutableListOf<CustomExternalSite>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("baseUrl", "").trim()
                if (url.isNotBlank()) {
                    list.add(
                        CustomExternalSite(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            name = obj.optString("name", extractHostName(url)),
                            baseUrl = url,
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }
        cachedSites = list
        return list
    }

    fun saveCustomSites(ctx: Context, sites: List<CustomExternalSite>) {
        cachedSites = sites.toMutableList()
        val arr = JSONArray()
        sites.forEach { site ->
            arr.put(
                JSONObject().apply {
                    put("id", site.id)
                    put("name", site.name)
                    put("baseUrl", site.baseUrl)
                    put("enabled", site.enabled)
                }
            )
        }
        prefs(ctx).edit().putString(PREF_CUSTOM_SITES, arr.toString()).apply()
    }

    fun extractHostName(rawUrl: String): String {
        return try {
            val fixed = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            val host = URI(fixed).host.orEmpty().removePrefix("www.")
            host.replaceFirstChar { it.uppercase() }.ifBlank { "Custom Site" }
        } catch (_: Exception) {
            "Custom Site"
        }
    }

    fun isStreamOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_STREAM_ONLY, false)
    fun setStreamOnly(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(PREF_STREAM_ONLY, v).apply() }

    fun isDownloadOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_DOWNLOAD_ONLY, false)
    fun setDownloadOnly(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(PREF_DOWNLOAD_ONLY, v).apply() }

    fun getParallelServers(ctx: Context): Int = prefs(ctx).getInt(PREF_CONCURRENCY, 10)
    fun setParallelServers(ctx: Context, v: Int) { prefs(ctx).edit().putInt(PREF_CONCURRENCY, v.coerceIn(1, 25)).apply() }

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(PREF_TIMEOUT_SEC, 12)
    fun setTimeoutSeconds(ctx: Context, v: Int) { prefs(ctx).edit().putInt(PREF_TIMEOUT_SEC, v.coerceIn(3, 30)).apply() }

    // --- UI STYLING HELPERS ---
    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun glassCard(
        bgHex: String = "#111520",
        strokeHex: String = "#232B3E",
        radius: Float = 24f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(bgHex))
        cornerRadius = radius
        setStroke(strokeWidth, Color.parseColor(strokeHex))
    }

    private fun gradientBanner(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.parseColor("#0F172A"),
            Color.parseColor("#1E1B4B"),
            Color.parseColor("#0F292E")
        )
    ).apply {
        cornerRadius = 26f
        setStroke(2, Color.parseColor("#334155"))
    }

    private fun pill(bgHex: String, radius: Float = 18f): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(bgHex))
        cornerRadius = radius
    }

    fun show(ctx: Context) {
        val customSites = getCustomSites(ctx).toMutableList()
        val switchMap = mutableMapOf<String, Switch>()
        val rowMap = mutableMapOf<String, LinearLayout>()
        var selectedCategory = "All"
        var searchQuery = ""

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCard("#07090E", "#1E2638", 34f, 3)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        // ==========================================
        // 1. HERO DASHBOARD HEADER WITH LIVE METRICS
        // ==========================================
        val heroCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientBanner()
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }

        val brandRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brandTitle = TextView(ctx).apply {
            text = "MOVIEFOX STUDIO"
            textSize = 19f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val liveBadge = TextView(ctx).apply {
            text = "⚡ TURBO ENGINE"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            background = pill("#0C2D48", 14f)
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
        }

        brandRow.addView(brandTitle)
        brandRow.addView(liveBadge)
        heroCard.addView(brandRow)

        // 3 Telemetry Mini-Cards
        val metricsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12) }
        }

        fun makeMetricBox(label: String, valueColor: String, marginEndDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = glassCard("#0B0F19", "#28354D", 16f, 2)
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (marginEndDp > 0) marginEnd = dp(ctx, marginEndDp)
                }
            }
            val valText = TextView(ctx).apply {
                text = "0"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(valueColor))
            }
            val lblText = TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            box.addView(valText)
            box.addView(lblText)
            return box to valText
        }

        val (srvBox, srvMetricVal) = makeMetricBox("ACTIVE SERVERS", "#34D399", 6)
        val (siteBox, siteMetricVal) = makeMetricBox("CUSTOM SITES", "#60A5FA", 6)
        val (concBox, concMetricVal) = makeMetricBox("CONCURRENCY", "#FBBF24", 0)

        metricsRow.addView(srvBox)
        metricsRow.addView(siteBox)
        metricsRow.addView(concBox)
        heroCard.addView(metricsRow)

        root.addView(heroCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12) })

        var currentConcurrency = getParallelServers(ctx)
        fun refreshMetrics() {
            val onServers = if (switchMap.isEmpty()) {
                BUILTIN_SERVERS.count { isServerEnabled(ctx, it.key) }
            } else {
                switchMap.values.count { it.isChecked }
            }
            val onSites = customSites.count { it.enabled }
            srvMetricVal.text = "$onServers / ${BUILTIN_SERVERS.size}"
            siteMetricVal.text = "$onSites / ${customSites.size}"
            concMetricVal.text = "${currentConcurrency}x"
        }
        refreshMetrics()

        // ==========================================
        // 2. SEGMENTED TAB SWITCHER
        // ==========================================
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassCard("#0E121B", "#1E2638", 20f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }

        val tabServers = Button(ctx).apply {
            text = "Servers"
            textSize = 12f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
        }
        val tabSites = Button(ctx).apply {
            text = "Custom Sites"
            textSize = 12f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
        }
        val tabEngine = Button(ctx).apply {
            text = "Engine"
            textSize = 12f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
        }

        tabBar.addView(tabServers)
        tabBar.addView(tabSites)
        tabBar.addView(tabEngine)
        root.addView(tabBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 10) })

        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 360)
            )
        }

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val serversTab = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sitesTab = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val engineTab = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // ==========================================
        // TAB 1: SERVERS (SEARCH + CATEGORY CHIPS + CARDS)
        // ==========================================
        fun applyServerFilters() {
            BUILTIN_SERVERS.forEach { srv ->
                val matchesCat = selectedCategory == "All" || srv.category.equals(selectedCategory, true)
                val matchesQuery = searchQuery.isBlank() ||
                    srv.title.lowercase().contains(searchQuery) ||
                    srv.subtitle.lowercase().contains(searchQuery) ||
                    srv.category.lowercase().contains(searchQuery)
                rowMap[srv.key]?.visibility = if (matchesCat && matchesQuery) View.VISIBLE else View.GONE
            }
        }

        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val searchInput = EditText(ctx).apply {
            hint = "🔍 Search servers..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            background = glassCard("#0E121B", "#263147", 16f)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
        }

        val enableAllBtn = Button(ctx).apply {
            text = "All ON"
            textSize = 11f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            background = glassCard("#0E2922", "#10B981", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 38)).apply { marginEnd = dp(ctx, 4) }
            setOnClickListener {
                switchMap.forEach { (k, sw) ->
                    sw.isChecked = true
                    setServerEnabled(ctx, k, true)
                }
                refreshMetrics()
            }
        }

        val disableAllBtn = Button(ctx).apply {
            text = "All OFF"
            textSize = 11f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F87171"))
            background = glassCard("#2A1519", "#EF4444", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 66), dp(ctx, 38))
            setOnClickListener {
                switchMap.forEach { (k, sw) ->
                    sw.isChecked = false
                    setServerEnabled(ctx, k, false)
                }
                refreshMetrics()
            }
        }

        searchRow.addView(searchInput)
        searchRow.addView(enableAllBtn)
        searchRow.addView(disableAllBtn)
        serversTab.addView(searchRow)

        // Horizontal Category Chips
        val chipScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val categories = listOf("All", "API", "Direct", "HLS", "Cloud", "Embed", "Extractors")
        val chipViews = mutableMapOf<String, TextView>()

        fun updateChipStyles() {
            chipViews.forEach { (cat, tv) ->
                if (cat == selectedCategory) {
                    tv.background = pill("#2563EB", 14f)
                    tv.setTextColor(Color.WHITE)
                } else {
                    tv.background = glassCard("#111520", "#232B3E", 14f)
                    tv.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        categories.forEach { cat ->
            val chip = TextView(ctx).apply {
                text = cat
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    selectedCategory = cat
                    updateChipStyles()
                    applyServerFilters()
                }
            }
            chipViews[cat] = chip
            chipRow.addView(chip)
        }
        updateChipStyles()
        chipScroll.addView(chipRow)
        serversTab.addView(chipScroll)

        // Server Cards
        BUILTIN_SERVERS.forEach { srv ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCard("#111520", "#222A3D", 20f)
                setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            titleRow.addView(TextView(ctx).apply {
                text = srv.title
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })

            titleRow.addView(TextView(ctx).apply {
                text = srv.category.uppercase()
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(srv.badgeColor))
                background = pill("#1A2234", 10f)
                setPadding(dp(ctx, 7), dp(ctx, 2), dp(ctx, 7), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 8) }
            })

            col.addView(titleRow)
            col.addView(TextView(ctx).apply {
                text = srv.subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#8B93A7"))
                setPadding(0, dp(ctx, 3), 0, 0)
            })

            val sw = Switch(ctx).apply {
                isChecked = isServerEnabled(ctx, srv.key)
                setOnCheckedChangeListener { _, checked ->
                    setServerEnabled(ctx, srv.key, checked)
                    refreshMetrics()
                }
            }
            switchMap[srv.key] = sw
            rowMap[srv.key] = card

            // Clicking anywhere on card toggles the switch smoothly
            card.setOnClickListener { sw.isChecked = !sw.isChecked }

            card.addView(col)
            card.addView(sw)
            serversTab.addView(card)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase()
                applyServerFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ==========================================
        // TAB 2: CUSTOM SITES (CLIPBOARD PASTE + INSTANT SAVE)
        // ==========================================
        val addBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCard("#121724", "#2D3B55", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

        addBox.addView(TextView(ctx).apply {
            text = "+ Add Custom Domain"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 10)
                bottomMargin = dp(ctx, 10)
            }
        }

        val urlInput = EditText(ctx).apply {
            hint = "https://example.com"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            background = glassCard("#080B12", "#323D54", 16f)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            }
        }

        val pasteBtn = Button(ctx).apply {
            text = "PASTE"
            textSize = 11f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
            background = glassCard("#172033", "#3B82F6", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 68), dp(ctx, 42))
            setOnClickListener {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = cb?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                if (clipText.isNotBlank()) {
                    urlInput.setText(clipText)
                    urlInput.setSelection(clipText.length)
                } else {
                    Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }

        inputRow.addView(urlInput)
        inputRow.addView(pasteBtn)

        val savedListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun renderSavedSites() {
            savedListHolder.removeAllViews()
            if (customSites.isEmpty()) {
                savedListHolder.addView(TextView(ctx).apply {
                    text = "No saved domains yet. Paste a domain URL above to add it."
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 18), dp(ctx, 12), dp(ctx, 18))
                })
                return
            }

            customSites.forEachIndexed { index, site ->
                val itemCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = glassCard("#111520", "#222A3D", 20f)
                    setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 8) }
                }

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                row.addView(TextView(ctx).apply {
                    text = site.name
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                val delBtn = TextView(ctx).apply {
                    text = "DELETE"
                    textSize = 10f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F87171"))
                    background = pill("#2A1519", 12f)
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(ctx, 8) }
                    setOnClickListener {
                        customSites.removeAt(index)
                        saveCustomSites(ctx, customSites)
                        refreshMetrics()
                        renderSavedSites()
                    }
                }

                val sw = Switch(ctx).apply {
                    isChecked = site.enabled
                    setOnCheckedChangeListener { _, checked ->
                        site.enabled = checked
                        saveCustomSites(ctx, customSites)
                        refreshMetrics()
                    }
                }

                row.addView(delBtn)
                row.addView(sw)

                itemCard.addView(row)
                itemCard.addView(TextView(ctx).apply {
                    text = site.baseUrl
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#8B93A7"))
                    setPadding(0, dp(ctx, 4), 0, 0)
                })
                savedListHolder.addView(itemCard)
            }
        }

        val addBtn = Button(ctx).apply {
            text = "ADD & SAVE DOMAIN"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pill("#10B981", 16f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42))
            setOnClickListener {
                var raw = urlInput.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(ctx, "Paste or enter a URL first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                    raw = "https://$raw"
                }
                val title = extractHostName(raw)
                customSites.add(
                    CustomExternalSite(
                        id = System.currentTimeMillis().toString(),
                        name = title,
                        baseUrl = raw,
                        enabled = true
                    )
                )
                saveCustomSites(ctx, customSites)
                urlInput.setText("")
                refreshMetrics()
                renderSavedSites()
                Toast.makeText(ctx, "$title saved!", Toast.LENGTH_SHORT).show()
            }
        }

        addBox.addView(inputRow)
        addBox.addView(addBtn)
        sitesTab.addView(addBox)
        sitesTab.addView(savedListHolder)
        renderSavedSites()

        // ==========================================
        // TAB 3: ENGINE & PERFORMANCE CONTROLS
        // ==========================================
        val streamSwitch = Switch(ctx).apply {
            isChecked = isStreamOnly(ctx)
            setOnCheckedChangeListener { _, v -> setStreamOnly(ctx, v) }
        }
        val downloadSwitch = Switch(ctx).apply {
            isChecked = isDownloadOnly(ctx)
            setOnCheckedChangeListener { _, v -> setDownloadOnly(ctx, v) }
        }

        fun addEngineToggle(title: String, desc: String, sw: Switch) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCard("#111520", "#222A3D", 20f)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
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
                textSize = 11.5f
                setTextColor(Color.parseColor("#8B93A7"))
            })
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(sw)
            engineTab.addView(card)
        }

        addEngineToggle("Stream Only Mode", "Prefer adaptive HLS (.m3u8) playlists", streamSwitch)
        addEngineToggle("Download Only Mode", "Show direct MP4 / cloud files only", downloadSwitch)

        fun addStepperCard(
            title: String,
            desc: String,
            initialVal: Int,
            minVal: Int,
            maxVal: Int,
            suffix: String,
            onChange: (Int) -> Unit
        ) {
            var currentVal = initialVal
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCard("#111520", "#222A3D", 20f)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
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
                textSize = 11.5f
                setTextColor(Color.parseColor("#8B93A7"))
            })

            val numView = TextView(ctx).apply {
                text = "$currentVal$suffix"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            }

            val minusBtn = Button(ctx).apply {
                text = "−"
                setTextColor(Color.WHITE)
                background = pill("#1E2638", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36))
                setOnClickListener {
                    if (currentVal > minVal) {
                        currentVal--
                        numView.text = "$currentVal$suffix"
                        onChange(currentVal)
                    }
                }
            }
            val plusBtn = Button(ctx).apply {
                text = "+"
                setTextColor(Color.WHITE)
                background = pill("#1E2638", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36))
                setOnClickListener {
                    if (currentVal < maxVal) {
                        currentVal++
                        numView.text = "$currentVal$suffix"
                        onChange(currentVal)
                    }
                }
            }

            card.addView(col)
            card.addView(minusBtn)
            card.addView(numView)
            card.addView(plusBtn)
            engineTab.addView(card)
        }

        addStepperCard(
            title = "Max Parallel Endpoints",
            desc = "Endpoints queried concurrently",
            initialVal = currentConcurrency,
            minVal = 1,
            maxVal = 25,
            suffix = "x"
        ) { newVal ->
            currentConcurrency = newVal
            setParallelServers(ctx, newVal)
            refreshMetrics()
        }

        addStepperCard(
            title = "Network Timeout",
            desc = "Max wait time per server response",
            initialVal = getTimeoutSeconds(ctx),
            minVal = 3,
            maxVal = 30,
            suffix = "s"
        ) { newSec ->
            setTimeoutSeconds(ctx, newSec)
        }

        // Tab Switching Logic
        fun selectTab(idx: Int) {
            serversTab.visibility = if (idx == 0) View.VISIBLE else View.GONE
            sitesTab.visibility = if (idx == 1) View.VISIBLE else View.GONE
            engineTab.visibility = if (idx == 2) View.VISIBLE else View.GONE

            listOf(tabServers, tabSites, tabEngine).forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pill("#2563EB", 16f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(Color.parseColor("#8B93A7"))
                }
            }
        }

        tabServers.setOnClickListener { selectTab(0) }
        tabSites.setOnClickListener { selectTab(1) }
        tabEngine.setOnClickListener { selectTab(2) }
        selectTab(0)

        wrapper.addView(serversTab)
        wrapper.addView(sitesTab)
        wrapper.addView(engineTab)
        scrollView.addView(wrapper)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()

        // Footer Bar
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12) }
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pill("#1E2534", 18f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 44), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener { dialog.dismiss() }
        }

        val doneBtn = Button(ctx).apply {
            text = "DONE & APPLY"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pill("#2563EB", 18f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 44), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveCustomSites(ctx, customSites)
                Toast.makeText(ctx, "Settings applied!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        footer.addView(closeBtn)
        footer.addView(doneBtn)
        root.addView(footer)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
