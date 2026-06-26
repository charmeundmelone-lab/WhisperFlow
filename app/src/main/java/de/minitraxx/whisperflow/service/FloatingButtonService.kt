package de.minitraxx.whisperflow.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import de.minitraxx.whisperflow.MainActivity
import de.minitraxx.whisperflow.R
import kotlin.math.abs

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var isRecording = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isDragging = false

    companion object {
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "whisperflow_service"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingButtonService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingButton()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { if (::buttonView.isInitialized) windowManager.removeView(buttonView) }
        super.onDestroy()
    }

    private fun showFloatingButton() {
        val dp = resources.displayMetrics.density
        val size = (62 * dp).toInt()
        val pad = (13 * dp).toInt()

        buttonView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            elevation = 10f * dp
        }
        applyIdleStyle()

        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = (120 * dp).toInt()
        }

        buttonView.setOnTouchListener(touchListener)
        windowManager.addView(buttonView, params)
    }

    private fun applyIdleStyle() {
        val stroke = (1.5f * resources.displayMetrics.density).toInt()
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1C1C1E"))
            setStroke(stroke, Color.parseColor("#3A3A3C"))
        }
    }

    private fun applyRecordingStyle() {
        val stroke = (1.5f * resources.displayMetrics.density).toInt()
        buttonView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#C00020"))
            setStroke(stroke, Color.parseColor("#FF2040"))
        }
    }

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchDownTime = System.currentTimeMillis()
                isDragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    isDragging = true
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    windowManager.updateViewLayout(buttonView, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && System.currentTimeMillis() - touchDownTime < 350) {
                    toggleRecording()
                }
                true
            }
            else -> false
        }
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            applyRecordingStyle()
            pulse()
        } else {
            applyIdleStyle()
            buttonView.animate().cancel()
            buttonView.scaleX = 1f
            buttonView.scaleY = 1f
        }
    }

    private fun pulse() {
        if (!isRecording) return
        buttonView.animate()
            .scaleX(1.2f).scaleY(1.2f)
            .setDuration(500)
            .withEndAction {
                if (!isRecording) return@withEndAction
                buttonView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(500)
                    .withEndAction { pulse() }
                    .start()
            }.start()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "WhisperFlow", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperFlow")
            .setContentText("Mikrofon-Button ist aktiv")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
