package com.hungrysturdy.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HungryBirdsSettingsDialog : DialogFragment() {

    data class ServerMeta(
        val id: String,
        val title: String,
        val badge: String,
        val tag: String,
        val url: String,
        val category: String
    )

    data class ExtractorMeta(
        val id: String,
        val title: String,
        val mirrors: String,
        val mode: String
    )

    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentTab = "Providers"
    private var selectedCategory = "All"
    private var searchQuery = ""
    private var testJob: Job? = null

    private val defaultServers = listOf(
        ServerMeta("dramaverse", "DramaVerse", "SKY", "K-DRAMA", "https://mydverse.com/", "Hindi"),
        ServerMeta("prmovies", "Prmovies", "SKY", "HI/M3U8", "https://prmovies.coupons/", "Movies"),
        ServerMeta("katdrama", "KatDrama", "SKY", "ASIAN", "https://katdrama.org/", "Hindi"),
        ServerMeta("hindidrama", "HindiDrama", "SKY", "K-DRA", "https://hindidrama.net/", "Hindi"),
        ServerMeta("sflix", "Sflix", "SKY", "HLS/MP4", "https://sflix.film/", "Series")
    )

    private val extractorList = listOf(
        ExtractorMeta("basejwp", "BaseJWP Extractor", "JWP / Unpacker / B64", "HLS / MP4"),
        ExtractorMeta("gdrive", "GDrive Extractor", "drive.google.com • usercontent", "Direct DL"),
        ExtractorMeta("streamtape", "StreamTape Mirrors", "streamtape.com/to/net/xyz • shavetape", "MP4"),
        ExtractorMeta("voe", "Voe Mirrors", "voe.sx • voe.to • voe.co", "HLS / MP4"),
        ExtractorMeta("pixeldrain", "Pixeldrain", "pixeldrain.com • pixeldrain.dev", "API Direct"),
        ExtractorMeta("hubcloud", "KatHubCloud & GamerXYT", "hubcloud.pw/dad/club/one • gamerxyt", "10Gbps / FSL"),
        ExtractorMeta("gdflix", "KatGDFlix Mirrors", "gdflix.* • gdlink.* • new15.gdflix", "Direct / R2"),
        ExtractorMeta("buzz_hb_go", "BuzzServer / HBPlay / GoFlix", "fuckingfast.net • hbplay • goflix.sbs", "Mirror")
    )

    private val healthStatuses = mutableMapOf<String, String>()
    private lateinit var contentContainer: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var saveBtn: Button
    private lateinit var resetBtn: Button

    private data class Palette(
        val bgScreen: Int,
        val bgCard: Int,
        val bgElevated: Int,
        val accent: Int,
        val accentSoft: Int,
        val border: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val liveGreen: Int,
        val dangerRed: Int,
        val infoBlue: Int
    )

    private fun currentPalette(ctx: Context): Palette {
        val accent = when (HungryBirdsPrefs.getTheme(ctx)) {
            HungryBirdsPrefs.THEME_BLUE -> Color.parseColor("#2563EB")
            HungryBirdsPrefs.THEME_EMERALD -> Color.parseColor("#059669")
            HungryBirdsPrefs.THEME_CRIMSON -> Color.parseColor("#DC2626")
            else -> Color.parseColor("#7C3AED")
        }
        val accentSoft = when (HungryBirdsPrefs.getTheme(ctx)) {
            HungryBirdsPrefs.THEME_BLUE -> Color.parseColor("#172554")
            HungryBirdsPrefs.THEME_EMERALD -> Color.parseColor("#064E3B")
            HungryBirdsPrefs.THEME_CRIMSON -> Color.parseColor("#450A0A")
            else -> Color.parseColor("#2E1065")
        }
        return Palette(
            bgScreen = Color.parseColor("#09090F"),
            bgCard = Color.parseColor("#12131F"),
            bgElevated = Color.parseColor("#1A1C2D"),
            accent = accent,
            accentSoft = accentSoft,
            border = Color.parseColor("#2A2D45"),
            textPrimary = Color.parseColor("#F8FAFC"),
            textSecondary = Color.parseColor("#94A3B8"),
            liveGreen = Color.parseColor("#10B981"),
            dangerRed = Color.parseColor("#EF4444"),
            infoBlue = Color.parseColor("#3B82F6")
        )
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun cardDrawable(
        bgColor: Int,
        strokeColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(bgColor)
        setStroke(strokeWidthPx, strokeColor)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val p = currentPalette(ctx)
        rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bgScreen)
            setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 12))
        }

        val outerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(p.bgCard, p.accent, dp(ctx, 18).toFloat(), dp(ctx, 2))
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        outerCard.addView(buildHeaderBanner(ctx, p))
        outerCard.addView(buildTabStrip(ctx, p))

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(contentContainer)
        outerCard.addView(scrollView)
        outerCard.addView(buildFooterBar(ctx, p))

        rootLayout.addView(outerCard)
        dialog.setContentView(rootLayout)
        renderCurrentTab(ctx)
        return dialog
    }

    private fun buildHeaderBanner(ctx: Context, p: Palette): View {
        val banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(p.accentSoft, p.accent, dp(ctx, 14).toFloat(), dp(ctx, 1))
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(ctx).apply {
            text = "HUNGRY BIRDS ULTRA"
            setTextColor(p.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val turboBadge = TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            setTextColor(Color.parseColor("#A7F3D0"))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 10f
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
            background = cardDrawable(Color.parseColor("#064E3B"), p.liveGreen, dp(ctx, 8).toFloat(), 1)
        }

        val infoBtn = TextView(ctx).apply {
            text = "ⓘ"
            setTextColor(Color.parseColor("#DDD6FE"))
            textSize = 15f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
            lp.leftMargin = dp(ctx, 8)
            layoutParams = lp
            background = cardDrawable(p.bgElevated, p.accent, dp(ctx, 8).toFloat(), 1)
            setOnClickListener {
                Toast.makeText(
                    ctx,
                    "HungrySturdy v2026 • DramaVerse, Prmovies, KatDrama, HindiDrama, Sflix",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        topRow.addView(titleTv)
        topRow.addView(turboBadge)
        topRow.addView(infoBtn)
        banner.addView(topRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 10)
            layoutParams = lp
        }

        val activeCount = defaultServers.count { HungryBirdsPrefs.isProviderEnabled(ctx, it.id) }
        statsRow.addView(statBox(ctx, p, "$activeCount/${defaultServers.size}", "SERVERS", Color.parseColor("#38BDF8"), true))
        statsRow.addView(statBox(ctx, p, "5 • SKY", "PROVIDERS", Color.parseColor("#F472B6"), false))
        statsRow.addView(statBox(ctx, p, "15x • 4K", "SPEED", Color.parseColor("#FBBF24"), false))
        banner.addView(statsRow)

        return banner
    }

    private fun statBox(
        ctx: Context,
        p: Palette,
        value: String,
        label: String,
        valueColor: Int,
        withMarginEnd: Boolean
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardDrawable(p.bgScreen, p.border, dp(ctx, 10).toFloat(), 1)
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (withMarginEnd) lp.rightMargin = dp(ctx, 8)
        layoutParams = lp

        addView(TextView(ctx).apply {
            text = value
            setTextColor(valueColor)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
        })
        addView(TextView(ctx).apply {
            text = label
            setTextColor(p.textSecondary)
            textSize = 9f
        })
    }

    private fun buildTabStrip(ctx: Context, p: Palette): View {
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 10)
            layoutParams = lp
        }

        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tabs = listOf("Providers", "Extractors", "Streaming", "Collection", "Health & Logs")
        tabs.forEach { tabName ->
            val isSelected = currentTab == tabName
            val btn = TextView(ctx).apply {
                text = tabName
                textSize = 12f
                setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else p.textSecondary)
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                background = if (isSelected) {
                    cardDrawable(p.accent, p.accent, dp(ctx, 10).toFloat(), 1)
                } else {
                    cardDrawable(p.bgElevated, p.border, dp(ctx, 10).toFloat(), 1)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dp(ctx, 6)
                layoutParams = lp
                setOnClickListener {
                    currentTab = tabName
                    dismissAllowingStateLoss()
                    HungryBirdsSettingsDialog().apply {
                        this.currentTab = tabName
                        this.selectedCategory = this@HungryBirdsSettingsDialog.selectedCategory
                        this.searchQuery = this@HungryBirdsSettingsDialog.searchQuery
                        this.healthStatuses.putAll(this@HungryBirdsSettingsDialog.healthStatuses)
                    }.show((ctx as? FragmentActivity)?.supportFragmentManager ?: return@setOnClickListener, "HungryBirdsSettings")
                }
            }
            tabRow.addView(btn)
        }

        hScroll.addView(tabRow)
        return hScroll
    }

    private fun renderCurrentTab(ctx: Context) {
        contentContainer.removeAllViews()
        val p = currentPalette(ctx)
        when (currentTab) {
            "Providers" -> renderProvidersTab(ctx, p)
            "Extractors" -> renderExtractorsTab(ctx, p)
            "Streaming" -> renderStreamingTab(ctx, p)
            "Collection" -> renderCollectionTab(ctx, p)
            "Health & Logs" -> renderHealthLogsTab(ctx, p)
        }
    }

    private fun renderProvidersTab(ctx: Context, p: Palette) {
        val controlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4)
            layoutParams = lp
        }

        val searchBox = EditText(ctx).apply {
            hint = "Search providers..."
            setText(searchQuery)
            setHintTextColor(p.textSecondary)
            setTextColor(p.textPrimary)
            textSize = 13f
            singleLine = true
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 10).toFloat(), 1)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    renderCurrentTab(ctx)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val allOnBtn = Button(ctx).apply {
            text = "ALL ON"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(p.accent, p.accent, dp(ctx, 10).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38))
            lp.leftMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                HungryBirdsPrefs.setAllProvidersEnabled(ctx, defaultServers.map { it.id }, true)
                renderCurrentTab(ctx)
            }
        }

        val allOffBtn = Button(ctx).apply {
            text = "ALL OFF"
            textSize = 11f
            setTextColor(p.textPrimary)
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 10).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38))
            lp.leftMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                HungryBirdsPrefs.setAllProvidersEnabled(ctx, defaultServers.map { it.id }, false)
                renderCurrentTab(ctx)
            }
        }

        controlsRow.addView(searchBox)
        controlsRow.addView(allOnBtn)
        controlsRow.addView(allOffBtn)
        contentContainer.addView(controlsRow)

        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 8)
            lp.bottomMargin = dp(ctx, 8)
            layoutParams = lp
        }

        listOf("All", "Movies", "Series", "Hindi").forEach { cat ->
            val active = selectedCategory == cat
            val chip = TextView(ctx).apply {
                text = cat
                textSize = 11f
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.WHITE else p.textSecondary)
                background = if (active) {
                    cardDrawable(p.accent, p.accent, dp(ctx, 9).toFloat(), 1)
                } else {
                    cardDrawable(p.bgElevated, p.border, dp(ctx, 9).toFloat(), 1)
                }
                setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dp(ctx, 6)
                layoutParams = lp
                setOnClickListener {
                    selectedCategory = cat
                    renderCurrentTab(ctx)
                }
            }
            chipRow.addView(chip)
        }
        contentContainer.addView(chipRow)

        val orderedIds = HungryBirdsPrefs.getProviderOrder(ctx, defaultServers.map { it.id })
        val byId = defaultServers.associateBy { it.id }
        val filtered = orderedIds.mapNotNull { byId[it] }.filter { item ->
            val catMatch = selectedCategory == "All" || item.category.equals(selectedCategory, true)
            val qMatch = searchQuery.isBlank() ||
                item.title.contains(searchQuery, true) ||
                item.url.contains(searchQuery, true)
            catMatch && qMatch
        }

        filtered.forEach { server ->
            val enabled = HungryBirdsPrefs.isProviderEnabled(ctx, server.id)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(p.bgElevated, p.border, dp(ctx, 12).toFloat(), 1)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(ctx, 8)
                layoutParams = lp
            }

            val avatar = TextView(ctx).apply {
                text = server.title.take(1).uppercase()
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                gravity = Gravity.CENTER
                background = cardDrawable(p.accentSoft, p.accent, dp(ctx, 8).toFloat(), 1)
                val lp = LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34))
                lp.rightMargin = dp(ctx, 10)
                layoutParams = lp
            }

            val metaCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(ctx).apply {
                text = server.title
                setTextColor(p.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
            })
            topLine.addView(TextView(ctx).apply {
                text = " ${server.badge} "
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = dp(ctx, 6)
                layoutParams = lp
                background = cardDrawable(Color.parseColor("#451A03"), Color.parseColor("#D97706"), dp(ctx, 5).toFloat(), 1)
            })
            topLine.addView(TextView(ctx).apply {
                text = " ${server.tag} "
                setTextColor(Color.parseColor("#BAE6FD"))
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = dp(ctx, 4)
                layoutParams = lp
                background = cardDrawable(Color.parseColor("#082F49"), Color.parseColor("#0284C7"), dp(ctx, 5).toFloat(), 1)
            })

            metaCol.addView(topLine)
            metaCol.addView(TextView(ctx).apply {
                text = server.url
                setTextColor(p.textSecondary)
                textSize = 11f
                maxLines = 1
            })

            val moveUpBtn = TextView(ctx).apply {
                text = "↑"
                setTextColor(p.textSecondary)
                textSize = 14f
                setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
                setOnClickListener {
                    val idx = orderedIds.indexOf(server.id)
                    if (idx > 0) {
                        val mutable = orderedIds.toMutableList()
                        val tmp = mutable[idx - 1]
                        mutable[idx - 1] = mutable[idx]
                        mutable[idx] = tmp
                        HungryBirdsPrefs.setProviderOrder(ctx, mutable)
                        renderCurrentTab(ctx)
                    }
                }
            }

            val toggle = Switch(ctx).apply {
                isChecked = enabled
                setOnCheckedChangeListener { _, isChecked ->
                    HungryBirdsPrefs.setProviderEnabled(ctx, server.id, isChecked)
                }
            }

            card.addView(avatar)
            card.addView(metaCol)
            card.addView(moveUpBtn)
            card.addView(toggle)
            contentContainer.addView(card)
        }
    }

    private fun renderExtractorsTab(ctx: Context, p: Palette) {
        contentContainer.addView(TextView(ctx).apply {
            text = "⚡ Active Extractors & Mirror Pools"
            setTextColor(p.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 8)
            layoutParams = lp
        })

        extractorList.forEach { ext ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(p.bgElevated, p.border, dp(ctx, 12).toFloat(), 1)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(ctx, 8)
                layoutParams = lp
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = "${ext.title}  [${ext.mode}]"
                setTextColor(p.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
            })
            col.addView(TextView(ctx).apply {
                text = ext.mirrors
                setTextColor(p.textSecondary)
                textSize = 11f
            })

            val sw = Switch(ctx).apply {
                isChecked = HungryBirdsPrefs.isExtractorEnabled(ctx, ext.id)
                setOnCheckedChangeListener { _, checked ->
                    HungryBirdsPrefs.setExtractorEnabled(ctx, ext.id, checked)
                }
            }

            card.addView(col)
            card.addView(sw)
            contentContainer.addView(card)
        }
    }

    private fun renderStreamingTab(ctx: Context, p: Palette) {
        val cfCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.parseColor("#091E1A"), p.liveGreen, dp(ctx, 14).toFloat(), 1)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }

        val cfTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cfTop.addView(TextView(ctx).apply {
            text = "🛡️ Cloudflare Bypass (WebView)"
            setTextColor(p.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        cfTop.addView(Switch(ctx).apply {
            isChecked = HungryBirdsPrefs.isWebViewCfEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                HungryBirdsPrefs.setWebViewCfEnabled(ctx, checked)
            }
        })
        cfCard.addView(cfTop)

        cfCard.addView(TextView(ctx).apply {
            text = "Solve challenge once in-app — cookies reused automatically across providers."
            setTextColor(p.textSecondary)
            textSize = 11f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4)
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        })

        val cfBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cfBtnRow.addView(Button(ctx).apply {
            text = "OPEN CF WEBVIEW"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(p.infoBlue, p.infoBlue, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.rightMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                Toast.makeText(ctx, "WebView CF challenge solver triggered", Toast.LENGTH_SHORT).show()
            }
        })
        cfBtnRow.addView(Button(ctx).apply {
            text = "CLEAR COOKIES"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(Color.parseColor("#7F1D1D"), p.dangerRed, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.leftMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                HungryBirdsPrefs.appendLog(ctx, "CF", "Cleared saved challenge cookies")
                Toast.makeText(ctx, "Cookies cleared", Toast.LENGTH_SHORT).show()
            }
        })
        cfCard.addView(cfBtnRow)
        contentContainer.addView(cfCard)

        val themeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 14).toFloat(), 1)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }
        themeCard.addView(TextView(ctx).apply {
            text = "🎨 UI Colour Theme"
            setTextColor(p.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
        })

        val themeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 8)
            layoutParams = lp
        }

        val themes = listOf(
            "Neon Purple" to HungryBirdsPrefs.THEME_PURPLE,
            "Cyber Blue" to HungryBirdsPrefs.THEME_BLUE,
            "Emerald" to HungryBirdsPrefs.THEME_EMERALD,
            "Crimson" to HungryBirdsPrefs.THEME_CRIMSON
        )
        themes.forEach { (label, key) ->
            val selected = HungryBirdsPrefs.getTheme(ctx) == key
            themeRow.addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else p.textSecondary)
                background = if (selected) {
                    cardDrawable(p.accent, p.accent, dp(ctx, 9).toFloat(), 1)
                } else {
                    cardDrawable(p.bgScreen, p.border, dp(ctx, 9).toFloat(), 1)
                }
                setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = dp(ctx, 6)
                layoutParams = lp
                setOnClickListener {
                    HungryBirdsPrefs.setTheme(ctx, key)
                    renderCurrentTab(ctx)
                }
            })
        }
        themeCard.addView(themeRow)
        contentContainer.addView(themeCard)

        val hostCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 14).toFloat(), 1)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        hostCard.addView(TextView(ctx).apply {
            text = "🌐 Sflix / Stream Mirror Host"
            setTextColor(p.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
        })

        val hosts = listOf(
            "https://sflix.film",
            "https://h5-api.aoneroom.com",
            "https://movie-box.co",
            "https://123moviesfree.club"
        )
        val rg = RadioGroup(ctx).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 6)
            layoutParams = lp
        }
        val activeHost = HungryBirdsPrefs.getMovieBoxHost(ctx)
        hosts.forEach { h ->
            rg.addView(RadioButton(ctx).apply {
                text = h
                setTextColor(p.textPrimary)
                textSize = 12f
                isChecked = activeHost.equals(h, true)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) HungryBirdsPrefs.setMovieBoxHost(ctx, h)
                }
            })
        }
        hostCard.addView(rg)
        contentContainer.addView(hostCard)
    }

    private fun renderCollectionTab(ctx: Context, p: Palette) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 14).toFloat(), 1)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        card.addView(TextView(ctx).apply {
            text = "⭐ Personal Collection & Internal JSON File"
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
        })

        val jsonPreview = TextView(ctx).apply {
            text = HungryBirdsPrefs.getCollectionJson(ctx)
            setTextColor(p.textSecondary)
            textSize = 11f
            maxLines = 5
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 6)
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }
        card.addView(jsonPreview)

        val btnRow1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }
        btnRow1.addView(Button(ctx).apply {
            text = "📁 COPY JSON"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(Color.parseColor("#047857"), p.liveGreen, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.rightMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                copyToClipboard(ctx, HungryBirdsPrefs.getCollectionJson(ctx))
                Toast.makeText(ctx, "Collection JSON copied", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow1.addView(Button(ctx).apply {
            text = "📋 PASTE JSON"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(p.accent, p.accent, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.leftMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                val pasted = readFromClipboard(ctx)
                if (!pasted.isNullOrBlank()) {
                    HungryBirdsPrefs.setCollectionJson(ctx, pasted)
                    jsonPreview.text = pasted
                    Toast.makeText(ctx, "JSON imported", Toast.LENGTH_SHORT).show()
                }
            }
        })
        card.addView(btnRow1)

        val titleInput = EditText(ctx).apply {
            hint = "Add single Movie or Series title..."
            setHintTextColor(p.textSecondary)
            setTextColor(p.textPrimary)
            textSize = 13f
            singleLine = true
            background = cardDrawable(p.bgScreen, p.border, dp(ctx, 10).toFloat(), 1)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }
        card.addView(titleInput)

        val btnRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow2.addView(Button(ctx).apply {
            text = "+ ADD MOVIE"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(p.accent, p.accent, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.rightMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                val txt = titleInput.text.toString()
                if (txt.isNotBlank()) {
                    HungryBirdsPrefs.addCollectionTitle(ctx, txt, false)
                    jsonPreview.text = HungryBirdsPrefs.getCollectionJson(ctx)
                    titleInput.text.clear()
                }
            }
        })
        btnRow2.addView(Button(ctx).apply {
            text = "+ ADD SERIES"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(Color.parseColor("#0284C7"), p.infoBlue, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.leftMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                val txt = titleInput.text.toString()
                if (txt.isNotBlank()) {
                    HungryBirdsPrefs.addCollectionTitle(ctx, txt, true)
                    jsonPreview.text = HungryBirdsPrefs.getCollectionJson(ctx)
                    titleInput.text.clear()
                }
            }
        })
        card.addView(btnRow2)
        contentContainer.addView(card)
    }

    private fun renderHealthLogsTab(ctx: Context, p: Palette) {
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
        }

        actionRow.addView(Button(ctx).apply {
            text = "TEST ALL (${defaultServers.size})"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(Color.parseColor("#047857"), p.liveGreen, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.rightMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                runHealthChecks(ctx)
            }
        })

        actionRow.addView(Button(ctx).apply {
            text = "COPY ALL"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = cardDrawable(Color.parseColor("#0284C7"), p.infoBlue, dp(ctx, 9).toFloat(), 1)
            val lp = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
            lp.rightMargin = dp(ctx, 6)
            layoutParams = lp
            setOnClickListener {
                val summary = defaultServers.joinToString("\n") { s ->
                    "${s.title}: ${healthStatuses[s.id] ?: "READY"} (${s.url})"
                }
                copyToClipboard(ctx, summary)
                Toast.makeText(ctx, "Server health copied", Toast.LENGTH_SHORT).show()
            }
        })

        actionRow.addView(Button(ctx).apply {
            text = "CLEAR"
            textSize = 11f
            setTextColor(p.textPrimary)
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 9).toFloat(), 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 40))
            setOnClickListener {
                healthStatuses.clear()
                HungryBirdsPrefs.clearLogs(ctx)
                renderCurrentTab(ctx)
            }
        })
        contentContainer.addView(actionRow)

        defaultServers.forEach { server ->
            val statusText = healthStatuses[server.id] ?: "READY"
            val isLive = statusText.startsWith("LIVE")
            val isDown = statusText.startsWith("DOWN") || statusText.startsWith("HTTP 4") || statusText.startsWith("HTTP 5")

            val rowCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(
                    p.bgElevated,
                    when {
                        isLive -> p.liveGreen
                        isDown -> p.dangerRed
                        else -> p.border
                    },
                    dp(ctx, 12).toFloat(),
                    1
                )
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(ctx, 8)
                layoutParams = lp
            }

            val dot = View(ctx).apply {
                background = cardDrawable(
                    when {
                        isLive -> p.liveGreen
                        isDown -> p.dangerRed
                        else -> p.textSecondary
                    },
                    Color.TRANSPARENT,
                    dp(ctx, 6).toFloat(),
                    0
                )
                val lp = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10))
                lp.rightMargin = dp(ctx, 10)
                layoutParams = lp
            }

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(ctx).apply {
                text = server.title
                setTextColor(p.textPrimary)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
            })
            titleRow.addView(TextView(ctx).apply {
                text = " ${server.badge} "
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 9f
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = dp(ctx, 6)
                layoutParams = lp
                background = cardDrawable(Color.parseColor("#451A03"), Color.parseColor("#D97706"), dp(ctx, 5).toFloat(), 1)
            })
            titleRow.addView(TextView(ctx).apply {
                text = " $statusText "
                setTextColor(Color.WHITE)
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = dp(ctx, 6)
                layoutParams = lp
                background = cardDrawable(
                    when {
                        isLive -> Color.parseColor("#065F46")
                        isDown -> Color.parseColor("#7F1D1D")
                        else -> Color.parseColor("#1E293B")
                    },
                    Color.TRANSPARENT,
                    dp(ctx, 5).toFloat(),
                    0
                )
            })

            infoCol.addView(titleRow)
            infoCol.addView(TextView(ctx).apply {
                text = server.url
                setTextColor(p.textSecondary)
                textSize = 11f
                maxLines = 1
            })

            val copyBtn = TextView(ctx).apply {
                text = "COPY"
                setTextColor(Color.parseColor("#93C5FD"))
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                background = cardDrawable(p.bgScreen, p.infoBlue, dp(ctx, 7).toFloat(), 1)
                setOnClickListener {
                    copyToClipboard(ctx, server.url)
                    Toast.makeText(ctx, "${server.title} URL copied", Toast.LENGTH_SHORT).show()
                }
            }

            rowCard.addView(dot)
            rowCard.addView(infoCol)
            rowCard.addView(copyBtn)
            contentContainer.addView(rowCard)
        }

        val logs = HungryBirdsPrefs.getLogs(ctx)
        contentContainer.addView(TextView(ctx).apply {
            text = if (logs.isBlank()) {
                "No error logs yet (ADB tag: HungryBirdsDiag)"
            } else {
                logs
            }
            setTextColor(if (logs.isBlank()) p.textSecondary else Color.parseColor("#FCA5A5"))
            textSize = 11f
            background = cardDrawable(p.bgScreen, p.border, dp(ctx, 10).toFloat(), 1)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 4)
            layoutParams = lp
        })
    }

    private fun runHealthChecks(ctx: Context) {
        testJob?.cancel()
        testJob = dialogScope.launch {
            defaultServers.forEach { server ->
                healthStatuses[server.id] = "TESTING..."
            }
            renderCurrentTab(ctx)

            defaultServers.forEach { server ->
                val result = withContext(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    try {
                        val resp = app.get(server.url, timeout = 8)
                        val ms = System.currentTimeMillis() - start
                        if (resp.code in 200..399) {
                            "LIVE • ${ms}ms"
                        } else {
                            "HTTP ${resp.code} • ${ms}ms"
                        }
                    } catch (e: Exception) {
                        HungryBirdsPrefs.appendLog(ctx, server.title, e.message ?: "Connection failed")
                        "DOWN • timeout"
                    }
                }
                healthStatuses[server.id] = result
                renderCurrentTab(ctx)
            }
        }
    }

    private fun buildFooterBar(ctx: Context, p: Palette): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 8)
        layoutParams = lp

        resetBtn = Button(ctx).apply {
            text = "RESET"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            background = cardDrawable(p.bgElevated, p.border, dp(ctx, 12).toFloat(), 1)
            val rLp = LinearLayout.LayoutParams(0, dp(ctx, 46), 1f)
            rLp.rightMargin = dp(ctx, 8)
            layoutParams = rLp
            setOnClickListener {
                HungryBirdsPrefs.resetAll(ctx)
                healthStatuses.clear()
                Toast.makeText(ctx, "Reset to defaults", Toast.LENGTH_SHORT).show()
                renderCurrentTab(ctx)
            }
        }

        saveBtn = Button(ctx).apply {
            text = "SAVE & APPLY"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = cardDrawable(p.accent, p.accent, dp(ctx, 12).toFloat(), 1)
            val sLp = LinearLayout.LayoutParams(0, dp(ctx, 46), 1.4f)
            sLp.leftMargin = dp(ctx, 4)
            layoutParams = sLp
            setOnClickListener {
                Toast.makeText(ctx, "Settings saved & applied", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
        }

        addView(resetBtn)
        addView(saveBtn)
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("HungryBirds", text))
    }

    private fun readFromClipboard(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }

    override fun onDestroyView() {
        testJob?.cancel()
        dialogScope.cancel()
        super.onDestroyView()
    }

    companion object {
        fun show(activity: FragmentActivity?) {
            val fm = activity?.supportFragmentManager ?: return
            if (fm.findFragmentByTag("HungryBirdsSettingsDialog") == null) {
                HungryBirdsSettingsDialog().show(fm, "HungryBirdsSettingsDialog")
            }
        }
    }
}
