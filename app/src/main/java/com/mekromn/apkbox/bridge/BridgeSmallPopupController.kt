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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Compact, non-modal, auto-dismissing bridge message above the current app. */
object BridgeSmallPopupController {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var attachedView: View? = null
    @Volatile private var dismissRunnable: Runnable? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context.applicationContext)

    fun show(context: Context, popup: BridgePopupMessage, durationMs: Int): Boolean {
        if (!canDraw(context)) return false
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return showOnMain(appContext, popup, durationMs)
        }
        main.post { showOnMain(appContext, popup, durationMs) }
        return true
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) dismissOnMain(appContext)
        else main.post { dismissOnMain(appContext) }
    }

    private fun showOnMain(context: Context, popup: BridgePopupMessage, durationMs: Int): Boolean {
        if (!canDraw(context)) return false
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        dismissOnMain(context)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(if (dark) Color.rgb(38, 38, 43) else Color.rgb(250, 249, 255))
                setStroke(dp(1), if (dark) Color.rgb(86, 86, 96) else Color.rgb(214, 213, 222))
            }
            elevation = dp(16).toFloat()
            isClickable = true
            setOnClickListener {
                dismissOnMain(context)
                context.startActivity(
                    Intent(context, BridgeMessageActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }

        card.addView(TextView(context).apply {
            text = popup.title.ifBlank { "ChatGPT via APKbox" }
            setTextColor(if (dark) Color.WHITE else Color.rgb(30, 30, 34))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        card.addView(TextView(context).apply {
            text = popup.message.ifBlank { "Message" }
            setTextColor(if (dark) Color.rgb(226, 226, 234) else Color.rgb(66, 66, 74))
            textSize = 14f
            maxLines = 3
            setPadding(0, dp(3), 0, 0)
        })

        val root = FrameLayout(context).apply {
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply {
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                    topMargin = dp(24)
                },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            title = "APKbox Bridge Small Message"
        }

        return runCatching {
            wm.addView(root, params)
            attachedView = root
            val runnable = Runnable { dismissOnMain(context) }
            dismissRunnable = runnable
            main.postDelayed(runnable, durationMs.coerceIn(1_500, 10_000).toLong())
            true
        }.getOrElse {
            attachedView = null
            false
        }
    }

    private fun dismissOnMain(context: Context) {
        dismissRunnable?.let(main::removeCallbacks)
        dismissRunnable = null
        val view = attachedView ?: return
        attachedView = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
    }
}
