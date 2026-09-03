package com.mekromn.apkbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle

internal const val EXTRA_OPEN_APK_INSTALL_MODE = "com.mekromn.apkbox.extra.OPEN_APK_INSTALL_MODE"
internal const val OPEN_APK_INSTALL_MODE_NORMAL = "normal"
internal const val OPEN_APK_INSTALL_MODE_UNATTENDED = "unattended"
internal const val OPEN_APK_INSTALL_MODE_REINSTALL = "reinstall"

/**
 * Tiny exported resolver targets that make Android expose APKbox's install modes as distinct
 * "Complete action using" entries without removing the existing generic APKbox chooser.
 *
 * The original VIEW/INSTALL_PACKAGE intent is copied verbatim so URI grants, MIME type, ClipData,
 * and caller extras survive the hop. Only the internal requested install mode is added, then this
 * router immediately finishes and never appears in recents.
 */
abstract class OpenApkModeRouterActivity : Activity() {
    protected abstract val installMode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent ?: run {
            finish()
            return
        }
        val forwarded = Intent(incoming).apply {
            setClass(this@OpenApkModeRouterActivity, OpenApkInstallerActivity::class.java)
            putExtra(EXTRA_OPEN_APK_INSTALL_MODE, installMode)
        }
        runCatching { startActivity(forwarded) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

class OpenApkInstallRouterActivity : OpenApkModeRouterActivity() {
    override val installMode: String = OPEN_APK_INSTALL_MODE_NORMAL
}

class OpenApkUnattendedRouterActivity : OpenApkModeRouterActivity() {
    override val installMode: String = OPEN_APK_INSTALL_MODE_UNATTENDED
}

class OpenApkReinstallRouterActivity : OpenApkModeRouterActivity() {
    override val installMode: String = OPEN_APK_INSTALL_MODE_REINSTALL
}
