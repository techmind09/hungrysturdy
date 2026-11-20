package com.hungrysturdy

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object DonationDialogFragment {

    fun show(
        context: Context,
        pluginName: String? = null,
        upiId: String = DonationManager.DEFAULT_UPI_ID,
        payeeName: String = DonationManager.DEFAULT_PAYEE_NAME,
        customNote: String? = null,
        presets: List<Int> = DonationManager.DEFAULT_PRESETS,
        defaultAmount: Int = DonationManager.DEFAULT_AMOUNT,
        goalCurrent: Float = 510f,
        goalTarget: Float = 1000f,
        supportersCount: Int = 37,
        extraChannels: List<DonationManager.SupportChannel> = emptyList()
    ) {
        val finalNote = customNote ?: if (!pluginName.isNullOrBlank()) {
            "${DonationManager.DEFAULT_NOTE} ($pluginName)"
        } else {
            DonationManager.DEFAULT_NOTE
        }

        val channels = buildList {
            add(
                DonationManager.SupportChannel(
                    id = "upi_direct",
                    pillLabel = "UPI • $upiId",
                    addressOrPayload = upiId,
                    helperText = "Scan QR or pay directly to $payeeName via UPI",
                    warningText = "Only send INR via supported UPI apps to $upiId. Double-check amount before paying.",
                    isUpi = true
                )
            )
            addAll(extraChannels)
        }

        var selectedAmount: Int? = defaultAmount.takeIf { it > 0 }
        var selectedChannelIndex = 0
        val dp: (Int) -> Int = { v -> (v * context.resources.displayMetrics.density).toInt() }

        fun cardBg(
            bg: String = "#151B2E",
            stroke: String = "#26304D",
            radiusDp: Int = 18
        ): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor(stroke))
        }

        fun pillBg(active: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (active) Color.parseColor("#2A2252") else Color.parseColor("#131829"))
            setStroke(
                dp(if (active) 2 else 1),
                if (active) Color.parseColor("#7C5CFF") else Color.parseColor("#252E48")
            )
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0B0F1A"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(22))
        }

        // Top Header Card
        val headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg("#131829", "#242D47", 16)
        }

        headerCard.addView(TextView(context).apply {
            text = "⚡"
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = cardBg("#261F47", "#5B46C7", 11)
        })

        val brandCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        brandCol.addView(TextView(context).apply {
            text = payeeName
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        brandCol.addView(TextView(context).apply {
            text = if (!pluginName.isNullOrBlank()) "$pluginName • Community support" else "Plugin community support"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        headerCard.addView(brandCol)
        root.addView(headerCard)

        // Hero Heading
        root.addView(TextView(context).apply {
            text = "Monthly Support Goal"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(2), dp(18), dp(2), dp(4))
        })
        root.addView(TextView(context).apply {
            text = "Every contribution keeps plugin updates & extractors running ✨"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(2), 0, dp(2), dp(14))
        })

        // Goal Card
        val goalCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cardBg()
        }

        val goalTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val goalLeft = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        goalLeft.addView(TextView(context).apply {
            text = "SUPPORT TIER & TARGET"
            textSize = 10.5f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B9BB4"))
        })
        goalLeft.addView(TextView(context).apply {
            text = if (!pluginName.isNullOrBlank()) "$pluginName Builds" else "Hungrysturdy Builds"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
            setPadding(0, dp(2), 0, 0)
        })

        val pct = if (goalTarget > 0f) ((goalCurrent / goalTarget) * 100f).toInt().coerceIn(0, 100) else 0
        val pctBadge = TextView(context).apply {
            text = "$pct%"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#C4B5FD"))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = cardBg("#2A1E46", "#6D4AFF", 20)
        }
        goalTop.addView(goalLeft)
        goalTop.addView(pctBadge)
        goalCard.addView(goalTop)

        goalCard.addView(TextView(context).apply {
            text = "₹${goalCurrent.toInt()} / ₹${goalTarget.toInt()}"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(10))
        })

        val trackH = dp(10)
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, trackH)
            background = GradientDrawable().apply {
                cornerRadius = (trackH / 2).toFloat()
                setColor(Color.parseColor("#242D47"))
            }
        }
        val fillFrac = (pct / 100f).coerceIn(0.04f, 1f)
        track.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, trackH, fillFrac)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#EC4899"), Color.parseColor("#A855F7"), Color.parseColor("#6366F1"))
            ).apply {
                cornerRadius = (trackH / 2).toFloat()
            }
        })
        track.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, trackH, (1f - fillFrac).coerceAtLeast(0f))
        })
        goalCard.addView(track)

        val rangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(12))
        }
        rangeRow.addView(TextView(context).apply {
            text = "₹0"
            textSize = 11.5f
            setTextColor(Color.parseColor("#7D8DA6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        rangeRow.addView(TextView(context).apply {
            text = "₹${goalTarget.toInt()} goal"
            textSize = 11.5f
            setTextColor(Color.parseColor("#7D8DA6"))
        })
        goalCard.addView(rangeRow)

        goalCard.addView(TextView(context).apply {
            text = "$supportersCount supporters this month"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
        })
        root.addView(goalCard)

        // Support This Project Card
        val supportCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cardBg()
        }

        supportCard.addView(TextView(context).apply {
            text = "SUPPORT THIS PROJECT"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B9BB4"))
            setPadding(0, 0, 0, dp(12))
        })

        val upiBanner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg("#1B2238", "#2E3A5C", 14)
        }
        upiBanner.addView(TextView(context).apply {
            text = "🇮🇳"
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = cardBg("#232C47", "#3B4973", 10)
        })
        val upiBannerText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        upiBannerText.addView(TextView(context).apply {
            text = "Support via UPI Apps"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        upiBannerText.addView(TextView(context).apply {
            text = "India • GPay, PhonePe, Paytm, BHIM"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2), 0, 0)
        })
        upiBanner.addView(upiBannerText)
        supportCard.addView(upiBanner)

        val amountBtns = mutableListOf<Pair<Int, Button>>()
        var payBtn: Button? = null

        fun refreshAmountState() {
            amountBtns.forEach { (amt, b) ->
                val active = amt == selectedAmount
                b.background = pillBg(active)
                b.setTextColor(if (active) Color.WHITE else Color.parseColor("#CBD5E1"))
            }
            val amt = selectedAmount
            payBtn?.text = if (amt != null && amt > 0) "Pay ₹$amt via UPI" else "Open UPI App"
        }

        val amtScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(12), 0, dp(4))
        }
        val amtRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        presets.forEachIndexed { idx, amt ->
            val b = Button(context).apply {
                text = "₹$amt"
                isAllCaps = false
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
                if (idx > 0) lp.leftMargin = dp(8)
                layoutParams = lp
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    selectedAmount = if (selectedAmount == amt) null else amt
                    refreshAmountState()
                }
            }
            amountBtns.add(amt to b)
            amtRow.addView(b)
        }
        amtScroll.addView(amtRow)
        supportCard.addView(amtScroll)

        val actionPay = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(10)
            }
            isAllCaps = false
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#2563EB"))
            ).apply {
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                DonationManager.launchUpiChooser(context, upiId, payeeName, finalNote, selectedAmount)
            }
        }
        payBtn = actionPay
        upiBanner.setOnClickListener { actionPay.performClick() }
        supportCard.addView(actionPay)
        root.addView(supportCard)

        // Direct QR + Address / UPI Copy Card
        val directCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cardBg()
        }

        directCard.addView(TextView(context).apply {
            text = "DIRECT SCAN & ADDRESS"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8B9BB4"))
            setPadding(0, 0, 0, dp(12))
        })

        val channelBtns = mutableListOf<Pair<Int, Button>>()
        val ivQr = ImageView(context).apply {
            val size = dp(176)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
                bottomMargin = dp(14)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
        }

        val tvHelper = TextView(context).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        }

        val copyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvAddr = TextView(context).apply {
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg("#101524", "#27314F", 12)
        }

        val btnCopy = Button(context).apply {
            text = "Copy"
            isAllCaps = false
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46))
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#7C5CFF"))
            }
        }

        val tvWarn = TextView(context).apply {
            textSize = 12f
            lineSpacingExtra = dp(3).toFloat()
            setTextColor(Color.parseColor("#FBBF24"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardBg("#262114", "#6B531B", 12)
        }

        fun refreshChannelState() {
            val active = channels[selectedChannelIndex]
            channelBtns.forEach { (idx, btn) ->
                val isSel = idx == selectedChannelIndex
                btn.background = pillBg(isSel)
                btn.setTextColor(if (isSel) Color.WHITE else Color.parseColor("#CBD5E1"))
            }
            val payload = if (active.isUpi) {
                DonationManager.buildUpiUri(upiId, payeeName, finalNote, selectedAmount).toString()
            } else {
                active.addressOrPayload
            }
            ivQr.setImageBitmap(DonationManager.generateMatrixQrBitmap(payload, dp(156)))
            tvHelper.text = active.helperText
            tvAddr.text = active.addressOrPayload
            tvWarn.text = "⚠️ ${active.warningText}"
            btnCopy.setOnClickListener {
                DonationManager.copyToClipboard(context, active.pillLabel, active.addressOrPayload)
            }
        }

        val chScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val chRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        channels.forEachIndexed { idx, ch ->
            val p = Button(context).apply {
                text = ch.pillLabel
                isAllCaps = false
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
                if (idx > 0) lp.leftMargin = dp(8)
                layoutParams = lp
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    selectedChannelIndex = idx
                    refreshChannelState()
                }
            }
            channelBtns.add(idx to p)
            chRow.addView(p)
        }
        chScroll.addView(chRow)
        directCard.addView(chScroll)
        directCard.addView(ivQr)
        directCard.addView(tvHelper)
        copyRow.addView(tvAddr)
        copyRow.addView(btnCopy)
        directCard.addView(copyRow)
        directCard.addView(tvWarn)
        root.addView(directCard)

        refreshAmountState()
        refreshChannelState()

        scrollView.addView(root)
        AlertDialog.Builder(context)
            .setView(scrollView)
            .show()
    }
}
