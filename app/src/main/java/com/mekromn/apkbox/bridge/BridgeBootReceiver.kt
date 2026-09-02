package com.mekromn.apkbox.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mekromn.apkbox.ApkBoxServices

class BridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        val prefs = ApkBoxServices.bridgePreferences(context.applicationContext)
        if (prefs.state.value.enabled && prefs.state.value.hasRelayToken) {
            runCatching { RemoteBridgeService.start(context.applicationContext) }
        }
    }
}
