package com.hungrysturdy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }
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

        root.addView(TextView(context).apply {
            text = "$payeeName ko Support Karein"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        })

        root.addView(TextView(context).apply {
            text = tagLine
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6), 0, dp(14))
        })

        val upiCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
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
        upiCol.addView(TextView(context).apply {
            text = "UPI ID"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        })
        upiCol.addView(TextView(context).apply {
            text = upiId
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(2), 0, 0)
        })

        val btnCopy = MaterialButton(context).apply {
            text = "Copy"
            textAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#283044"))
            setTextColor(Color.parseColor("#E2E8F0"))
            cornerRadius = dp(10)
            setOnClickListener {
                copyText(context, "UPI ID", upiId)
            }
        }

        upiCard.addView(upiCol)
        upiCard.addView(btnCopy)
        root.addView(upiCard)

        root.addView(TextView(context).apply {
            text = "Amount Chunein"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(16), 0, dp(8))
        })

        var etCustomAmount: TextInputEditText? = null
        var btnDonate: MaterialButton? = null

        fun refreshDonateLabel() {
            val amt = selectedAmount
            btnDonate?.text = if (amt != null && amt > 0) {
                "₹$amt ke Saath Support Karein"
            } else {
                "UPI App Mein Aage Badhein"
            }
        }

        val chipGroup = ChipGroup(context).apply {
            isSingleSelection = true
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(6)
        }

        presets.forEach { amt ->
            val chip = Chip(context).apply {
                text = "₹$amt"
                isCheckable = true
                isChecked = (amt == selectedAmount)
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#1E222D"))
                )
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isSyncingFromChip = true
                    selectedAmount = amt
                    etCustomAmount?.setText(amt.toString())
                    etCustomAmount?.setSelection(etCustomAmount?.text?.length ?: 0)
                    isSyncingFromChip = false
                    refreshDonateLabel()
                }
            }
            chipGroup.addView(chip)
        }
        root.addView(chipGroup)

        val til = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            hint = "Custom Amount (₹)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            hintTextColor = ColorStateList.valueOf(Color.parseColor("#94A3B8"))
        }

        val editText = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            selectedAmount?.let { setText(it.toString()) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val parsed = s?.toString()?.trim()?.toIntOrNull()
                    selectedAmount = if (parsed != null && parsed > 0) parsed else null
                    if (!isSyncingFromChip) {
                        for (i in 0 until chipGroup.childCount) {
                            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
                            val chipVal = chip.text.toString().removePrefix("₹").trim().toIntOrNull()
                            chip.isChecked = (chipVal != null && chipVal == selectedAmount)
                        }
                    }
                    refreshDonateLabel()
                }
            })
        }
        etCustomAmount = editText
        til.addView(editText)
        root.addView(til)

        val tvStatus = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(8), 0, 0)
            visibility = android.view.View.GONE
        }
        root.addView(tvStatus)

        val dialog = AlertDialog.Builder(context)
            .setView(scrollView)
            .create()

        val donateButton = MaterialButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                topMargin = dp(16)
            }
            textAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
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
                    tvStatus.visibility = android.view.View.VISIBLE
                } catch (_: Exception) {
                    copyText(context, "UPI ID", upiId, "Koi UPI app nahi mila. UPI ID copy ho gayi hai.")
                    tvStatus.text = "UPI ID clipboard mein copy ho gayi hai."
                    tvStatus.visibility = android.view.View.VISIBLE
                }
            }
        }
        btnDonate = donateButton
        root.addView(donateButton)

        refreshDonateLabel()
        scrollView.addView(root)
        dialog.show()
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
