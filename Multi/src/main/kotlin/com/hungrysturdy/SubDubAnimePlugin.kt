package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SubDubAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // Registering SubDubAnimeProvider
        registerMainAPI(SubDubAnimeProvider())
    }
}
