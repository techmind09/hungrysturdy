package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeDubIndiaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main API provider implementation
        registerMainAPI(AnimeDubIndiaProvider())
    }
}
