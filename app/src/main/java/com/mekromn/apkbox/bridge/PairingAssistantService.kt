package com.mekromn.apkbox.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.mekromn.apkbox.ApkBoxServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-shot bootstrap helper for the only part of Wireless ADB that cannot be discovered from mDNS:
 * Android's temporary six-digit pairing code.
 *
 * The service is deliberately restricted to com.android.settings. It never records, persists, logs,
 * or relays the pairing code. While the pairing-code dialog is visible, Android advertises the
 * pairing server as _adb-tls-pairing._tcp; APKbox discovers that service to obtain the temporary
 * pairing port, combines it with the visible six-digit code, pairs locally, then disables this
 * accessibility service after success so the privilege is not retained unnecessarily.
 */
class PairingAssistantService : AccessibilityService() {
    companion object {
        private const val PREFS = "apkbox-pairing-assistant"
        private const val KEY_REQUESTED = "requested"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp"
        private const val ACTION_THROTTLE_MS = 650L

        private val CODE_REGEX = Regex("(?<!\\d)\\d{6}(?!\\d)")
        private val PORT_REGEX = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}:(\\d{1,5})(?!\\d)")
        private val PAIR_TEXTS = listOf(
            "Pair device with pairing code",
            "Pair using pairing code",
            "Pair device using pairing code",
        )

        fun request(context: Context): Boolean {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REQUESTED, true)
                .apply()

            val enabled = isEnabled(context)
            val intent = if (enabled) {
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return enabled
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REQUESTED, false)
                .apply()
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val expected = ComponentName(context, PairingAssistantService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(service.packageName, service.name) == expected
                }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { ApkBoxServices.bridgePreferences(applicationContext) }
    private val adb by lazy { ApkBoxServices.adbBridge(applicationContext) }
    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false
    private var pairingPort: Int? = null
    private var pairingCode: String? = null
    private var pairingInFlight = false
    private var lastPairAttempt = ""
    private var lastUiActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!isRequested()) return
        startPairingDiscovery()
        handler.postDelayed({ openDeveloperOptions() }, 350L)
        toast("APKbox Pairing Assistant started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRequested()) return
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return
        val root = rootInActiveWindow ?: return

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val text = nodes.mapNotNull { node ->
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }

        capturePairingSecrets(text)
        maybePair()
        if (pairingCode != null) return

        navigateSettings(root, nodes, text)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopPairingDiscovery()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun isRequested(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)

    private fun clearRequest() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, false)
            .apply()
    }

    private fun openDeveloperOptions() {
        if (!isRequested()) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            toast("Android could not open Developer options")
            clearRequest()
        }
    }

    private fun navigateSettings(
        root: AccessibilityNodeInfo,
        nodes: List<AccessibilityNodeInfo>,
        text: List<String>,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastUiActionAt < ACTION_THROTTLE_MS) return

        val combined = text.joinToString("\n")
        if (combined.contains("Allow wireless debugging", ignoreCase = true)) {
            if (clickByText(root, listOf("Allow"), exact = true)) markUiAction(now)
            return
        }

        // On the Wireless debugging detail page there is normally one master switch. If it is off,
        // enable it before trying to open the pairing-code dialog. Developer options itself has many
        // switches, so the exactly-one rule prevents accidentally toggling an unrelated preference.
        val visibleSwitches = nodes.filter { node ->
            node.isVisibleToUser && node.isCheckable &&
                node.className?.toString()?.contains("Switch", ignoreCase = true) == true
        }
        if (visibleSwitches.size == 1 && !visibleSwitches.single().isChecked &&
            text.any { it.equals("Wireless debugging", ignoreCase = true) }
        ) {
            if (clickNodeOrParent(visibleSwitches.single())) markUiAction(now)
            return
        }

        if (PAIR_TEXTS.any { wanted -> text.any { it.equals(wanted, ignoreCase = true) } }) {
            startPairingDiscovery()
            if (clickByText(root, PAIR_TEXTS, exact = true)) markUiAction(now)
            return
        }

        // Developer options list: open the Wireless debugging row. A title node on the detail page
        // has no clickable ancestor, so this naturally becomes a no-op once we are already there.
        if (text.any { it.equals("Wireless debugging", ignoreCase = true) }) {
            if (clickByText(root, listOf("Wireless debugging"), exact = true)) markUiAction(now)
            return
        }

        // Pixel's Wireless debugging preference is normally below the first Developer options
        // screenful. Scroll one page at a time until the preference enters the accessibility tree.
        if (text.any { it.equals("Developer options", ignoreCase = true) }) {
            val scrollable = nodes.firstOrNull { it.isVisibleToUser && it.isScrollable }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
                markUiAction(now)
            }
        }
    }

    private fun markUiAction(now: Long) {
        lastUiActionAt = now
    }

    private fun capturePairingSecrets(text: List<String>) {
        // The normal Wireless debugging page also contains an IP:port, but that is the TLS connect
        // port, not the temporary pairing-server port. Do not scrape either secret until the actual
        // pairing-code dialog is positively identified.
        val pairingDialogVisible = text.any { line ->
            line.contains("pairing code", ignoreCase = true) ||
                line.contains("Wi-Fi pairing code", ignoreCase = true)
        } && text.any { line ->
            line.contains("IP address", ignoreCase = true) || PORT_REGEX.containsMatchIn(line)
        }
        if (!pairingDialogVisible) return

        if (pairingCode == null) {
            pairingCode = text.asSequence()
                .mapNotNull { CODE_REGEX.find(it)?.value }
                .firstOrNull()
            if (pairingCode != null) startPairingDiscovery()
        }

        // mDNS is authoritative for the temporary pairing port, but parsing the pairing dialog's
        // visible IP:port is a useful fallback on builds where local service discovery is delayed.
        if (pairingPort == null) {
            pairingPort = text.asSequence()
                .mapNotNull { line -> PORT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .firstOrNull { it in 1..65535 }
        }
    }

    private fun maybePair() {
        val code = pairingCode ?: return
        val port = pairingPort ?: return
        val attemptKey = "$port:$code"
        if (pairingInFlight || attemptKey == lastPairAttempt) return
        pairingInFlight = true
        lastPairAttempt = attemptKey

        scope.launch {
            val paired = withContext(Dispatchers.IO) {
                runCatching { adb.pair(port, code) }.getOrDefault(false)
            }
            pairingInFlight = false
            if (!paired) {
                toast("Pairing code was not accepted; APKbox will watch for a fresh code")
                return@launch
            }

            prefs.setPaired(true)
            clearRequest()
            stopPairingDiscovery()

            val connected = withContext(Dispatchers.IO) {
                runCatching { adb.autoConnect() }.getOrDefault(false)
            }
            toast(if (connected) "Wireless ADB paired and connected" else "Wireless ADB paired; connection will self-heal")

            runCatching {
                startActivity(
                    Intent(this@PairingAssistantService, BridgeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }

            // Pairing is a rare bootstrap operation. Drop the accessibility privilege immediately
            // after success; APKbox's normal mDNS/ADB self-heal path handles subsequent reconnects.
            disableSelf()
        }
    }

    private fun startPairingDiscovery() {
        if (discoveryListener != null) return
        runCatching {
            multicastLock = wifi?.createMulticastLock("APKbox-pairing").also { lock ->
                lock?.setReferenceCounted(false)
                lock?.acquire()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_adb-tls-pairing._tcp", ignoreCase = true)) return
                // Pairing-code servers are adb-*; studio-* instances are QR-code pairing sessions.
                if (serviceInfo.serviceName.startsWith("studio-", ignoreCase = true)) return
                if (resolving) return
                resolving = true
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        serviceInfo.port.takeIf { it in 1..65535 }?.let { port ->
                            pairingPort = port
                            maybePair()
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }

        discoveryListener = listener
        runCatching {
            nsd.discoverServices(PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
            releaseMulticastLock()
        }
    }

    private fun stopPairingDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        multicastLock = null
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        output += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> collectNodes(child, output) }
        }
    }

    private fun clickByText(root: AccessibilityNodeInfo, labels: List<String>, exact: Boolean): Boolean {
        for (label in labels) {
            val candidates = root.findAccessibilityNodeInfosByText(label).orEmpty()
            for (candidate in candidates) {
                val visible = candidate.text?.toString()?.trim().orEmpty()
                if (exact && !visible.equals(label, ignoreCase = true)) continue
                if (clickNodeOrParent(candidate)) return true
            }
        }
        return false
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(5) {
            val current = node ?: return false
            if (current.isVisibleToUser && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            node = current.parent
        }
        return false
    }

    private fun toast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
