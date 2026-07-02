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
 * Verwaltet das Whisper-Modell (large-v3-turbo, q5_0-quantisiert, ~547 MB).
 *
 * Download nur über expliziten Button in den Einstellungen — nie automatisch.
 * Der Download läuft in einem eigenen Prozess-Scope weiter, solange die App lebt
 * (der Floating-Button-Foreground-Service hält den Prozess am Leben), und ist
 * über HTTP-Range-Requests fortsetzbar.
 */
object ModelManager {

    private const val MODEL_FILE_NAME = "ggml-large-v3-turbo-q5_0.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin"

    // Plausibilitäts-Untergrenze: die echte Datei ist ~547 MB.
    private const val MIN_VALID_BYTES = 400L * 1024 * 1024

    const val MODEL_SIZE_LABEL = "~550 MB"

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : DownloadState() {
            val percent: Int
                get() = if (bytesTotal > 0) ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100) else 0
        }
        data class Failed(val message: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(dir, MODEL_FILE_NAME)
    }

    fun isModelAvailable(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() >= MIN_VALID_BYTES
    }

    fun isDownloading(): Boolean = downloadJob?.isActive == true

    @Synchronized
    fun startDownload(context: Context) {
        if (isDownloading() || isModelAvailable(context)) return
        val appContext = context.applicationContext
        _state.value = DownloadState.Downloading(0, -1)
        downloadJob = scope.launch {
            runCatching { doDownload(appContext) }
                .onSuccess { _state.value = DownloadState.Idle }
                .onFailure { e ->
                    if (e is CancellationException) {
                        _state.value = DownloadState.Idle
                    } else {
                        _state.value = DownloadState.Failed(
                            e.message?.take(120) ?: "Unbekannter Fehler"
                        )
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

    fun deleteModel(context: Context) {
        cancelDownload()
        LocalWhisperEngine.release()
        modelFile(context).delete()
        partFile(context).delete()
    }

    private fun partFile(context: Context): File =
        File(modelFile(context).absolutePath + ".part")

    private fun CoroutineScope.doDownload(context: Context) {
        val target = modelFile(context)
        val part = partFile(context)
        target.parentFile?.mkdirs()

        var alreadyDone = if (part.exists()) part.length() else 0L

        val requestBuilder = Request.Builder().url(MODEL_URL)
        if (alreadyDone > 0) requestBuilder.header("Range", "bytes=$alreadyDone-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                // Range ungültig (z.B. Datei serverseitig geändert): von vorn beginnen
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

            _state.value = DownloadState.Downloading(alreadyDone, total)

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
                            _state.value = DownloadState.Downloading(written, total)
                        }
                    }
                    out.flush()
                }
            }

            if (total > 0 && part.length() != total) {
                error("Download unvollständig (${part.length()}/$total Bytes) — erneut starten setzt fort")
            }
            if (part.length() < MIN_VALID_BYTES) {
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
