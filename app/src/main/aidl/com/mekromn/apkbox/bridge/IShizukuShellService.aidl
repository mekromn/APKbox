package com.mekromn.apkbox.bridge;

import android.os.ParcelFileDescriptor;

/**
 * Streaming UserService boundary used by APKbox's Shizuku/Sui backend.
 *
 * Large command output and APK input travel through pipes instead of Binder byte arrays so raw
 * screenshots and full APK streams are not constrained by Binder's transaction-size limit.
 */
interface IShizukuShellService {
    int getPrivilegedUid();
    ParcelFileDescriptor openShell(String command);
    ParcelFileDescriptor openInstallWrite(int sessionId, long totalBytes);
    String finishInstallWrite(int sessionId, int timeoutSeconds);
    void abandonInstall(int sessionId);
}
