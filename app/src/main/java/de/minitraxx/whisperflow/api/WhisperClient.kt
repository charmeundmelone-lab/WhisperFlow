package de.minitraxx.whisperflow.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object WhisperClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(file: File, apiKey: String, language: String = ""): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/m4a".toMediaType()))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "text")
                .addFormDataPart("prompt", WhisperPrompts.contextPrompt(language))
            if (language.isNotBlank()) builder.addFormDataPart("language", language)
            val body = builder.build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("${response.code}: ${response.body?.string()?.take(200)}")
                }
                response.body!!.string().trim()
            }
        }
    }
}
