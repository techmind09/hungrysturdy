package com.hungrysturdy.donation

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
        ): GradientDrawable {
            val gd = GradientDrawable()
            gd.cornerRadius = dp(radiusDp).toFloat()
            gd.setColor(Color.parseColor(bg))
            gd.setStroke(dp(1), Color.parseColor(stroke))
            return gd
        }

        fun pillBg(active: Boolean): GradientDrawable {
            val gd = GradientDrawable()
            gd.cornerRadius = dp(20).toFloat()
            gd.setColor(if (active) Color.parseColor("#2A2252") else Color.parseColor("#131829"))
            gd.setStroke(
                dp(if (active) 2 else 1),
                if (active) Color.parseColor("#7C5CFF") else Color.parseColor("#252E48")
            )
            return gd
        }

        fun makeTitle(textValue: String, sizeSp: Float, colorHex: String): TextView {
            val tv = TextView(context)
            tv.text = textValue
            tv.textSize = sizeSp
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setTextColor(Color.parseColor(colorHex))
            return tv
        }

        fun makeSubtitle(textValue: String, sizeSp: Float, colorHex: String): TextView {
            val tv = TextView(context)
            tv.text = textValue
            tv.textSize = sizeSp
            tv.setTextColor(Color.parseColor(colorHex))
            return tv
        }

        val scrollView = ScrollView(context)
        scrollView.isFillViewport = true
        scrollView.setBackgroundColor(Color.parseColor("#0B0F1A"))

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(22))

        // Top Header Card
        val headerCard = LinearLayout(context)
        headerCard.orientation = LinearLayout.HORIZONTAL
        headerCard.gravity = Gravity.CENTER_VERTICAL
        headerCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        headerCard.background = cardBg("#131829", "#242D47", 16)

        val badge = TextView(context)
        badge.text = "⚡"
        badge.textSize = 18f
        badge.gravity = Gravity.CENTER
        badge.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        badge.background = cardBg("#261F47", "#5B46C7", 11)

        val brandCol = LinearLayout(context)
        brandCol.orientation = LinearLayout.VERTICAL
        val brandLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        brandLp.leftMargin = dp(12)
        brandCol.layoutParams = brandLp
        brandCol.addView(makeTitle(payeeName, 17f, "#F8FAFC"))
        brandCol.addView(
            makeSubtitle(
                if (!pluginName.isNullOrBlank()) "$pluginName • Community support" else "Plugin community support",
                12f,
                "#94A3B8"
            )
        )

        headerCard.addView(badge)
        headerCard.addView(brandCol)
        root.addView(headerCard)

        // Hero Heading
        val heroTitle = makeTitle("Monthly Support Goal", 21f, "#F8FAFC")
        heroTitle.setPadding(dp(2), dp(18), dp(2), dp(4))
        root.addView(heroTitle)

        val heroSub = makeSubtitle(
            "Every contribution keeps plugin updates & extractors running ✨",
            13f,
            "#94A3B8"
        )
        heroSub.setPadding(dp(2), 0, dp(2), dp(14))
        root.addView(heroSub)

        // Goal Card
        val goalCard = LinearLayout(context)
        goalCard.orientation = LinearLayout.VERTICAL
        goalCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        goalCard.background = cardBg()

        val goalTop = LinearLayout(context)
        goalTop.orientation = LinearLayout.HORIZONTAL
        goalTop.gravity = Gravity.CENTER_VERTICAL

        val goalLeft = LinearLayout(context)
        goalLeft.orientation = LinearLayout.VERTICAL
        goalLeft.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val tierLabel = makeTitle("SUPPORT TIER & TARGET", 10.5f, "#8B9BB4")
        tierLabel.letterSpacing = 0.08f
        goalLeft.addView(tierLabel)

        val tierTitle = makeTitle(
            if (!pluginName.isNullOrBlank()) "$pluginName Builds" else "Hungrysturdy Builds",
            16f,
            "#F1F5F9"
        )
        tierTitle.setPadding(0, dp(2), 0, 0)
        goalLeft.addView(tierTitle)

        val pct = if (goalTarget > 0f) ((goalCurrent / goalTarget) * 100f).toInt().coerceIn(0, 100) else 0
        val pctBadge = makeTitle("$pct%", 12.5f, "#C4B5FD")
        pctBadge.setPadding(dp(12), dp(5), dp(12), dp(5))
        pctBadge.background = cardBg("#2A1E46", "#6D4AFF", 20)

        goalTop.addView(goalLeft)
        goalTop.addView(pctBadge)
        goalCard.addView(goalTop)

        val ratioTv = makeTitle("₹${goalCurrent.toInt()} / ₹${goalTarget.toInt()}", 24f, "#FFFFFF")
        ratioTv.setPadding(0, dp(12), 0, dp(10))
        goalCard.addView(ratioTv)

        val trackH = dp(10)
        val track = LinearLayout(context)
        track.orientation = LinearLayout.HORIZONTAL
        track.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, trackH)
        val trackBg = GradientDrawable()
        trackBg.cornerRadius = (trackH / 2).toFloat()
        trackBg.setColor(Color.parseColor("#242D47"))
        track.background = trackBg

        val fillFrac = (pct / 100f).coerceIn(0.04f, 1f)
        val fillView = View(context)
        fillView.layoutParams = LinearLayout.LayoutParams(0, trackH, fillFrac)
        val fillBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#EC4899"), Color.parseColor("#A855F7"), Color.parseColor("#6366F1"))
        )
        fillBg.cornerRadius = (trackH / 2).toFloat()
        fillView.background = fillBg

        val emptyView = View(context)
        emptyView.layoutParams = LinearLayout.LayoutParams(0, trackH, (1f - fillFrac).coerceAtLeast(0f))

        track.addView(fillView)
        track.addView(emptyView)
        goalCard.addView(track)

        val rangeRow = LinearLayout(context)
        rangeRow.orientation = LinearLayout.HORIZONTAL
        rangeRow.setPadding(0, dp(7), 0, dp(12))
        val minTv = makeSubtitle("₹0", 11.5f, "#7D8DA6")
        minTv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val maxTv = makeSubtitle("₹${goalTarget.toInt()} goal", 11.5f, "#7D8DA6")
        rangeRow.addView(minTv)
        rangeRow.addView(maxTv)
        goalCard.addView(rangeRow)

        goalCard.addView(makeTitle("$supportersCount supporters this month", 12.5f, "#CBD5E1"))
        root.addView(goalCard)

        // Support This Project Card
        val supportCard = LinearLayout(context)
        supportCard.orientation = LinearLayout.VERTICAL
        val supportLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        supportLp.topMargin = dp(16)
        supportCard.layoutParams = supportLp
        supportCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        supportCard.background = cardBg()

        val supportHeader = makeTitle("SUPPORT THIS PROJECT", 11f, "#8B9BB4")
        supportHeader.letterSpacing = 0.08f
        supportHeader.setPadding(0, 0, 0, dp(12))
        supportCard.addView(supportHeader)

        val upiBanner = LinearLayout(context)
        upiBanner.orientation = LinearLayout.HORIZONTAL
        upiBanner.gravity = Gravity.CENTER_VERTICAL
        upiBanner.setPadding(dp(14), dp(12), dp(14), dp(12))
        upiBanner.background = cardBg("#1B2238", "#2E3A5C", 14)

        val flagTv = TextView(context)
        flagTv.text = "🇮🇳"
        flagTv.textSize = 18f
        flagTv.gravity = Gravity.CENTER
        flagTv.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        flagTv.background = cardBg("#232C47", "#3B4973", 10)

        val upiBannerText = LinearLayout(context)
        upiBannerText.orientation = LinearLayout.VERTICAL
        val bannerTextLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bannerTextLp.leftMargin = dp(12)
        upiBannerText.layoutParams = bannerTextLp
        upiBannerText.addView(makeTitle("Support via UPI Apps", 15f, "#FFFFFF"))
        val bannerSub = makeSubtitle("India • GPay, PhonePe, Paytm, BHIM", 12f, "#94A3B8")
        bannerSub.setPadding(0, dp(2), 0, 0)
        upiBannerText.addView(bannerSub)

        upiBanner.addView(flagTv)
        upiBanner.addView(upiBannerText)
        supportCard.addView(upiBanner)

        val amountBtns = mutableListOf<Pair<Int, Button>>()
        var payBtn: Button? = null

        fun refreshAmountState() {
            amountBtns.forEach { (amt, b) ->
                val active = amt == selectedAmount
                b.background = pillBg(active)
                b.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#CBD5E1"))
            }
            val amt = selectedAmount
            payBtn?.text = if (amt != null && amt > 0) "Pay ₹$amt via UPI" else "Open UPI App"
        }

        val amtScroll = HorizontalScrollView(context)
        amtScroll.isHorizontalScrollBarEnabled = false
        amtScroll.setPadding(0, dp(12), 0, dp(4))
        val amtRow = LinearLayout(context)
        amtRow.orientation = LinearLayout.HORIZONTAL

        presets.forEachIndexed { idx, amt ->
            val b = Button(context)
            b.text = "₹$amt"
            b.isAllCaps = false
            b.textSize = 13f
            b.setTypeface(b.typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
            if (idx > 0) lp.leftMargin = dp(8)
            b.layoutParams = lp
            b.setPadding(dp(14), 0, dp(14), 0)
            b.setOnClickListener {
                selectedAmount = if (selectedAmount == amt) null else amt
                refreshAmountState()
            }
            amountBtns.add(amt to b)
            amtRow.addView(b)
        }
        amtScroll.addView(amtRow)
        supportCard.addView(amtScroll)

        val actionPay = Button(context)
        val payLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
        payLp.topMargin = dp(10)
        actionPay.layoutParams = payLp
        actionPay.isAllCaps = false
        actionPay.textSize = 14.5f
        actionPay.setTypeface(actionPay.typeface, Typeface.BOLD)
        actionPay.setTextColor(Color.WHITE)
        val payBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#2563EB"))
        )
        payBg.cornerRadius = dp(12).toFloat()
        actionPay.background = payBg
        actionPay.setOnClickListener {
            DonationManager.launchUpiChooser(context, upiId, payeeName, finalNote, selectedAmount)
        }
        payBtn = actionPay
        upiBanner.setOnClickListener { actionPay.performClick() }
        supportCard.addView(actionPay)
        root.addView(supportCard)

        // Direct QR + Address / UPI Copy Card
        val directCard = LinearLayout(context)
        directCard.orientation = LinearLayout.VERTICAL
        val directLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        directLp.topMargin = dp(16)
        directCard.layoutParams = directLp
        directCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        directCard.background = cardBg()

        val directHeader = makeTitle("DIRECT SCAN & ADDRESS", 11f, "#8B9BB4")
        directHeader.letterSpacing = 0.08f
        directHeader.setPadding(0, 0, 0, dp(12))
        directCard.addView(directHeader)

        val channelBtns = mutableListOf<Pair<Int, Button>>()
        val ivQr = ImageView(context)
        val qrSize = dp(176)
        val qrLp = LinearLayout.LayoutParams(qrSize, qrSize)
        qrLp.gravity = Gravity.CENTER_HORIZONTAL
        qrLp.topMargin = dp(8)
        qrLp.bottomMargin = dp(14)
        ivQr.layoutParams = qrLp
        ivQr.setPadding(dp(10), dp(10), dp(10), dp(10))
        val qrBg = GradientDrawable()
        qrBg.cornerRadius = dp(16).toFloat()
        qrBg.setColor(Color.WHITE)
        ivQr.background = qrBg

        val tvHelper = makeTitle("", 14f, "#FFFFFF")
        tvHelper.setPadding(0, 0, 0, dp(10))

        val copyRow = LinearLayout(context)
        copyRow.orientation = LinearLayout.HORIZONTAL
        copyRow.gravity = Gravity.CENTER_VERTICAL

        val tvAddr = TextView(context)
        tvAddr.textSize = 13f
        tvAddr.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        tvAddr.setTextColor(Color.parseColor("#E2E8F0"))
        tvAddr.maxLines = 2
        tvAddr.ellipsize = TextUtils.TruncateAt.MIDDLE
        val addrLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addrLp.rightMargin = dp(10)
        tvAddr.layoutParams = addrLp
        tvAddr.setPadding(dp(12), dp(12), dp(12), dp(12))
        tvAddr.background = cardBg("#101524", "#27314F", 12)

        val btnCopy = Button(context)
        btnCopy.text = "Copy"
        btnCopy.isAllCaps = false
        btnCopy.textSize = 13.5f
        btnCopy.setTypeface(btnCopy.typeface, Typeface.BOLD)
        btnCopy.setTextColor(Color.WHITE)
        btnCopy.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46))
        btnCopy.setPadding(dp(18), 0, dp(18), 0)
        val copyBg = GradientDrawable()
        copyBg.cornerRadius = dp(12).toFloat()
        copyBg.setColor(Color.parseColor("#7C5CFF"))
        btnCopy.background = copyBg

        val tvWarn = TextView(context)
        tvWarn.textSize = 12f
        tvWarn.setLineSpacing(dp(3).toFloat(), 1f)
        tvWarn.setTextColor(Color.parseColor("#FBBF24"))
        val warnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        warnLp.topMargin = dp(12)
        tvWarn.layoutParams = warnLp
        tvWarn.setPadding(dp(12), dp(10), dp(12), dp(10))
        tvWarn.background = cardBg("#262114", "#6B531B", 12)

        fun refreshChannelState() {
            val active = channels[selectedChannelIndex]
            channelBtns.forEach { (idx, btn) ->
                val isSel = idx == selectedChannelIndex
                btn.background = pillBg(isSel)
                btn.setTextColor(Color.parseColor(if (isSel) "#FFFFFF" else "#CBD5E1"))
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

        val chScroll = HorizontalScrollView(context)
        chScroll.isHorizontalScrollBarEnabled = false
        val chRow = LinearLayout(context)
        chRow.orientation = LinearLayout.HORIZONTAL

        channels.forEachIndexed { idx, ch ->
            val p = Button(context)
            p.text = ch.pillLabel
            p.isAllCaps = false
            p.textSize = 12.5f
            p.setTypeface(p.typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38))
            if (idx > 0) lp.leftMargin = dp(8)
            p.layoutParams = lp
            p.setPadding(dp(14), 0, dp(14), 0)
            p.setOnClickListener {
                selectedChannelIndex = idx
                refreshChannelState()
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
