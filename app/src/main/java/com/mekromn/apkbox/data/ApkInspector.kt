package com.mekromn.apkbox.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal data class ArchiveInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingCertSha256: String?,
)

internal object ApkInspector {
    fun inspect(context: Context, apkFile: File): ArchiveInfo {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("Android could not parse this APK.")

        val applicationInfo = packageInfo.applicationInfo
            ?: error("This APK does not contain application metadata.")
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath

        val label = runCatching {
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageInfo.packageName)

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        }

        return ArchiveInfo(
            label = label,
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = versionCode,
            signingCertSha256 = certBytes?.let { sha256Hex(it) },
        )
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
