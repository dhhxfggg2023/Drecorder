package dev.drecorder.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * A small floating control shown while a recording runs in the background.
 *
 * Why a plain View instead of Compose: an overlay lives outside any activity, and a
 * ComposeView would need a view-tree lifecycle owner wired into the service just to
 * draw two labels. Two `TextView`s update on a 500 ms tick and cost nothing.
 *
 * The overlay is draggable and remembers its position; tapping the timer reopens the
 * recording screen, tapping "停止" stops the session from anywhere.
 *
 * Requires `SYSTEM_ALERT_WINDOW`; [canShow] is the caller's permission check.
 */
class CaptureOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onOpen: () -> Unit,
) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var timeLabel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var startedAtMillis = 0L
    private val tick = object : Runnable {
        override fun run() {
            val view = root ?: return
            // Hide while one of our screens is visible: the app already shows the timer.
            view.visibility = if (AppVisibility.visible) View.GONE else View.VISIBLE
            val elapsed = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
            timeLabel?.text = formatElapsed(elapsed)
            handler.postDelayed(this, TICK_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(startedAtMillis: Long) {
        if (root != null) return
        val manager = windowManager ?: return
        this.startedAtMillis = startedAtMillis

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        timeLabel = TextView(context).apply {
            text = formatElapsed(0)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val stop = TextView(context).apply {
            text = context.getString(dev.drecorder.R.string.overlay_stop)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { onStop() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF200696E.toInt())
            }
            addView(timeLabel)
            addView(stop)
            contentDescription = context.getString(dev.drecorder.R.string.overlay_description)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16))
            y = prefs.getInt(KEY_Y, dp(120))
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx * dx + dy * dy > dp(6) * dp(6)) moved = true
                    layoutParams.x = startX + dx.toInt()
                    layoutParams.y = startY + dy.toInt()
                    runCatching { manager.updateViewLayout(row, layoutParams) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(KEY_X, layoutParams.x).putInt(KEY_Y, layoutParams.y).apply()
                    // A tap (not a drag) reopens the recording screen.
                    if (!moved) onOpen()
                    true
                }

                else -> false
            }
        }

        runCatching { manager.addView(row, layoutParams) }.onFailure { return }
        root = row
        params = layoutParams
        handler.post(tick)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val view = root ?: return
        runCatching { windowManager?.removeView(view) }
        root = null
        timeLabel = null
        params = null
    }

    companion object {
        private const val TICK_MS = 500L
        private const val KEY_X = "overlayX"
        private const val KEY_Y = "overlayY"

        /** True when the user has granted the overlay permission. */
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Opens the system page where that permission is granted. */
        fun requestPermission(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        private fun formatElapsed(millis: Long): String {
            val totalSeconds = millis / 1_000
            return String.format(
                Locale.US,
                "%02d:%02d",
                totalSeconds / 60,
                totalSeconds % 60,
            )
        }
    }
}
