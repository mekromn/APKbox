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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Process-local always-on-top informational bridge popup.
 *
 * This is deliberately informational only: command approvals keep their independent local approval
 * path and are never accepted through this overlay. TYPE_APPLICATION_OVERLAY requires the user's
 * explicit Android "Display over other apps" grant.
 */
object BridgeOverlayController {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var attachedView: View? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    fun show(
        context: Context,
        popup: BridgePopupMessage,
        stateStore: BridgeStateStore,
    ): Boolean {
        if (!canDraw(context)) return false
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return showOnMain(appContext, popup, stateStore)
        }

        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        if (!main.post {
                try {
                    result.set(showOnMain(appContext, popup, stateStore))
                } finally {
                    latch.countDown()
                }
            }
        ) return false
        return runCatching { latch.await(2, TimeUnit.SECONDS) && result.get() }.getOrDefault(false)
    }

    fun dismiss(context: Context, stateStore: BridgeStateStore? = null) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissOnMain(appContext, stateStore)
        } else {
            main.post { dismissOnMain(appContext, stateStore) }
        }
    }

    private fun showOnMain(
        context: Context,
        popup: BridgePopupMessage,
        stateStore: BridgeStateStore,
    ): Boolean {
        if (!canDraw(context)) return false
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
        dismissOnMain(context, null)

        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(if (dark) 0x7A000000 else 0x66000000)
            isClickable = true
            isFocusable = false
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(if (dark) Color.rgb(31, 31, 35) else Color.rgb(250, 249, 255))
                setStroke(dp(1), if (dark) Color.rgb(72, 72, 80) else Color.rgb(220, 218, 226))
            }
            elevation = dp(20).toFloat()
            isClickable = true
        }

        val eyebrow = TextView(context).apply {
            text = "APKbox · ChatGPT Bridge"
            setTextColor(if (dark) Color.rgb(174, 198, 255) else Color.rgb(48, 91, 181))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(
            eyebrow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val titleView = TextView(context).apply {
            text = popup.title.ifBlank { "ChatGPT via APKbox" }
            setTextColor(if (dark) Color.WHITE else Color.rgb(30, 30, 34))
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(10))
        }
        card.addView(titleView)

        val bodyText = TextView(context).apply {
            text = popup.message.ifBlank { "No message was supplied." }
            setTextColor(if (dark) Color.rgb(232, 232, 238) else Color.rgb(50, 50, 56))
            textSize = 16f
            setLineSpacing(0f, 1.08f)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                bodyText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260),
            ),
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        val settingsButton = Button(context).apply {
            text = "Display options"
            isAllCaps = false
            setOnClickListener {
                dismissOnMain(context, null)
                context.startActivity(
                    Intent(context, BridgeMessageDisplaySettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
        actions.addView(settingsButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(10)
        })

        val dismissButton = Button(context).apply {
            text = "Got it"
            isAllCaps = false
            setOnClickListener { dismissOnMain(context, stateStore) }
        }
        actions.addView(dismissButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        card.addView(actions)

        // Tapping the dimmed area dismisses informational overlays, matching a modal dialog while
        // keeping the explicit Got it affordance obvious.
        root.setOnClickListener { dismissOnMain(context, stateStore) }
        card.setOnClickListener { /* consume so touching the card never dismisses it */ }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        }
        root.addView(card, cardParams)

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
            this.title = "APKbox Bridge Message"
        }

        return runCatching {
            windowManager.addView(root, params)
            attachedView = root
            true
        }.getOrElse {
            attachedView = null
            false
        }
    }

    private fun dismissOnMain(context: Context, stateStore: BridgeStateStore?) {
        val view = attachedView ?: return
        attachedView = null
        runCatching {
            context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        }
        stateStore?.clearPopup()
    }
}
