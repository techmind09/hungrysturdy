package com.hungrysturdy.donation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.net.URLEncoder

object MovieFoxDonation {

    private const val TAG = "HungrysturdyDonationSheet"

    const val DEFAULT_UPI_ID = "7409811853@nyes"
    const val DEFAULT_PAYEE_NAME = "Hungrysturdy"
    const val DEFAULT_NOTE = "Fuel for late-night plugin builds"
    val DEFAULT_PRESETS = listOf(21, 51, 101, 251, 501)
    const val DEFAULT_AMOUNT = 101

    /**
     * Kisi bhi plugin se ek line mein call karein:
     * PluginDonation.open(parentFragmentManager)
     * ya kisi specific plugin ke liye:
     * PluginDonation.open(parentFragmentManager, pluginLabel = "Anime Extension")
     */
    fun open(
        fragmentManager: FragmentManager,
        pluginLabel: String? = null,
        upiId: String = DEFAULT_UPI_ID,
        payeeName: String = DEFAULT_PAYEE_NAME,
        customNote: String? = null,
        presets: List<Int> = DEFAULT_PRESETS,
        defaultAmount: Int = DEFAULT_AMOUNT
    ) {
        if (fragmentManager.findFragmentByTag(TAG) != null) return

        val finalNote = customNote ?: if (!pluginLabel.isNullOrBlank()) {
            "$DEFAULT_NOTE ($pluginLabel)"
        } else {
            DEFAULT_NOTE
        }

        val tagLine = if (!pluginLabel.isNullOrBlank()) {
            "$pluginLabel aur baaki Hungrysturdy plugins ke fast updates ke liye chhoti si support de sakte hain."
        } else {
            "Agar yeh plugin aapka time bachata hai, toh ek chhoti si support se agle builds ko boost mil sakta hai."
        }

        DonationBottomSheetFragment.newInstance(
            upiId = upiId,
            payeeName = payeeName,
            note = finalNote,
            tagLine = tagLine,
            presets = presets,
            defaultAmount = defaultAmount
        ).show(fragmentManager, TAG)
    }

    /**
     * Agar kisi jagah FragmentManager available na ho toh direct UPI launch fallback
     */
    fun quickLaunchUpi(context: Context, amount: Int? = DEFAULT_AMOUNT, pluginLabel: String? = null) {
        val note = if (!pluginLabel.isNullOrBlank()) "$DEFAULT_NOTE ($pluginLabel)" else DEFAULT_NOTE
        val uri = buildUpiUri(DEFAULT_UPI_ID, DEFAULT_PAYEE_NAME, note, amount)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            context.startActivity(Intent.createChooser(intent, "UPI App chunein"))
        } catch (_: Exception) {
            copyText(context, "UPI ID", DEFAULT_UPI_ID, "Koi UPI app nahi mila. UPI ID copy kar li gayi hai.")
        }
    }

    internal fun buildUpiUri(upiId: String, payeeName: String, note: String, amount: Int?): Uri {
        val cleanNote = note.take(50)
        val encodedName = URLEncoder.encode(payeeName.trim(), "UTF-8")
        val encodedNote = URLEncoder.encode(cleanNote.trim(), "UTF-8")

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

    internal fun copyText(
        context: Context,
        label: String,
        value: String,
        customToast: String? = null
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, customToast ?: "$label copy ho gaya", Toast.LENGTH_SHORT).show()
    }
}

class DonationBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var upiId: String
    private lateinit var payeeName: String
    private lateinit var note: String
    private lateinit var tagLine: String
    private lateinit var presets: List<Int>
    private var selectedAmount: Int? = null

    private var etCustomAmount: TextInputEditText? = null
    private var chipGroup: ChipGroup? = null
    private var btnDonate: MaterialButton? = null
    private var tvStatus: TextView? = null
    private var isSyncingFromChip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        upiId = args.getString(ARG_UPI_ID).orEmpty()
        payeeName = args.getString(ARG_PAYEE_NAME).orEmpty()
        note = args.getString(ARG_NOTE).orEmpty()
        tagLine = args.getString(ARG_TAG_LINE).orEmpty()
        presets = args.getIntArray(ARG_PRESETS)?.toList() ?: PluginDonation.DEFAULT_PRESETS
        val defAmt = args.getInt(ARG_DEFAULT_AMOUNT, PluginDonation.DEFAULT_AMOUNT)
        selectedAmount = defAmt.takeIf { it > 0 }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp = { value: Int -> (value * ctx.resources.displayMetrics.density).toInt() }

        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#12141A"))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }

        // Top Drag Handle
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#475569"))
            }
        }
        root.addView(handle)

        // Title
        val tvTitle = TextView(ctx).apply {
            text = "$payeeName ko Support Karein"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        root.addView(tvTitle)

        // Tagline
        val tvSub = TextView(ctx).apply {
            text = tagLine
            textSize = 13.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(6), 0, dp(16))
        }
        root.addView(tvSub)

        // UPI ID Box
        val upiCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E222D"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val upiTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvUpiLabel = TextView(ctx).apply {
            text = "DIRECT UPI ID"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        }

        val tvUpiVal = TextView(ctx).apply {
            text = upiId
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(2), 0, 0)
        }

        upiTextCol.addView(tvUpiLabel)
        upiTextCol.addView(tvUpiVal)

        val btnCopy = MaterialButton(ctx).apply {
            text = "Copy"
            textAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#283044"))
            setTextColor(Color.parseColor("#E2E8F0"))
            cornerRadius = dp(10)
            setOnClickListener {
                PluginDonation.copyText(ctx, "UPI ID", upiId)
            }
        }

        upiCard.addView(upiTextCol)
        upiCard.addView(btnCopy)
        root.addView(upiCard)

        // Preset Label
        val tvPresetLabel = TextView(ctx).apply {
            text = "Amount Chunein"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(tvPresetLabel)

        // Chips
        val chips = ChipGroup(ctx).apply {
            isSingleSelection = true
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(6)
        }

        presets.forEach { amt ->
            val chip = Chip(ctx).apply {
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
                    refreshDonateButton()
                }
            }
            chips.addView(chip)
        }
        chipGroup = chips
        root.addView(chips)

        // Custom Input
        val til = TextInputLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            hint = "Custom Amount (₹)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(Color.parseColor("#3B82F6"), Color.parseColor("#334155"))
                )
            )
            hintTextColor = ColorStateList.valueOf(Color.parseColor("#94A3B8"))
            defaultHintTextColor = ColorStateList.valueOf(Color.parseColor("#64748B"))
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
                        syncChips(selectedAmount)
                    }
                    refreshDonateButton()
                }
            })
        }
        etCustomAmount = editText
        til.addView(editText)
        root.addView(til)

        // Status text
        val statusView = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        tvStatus = statusView
        root.addView(statusView)

        // Primary Donate CTA
        val donateBtn = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(18)
            }
            textAllCaps = false
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val uri = PluginDonation.buildUpiUri(upiId, payeeName, note, selectedAmount)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(Intent.createChooser(intent, "UPI App chunein"))
                    statusView.text = "UPI app open ho raha hai..."
                    statusView.visibility = View.VISIBLE
                } catch (_: Exception) {
                    PluginDonation.copyText(
                        ctx,
                        "UPI ID",
                        upiId,
                        "Koi UPI app nahi mila. UPI ID copy kar li gayi hai."
                    )
                    statusView.text = "UPI ID clipboard mein copy ho gayi hai."
                    statusView.visibility = View.VISIBLE
                }
            }
        }
        btnDonate = donateBtn
        root.addView(donateBtn)

        refreshDonateButton()
        scrollView.addView(root)
        return scrollView
    }

    private fun syncChips(amount: Int?) {
        val group = chipGroup ?: return
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val chipVal = chip.text.toString().removePrefix("₹").trim().toIntOrNull()
            chip.isChecked = (chipVal != null && chipVal == amount)
        }
    }

    private fun refreshDonateButton() {
        val amt = selectedAmount
        btnDonate?.text = if (amt != null && amt > 0) {
            "₹$amt ke Saath Support Karein"
        } else {
            "UPI App Mein Aage Badhein"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        etCustomAmount = null
        chipGroup = null
        btnDonate = null
        tvStatus = null
    }

    companion object {
        private const val ARG_UPI_ID = "arg_upi_id"
        private const val ARG_PAYEE_NAME = "arg_payee_name"
        private const val ARG_NOTE = "arg_note"
        private const val ARG_TAG_LINE = "arg_tag_line"
        private const val ARG_PRESETS = "arg_presets"
        private const val ARG_DEFAULT_AMOUNT = "arg_default_amount"

        fun newInstance(
            upiId: String,
            payeeName: String,
            note: String,
            tagLine: String,
            presets: List<Int>,
            defaultAmount: Int
        ): DonationBottomSheetFragment {
            return DonationBottomSheetFragment().apply {
                arguments = bundleOf(
                    ARG_UPI_ID to upiId,
                    ARG_PAYEE_NAME to payeeName,
                    ARG_NOTE to note,
                    ARG_TAG_LINE to tagLine,
                    ARG_PRESETS to presets.toIntArray(),
                    ARG_DEFAULT_AMOUNT to defaultAmount
                )
            }
        }
    }
}
