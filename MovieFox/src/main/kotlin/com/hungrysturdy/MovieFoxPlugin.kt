package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieFoxProvider())
        openSettings = { ctx ->
            try {
                MovieFoxSettingsDialog.show(ctx)
            } catch (_: Exception) {}
        }
    }
}
