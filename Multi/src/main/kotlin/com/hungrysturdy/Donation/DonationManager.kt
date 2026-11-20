package com.hungrysturdy.Donation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder
import kotlin.math.abs

object DonationManager {

    const val DEFAULT_UPI_ID = "7409811853@nyes"
    const val DEFAULT_PAYEE_NAME = "Hungrysturdy"
    const val DEFAULT_NOTE = "Fuel for late-night plugin builds"
    val DEFAULT_PRESETS = listOf(21, 51, 101, 251, 501)
    const val DEFAULT_AMOUNT = 101

    data class SupportChannel(
        val id: String,
        val pillLabel: String,
        val addressOrPayload: String,
        val helperText: String,
        val warningText: String,
        val isUpi: Boolean = false
    )

    fun buildUpiUri(
        upiId: String = DEFAULT_UPI_ID,
        payeeName: String = DEFAULT_PAYEE_NAME,
        note: String = DEFAULT_NOTE,
        amount: Int? = DEFAULT_AMOUNT
    ): Uri {
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

    fun launchUpiChooser(
        context: Context,
        upiId: String = DEFAULT_UPI_ID,
        payeeName: String = DEFAULT_PAYEE_NAME,
        note: String = DEFAULT_NOTE,
        amount: Int? = DEFAULT_AMOUNT,
        onStatus: ((String) -> Unit)? = null
    ) {
        val uri = buildUpiUri(upiId, payeeName, note, amount)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "Choose UPI App").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            onStatus?.invoke("UPI app open ho raha hai...")
        } catch (_: Exception) {
            copyToClipboard(context, "UPI ID", upiId, "No UPI app found. UPI ID copied.")
            onStatus?.invoke("UPI ID clipboard mein copy ho gayi hai.")
        }
    }

    fun copyToClipboard(
        context: Context,
        label: String,
        value: String,
        customToast: String? = null
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, customToast ?: "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun generateMatrixQrBitmap(payload: String, sizePx: Int): Bitmap {
        val cells = 29
        val matrix = Array(cells) { BooleanArray(cells) }

        fun drawFinder(top: Int, left: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val rr = top + r
                    val cc = left + c
                    if (rr !in 0 until cells || cc !in 0 until cells) continue
                    val onBorder = r == -1 || r == 7 || c == -1 || c == 7
                    val outerRing = r == 0 || r == 6 || c == 0 || c == 6
                    val innerBox = r in 2..4 && c in 2..4
                    matrix[rr][cc] = !onBorder && (outerRing || innerBox)
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(0, cells - 7)
        drawFinder(cells - 7, 0)

        for (i in 8 until cells - 8) {
            matrix[6][i] = i % 2 == 0
            matrix[i][6] = i % 2 == 0
        }

        val bytes = payload.toByteArray(Charsets.UTF_8)
        var hashState = 2166136261L
        for (b in bytes) {
            hashState = (hashState xor (b.toLong() and 0xFF)) * 16777619L
        }

        for (r in 0 until cells) {
            for (c in 0 until cells) {
                val inTopLeft = r in 0..7 && c in 0..7
                val inTopRight = r in 0..7 && c in (cells - 8) until cells
                val inBottomLeft = r in (cells - 8) until cells && c in 0..7
                val inTiming = r == 6 || c == 6
                if (inTopLeft || inTopRight || inBottomLeft || inTiming) continue

                val byteIdx = (r * cells + c) % bytes.size.coerceAtLeast(1)
                val bitIdx = (r + c * 3) % 8
                val dataBit = ((bytes[byteIdx].toInt() shr bitIdx) and 1) == 1
                val mixBit = abs((hashState + r * 31L + c * 17L) % 2L) == 1L
                matrix[r][c] = dataBit xor mixBit
            }
        }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cellSize = (sizePx.toFloat() / cells).coerceAtLeast(1f)
        for (r in 0 until cells) {
            val y0 = (r * cellSize).toInt().coerceAtMost(sizePx - 1)
            val y1 = ((r + 1) * cellSize).toInt().coerceAtMost(sizePx)
            for (c in 0 until cells) {
                val x0 = (c * cellSize).toInt().coerceAtMost(sizePx - 1)
                val x1 = ((c + 1) * cellSize).toInt().coerceAtMost(sizePx)
                val color = if (matrix[r][c]) Color.BLACK else Color.WHITE
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        bmp.setPixel(x, y, color)
                    }
                }
            }
        }
        return bmp
    }
}
