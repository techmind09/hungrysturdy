package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Main Providers
        registerMainAPI(SflixProvider())
        registerMainAPI(HindiDramaProvider())
    }
}
