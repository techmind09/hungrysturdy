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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object MovieFoxSettingsDialog {

    private const val PREF_SERVER_PREFIX = "mf_srv_"
    private const val PREF_STREAM_ONLY = "mf_stream_only"
    private const val PREF_DOWNLOAD_ONLY = "mf_download_only"
    private const val PREF_CONCURRENCY = "mf_parallel_servers"

    // Yahan aap aage FSL, FSL2, GDrive ya naye servers add kar sakte hain
    data class ServerConfig(
        val key: String,
        val title: String,
        val subtitle: String,
        val defaultEnabled: Boolean = true
    )

    val SERVERS = listOf(
        ServerConfig("purstream", "Purstream", "Direct stream API"),
        ServerConfig("castle", "Castle", "Direct stream API"),
        ServerConfig("direct", "Direct Server", "Direct MP4 / HLS API"),
        ServerConfig("debrid", "Debrid", "Debrid / cached links"),
        ServerConfig("vidsrc", "VidSrc", "VidSrc stream endpoint"),
        ServerConfig("autoembed", "AutoEmbed", "AutoEmbed stream endpoint"),
        ServerConfig("hls", "HLS / Workers / Nitrox", "m3u8, workers.dev, nitrox, vibuxer"),
        ServerConfig("fsl", "FSL Server", "FSL stream server"),
        ServerConfig("fsl2", "FSL2 Server", "FSL2 stream server"),
        ServerConfig("gdrive", "GDrive Server", "Google Drive stream server"),
        ServerConfig("embeds", "Embed Hosts", "Voe, StreamTape, Vidara, Lulu, Pixeldrain, VidPrime")
    )

    fun isServerEnabled(key: String): Boolean {
        val defaultVal = SERVERS.find { it.key == key }?.defaultEnabled ?: true
        return getKey("$PREF_SERVER_PREFIX$key", defaultVal) ?: defaultVal
    }

    fun setServerEnabled(key: String, enabled: Boolean) {
        setKey("$PREF_SERVER_PREFIX$key", enabled)
    }

    fun isStreamOnly(): Boolean = getKey(PREF_STREAM_ONLY, false) ?: false
    fun setStreamOnly(value: Boolean) = setKey(PREF_STREAM_ONLY, value)

    fun isDownloadOnly(): Boolean = getKey(PREF_DOWNLOAD_ONLY, false) ?: false
    fun setDownloadOnly(value: Boolean) = setKey(PREF_DOWNLOAD_ONLY, value)

    fun getParallelServers(): Int = getKey(PREF_CONCURRENCY, 6) ?: 6
    fun setParallelServers(value: Int) = setKey(PREF_CONCURRENCY, value.coerceIn(1, 20))

    private fun cardBg(bgColor: Int = Color.parseColor("#181A20"), strokeColor: Int = Color.parseColor("#2A2E39")): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 24f
            setStroke(2, strokeColor)
        }
    }

    private fun pillButtonBg(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 20f
        }
    }

    private fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()

    fun show(ctx: Context) {
        val activeCount = SERVERS.count { isServerEnabled(it.key) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(ctx, 16), dp(ctx, 18), dp(ctx, 16), dp(ctx, 16))
        }

        // Header Card (JustPlay style)
        val headerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.parseColor("#171A21"))
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        val titleView = TextView(ctx).apply {
            text = "MovieFox"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val subView = TextView(ctx).apply {
            text = "MOVIEFOX SERVER MANAGER"
            textSize = 11f
            setTextColor(Color.parseColor("#8E95A5"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 12))
        }

        val badgeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val modeBadge = TextView(ctx).apply {
            text = "●  CUSTOM SERVERS"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38D996"))
            background = pillButtonBg(Color.parseColor("#122B22"))
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
        }

        val countBadge = TextView(ctx).apply {
            text = "$activeCount/${SERVERS.size} SERVERS ON"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#D0D5E0"))
            background = pillButtonBg(Color.parseColor("#232732"))
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(ctx, 8)
            }
        }

        badgeRow.addView(modeBadge)
        badgeRow.addView(countBadge)
        headerCard.addView(titleView)
        headerCard.addView(subView)
        headerCard.addView(badgeRow)

        root.addView(headerCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(ctx, 14)
        })

        // Scrollable Content
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val contentList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Section: Servers
        val serversHeader = TextView(ctx).apply {
            text = "—  SERVERS"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8E95A5"))
            setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 8))
        }
        contentList.addView(serversHeader)

        val switchMap = mutableMapOf<String, Switch>()

        SERVERS.forEach { srv ->
            val rowCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg()
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(ctx, 8)
                }
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val srvName = TextView(ctx).apply {
                text = srv.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val srvDesc = TextView(ctx).apply {
                text = srv.subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#8E95A5"))
            }

            textCol.addView(srvName)
            textCol.addView(srvDesc)

            val toggle = Switch(ctx).apply {
                isChecked = isServerEnabled(srv.key)
                setOnCheckedChangeListener { _, _ ->
                    val onCount = switchMap.values.count { it.isChecked }
                    countBadge.text = "$onCount/${SERVERS.size} SERVERS ON"
                }
            }
            switchMap[srv.key] = toggle

            rowCard.addView(textCol)
            rowCard.addView(toggle)
            contentList.addView(rowCard)
        }

        // Section: Manage Sources
        val sourcesHeader = TextView(ctx).apply {
            text = "—  MANAGE SOURCES"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8E95A5"))
            setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 8))
        }
        contentList.addView(sourcesHeader)

        val streamSwitch = Switch(ctx).apply { isChecked = isStreamOnly() }
        val downloadSwitch = Switch(ctx).apply { isChecked = isDownloadOnly() }

        fun addModeCard(title: String, desc: String, sw: Switch) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg()
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(ctx, 8)
                }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor("#8E95A5"))
            })
            card.addView(col)
            card.addView(sw)
            contentList.addView(card)
        }

        addModeCard("Stream Only Sources", "Prioritize streaming HLS / MP4 links", streamSwitch)
        addModeCard("Download Only Sources", "Show downloadable / direct links only", downloadSwitch)

        // Section: Performance
        val perfHeader = TextView(ctx).apply {
            text = "—  PERFORMANCE"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8E95A5"))
            setPadding(dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 8))
        }
        contentList.addView(perfHeader)

        var currentConcurrency = getParallelServers()
        val perfCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 12)
            }
        }

        val perfTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        perfTextCol.addView(TextView(ctx).apply {
            text = "Parallel servers"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        perfTextCol.addView(TextView(ctx).apply {
            text = "How many servers fetch links at once"
            textSize = 12f
            setTextColor(Color.parseColor("#8E95A5"))
        })

        val countLabel = TextView(ctx).apply {
            text = "$currentConcurrency"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#4DA3FF"))
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        }

        val minusBtn = Button(ctx).apply {
            text = "−"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pillButtonBg(Color.parseColor("#262B38"))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42))
            setOnClickListener {
                if (currentConcurrency > 1) {
                    currentConcurrency--
                    countLabel.text = "$currentConcurrency"
                }
            }
        }

        val plusBtn = Button(ctx).apply {
            text = "+"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = pillButtonBg(Color.parseColor("#262B38"))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42))
            setOnClickListener {
                if (currentConcurrency < 20) {
                    currentConcurrency++
                    countLabel.text = "$currentConcurrency"
                }
            }
        }

        perfCard.addView(perfTextCol)
        perfCard.addView(minusBtn)
        perfCard.addView(countLabel)
        perfCard.addView(plusBtn)
        contentList.addView(perfCard)

        scrollView.addView(contentList)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .create()

        // Action Buttons (SAVE & CLOSE)
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 10)
            }
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            setTextColor(Color.WHITE)
            background = pillButtonBg(Color.parseColor("#232732"))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f).apply {
                marginEnd = dp(ctx, 8)
            }
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = Button(ctx).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            background = pillButtonBg(Color.parseColor("#2563EB"))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f).apply {
                marginStart = dp(ctx, 8)
            }
            setOnClickListener {
                switchMap.forEach { (key, sw) ->
                    setServerEnabled(key, sw.isChecked)
                }
                setStreamOnly(streamSwitch.isChecked)
                setDownloadOnly(downloadSwitch.isChecked)
                setParallelServers(currentConcurrency)
                dialog.dismiss()
            }
        }

        actionRow.addView(closeBtn)
        actionRow.addView(saveBtn)
        root.addView(actionRow)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
