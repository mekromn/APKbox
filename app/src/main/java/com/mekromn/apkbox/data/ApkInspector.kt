package com.mekromn.apkbox.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal data class ArchiveInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingCertSha256: String?,
)

internal data class InstalledInfo(
    val versionName: String?,
    val versionCode: Long,
    val signingCertSha256: String?,
)

internal object ApkInspector {
    fun inspect(context: Context, apkFile: File): ArchiveInfo {
        val packageManager = context.packageManager
        val packageInfo = archivePackageInfo(packageManager, apkFile)
        val applicationInfo = archiveApplicationInfo(packageInfo, apkFile)

        val label = runCatching {
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageInfo.packageName)

        return ArchiveInfo(
            label = label,
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = versionCode(packageInfo),
            signingCertSha256 = signingCertSha256(packageInfo),
        )
    }

    /**
     * Renders the icon actually declared by the archived APK's ApplicationInfo. We only display
     * this cached PNG as an app icon when Android successfully resolves the APK resource itself;
     * callers use a neutral placeholder when this returns null.
     */
    fun renderApplicationIconPng(context: Context, apkFile: File, sizePx: Int = 192): ByteArray? = runCatching {
        val packageManager = context.packageManager
        val packageInfo = archivePackageInfo(packageManager, apkFile)
        val applicationInfo = archiveApplicationInfo(packageInfo, apkFile)
        val drawable = packageManager.getApplicationIcon(applicationInfo)

        val size = sizePx.coerceIn(96, 512)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val old = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            } finally {
                drawable.setBounds(old.left, old.top, old.right, old.bottom)
            }

            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    fun inspectInstalled(context: Context, packageName: String): InstalledInfo? {
        val packageManager = context.packageManager
        val flags = signatureFlags()
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
        }.getOrNull() ?: return null

        return InstalledInfo(
            versionName = packageInfo.versionName,
            versionCode = versionCode(packageInfo),
            signingCertSha256 = signingCertSha256(packageInfo),
        )
    }

    private fun archivePackageInfo(packageManager: PackageManager, apkFile: File): PackageInfo {
        val flags = signatureFlags()
        @Suppress("DEPRECATION")
        return packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("Android could not parse this APK.")
    }

    private fun archiveApplicationInfo(packageInfo: PackageInfo, apkFile: File): ApplicationInfo {
        val applicationInfo = packageInfo.applicationInfo
            ?: error("This APK does not contain application metadata.")
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath
        return applicationInfo
    }

    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun signingCertSha256(packageInfo: PackageInfo): String? {
        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        }
        return certBytes?.let { sha256Hex(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex string" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
