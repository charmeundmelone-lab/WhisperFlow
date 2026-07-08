package de.minitraxx.whisperflow.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import de.minitraxx.whisperflow.R
import de.minitraxx.whisperflow.service.PomodoroReceiver
import de.minitraxx.whisperflow.ui.ParkingBoardActivity

/**
 * Pomodoro-Timer für Parkplatz-Aufgaben. Läuft zuverlässig auch bei
 * ausgeschaltetem Bildschirm und beendeter App, weil der Endzeitpunkt über
 * AlarmManager.setExactAndAllowWhileIdle gescheduled wird (feuert sogar im
 * Doze-Stromsparmodus) — der Timer hängt NICHT am App-Prozess. Während des
 * Laufens zeigt eine Benachrichtigung die Restzeit als System-Countdown
 * (batterieschonend, kein Sekunden-Update nötig). Am Ende gibt es Gong +
 * Vibration und eine Benachrichtigung mit drei Aktionen (Fertig / Noch nicht /
 * Nochmal), die direkt vom Sperrbildschirm beantwortbar sind.
 */
object PomodoroManager {
    private const val PREFS = "whisperflow_prefs"
    private const val KEY_ACTIVE = "pomo_active"
    private const val KEY_TASK_ID = "pomo_task_id"
    private const val KEY_TASK_TEXT = "pomo_task_text"
    private const val KEY_END = "pomo_end"
    private const val KEY_DURATION = "pomo_duration_min"
    // Timer abgelaufen, aber vom Nutzer noch nicht beantwortet (Fertig/Noch nicht/
    // Nochmal). Dient dem In-App-Sicherheitsnetz: falls die Benachrichtigung mal
    // unterdrückt wird, zeigt das Board die drei Optionen selbst beim Öffnen.
    private const val KEY_FINISHED_PENDING = "pomo_finished_pending"

    const val CHANNEL_RUNNING = "pomodoro_running"
    const val CHANNEL_DONE = "pomodoro_done"
    private const val NOTIF_RUNNING = 1001
    private const val NOTIF_DONE = 1002
    private const val ALARM_REQUEST = 7001

    /** Klassische Pomodoro-Längen, ein Tap statt Tippen. */
    val DURATIONS_MIN = listOf(15, 25, 45)

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)
    fun activeTaskId(context: Context): Long = prefs(context).getLong(KEY_TASK_ID, -1L)
    fun endTime(context: Context): Long = prefs(context).getLong(KEY_END, 0L)
    private fun taskText(context: Context): String = prefs(context).getString(KEY_TASK_TEXT, "") ?: ""
    private fun durationMin(context: Context): Int = prefs(context).getInt(KEY_DURATION, 25)

    /** Abgelaufen und noch nicht beantwortet? (In-App-Sicherheitsnetz für die Optionen.) */
    fun isFinishedPending(context: Context): Boolean = prefs(context).getBoolean(KEY_FINISHED_PENDING, false)
    fun finishedTaskText(context: Context): String = taskText(context)

    fun remainingMs(context: Context): Long =
        if (isActive(context)) (endTime(context) - System.currentTimeMillis()).coerceAtLeast(0) else 0

    fun start(context: Context, taskId: Long, taskText: String, durationMin: Int) {
        val end = System.currentTimeMillis() + durationMin * 60_000L
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_FINISHED_PENDING, false)
            .putLong(KEY_TASK_ID, taskId)
            .putString(KEY_TASK_TEXT, taskText)
            .putLong(KEY_END, end)
            .putInt(KEY_DURATION, durationMin)
            .apply()
        scheduleAlarm(context, end)
        postRunningNotification(context, taskText, end)
    }

    /** Bricht einen laufenden Timer ab (Aufgabe bleibt unverändert in Arbeit). */
    fun cancel(context: Context) {
        cancelAlarm(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_RUNNING)
        nm.cancel(NOTIF_DONE)
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    /** Vom Receiver aufgerufen, wenn der Alarm feuert: Gong + Frage-Benachrichtigung. */
    fun onFinished(context: Context) {
        val text = taskText(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_RUNNING)
        // Aufgaben-ID/Text bleiben in den Prefs, damit die Aktions-Knöpfe der
        // Frage-Benachrichtigung sie noch auflösen können; nur "läuft" endet.
        // FINISHED_PENDING → das Board zeigt die Optionen beim Öffnen zusätzlich selbst.
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_FINISHED_PENDING, true)
            .apply()
        postDoneNotification(context, text)
        // Gong + Vibration bewusst EXPLIZIT (nicht nur über den Kanal-Ton): so kommt
        // der Ton über den Alarm-Audiostream garantiert — auch bei "Nicht stören" und
        // selbst wenn der Benachrichtigungston vom System unterdrückt würde.
        playGong(context)
        vibrate(context)
    }

    private fun playGong(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
        }
    }

    private fun vibrate(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 400, 250, 400)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** Nach beantworteter Frage-Benachrichtigung: alles aufräumen. */
    fun clearFinished(context: Context) {
        cancelAlarm(context)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_DONE)
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_FINISHED_PENDING, false)
            .remove(KEY_TASK_ID)
            .remove(KEY_TASK_TEXT)
            .apply()
    }

    /** "Nochmal": gleiche Aufgabe, gleiche Länge, neuer Durchlauf. */
    fun restartSame(context: Context) {
        val id = activeTaskId(context)
        val text = taskText(context)
        val dur = durationMin(context)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_DONE)
        if (id >= 0) start(context, id, text, dur)
    }

    // ── AlarmManager ──────────────────────────────────────────────────────────

    private fun scheduleAlarm(context: Context, end: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmPendingIntent(context)
        // setAlarmClock: Android behandelt das wie einen echten Wecker — feuert exakt
        // zur Zeit, auch im Doze-Modus, und wird von OEM-Stromsparmaßnahmen (Nothing OS
        // u.a.) praktisch nicht verzögert. Deutlich zuverlässiger als
        // setExactAndAllowWhileIdle, das bei sideloadeten Apps in den ungenauen Fallback
        // rutschen und den Gong verschlucken konnte.
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(end, openBoardIntent(context)), pi)
        }.onFailure {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi) }
                .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi) } }
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, ALARM_REQUEST,
            Intent(context, PomodoroReceiver::class.java).setAction(PomodoroReceiver.ACTION_FINISHED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ── Benachrichtigungen ──────────────────────────────────────────────────

    private fun postRunningNotification(context: Context, taskText: String, end: Long) {
        ensureChannels(context)
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PomodoroReceiver::class.java).setAction(PomodoroReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("🍅 $taskText")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(end)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openBoardIntent(context))
            .addAction(0, "Stopp", stop)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_RUNNING, n)
    }

    private fun postDoneNotification(context: Context, taskText: String) {
        ensureChannels(context)
        fun act(action: String, code: Int, label: String) = NotificationCompat.Action(
            0, label,
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, PomodoroReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Zeit um! 🍅")
            .setContentText(taskText)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openBoardIntent(context))
            .addAction(act(PomodoroReceiver.ACTION_DONE, 11, "Fertig"))
            .addAction(act(PomodoroReceiver.ACTION_BACKLOG, 12, "Noch nicht"))
            .addAction(act(PomodoroReceiver.ACTION_AGAIN, 13, "Nochmal"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
    }

    private fun openBoardIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 2,
            Intent(context, ParkingBoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "Pomodoro läuft", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            // Heads-up (hohe Priorität), aber bewusst OHNE Kanal-Ton/Vibration:
            // Gong und Vibration werden in onFinished explizit ausgelöst, damit es
            // nicht doppelt tönt und der Alarm-Audiostream (auch bei "Nicht stören")
            // zuverlässig greift.
            val ch = NotificationChannel(CHANNEL_DONE, "Pomodoro fertig", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
