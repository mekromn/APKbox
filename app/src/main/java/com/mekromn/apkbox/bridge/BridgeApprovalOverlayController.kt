package com.mekromn.apkbox.bridge

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * True always-on-top security prompt for pending Remote Bridge approvals.
 *
 * This overlay exposes only the same local approval actions as BridgeApprovalActivity. It cannot
 * execute arbitrary commands directly: button presses are routed back to RemoteBridgeService,
 * which re-reads the persisted pending request and applies the normal risk/trust policy.
 */
object BridgeApprovalOverlayController {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var attachedView: View? = null
    @Volatile private var attachedRequestId: String? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    /**
     * Return true only after WindowManager actually accepted the overlay. RemoteBridgeService uses
     * this as the gate for suppressing the notification fallback, so an invisible approval prompt
     * can never be reported as successfully shown.
     */
    fun show(context: Context, pending: BridgePendingRequest): Boolean {
        if (!canDraw(context)) return false
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) return showOnMain(appContext, pending)

        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = main.post {
            try {
                result.set(showOnMain(appContext, pending))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return runCatching {
            latch.await(2, TimeUnit.SECONDS) && result.get()
        }.getOrDefault(false)
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissOnMain(appContext)
        } else {
            main.post { dismissOnMain(appContext) }
        }
    }

    private fun showOnMain(context: Context, pending: BridgePendingRequest): Boolean {
        if (!canDraw(context)) return false
        if (attachedView != null && attachedRequestId == pending.request.id) return true

        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        dismissOnMain(context)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val cardColor = if (dark) Color.rgb(31, 31, 35) else Color.rgb(250, 249, 255)
        val textPrimary = if (dark) Color.WHITE else Color.rgb(28, 28, 32)
        val textSecondary = if (dark) Color.rgb(220, 220, 228) else Color.rgb(72, 72, 80)
        val accent = when (pending.risk) {
            BridgeRisk.INFO, BridgeRisk.READ_ONLY -> if (dark) Color.rgb(168, 197, 255) else Color.rgb(40, 88, 180)
            BridgeRisk.DEBUG_ACTION -> if (dark) Color.rgb(218, 177, 255) else Color.rgb(112, 65, 158)
            BridgeRisk.MUTATING, BridgeRisk.DANGEROUS -> if (dark) Color.rgb(255, 180, 171) else Color.rgb(186, 26, 26)
        }

        val dimRoot = FrameLayout(context).apply {
            setBackgroundColor(if (dark) 0x99000000.toInt() else 0x77000000)
            isClickable = true
            isFocusable = false
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(cardColor)
                setStroke(dp(2), accent)
            }
            elevation = dp(24).toFloat()
            isClickable = true
        }

        card.addView(TextView(context).apply {
            text = "APKbox · Remote Bridge Security"
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })

        card.addView(TextView(context).apply {
            text = "ChatGPT requests ${pending.risk.name.lowercase().replace('_', ' ')} access"
            setTextColor(textPrimary)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(5))
        })

        card.addView(TextView(context).apply {
            text = pending.request.source
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        val details = buildString {
            append("Why\n")
            append(pending.request.reason.ifBlank { "No reason was supplied." })
            append("\n\nAction\n")
            append(summary(pending.request))
            pending.request.runId.takeIf { it.isNotBlank() }?.let { append("\n\nRun ID\n").append(it) }
            pending.request.buildId.takeIf { it.isNotBlank() }?.let { append("\n\nBuild ID\n").append(it) }
            pending.request.command.takeIf { it.isNotBlank() }?.let {
                append("\n\nCommand\n").append(it.take(2_000))
            }
            pending.request.selector.takeIf { it.isNotBlank() }?.let {
                append("\n\nSelector\n").append(it.take(500))
            }
            pending.request.imagePath.takeIf { it.isNotBlank() }?.let {
                append("\n\nImage\n").append(it.take(500))
            }
            if (pending.request.type == BridgeCommandType.APK_INSTALL_URL) {
                append("\n\nAPK source\n").append(pending.request.downloadUrl.take(2_000))
                pending.request.packageName.takeIf { it.isNotBlank() }?.let {
                    append("\n\nExpected package\n").append(it)
                }
                append("\n\nExpected SHA-256\n")
                append(pending.request.expectedApkSha256.ifBlank { "Not supplied; APKbox will compute and report it" })
                append("\n\nSave to project\n").append(if (pending.request.saveToProject) "Yes" else "No")
                if (pending.request.saveToProject) {
                    append("\n\nProject\n").append(
                        pending.request.projectId.ifBlank { "Auto-resolve by package; create if none exists" }
                    )
                    pending.request.projectName.takeIf { it.isNotBlank() }?.let { append("\nProject name\n").append(it) }
                    pending.request.displayName.takeIf { it.isNotBlank() }?.let { append("\nAPK display name\n").append(it) }
                    pending.request.archiveTitle.takeIf { it.isNotBlank() }?.let { append("\nAPK title\n").append(it) }
                    pending.request.archiveDescription.takeIf { it.isNotBlank() }?.let {
                        append("\nAPK description\n").append(it.take(2_000))
                    }
                }
                append("\n\nDowngrade\n").append(if (pending.request.allowDowngrade) "Allowed" else "Not allowed")
                append("\n\nLaunch after install\n").append(if (pending.request.autoLaunch) "Yes" else "No")
                append("\n\nAuthenticated source\n").append(if (pending.request.requiresBuildToken) "Yes" else "No")
            }
            append("\n\nExpires\n")
            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(pending.request.expiresAtEpochMs)))
        }

        val detailText = TextView(context).apply {
            text = details
            setTextColor(textSecondary)
            textSize = 15f
            setLineSpacing(0f, 1.07f)
            setTextIsSelectable(true)
        }
        card.addView(
            ScrollView(context).apply { addView(detailText) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300),
            ),
        )

        card.addView(TextView(context).apply {
            text = policyText(pending)
            setTextColor(textSecondary)
            textSize = 12f
            setPadding(0, dp(12), 0, dp(8))
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val displayOptions = Button(context).apply {
            text = "Approval display options"
            isAllCaps = false
            setOnClickListener {
                dismissOnMain(context)
                context.startActivity(
                    Intent(context, BridgeMessageDisplaySettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
        actions.addView(
            displayOptions,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(8)
            },
        )

        fun actionButton(label: String, action: String): Button = Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                dismissOnMain(context)
                context.startService(Intent(context, RemoteBridgeService::class.java).setAction(action))
            }
        }

        if (BridgePolicy.trustedSessionEligible(pending.request)) {
            actions.addView(
                actionButton("Allow + trust safe debugging for 10 min", RemoteBridgeService.ACTION_APPROVE_TRUST),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                    bottomMargin = dp(8)
                },
            )
        }

        val primaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        primaryRow.addView(
            actionButton("Deny", RemoteBridgeService.ACTION_DENY),
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) },
        )
        primaryRow.addView(
            actionButton(
                when (pending.request.type) {
                    BridgeCommandType.AGENT_START -> "Start plan"
                    BridgeCommandType.BUILD_START -> "Start build"
                    BridgeCommandType.APK_INSTALL_URL -> "Install APK"
                    else -> "Allow once"
                },
                RemoteBridgeService.ACTION_APPROVE_ONCE,
            ),
            LinearLayout.LayoutParams(0, dp(52), 1f),
        )
        actions.addView(primaryRow)
        card.addView(actions)

        // Do not dismiss a security prompt by tapping outside it. A deliberate Deny/Allow decision
        // is required, matching the modal behavior expected from installer/security surfaces.
        dimRoot.setOnClickListener { }
        card.setOnClickListener { }
        dimRoot.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            this.title = "APKbox Bridge Security Approval"
        }

        return runCatching {
            wm.addView(dimRoot, params)
            attachedView = dimRoot
            attachedRequestId = pending.request.id
            true
        }.getOrElse {
            attachedView = null
            attachedRequestId = null
            false
        }
    }

    private fun dismissOnMain(context: Context) {
        val view = attachedView ?: return
        attachedView = null
        attachedRequestId = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }

    private fun policyText(pending: BridgePendingRequest): String = when {
        pending.request.type in setOf(BridgeCommandType.AGENT_START, BridgeCommandType.AGENT_RESUME) ->
            "Autonomous plan starts/resumes always require a fresh approval and never inherit an earlier trusted session."
        pending.request.type == BridgeCommandType.BUILD_START ->
            "Build Runner can change installed app state, so BUILD_START always requires a fresh approval."
        pending.request.type == BridgeCommandType.APK_INSTALL_URL ->
            "Direct URL installation always requires a fresh approval. APKbox downloads and hashes the full APK before unattended install, verifies installed bytes afterward, and never silently removes a signature-conflicting app."
        pending.risk == BridgeRisk.READ_ONLY ->
            "Read-only debugging can be covered by a temporary trusted session."
        pending.risk == BridgeRisk.DEBUG_ACTION ->
            "Only safely scoped debug actions may inherit a temporary trusted session."
        pending.risk in setOf(BridgeRisk.MUTATING, BridgeRisk.DANGEROUS) ->
            "APKbox never auto-approves mutating or dangerous operations."
        else -> "This request is informational."
    }

    private fun summary(request: BridgeRequest): String = when (request.type) {
        BridgeCommandType.SHELL -> "Run a shell command through APKbox's active privileged transport"
        BridgeCommandType.LOGCAT -> "Capture a system logcat snapshot"
        BridgeCommandType.APP_LOGCAT -> "Capture logcat for ${request.packageName}"
        BridgeCommandType.DUMPSYS -> "Capture dumpsys ${request.service}"
        BridgeCommandType.LAUNCH -> "Launch ${request.packageName}"
        BridgeCommandType.TOAST -> "Show a toast message"
        BridgeCommandType.NOTIFICATION -> "Show a bridge message using the local default"
        BridgeCommandType.POPUP -> "Show a bridge popup using the local default"
        BridgeCommandType.MESSAGE_SMALL_POPUP -> "Show a compact floating message"
        BridgeCommandType.MESSAGE_ALWAYS_ON_TOP -> "Show a persistent always-on-top message"
        BridgeCommandType.MESSAGE_FULL_WINDOW -> "Show a full-window bridge message"
        BridgeCommandType.MESSAGE_HEADS_UP -> "Show an expandable heads-up bridge message"
        BridgeCommandType.PICTURE_MESSAGE -> "Show a private Continuity picture message"
        BridgeCommandType.APK_INSTALL_URL -> "Download and unattended-install an APK from HTTPS${if (request.saveToProject) ", saving the exact APK to APKbox" else ""}"
        BridgeCommandType.UI_SNAPSHOT -> "Inspect the current UI hierarchy"
        BridgeCommandType.SCREENSHOT -> "Capture the current screen"
        BridgeCommandType.UI_TAP -> "Tap (${request.x}, ${request.y}) in ${request.packageName}"
        BridgeCommandType.UI_FIND_TAP -> "Find and tap '${request.selector}' in ${request.packageName}"
        BridgeCommandType.UI_SWIPE -> "Swipe in ${request.packageName}"
        BridgeCommandType.UI_TEXT -> "Type text in ${request.packageName}"
        BridgeCommandType.UI_KEY -> "Send Android key code ${request.keyCode} in ${request.packageName}"
        BridgeCommandType.UI_WAIT -> "Wait for '${request.selector}' in ${request.packageName}"
        BridgeCommandType.AGENT_START -> "Start bounded autonomous plan '${request.runId}'"
        BridgeCommandType.AGENT_RESUME -> "Resume bounded autonomous plan '${request.runId}'"
        BridgeCommandType.AGENT_STATUS -> "Read autonomous plan '${request.runId}' status"
        BridgeCommandType.BUILD_START -> "Start verified build '${request.buildId.ifBlank { request.runId }}'"
        BridgeCommandType.BUILD_STATUS -> "Read build status"
    }
}
