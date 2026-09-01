package com.mekromn.apkbox.data

import java.io.Closeable
import java.io.File

/**
 * Tiny process-local coordination layer between high-throughput readers and chunk garbage
 * collection. It never serializes normal reads/writes. Active readers only register hash
 * ref-counts; GC briefly owns the coordination lock while walking/deleting so claimed chunks can
 * never disappear underneath a parallel install.
 */
internal object VaultChunkClaims {
    private val lock = Any()
    private val claimsByVault = HashMap<String, HashMap<String, Int>>()

    fun claim(vaultRoot: File, hashes: Collection<String>): Closeable {
        val vaultKey = key(vaultRoot)
        val distinct = hashes.distinct()
        synchronized(lock) {
            val claims = claimsByVault.getOrPut(vaultKey) { HashMap() }
            for (hash in distinct) claims[hash] = (claims[hash] ?: 0) + 1
        }

        var closed = false
        return Closeable {
            synchronized(lock) {
                if (closed) return@Closeable
                closed = true
                val claims = claimsByVault[vaultKey] ?: return@Closeable
                for (hash in distinct) {
                    val remaining = (claims[hash] ?: 1) - 1
                    if (remaining <= 0) claims.remove(hash) else claims[hash] = remaining
                }
                if (claims.isEmpty()) claimsByVault.remove(vaultKey)
            }
        }
    }

    /**
     * Runs one GC pass while preventing new claims from racing between the protected-hash snapshot
     * and deletion. This path is rare; holding the small global coordination lock here is much safer
     * than making every hot-path chunk read acquire a lock.
     */
    inline fun <T> withGarbageCollection(
        vaultRoot: File,
        referencedHashes: Set<String>,
        block: (Set<String>) -> T,
    ): T = synchronized(lock) {
        val claims = claimsByVault[key(vaultRoot)]?.keys.orEmpty()
        if (claims.isEmpty()) {
            block(referencedHashes)
        } else {
            block(HashSet<String>(referencedHashes.size + claims.size).apply {
                addAll(referencedHashes)
                addAll(claims)
            })
        }
    }

    private fun key(vaultRoot: File): String =
        runCatching { vaultRoot.canonicalPath }.getOrElse { vaultRoot.absolutePath }
}
