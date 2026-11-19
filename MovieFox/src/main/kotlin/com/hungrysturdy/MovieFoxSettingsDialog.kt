package com.hungrysturdy

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object MovieFoxSettingsDialog {

    private const val PREF_SERVER_PREFIX = "mf_srv_"
    private const val PREF_CUSTOM_SITES = "mf_custom_external_sites_v2"
    private const val PREF_STREAM_ONLY = "mf_stream_only"
    private const val PREF_DOWNLOAD_ONLY = "mf_download_only"
    private const val PREF_CONCURRENCY = "mf_parallel_servers"

    data class ServerConfig(
        val key: String,
        val title: String,
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
        ServerConfig("purstream", "Purstream", "MovieFox Purstream API"),
        ServerConfig("castle", "Castle", "MovieFox Castle API"),
        ServerConfig("watchout", "Watchout", "MovieFox Watchout API"),
        ServerConfig("direct", "Direct Server", "Direct MP4 / HLS API"),
        ServerConfig("debrid", "Debrid", "Debrid / Cached Streams"),
        ServerConfig("vidsrc", "VidSrc", "VidSrc Stream API"),
        ServerConfig("autoembed", "AutoEmbed", "AutoEmbed Stream API"),
        ServerConfig("hls", "HLS / Workers / Nitrox", "m3u8, workers.dev, nitrox, vibuxer"),
        ServerConfig("fsl", "FSL Server", "FSL Direct Server"),
        ServerConfig("fsl2", "FSL2 Server", "FSL2 Backup Server"),
        ServerConfig("gdrive", "GDrive Server", "Google Drive Stream Server"),
        ServerConfig("embeds", "Embed Hosts", "Voe, StreamTape, Vidara, Lulu, Pixeldrain, VidPrime")
    )

    fun isServerEnabled(key: String): Boolean {
        val defaultVal = BUILTIN_SERVERS.find { it.key == key }?.defaultEnabled ?: true
        return getKey("$PREF_SERVER_PREFIX$key", defaultVal) ?: defaultVal
    }

    fun setServerEnabled(key: String, enabled: Boolean) {
        setKey("$PREF_SERVER_PREFIX$key", enabled)
    }

    fun getCustomSites(): MutableList<CustomExternalSite> {
        val raw = getKey<String>(PREF_CUSTOM_SITES, "[]").orEmpty()
        val list = mutableListOf<CustomExternalSite>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("baseUrl", "").trim()
                if (url.isBlank()) continue
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
        return list
    }

    fun saveCustomSites(sites: List<CustomExternalSite>) {
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
        setKey(PREF_CUSTOM_SITES, arr.toString())
    }

    fun extractHostName(rawUrl: String): String {
        return try {
            val fixed = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
            val host = URI(fixed).host.orEmpty().removePrefix("www.")
            host.replaceFirstChar { it.uppercase() }.ifBlank { "External Site" }
        } catch (_: Exception) {
            "External Site"
        }
    }

    fun isStreamOnly(): Boolean = getKey(PREF_STREAM_ONLY, false) ?: false
    fun setStreamOnly(value: Boolean) = setKey(PREF_STREAM_ONLY, value)

    fun isDownloadOnly(): Boolean = getKey(PREF_DOWNLOAD_ONLY, false) ?: false
    fun setDownloadOnly(value: Boolean) = setKey(PREF_DOWNLOAD_ONLY, value)

    fun getParallelServers(): Int = getKey(PREF_CONCURRENCY, 10) ?: 10
    fun setParallelServers(value: Int) = setKey(PREF_CONCURRENCY, value.coerceIn(1, 25))

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun cardBg(
        bgColor: Int = Color.parseColor("#161922"),
        strokeColor: Int = Color.parseColor("#282D3C"),
        radius: Float = 26f
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
            setStroke(2, strokeColor)
        }
    }

    private fun pillBg(bgColor: Int, radius: Float = 20f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radius
        }
    }

    private fun inputBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#0D0F14"))
            cornerRadius = 18f
            setStroke(2, Color.parseColor("#32384A"))
        }
    }

    fun show(ctx: Context) {
        val customSites = getCustomSites()
        val switchMap = mutableMapOf<String, Switch>()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.parseColor("#0D0F14"), Color.parseColor("#232836"), 32f)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        // Top Banner Card
        val headerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.parseColor("#151923"), Color.parseColor("#293042"))
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(ctx).apply {
            text = "MovieFox Pro"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusPill = TextView(ctx).apply {
            text = "● ACTIVE"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            background = pillBg(Color.parseColor("#102A22"))
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
        }

        titleRow.addView(titleText)
        titleRow.addView(statusPill)

        val subtitleText = TextView(ctx).apply {
            text = "MULTI-SOURCE & EXTERNAL SITE SERVER ENGINE"
            textSize = 11f
            setTextColor(Color.parseColor("#8B93A7"))
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 10))
        }

        val statsText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            setTypeface(typeface, Typeface.BOLD)
        }

        fun updateStats() {
            val builtInOn = switchMap.values.count { it.isChecked }.let {
                if (switchMap.isEmpty()) BUILTIN_SERVERS.count { isServerEnabled(it.key) } else it
            }
            val extOn = customSites.count { it.enabled }
            statsText.text = "Built-in: $builtInOn/${BUILTIN_SERVERS.size} ON   •   External Sites: $extOn/${customSites.size} ON"
        }
        updateStats()

        headerCard.addView(titleRow)
        headerCard.addView(subtitleText)
        headerCard.addView(statsText)
        root.addView(headerCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12) })

        // 3-Tab Switcher
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg(Color.parseColor("#13161F"), Color.parseColor("#242938"), 22f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }

        val tabBuiltIn = Button(ctx).apply {
            text = "Servers"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f)
        }
        val tabExternal = Button(ctx).apply {
            text = "External Sites"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f)
        }
        val tabSettings = Button(ctx).apply {
            text = "Engine"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f)
        }

        tabBar.addView(tabBuiltIn)
        tabBar.addView(tabExternal)
        tabBar.addView(tabSettings)
        root.addView(tabBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12) })

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 340)
            )
        }

        val containerWrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val builtInContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val externalContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val engineContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // TAB 1: BUILT-IN SERVERS
        BUILTIN_SERVERS.forEach { srv ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg()
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
            col.addView(TextView(ctx).apply {
                text = srv.title
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = srv.subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#8B93A7"))
            })

            val sw = Switch(ctx).apply {
                isChecked = isServerEnabled(srv.key)
                setOnCheckedChangeListener { _, _ -> updateStats() }
            }
            switchMap[srv.key] = sw

            card.addView(col)
            card.addView(sw)
            builtInContainer.addView(card)
        }

        // TAB 2: SIMPLE SINGLE-BOX EXTERNAL WEBSITE FETCHER
        val addFormCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.parseColor("#151A24"), Color.parseColor("#2E3A52"))
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

        addFormCard.addView(TextView(ctx).apply {
            text = "+ Paste Any Website URL"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#60A5FA"))
        })

        addFormCard.addView(TextView(ctx).apply {
            text = "Bas kisi bhi website ka link paste karein (jaise https://sflix.film/ ya https://vidlink.pro). MovieFox khud us site se servers fetch kar lega."
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B93A7"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 10))
        })

        val siteUrlInput = EditText(ctx).apply {
            hint = "Paste Website URL (e.g., https://sflix.film/)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            background = inputBg()
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }

        val externalListHolder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderExternalSitesList() {
            externalListHolder.removeAllViews()
            if (customSites.isEmpty()) {
                externalListHolder.addView(TextView(ctx).apply {
                    text = "No external websites added yet. Paste any site URL above and tap ADD WEBSITE SERVER."
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                })
                return
            }

            customSites.forEachIndexed { index, site ->
                val itemCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg()
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 8) }
                }

                val topRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val siteTitle = TextView(ctx).apply {
                    text = site.name
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val delBtn = TextView(ctx).apply {
                    text = "DELETE"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F87171"))
                    background = pillBg(Color.parseColor("#2A1519"), 14f)
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(ctx, 8) }
                    setOnClickListener {
                        customSites.removeAt(index)
                        updateStats()
                        renderExternalSitesList()
                    }
                }

                val siteSwitch = Switch(ctx).apply {
                    isChecked = site.enabled
                    setOnCheckedChangeListener { _, checked ->
                        site.enabled = checked
                        updateStats()
                    }
                }

                topRow.addView(siteTitle)
                topRow.addView(delBtn)
                topRow.addView(siteSwitch)

                val urlPreview = TextView(ctx).apply {
                    text = site.baseUrl
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#8B93A7"))
                    setPadding(0, dp(ctx, 4), 0, 0)
                }

                itemCard.addView(topRow)
                itemCard.addView(urlPreview)
                externalListHolder.addView(itemCard)
            }
        }

        val addSiteBtn = Button(ctx).apply {
            text = "ADD WEBSITE SERVER"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg(Color.parseColor("#10B981"), 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 44)
            )
            setOnClickListener {
                var rawUrl = siteUrlInput.text.toString().trim()
                if (rawUrl.isBlank()) {
                    Toast.makeText(ctx, "Please paste a website URL first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    rawUrl = "https://$rawUrl"
                }

                val cleanSiteName = extractHostName(rawUrl)
                customSites.add(
                    CustomExternalSite(
                        id = System.currentTimeMillis().toString(),
                        name = cleanSiteName,
                        baseUrl = rawUrl,
                        enabled = true
                    )
                )
                siteUrlInput.setText("")
                updateStats()
                renderExternalSitesList()
                Toast.makeText(ctx, "$cleanSiteName added! Click SAVE SETTINGS.", Toast.LENGTH_SHORT).show()
            }
        }

        addFormCard.addView(siteUrlInput)
        addFormCard.addView(addSiteBtn)

        externalContainer.addView(addFormCard)
        externalContainer.addView(externalListHolder)
        renderExternalSitesList()

        // TAB 3: ENGINE & PERFORMANCE
        val streamOnlySwitch = Switch(ctx).apply { isChecked = isStreamOnly() }
        val downloadOnlySwitch = Switch(ctx).apply { isChecked = isDownloadOnly() }

        fun addToggleCard(title: String, desc: String, sw: Switch) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg()
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
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11.5f
                setTextColor(Color.parseColor("#8B93A7"))
            })
            card.addView(col)
            card.addView(sw)
            engineContainer.addView(card)
        }

        addToggleCard("Stream Only Mode", "Prioritize adaptive HLS (.m3u8) streams", streamOnlySwitch)
        addToggleCard("Download Only Mode", "Show direct MP4 / GDrive / Pixeldrain files only", downloadOnlySwitch)

        var concurrency = getParallelServers()
        val perfCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val perfCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        perfCol.addView(TextView(ctx).apply {
            text = "Max Active Endpoints"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        perfCol.addView(TextView(ctx).apply {
            text = "Limit how many API servers run per title"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8B93A7"))
        })

        val numLabel = TextView(ctx).apply {
            text = "$concurrency"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        }

        val minusBtn = Button(ctx).apply {
            text = "−"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = pillBg(Color.parseColor("#242938"), 16f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
            setOnClickListener {
                if (concurrency > 1) {
                    concurrency--
                    numLabel.text = "$concurrency"
                }
            }
        }
        val plusBtn = Button(ctx).apply {
            text = "+"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = pillBg(Color.parseColor("#242938"), 16f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
            setOnClickListener {
                if (concurrency < 25) {
                    concurrency++
                    numLabel.text = "$concurrency"
                }
            }
        }

        perfCard.addView(perfCol)
        perfCard.addView(minusBtn)
        perfCard.addView(numLabel)
        perfCard.addView(plusBtn)
        engineContainer.addView(perfCard)

        fun selectTab(activeIndex: Int) {
            builtInContainer.visibility = if (activeIndex == 0) View.VISIBLE else View.GONE
            externalContainer.visibility = if (activeIndex == 1) View.VISIBLE else View.GONE
            engineContainer.visibility = if (activeIndex == 2) View.VISIBLE else View.GONE

            val tabs = listOf(tabBuiltIn, tabExternal, tabSettings)
            tabs.forEachIndexed { idx, btn ->
                if (idx == activeIndex) {
                    btn.background = pillBg(Color.parseColor("#2563EB"), 18f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(Color.parseColor("#8B93A7"))
                }
            }
        }

        tabBuiltIn.setOnClickListener { selectTab(0) }
        tabExternal.setOnClickListener { selectTab(1) }
        tabSettings.setOnClickListener { selectTab(2) }
        selectTab(1) // Opens directly on External Sites tab for quick paste

        containerWrapper.addView(builtInContainer)
        containerWrapper.addView(externalContainer)
        containerWrapper.addView(engineContainer)
        scrollView.addView(containerWrapper)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .create()

        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12) }
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg(Color.parseColor("#232732"), 20f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = Button(ctx).apply {
            text = "SAVE SETTINGS"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg(Color.parseColor("#2563EB"), 20f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                switchMap.forEach { (key, sw) -> setServerEnabled(key, sw.isChecked) }
                saveCustomSites(customSites)
                setStreamOnly(streamOnlySwitch.isChecked)
                setDownloadOnly(downloadOnlySwitch.isChecked)
                setParallelServers(concurrency)
                Toast.makeText(ctx, "MovieFox Settings Saved!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        bottomRow.addView(closeBtn)
        bottomRow.addView(saveBtn)
        root.addView(bottomRow)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
