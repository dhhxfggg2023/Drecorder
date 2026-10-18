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

        // A small translucent chip rather than a slab: it floats over video and text, so
        // it has to stay narrow and let the content behind it show through.
        timeLabel = TextView(context).apply {
            text = formatElapsed(0)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 0)
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF5252.toInt())
            }
        }
        val stop = TextView(context).apply {
            text = context.getString(dev.drecorder.R.string.overlay_stop)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x33FFFFFF)
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            // No click listener: the chip decides tap-vs-drag itself, otherwise a drag
            // that starts on "停止" would stop the recording instead of moving the chip.
            isClickable = false
            isFocusable = false
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                // 70% opacity: readable over bright and dark content, and the video
                // behind it is still visible.
                setColor(0xB3151B1B.toInt())
            }
            elevation = dp(4).toFloat()
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) })
            addView(timeLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(9) })
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

        // Drag anywhere on the chip; the stop button keeps its own click because a
        // clickable child consumes the touch before this listener sees it.
        val touchSlop = dp(4).toFloat()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downOnStop = false
        row.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    downOnStop = event.x >= stop.left && event.x <= stop.right &&
                        event.y >= stop.top && event.y <= stop.bottom
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = startX + dx.toInt()
                        layoutParams.y = startY + dy.toInt()
                        runCatching { manager.updateViewLayout(row, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt(KEY_X, layoutParams.x)
                        .putInt(KEY_Y, layoutParams.y)
                        .apply()
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (downOnStop) onStop() else onOpen()
                    }
                    dragging = false
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

        /** The system page where that permission is granted, for a launcher to open. */
        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        )

        /** Opens the system page where that permission is granted. */
        fun requestPermission(context: Context) {
            runCatching { context.startActivity(permissionIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
