package de.minitraxx.whisperflow.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CostTracker {
    private const val PREFS = "whisperflow_costs"
    private const val KEY_SPENT = "total_spent_eur"
    private const val KEY_BUDGET = "budget_eur"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_TODAY_SPENT = "today_spent_eur"

    private const val WHISPER_EUR_PER_SECOND = 0.006 / 60.0
    private const val CLAUDE_EUR_PER_CALL = 0.0005

    fun recordAudio(durationSeconds: Long, context: Context) {
        add(durationSeconds * WHISPER_EUR_PER_SECOND, context)
    }

    fun recordClaude(context: Context) {
        add(CLAUDE_EUR_PER_CALL, context)
    }

    private fun add(amount: Double, context: Context) {
        val p = prefs(context)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val storedDate = p.getString(KEY_TODAY_DATE, "")
        val todayPrev = if (storedDate == today) p.getFloat(KEY_TODAY_SPENT, 0f).toDouble() else 0.0
        p.edit()
            .putFloat(KEY_SPENT, (getSpent(context) + amount).toFloat())
            .putFloat(KEY_TODAY_SPENT, (todayPrev + amount).toFloat())
            .putString(KEY_TODAY_DATE, today)
            .apply()
    }

    fun getSpent(context: Context): Double = prefs(context).getFloat(KEY_SPENT, 0f).toDouble()

    fun getTodaySpent(context: Context): Double {
        val p = prefs(context)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return if (p.getString(KEY_TODAY_DATE, "") == today) p.getFloat(KEY_TODAY_SPENT, 0f).toDouble() else 0.0
    }

    fun getBudget(context: Context): Double = prefs(context).getFloat(KEY_BUDGET, 10f).toDouble()

    fun setBudget(eur: Double, context: Context) {
        prefs(context).edit().putFloat(KEY_BUDGET, eur.toFloat()).apply()
    }

    fun getRemaining(context: Context): Double = (getBudget(context) - getSpent(context)).coerceAtLeast(0.0)

    fun isExceeded(context: Context): Boolean = getSpent(context) >= getBudget(context)

    fun reset(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_SPENT, 0f)
            .putFloat(KEY_TODAY_SPENT, 0f)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
