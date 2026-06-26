package de.minitraxx.whisperflow.util

import android.content.Context

object CostTracker {
    private const val PREFS = "whisperflow_costs"
    private const val KEY_SPENT = "total_spent_eur"
    private const val KEY_BUDGET = "budget_eur"

    // Groq Whisper Large v3 Turbo: $0.04/hour = $0.000667/min = $0.0000111/sec
    private const val WHISPER_EUR_PER_SECOND = 0.04 / 3600.0

    // Claude Haiku 4.5: ~200 in + 100 out tokens per correction ~€0.0005
    private const val CLAUDE_EUR_PER_CALL = 0.0005

    fun recordAudio(durationSeconds: Long, context: Context) {
        add(durationSeconds * WHISPER_EUR_PER_SECOND, context)
    }

    fun recordClaude(context: Context) {
        add(CLAUDE_EUR_PER_CALL, context)
    }

    private fun add(amount: Double, context: Context) {
        prefs(context).edit()
            .putFloat(KEY_SPENT, (getSpent(context) + amount).toFloat())
            .apply()
    }

    fun getSpent(context: Context): Double =
        prefs(context).getFloat(KEY_SPENT, 0f).toDouble()

    fun getBudget(context: Context): Double =
        prefs(context).getFloat(KEY_BUDGET, 10f).toDouble()

    fun setBudget(eur: Double, context: Context) {
        prefs(context).edit().putFloat(KEY_BUDGET, eur.toFloat()).apply()
    }

    fun getRemaining(context: Context): Double =
        (getBudget(context) - getSpent(context)).coerceAtLeast(0.0)

    fun isExceeded(context: Context): Boolean = getSpent(context) >= getBudget(context)

    fun reset(context: Context) {
        prefs(context).edit().putFloat(KEY_SPENT, 0f).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
