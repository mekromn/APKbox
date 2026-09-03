package com.mekromn.apkbox.bridge

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs inside Shizuku/Sui's UserService process as shell (uid 2000) or root (uid 0).
 *
 * This class deliberately exposes only a tiny streaming shell boundary. APKbox keeps command
 * policy, package scoping, at-most-once sequencing, APK hashing and commit decisions in its normal
 * application process. Raw output and APK input use ParcelFileDescriptor pipes so the transport is
 * not constrained by Binder's transaction-size limit.
 */
class ShizukuShellUserService : IShizukuShellService.Stub {
    companion object {
        private const val MAX_COMMAND_CHARS = 65_536
        private const val MAX_WRITE_RESULT_CHARS = 256 * 1024
        private const val COPY_BUFFER_BYTES = 1024 * 1024
    }

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "APKbox-Shizuku-IO").apply { isDaemon = true }
    }
    private val installWrites = ConcurrentHashMap<Int, InstallWriteState>()

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun getPrivilegedUid(): Int = Process.myUid()

    override fun openShell(command: String): ParcelFileDescriptor {
        require(command.isNotBlank()) { "Command is empty." }
        require(command.length <= MAX_COMMAND_CHARS) { "Command is too long." }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        executor.execute {
            var process: java.lang.Process? = null
            try {
                process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { sink ->
                    process.inputStream.use { source ->
                        source.copyTo(sink, COPY_BUFFER_BYTES)
                    }
                }
                process.waitFor()
            } catch (_: Throwable) {
                runCatching { writeEnd.close() }
            } finally {
                runCatching { process?.destroy() }
                if (process?.isAlive == true) runCatching { process.destroyForcibly() }
            }
        }
        return readEnd
    }

    override fun openInstallWrite(sessionId: Int, totalBytes: Long): ParcelFileDescriptor {
        require(sessionId >= 0) { "Invalid package installer session." }
        require(totalBytes > 0L) { "APK size must be positive." }
        require(installWrites[sessionId] == null) { "Install session $sessionId already has an active writer." }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val state = InstallWriteState()
        check(installWrites.putIfAbsent(sessionId, state) == null) {
            "Install session $sessionId already has an active writer."
        }

        state.future = executor.submit<String> {
            var process: java.lang.Process? = null
            try {
                process = ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "pm install-write -S $totalBytes $sessionId base.apk -",
                ).redirectErrorStream(true).start()
                state.process = process

                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { source ->
                    process.outputStream.use { sink ->
                        source.copyTo(sink, COPY_BUFFER_BYTES)
                        sink.flush()
                    }
                }

                val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText().take(MAX_WRITE_RESULT_CHARS)
                }.trim()
                val exit = process.waitFor()
                buildString {
                    if (output.isNotBlank()) append(output)
                    if (isNotEmpty()) append('\n')
                    append("__APKBOX_EXIT__=").append(exit)
                }
            } catch (failure: Throwable) {
                "${failure.message ?: failure.javaClass.simpleName}\n__APKBOX_EXIT__=-1"
            } finally {
                state.process = null
                runCatching { readEnd.close() }
                runCatching { process?.destroy() }
                if (process?.isAlive == true) runCatching { process.destroyForcibly() }
            }
        }
        return writeEnd
    }

    override fun finishInstallWrite(sessionId: Int, timeoutSeconds: Int): String {
        val state = installWrites[sessionId]
            ?: return "No active install writer for session $sessionId.\n__APKBOX_EXIT__=-1"
        return try {
            state.future.get(timeoutSeconds.coerceIn(1, 300).toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            runCatching { state.process?.destroyForcibly() }
            state.future.cancel(true)
            "Install write timed out.\n__APKBOX_EXIT__=-1"
        } catch (failure: Throwable) {
            "${failure.message ?: failure.javaClass.simpleName}\n__APKBOX_EXIT__=-1"
        } finally {
            installWrites.remove(sessionId, state)
        }
    }

    override fun abandonInstall(sessionId: Int) {
        installWrites.remove(sessionId)?.let { state ->
            runCatching { state.process?.destroyForcibly() }
            runCatching { state.future.cancel(true) }
        }
        runCatching {
            val process = ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "pm install-abandon $sessionId >/dev/null 2>&1",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private class InstallWriteState {
        @Volatile var process: java.lang.Process? = null
        lateinit var future: Future<String>
    }
}
