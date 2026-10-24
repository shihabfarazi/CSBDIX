package com.bdixstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BDIXStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BDIXStreamProvider())
    }
}
