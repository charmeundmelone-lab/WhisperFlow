package de.minitraxx.whisperflow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import de.minitraxx.whisperflow.util.ParkStatus
import de.minitraxx.whisperflow.util.ParkingBoardStore
import de.minitraxx.whisperflow.util.PomodoroManager

/**
 * Empfängt den Ablauf-Alarm des Pomodoro-Timers und die Aktionen der
 * Frage-Benachrichtigung. Läuft unabhängig vom App-Prozess — auch wenn die App
 * beendet und der Bildschirm aus war, startet Android den Receiver.
 */
class PomodoroReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Timer abgelaufen → Gong + Frage-Benachrichtigung. goAsync() hält den
            // Receiver kurz am Leben, damit der Gong auch bei einem vom Alarm frisch
            // gestarteten Prozess sicher abgespielt wird, bevor onReceive endet.
            ACTION_FINISHED -> {
                val result = goAsync()
                runCatching { PomodoroManager.onFinished(context) }
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { result.finish() } }, 3500)
            }

            // Nutzer hat den laufenden Timer manuell gestoppt (Aufgabe bleibt in Arbeit)
            ACTION_STOP -> PomodoroManager.cancel(context)

            // Antworten auf die Frage-Benachrichtigung
            ACTION_DONE -> {
                val id = PomodoroManager.activeTaskId(context)
                if (id >= 0) ParkingBoardStore.updateStatus(context, id, ParkStatus.DONE)
                PomodoroManager.clearFinished(context)
            }
            ACTION_BACKLOG -> {
                val id = PomodoroManager.activeTaskId(context)
                if (id >= 0) ParkingBoardStore.updateStatus(context, id, ParkStatus.BACKLOG)
                PomodoroManager.clearFinished(context)
            }
            ACTION_AGAIN -> PomodoroManager.restartSame(context)
        }
    }

    companion object {
        const val ACTION_FINISHED = "de.minitraxx.whisperflow.POMO_FINISHED"
        const val ACTION_STOP = "de.minitraxx.whisperflow.POMO_STOP"
        const val ACTION_DONE = "de.minitraxx.whisperflow.POMO_DONE"
        const val ACTION_BACKLOG = "de.minitraxx.whisperflow.POMO_BACKLOG"
        const val ACTION_AGAIN = "de.minitraxx.whisperflow.POMO_AGAIN"
    }
}
