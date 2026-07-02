package de.minitraxx.whisperflow.whisper

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Verwaltet die Whisper-Modelle. Zwei Stufen zur Auswahl:
 *  - "Schnell" (small, q5_1, ~190 MB) — Empfehlung fürs Handy, ~5-6x schneller
 *  - "Maximale Qualität" (large-v3-turbo, q5_0, ~547 MB) — beste Qualität, aber langsam auf CPU
 *
 * Download nur über expliziten Button in den Einstellungen — nie automatisch.
 * Der Download läuft in einem eigenen Prozess-Scope weiter, solange die App lebt,
 * und ist über HTTP-Range-Requests fortsetzbar.
 */
object ModelManager {

    const val MODEL_SMALL = "small"
    const val MODEL_TURBO = "turbo"
    const val KEY_ONDEVICE_MODEL = "ondevice_model"
    private const val PREFS_NAME = "whisperflow_prefs"

    data class ModelInfo(
        val id: String,
        val fileName: String,
        val url: String,
        val minValidBytes: Long,
        val label: String,
        val sizeLabel: String
    )

    // Reihenfolge = Anzeige-Reihenfolge UND Auto-Auswahl-Priorität (schnellstes zuerst)
    val MODELS = listOf(
        ModelInfo(
            id = MODEL_SMALL,
            fileName = "ggml-small-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            minValidBytes = 150L * 1024 * 1024,
            label = "Schnell (small)",
            sizeLabel = "~190 MB"
        ),
        ModelInfo(
            id = MODEL_TURBO,
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            minValidBytes = 400L * 1024 * 1024,
            label = "Maximale Qualität (large-v3-turbo)",
            sizeLabel = "~550 MB"
        )
    )

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val modelId: String, val bytesDone: Long, val bytesTotal: Long) : DownloadState() {
            val percent: Int
                get() = if (bytesTotal > 0) ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100) else 0
        }
        data class Failed(val modelId: String, val message: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Auswahl ────────────────────────────────────────────────────────────────

    /** Explizite Wahl aus den Prefs; ohne Wahl das schnellste vorhandene Modell, sonst small. */
    fun selectedModel(context: Context): ModelInfo {
        val prefId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ONDEVICE_MODEL, null)
        MODELS.find { it.id == prefId }?.let { return it }
        return MODELS.firstOrNull { isAvailable(context, it) } ?: MODELS.first()
    }

    fun setSelectedModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ONDEVICE_MODEL, id).apply()
    }

    // ── Dateien / Verfügbarkeit ────────────────────────────────────────────────

    fun modelFile(context: Context, info: ModelInfo): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(dir, info.fileName)
    }

    fun selectedModelFile(context: Context): File = modelFile(context, selectedModel(context))

    fun isAvailable(context: Context, info: ModelInfo): Boolean {
        val f = modelFile(context, info)
        return f.exists() && f.length() >= info.minValidBytes
    }

    /** Ist das AKTUELL GEWÄHLTE Modell einsatzbereit? */
    fun isModelAvailable(context: Context): Boolean = isAvailable(context, selectedModel(context))

    fun isDownloading(): Boolean = downloadJob?.isActive == true

    // ── Download ───────────────────────────────────────────────────────────────

    @Synchronized
    fun startDownload(context: Context, info: ModelInfo) {
        if (isDownloading() || isAvailable(context, info)) return
        val appContext = context.applicationContext
        _state.value = DownloadState.Downloading(info.id, 0, -1)
        downloadJob = scope.launch {
            runCatching { doDownload(appContext, info) }
                .onSuccess { _state.value = DownloadState.Idle }
                .onFailure { e ->
                    if (e is CancellationException) {
                        _state.value = DownloadState.Idle
                    } else {
                        _state.value = DownloadState.Failed(info.id, e.message?.take(120) ?: "Unbekannter Fehler")
                    }
                }
        }
    }

    @Synchronized
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = DownloadState.Idle
    }

    fun deleteModel(context: Context, info: ModelInfo) {
        val st = _state.value
        if (st is DownloadState.Downloading && st.modelId == info.id) cancelDownload()
        LocalWhisperEngine.release()
        modelFile(context, info).delete()
        File(modelFile(context, info).absolutePath + ".part").delete()
        // Falls das gelöschte Modell das gewählte war, Zustand konsistent lassen —
        // selectedModel() fällt beim nächsten Aufruf auf ein vorhandenes Modell zurück,
        // sobald die explizite Wahl entfernt ist.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ONDEVICE_MODEL, null) == info.id) {
            prefs.edit().remove(KEY_ONDEVICE_MODEL).apply()
        }
    }

    private fun CoroutineScope.doDownload(context: Context, info: ModelInfo) {
        val target = modelFile(context, info)
        val part = File(target.absolutePath + ".part")
        target.parentFile?.mkdirs()

        var alreadyDone = if (part.exists()) part.length() else 0L

        val requestBuilder = Request.Builder().url(info.url)
        if (alreadyDone > 0) requestBuilder.header("Range", "bytes=$alreadyDone-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                part.delete()
                error("Download-Fortsetzung ungültig — bitte erneut starten")
            }
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val resuming = response.code == 206
            if (!resuming) alreadyDone = 0L

            val body = response.body ?: error("Leere Antwort")
            val total = if (body.contentLength() > 0) alreadyDone + body.contentLength() else -1L

            _state.value = DownloadState.Downloading(info.id, alreadyDone, total)

            FileOutputStream(part, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = alreadyDone
                    var lastUpdate = 0L
                    while (true) {
                        if (!isActive) throw CancellationException("abgebrochen")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 400) {
                            lastUpdate = now
                            _state.value = DownloadState.Downloading(info.id, written, total)
                        }
                    }
                    out.flush()
                }
            }

            if (total > 0 && part.length() != total) {
                error("Download unvollständig (${part.length()}/$total Bytes) — erneut starten setzt fort")
            }
            if (part.length() < info.minValidBytes) {
                part.delete()
                error("Datei unerwartet klein — Download fehlgeschlagen")
            }
            target.delete()
            if (!part.renameTo(target)) {
                error("Konnte Modelldatei nicht finalisieren")
            }
        }
    }
}
