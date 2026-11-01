package com.AryaVidya

import com.lagradost.cloudstream3.Plugins
import com.lagradost.cloudstream3.MainAPI
import android.content.Context

@Plugins
class AryaVidyaPlugin : Plugin() {
    override fun load(context: Context) {
        // Registering the provider
        registerMainAPI(AryaVidyaProvider())
    }
}
