package de.minitraxx.whisperflow.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ParkStatus { BACKLOG, IN_PROGRESS, DONE }

data class ParkItem(
    val id: Long,
    val text: String,
    val status: ParkStatus,
    val createdAt: Long
)

/**
 * "Parkplatz": Sprach-Erfassung für spontane Gedanken, die von der aktuellen
 * Aufgabe ablenken würden. Erfassung passiert ausschließlich per Sprache (Badge
 * am Floating Button), Organisieren ausschließlich per Antippen im Board —
 * Sprache ist nur für die reibungsarme Eingabe da, nicht fürs Verwalten.
 */
object ParkingBoardStore {
    private const val PREFS_NAME = "whisperflow_prefs"
    private const val KEY_PARK_ITEMS = "park_items"

    // Bewusst niedrig: WIP-Limit soll bremsen, bevor zu viele Dinge gleichzeitig
    // "in Arbeit" hängen — kein Hard-Block, nur eine sanfte Warnung beim Verschieben.
    const val WIP_LIMIT = 3

    fun getAll(context: Context): List<ParkItem> {
        val raw = prefs(context).getString(KEY_PARK_ITEMS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ParkItem(
                    id = o.getLong("id"),
                    text = o.getString("text"),
                    status = runCatching { ParkStatus.valueOf(o.getString("status")) }
                        .getOrDefault(ParkStatus.BACKLOG),
                    createdAt = o.getLong("createdAt")
                )
            }
        }.getOrDefault(emptyList())

        // Speicherstände von vor dem nanoTime()-Fix (siehe add()) können noch
        // doppelte IDs enthalten — die Board-Liste nutzt die ID als Compose-
        // Schlüssel, eine Dopplung crasht dort hart. Selbstheilend beim Laden
        // reparieren, kein manuelles Aufräumen auf dem Gerät nötig.
        val seen = mutableSetOf<Long>()
        var repairedAny = false
        val repaired = parsed.map { item ->
            if (!seen.add(item.id)) {
                repairedAny = true
                item.copy(id = System.nanoTime())
            } else {
                item
            }
        }
        if (repairedAny) save(context, repaired)
        return repaired
    }

    fun add(context: Context, text: String): ParkItem {
        // System.nanoTime() statt currentTimeMillis(): wenn eine Aufnahme mehrere
        // Gedanken liefert, entstehen mehrere add()-Aufrufe in derselben Schleife —
        // Millisekunden-Auflösung kollidiert dabei leicht (führte zu doppelten IDs
        // und einem Compose-Crash, da die Board-Liste die ID als Schlüssel nutzt).
        val item = ParkItem(id = System.nanoTime(), text = text.trim(), status = ParkStatus.BACKLOG, createdAt = System.currentTimeMillis())
        save(context, getAll(context) + item)
        return item
    }

    fun updateStatus(context: Context, id: Long, status: ParkStatus) {
        save(context, getAll(context).map { if (it.id == id) it.copy(status = status) else it })
    }

    /** Ersetzt den Text eines Eintrags (Board-Editor). Leerer Text wird ignoriert. */
    fun updateText(context: Context, id: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        save(context, getAll(context).map { if (it.id == id) it.copy(text = trimmed) else it })
    }

    fun delete(context: Context, id: Long) {
        save(context, getAll(context).filterNot { it.id == id })
    }

    fun countInProgress(context: Context): Int =
        getAll(context).count { it.status == ParkStatus.IN_PROGRESS }

    private fun save(context: Context, items: List<ParkItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("status", item.status.name)
                    .put("createdAt", item.createdAt)
            )
        }
        prefs(context).edit().putString(KEY_PARK_ITEMS, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
