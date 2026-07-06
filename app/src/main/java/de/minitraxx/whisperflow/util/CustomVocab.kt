package de.minitraxx.whisperflow.util

import android.content.Context

/**
 * Persönliches Wörterbuch: Namen oder Fachbegriffe, die Whisper wiederholt
 * falsch versteht. Wird als Zusatz an den Whisper-Kontext-Prompt angehängt
 * (siehe WhisperPrompts) — kein Ersatz für die Claude-Verhörer-Reparatur,
 * sondern gezielt für Eigennamen, für die Whisper keinen Trainings-Bias hat.
 */
object CustomVocab {
    private const val PREFS_NAME = "whisperflow_prefs"
    private const val KEY_CUSTOM_VOCAB = "custom_vocab"

    // Whisper begrenzt den prompt-Parameter auf ~224 Tokens; der feste
    // Kontext-Text belegt davon schon einen Teil. 30 eigene Begriffe lassen
    // genug Luft und reichen für den privaten Gebrauch (1-2 Nutzer) locker.
    private const val MAX_ENTRIES = 30

    fun getAll(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_VOCAB, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Fügt [word] hinzu (dedupliziert case-insensitiv, verschiebt an letzte Position). */
    fun add(context: Context, word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val current = getAll(context).filterNot { it.equals(trimmed, ignoreCase = true) }
        save(context, (current + trimmed).takeLast(MAX_ENTRIES))
    }

    fun remove(context: Context, word: String) {
        save(context, getAll(context).filterNot { it.equals(word, ignoreCase = true) })
    }

    fun contains(context: Context, word: String): Boolean =
        getAll(context).any { it.equals(word, ignoreCase = true) }

    /** Anzuhängender Satz an den Whisper-Kontext-Prompt — leer, wenn keine Einträge vorhanden sind. */
    fun promptSuffix(context: Context): String {
        val words = getAll(context)
        if (words.isEmpty()) return ""
        return " Außerdem können folgende Namen oder Begriffe vorkommen: ${words.joinToString(", ")}."
    }

    private fun save(context: Context, words: List<String>) {
        prefs(context).edit().putString(KEY_CUSTOM_VOCAB, words.joinToString("\n")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
