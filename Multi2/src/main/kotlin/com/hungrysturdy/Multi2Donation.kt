package com.hungrysturdy

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder

object Multi2Donation {

    const val DEFAULT_UPI_ID = "7409811853@nyes"
    const val DEFAULT_PAYEE_NAME = "Hungrysturdy"
    const val DEFAULT_NOTE = "Fuel for late-night plugin builds"
    val DEFAULT_PRESETS = listOf(21, 51, 101, 251, 501)
    const val DEFAULT_AMOUNT = 101

    fun open(
        context: Context,
        pluginName: String? = null,
        upiId: String = DEFAULT_UPI_ID,
        payeeName: String = DEFAULT_PAYEE_NAME,
        customNote: String? = null,
        presets: List<Int> = DEFAULT_PRESETS,
        defaultAmount: Int = DEFAULT_AMOUNT
    ) {
        val finalNote = customNote ?: if (!pluginName.isNullOrBlank()) {
            "$DEFAULT_NOTE ($pluginName)"
        } else {
            DEFAULT_NOTE
        }

        val tagLine = if (!pluginName.isNullOrBlank()) {
            "$pluginName aur sab providers ke fast updates ke liye ek chhoti si support de sakte hain."
        } else {
            "Agar yeh plugin aapka time bachata hai, toh ek chhoti si support se agle builds ko boost mil sakta hai."
        }

        val dp: (Int) -> Int = { value ->
            (value * context.resources.displayMetrics.density).toInt()
        }

        var selectedAmount: Int? = defaultAmount.takeIf { it > 0 }
        var isSyncingFromChip = false

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#12141A"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }

        // Title
        val tvTitle = TextView(context).apply {
            text = "$payeeName ko Support Karein"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        root.addView(tvTitle)

        // Tagline
        val tvSub = TextView(context).apply {
            text = tagLine
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(tvSub)

        // UPI ID Card
        val upiCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E222D"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val upiCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvUpiLabel = TextView(context).apply {
            text = "UPI ID"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        }

        val tvUpiVal = TextView(context).apply {
            text = upiId
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(2), 0, 0)
        }

        upiCol.addView(tvUpiLabel)
        upiCol.addView(tvUpiVal)

        val btnCopy = Button(context).apply {
            text = "Copy"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#283044"))
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                copyText(context, "UPI ID", upiId)
            }
        }

        upiCard.addView(upiCol)
        upiCard.addView(btnCopy)
        root.addView(upiCard)

        // Preset Label
        val tvPresetLabel = TextView(context).apply {
            text = "Amount Chunein"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(tvPresetLabel)

        val chipButtons = mutableListOf<Pair<Int, Button>>()
        var etCustomAmount: EditText? = null
        var btnDonate: Button? = null

        fun styleChip(button: Button, checked: Boolean) {
            button.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(
                    if (checked) Color.parseColor("#2563EB")
                    else Color.parseColor("#1E222D")
                )
                setStroke(
                    dp(1),
                    if (checked) Color.parseColor("#3B82F6")
                    else Color.parseColor("#334155")
                )
            }
            button.setTextColor(Color.WHITE)
        }

        fun refreshChips() {
            chipButtons.forEach { (amt, btn) ->
                styleChip(btn, amt == selectedAmount)
            }
        }

        fun refreshDonateLabel() {
            val amt = selectedAmount
            btnDonate?.text = if (amt != null && amt > 0) {
                "₹$amt ke Saath Support Karein"
            } else {
                "UPI App Mein Aage Badhein"
            }
        }

        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        presets.forEachIndexed { idx, amt ->
            val chipBtn = Button(context).apply {
                text = "₹$amt"
                isAllCaps = false
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(38)
                )
                if (idx > 0) lp.leftMargin = dp(8)
                layoutParams = lp
                setPadding(dp(14), 0, dp(14), 0)
                styleChip(this, amt == selectedAmount)
                setOnClickListener {
                    isSyncingFromChip = true
                    selectedAmount = amt
                    etCustomAmount?.setText(amt.toString())
                    etCustomAmount?.setSelection(etCustomAmount?.text?.length ?: 0)
                    isSyncingFromChip = false
                    refreshChips()
                    refreshDonateLabel()
                }
            }
            chipButtons.add(amt to chipBtn)
            chipRow.addView(chipBtn)
        }

        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // Custom Amount Input
        val tvCustomLabel = TextView(context).apply {
            text = "Custom Amount (₹)"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(tvCustomLabel)

        val editText = EditText(context).apply {
            hint = "Jaise 150"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E222D"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            selectedAmount?.let { setText(it.toString()) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val parsed = s?.toString()?.trim()?.toIntOrNull()
                    selectedAmount = if (parsed != null && parsed > 0) parsed else null
                    if (!isSyncingFromChip) {
                        refreshChips()
                    }
                    refreshDonateLabel()
                }
            })
        }
        etCustomAmount = editText
        root.addView(editText)

        // Status Hint
        val tvStatus = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(tvStatus)

        // Donate Button
        val donateButton = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(16)
            }
            isAllCaps = false
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setOnClickListener {
                val uri = buildUpiUri(upiId, payeeName, finalNote, selectedAmount)
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "UPI App chunein").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    tvStatus.text = "UPI app open ho raha hai..."
                    tvStatus.visibility = View.VISIBLE
                } catch (_: Exception) {
                    copyText(context, "UPI ID", upiId, "Koi UPI app nahi mila. UPI ID copy ho gayi hai.")
                    tvStatus.text = "UPI ID clipboard mein copy ho gayi hai."
                    tvStatus.visibility = View.VISIBLE
                }
            }
        }
        btnDonate = donateButton
        root.addView(donateButton)

        refreshDonateLabel()
        scrollView.addView(root)

        AlertDialog.Builder(context)
            .setView(scrollView)
            .show()
    }

    fun buildUpiUri(upiId: String, payeeName: String, note: String, amount: Int?): Uri {
        val cleanNote = note.take(50).trim()
        val encodedName = URLEncoder.encode(payeeName.trim(), "UTF-8")
        val encodedNote = URLEncoder.encode(cleanNote, "UTF-8")

        val sb = StringBuilder("upi://pay")
            .append("?pa=").append(Uri.encode(upiId.trim()))
            .append("&pn=").append(encodedName)
            .append("&tn=").append(encodedNote)
            .append("&cu=INR")

        if (amount != null && amount > 0) {
            sb.append("&am=").append(amount)
        }
        return Uri.parse(sb.toString())
    }

    fun copyText(context: Context, label: String, value: String, toastMsg: String? = null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, toastMsg ?: "$label copy ho gaya", Toast.LENGTH_SHORT).show()
    }
}
