package de.minitraxx.whisperflow.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import de.minitraxx.whisperflow.MainActivity
import de.minitraxx.whisperflow.R
import de.minitraxx.whisperflow.api.ClaudeClient
import de.minitraxx.whisperflow.api.StylePrompts
import de.minitraxx.whisperflow.api.WhisperClient
import de.minitraxx.whisperflow.util.CostTracker
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var isRecording = false
    private var isWalkieTalkieMode = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var isDragging = false
    private var recordingStartTime = 0L

    private var mediaRecorder: MediaRecorder? = null
    private var lastRecordingFile: File? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && !isRecording) {
            isWalkieTalkieMode = true
            startRecording()
        }
    }

    companion object {
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "whisperflow_service"
        private const val LONG_PRESS_MS = 500L

        const val PREFS_NAME = "whisperflow_prefs"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"

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
        longPressHandler.removeCallbacks(longPressRunnable)
        serviceScope.cancel()
        if (isRecording) stopRecording(transcribe = false)
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
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    if (!isDragging) {
                        isDragging = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    windowManager.updateViewLayout(buttonView, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                val elapsed = System.currentTimeMillis() - touchDownTime
                when {
                    isDragging -> {}
                    isWalkieTalkieMode -> {
                        isWalkieTalkieMode = false
                        stopRecording(transcribe = true)
                    }
                    elapsed < 350 -> toggleRecording()
                }
                true
            }
            else -> false
        }
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording(transcribe = true) else startRecording()
    }

    private fun startRecording() {
        if (CostTracker.isExceeded(this)) {
            Toast.makeText(this, "Guthaben aufgebraucht — bitte in der App aufladen", Toast.LENGTH_LONG).show()
            return
        }
        val file = File(cacheDir, "wf_${System.currentTimeMillis()}.m4a")
        lastRecordingFile = file
        recordingStartTime = System.currentTimeMillis()
        runCatching {
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            applyRecordingStyle()
            pulse()
        }.onFailure {
            isRecording = false
            lastRecordingFile = null
            file.delete()
        }
    }

    private fun stopRecording(transcribe: Boolean) {
        val durationMs = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0)
        runCatching { mediaRecorder?.apply { stop(); release() } }
        mediaRecorder = null
        isRecording = false
        applyIdleStyle()
        buttonView.animate().cancel()
        buttonView.scaleX = 1f
        buttonView.scaleY = 1f

        val file = lastRecordingFile ?: return
        lastRecordingFile = null

        if (!transcribe || durationMs < 300) {
            file.delete()
            return
        }

        CostTracker.recordAudio(durationMs / 1000L, this)
        serviceScope.launch { processAudio(file) }
    }

    private suspend fun processAudio(file: File) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val openAiKey = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        val anthropicKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "") ?: ""

        if (openAiKey.isBlank()) {
            showToast("Kein OpenAI API-Key — bitte in der WhisperFlow-App eintragen")
            file.delete()
            return
        }

        // Schritt 1: Whisper transkribiert
        val transcription = WhisperClient.transcribe(file, openAiKey).getOrElse {
            showToast("Whisper-Fehler: ${it.message?.take(60)}")
            file.delete()
            return
        }
        file.delete()

        // Schritt 2: Claude korrigiert (optional)
        val finalText = if (anthropicKey.isNotBlank()) {
            CostTracker.recordClaude(this)
            ClaudeClient.correct(transcription, StylePrompts.WHATSAPP, anthropicKey)
                .getOrDefault(transcription)
        } else {
            transcription
        }

        // Schritt 3: Text injizieren
        withContext(Dispatchers.Main) {
            if (WhisperAccessibilityService.isRunning) {
                WhisperAccessibilityService.inject(finalText)
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("whisperflow", finalText))
                Toast.makeText(this@FloatingButtonService, finalText, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun showToast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@FloatingButtonService, text, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else MediaRecorder()

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
