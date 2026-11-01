package com.hungrysturdy.Aryavidya
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AryaVidyaPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be registered here using registerMainAPI
        registerMainAPI(AryaVidyaProvider())
    }
}
