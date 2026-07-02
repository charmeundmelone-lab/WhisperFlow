// JNI-Bridge zwischen LocalWhisperEngine (Kotlin) und whisper.cpp.
// Alle Funktionen sind defensiv: Fehler liefern 0/nullptr, niemals Prozess-Abbrueche.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

void ggml_android_log(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) return;
    const int prio = (level == GGML_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR
                   : (level == GGML_LOG_LEVEL_WARN)  ? ANDROID_LOG_WARN
                                                     : ANDROID_LOG_DEBUG;
    __android_log_write(prio, LOG_TAG, text);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_de_minitraxx_whisperflow_whisper_WhisperJni_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring model_path) {
    if (model_path == nullptr) return 0;

    whisper_log_set(ggml_android_log, nullptr);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGW("whisper_init_from_file_with_params fehlgeschlagen");
        return 0;
    }
    LOGI("Whisper-Kontext geladen");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_minitraxx_whisperflow_whisper_WhisperJni_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctx_ptr, jfloatArray samples,
        jstring language, jint n_threads, jstring initial_prompt, jint audio_ctx) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr || samples == nullptr) return nullptr;

    const jsize n_samples = env->GetArrayLength(samples);
    if (n_samples <= 0) return nullptr;

    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) return nullptr;

    const char *lang = (language != nullptr)
        ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char *prompt = (initial_prompt != nullptr)
        ? env->GetStringUTFChars(initial_prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_timestamps    = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.n_threads        = n_threads > 0 ? n_threads : 4;
    params.language         = (lang != nullptr && lang[0] != '\0') ? lang : "auto";
    if (prompt != nullptr && prompt[0] != '\0') {
        params.initial_prompt = prompt;
    }
    // Encoder-Kontext auf die tatsächliche Audiolänge begrenzen (0 = volles 30s-Fenster).
    // Beschleunigt kurze Aufnahmen massiv, weil Whisper sonst immer 30s durchrechnet.
    if (audio_ctx > 0 && audio_ctx <= 1500) {
        params.audio_ctx = audio_ctx;
    }

    const int rc = whisper_full(ctx, params, pcm, n_samples);

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    std::string result;
    if (rc == 0) {
        const int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) result += text;
        }
    } else {
        LOGW("whisper_full fehlgeschlagen: rc=%d", rc);
    }

    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
    if (prompt != nullptr) env->ReleaseStringUTFChars(initial_prompt, prompt);

    if (rc != 0) return nullptr;
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_de_minitraxx_whisperflow_whisper_WhisperJni_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Whisper-Kontext freigegeben");
    }
}
