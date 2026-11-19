package com.hungrysturdy

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Plugin : Plugin() {

    companion object {
        const val PREF_PREFIX = "moviefox_server_"

        val AVAILABLE_SERVERS = listOf(
            "purstream" to "Purstream (Direct API)",
            "castle" to "Castle (Direct API)",
            "direct" to "Direct Server (API)",
            "debrid" to "Debrid (API)",
            "vidsrc" to "VidSrc (API)",
            "autoembed" to "AutoEmbed (API)",
            "hls" to "HLS / Workers / Nitrox",
            "fsl" to "FSL Server",
            "fsl2" to "FSL2 Server",
            "gdrive" to "GDrive Server",
            "embeds" to "Embed Hosts (VidPrime, Voe, StreamTape, Vidara, Lulu, Pixeldrain)"
        )

        fun isServerEnabled(key: String): Boolean {
            return getKey("$PREF_PREFIX$key", true) ?: true
        }

        fun setServerEnabled(key: String, enabled: Boolean) {
            setKey("$PREF_PREFIX$key", enabled)
        }
    }

    override fun load(context: Context) {
        registerMainAPI(SflixProvider())
        registerMainAPI(HindiDramaProvider())
        registerMainAPI(MovieFoxProvider())

        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(VoeToExtractor())
        registerExtractorAPI(VoeCoExtractor())

        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(PixeldrainDevExtractor())
        registerExtractorAPI(GDriveExtractor())

        registerExtractorAPI(VidPrimeExtractor())
        registerExtractorAPI(NitroxExtractor())
        registerExtractorAPI(NxStreamHubExtractor())
        registerExtractorAPI(VibuxerExtractor())

        registerExtractorAPI(RabbitstreamExtractor())
        registerExtractorAPI(UpCloudExtractor())
        registerExtractorAPI(MegaCloudExtractor())
        registerExtractorAPI(MegaCloudClubExtractor())

        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(FilemoonExtractor())

        registerExtractorAPI(LuluVidExtractor())
        registerExtractorAPI(LuluStreamAltExtractor())
        registerExtractorAPI(LuluVdoExtractor())

        registerExtractorAPI(EarnVidsExtractor())
        registerExtractorAPI(EarnVidsProExtractor())

        registerExtractorAPI(VidaraExtractor())
        registerExtractorAPI(VidaraAltExtractor())

        registerExtractorAPI(UpBoltExtractor())
        registerExtractorAPI(UpBoltTvExtractor())

        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssCdnExtractor())
        registerExtractorAPI(ShortIcuExtractor())

        registerExtractorAPI(LPlayGoExtractor())
        registerExtractorAPI(VidMolyExtractor())
        registerExtractorAPI(SwVidMolyExtractor())
        registerExtractorAPI(VidMolyToExtractor())

        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(StreamTapeToExtractor())
        registerExtractorAPI(StreamTapeNetExtractor())
        registerExtractorAPI(StreamTapeXyzExtractor())
        registerExtractorAPI(ShaveTapeExtractor())
        registerExtractorAPI(StrCloudExtractor())
        registerExtractorAPI(StrTapeTechExtractor())
        registerExtractorAPI(StapeFunExtractor())

        openSettings = { ctx ->
            val labels = AVAILABLE_SERVERS.map { it.second }.toTypedArray()
            val checkedItems = AVAILABLE_SERVERS.map { isServerEnabled(it.first) }.toBooleanArray()

            AlertDialog.Builder(ctx)
                .setTitle("MovieFox - Select Active Servers")
                .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("Save") { dialog, _ ->
                    AVAILABLE_SERVERS.forEachIndexed { index, pair ->
                        setServerEnabled(pair.first, checkedItems[index])
                    }
                    dialog.dismiss()
                }
                .setNeutralButton("Select All") { dialog, _ ->
                    AVAILABLE_SERVERS.forEach { pair ->
                        setServerEnabled(pair.first, true)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }
}
