package com.mekromn.apkbox

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Wakes APKbox after Android DownloadManager finishes a download and runs the configured rules. */
class AutoScanDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ApkBoxServices.autoScanner(context).scanNow("Android download complete")
            } catch (_: Throwable) {
                // Scanner failures are persisted as events where possible; never crash the receiver.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
