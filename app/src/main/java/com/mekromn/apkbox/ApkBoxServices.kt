package com.mekromn.apkbox

import android.content.Context
import com.mekromn.apkbox.data.AutoScanManager
import com.mekromn.apkbox.data.LibraryStore

/**
 * Process-local service graph so UI, broadcast receivers, and background scanners share one vault
 * writer and one set of StateFlows. Master restore explicitly resets this graph before recreation.
 */
object ApkBoxServices {
    private val lock = Any()

    @Volatile
    private var libraryStoreInstance: LibraryStore? = null

    @Volatile
    private var autoScanManagerInstance: AutoScanManager? = null

    fun libraryStore(context: Context): LibraryStore =
        libraryStoreInstance ?: synchronized(lock) {
            libraryStoreInstance ?: LibraryStore(context.applicationContext).also {
                libraryStoreInstance = it
            }
        }

    fun autoScanner(context: Context): AutoScanManager =
        autoScanManagerInstance ?: synchronized(lock) {
            autoScanManagerInstance ?: AutoScanManager(
                context = context.applicationContext,
                libraryStore = libraryStore(context.applicationContext),
            ).also {
                autoScanManagerInstance = it
            }
        }

    fun resetVaultServices() {
        synchronized(lock) {
            autoScanManagerInstance?.shutdown()
            autoScanManagerInstance = null
            libraryStoreInstance = null
        }
    }
}
