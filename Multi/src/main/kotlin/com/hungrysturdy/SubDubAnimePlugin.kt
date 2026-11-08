package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SubDubAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // Registering SubDubAnimeProvider
        registerMainAPI(SubDubAnimeProvider())
    }
}
