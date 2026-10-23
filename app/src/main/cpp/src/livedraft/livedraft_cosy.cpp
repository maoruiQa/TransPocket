#include <jni.h>

#if defined(LIVE_DRAFT_COSY)
extern "C" {
struct crispasr_session;
void crispasr_set_gpu_backend(const char* name);
crispasr_session* crispasr_session_open_explicit(const char* model_path, const char* backend_name, int n_threads);
void crispasr_session_close(crispasr_session* session);
int crispasr_session_set_voice(crispasr_session* session, const char* path, const char* ref_text_or_null);
int crispasr_session_set_target_language(crispasr_session* session, const char* lang);
typedef void (*crispasr_pcm_stream_cb)(const float* pcm, int n_samples, int is_final, void* user_data);
int crispasr_session_synthesize_streaming(crispasr_session* session, const char* text, crispasr_pcm_stream_cb cb, void* user_data);
}
#endif

namespace {

struct Stream {
    JNIEnv* env;
    jobject sink;
    jobject cancel;
    jmethodID onSamples;
    jmethodID cancelled;
    bool keep;
};

#if defined(LIVE_DRAFT_COSY)
void onPcm(const float* pcm, int n_samples, int /*is_final*/, void* user) {
    Stream* stream = static_cast<Stream*>(user);
    if (stream == nullptr || !stream->keep || pcm == nullptr || n_samples <= 0) {
        return;
    }
    if (stream->env->CallBooleanMethod(stream->cancel, stream->cancelled)) {
        stream->keep = false;
        return;
    }
    if (stream->env->ExceptionCheck()) {
        stream->env->ExceptionClear();
        stream->keep = false;
        return;
    }
    jfloatArray samples = stream->env->NewFloatArray(n_samples);
    if (samples == nullptr) {
        stream->keep = false;
        return;
    }
    stream->env->SetFloatArrayRegion(samples, 0, n_samples, pcm);
    jboolean more = stream->env->CallBooleanMethod(stream->sink, stream->onSamples, samples);
    stream->env->DeleteLocalRef(samples);
    if (stream->env->ExceptionCheck()) {
        stream->env->ExceptionClear();
        stream->keep = false;
        return;
    }
    if (!more) {
        stream->keep = false;
    }
}
#endif

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeProbe(JNIEnv*, jclass) {
#if defined(LIVE_DRAFT_COSY)
    return 1;
#else
    return 0;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeOpen(JNIEnv* env, jclass, jstring path) {
#if defined(LIVE_DRAFT_COSY)
    const char* model = env->GetStringUTFChars(path, nullptr);
    crispasr_set_gpu_backend("");
    crispasr_session* session = crispasr_session_open_explicit(model, "cosyvoice3-tts", 2);
    env->ReleaseStringUTFChars(path, model);
    return reinterpret_cast<jlong>(session);
#else
    (void) env;
    (void) path;
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeClose(JNIEnv*, jclass, jlong handle) {
#if defined(LIVE_DRAFT_COSY)
    if (handle != 0) {
        crispasr_session_close(reinterpret_cast<crispasr_session*>(handle));
    }
#else
    (void) handle;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeSetVoice(JNIEnv* env, jclass, jlong handle, jstring voice, jstring reference) {
#if defined(LIVE_DRAFT_COSY)
    if (handle == 0 || voice == nullptr) {
        return -1;
    }
    const char* voice_chars = env->GetStringUTFChars(voice, nullptr);
    const char* reference_chars = reference == nullptr ? nullptr : env->GetStringUTFChars(reference, nullptr);
    int result = crispasr_session_set_voice(reinterpret_cast<crispasr_session*>(handle), voice_chars, reference_chars);
    env->ReleaseStringUTFChars(voice, voice_chars);
    if (reference != nullptr) {
        env->ReleaseStringUTFChars(reference, reference_chars);
    }
    return result;
#else
    (void) env;
    (void) handle;
    (void) voice;
    (void) reference;
    return -1;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeSetLanguage(JNIEnv* env, jclass, jlong handle, jstring language) {
#if defined(LIVE_DRAFT_COSY)
    if (handle == 0 || language == nullptr) {
        return;
    }
    const char* chars = env->GetStringUTFChars(language, nullptr);
    crispasr_session_set_target_language(reinterpret_cast<crispasr_session*>(handle), chars);
    env->ReleaseStringUTFChars(language, chars);
#else
    (void) env;
    (void) handle;
    (void) language;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_tools_CosyVoiceQuant_nativeSynthesize(JNIEnv* env, jclass, jlong handle, jstring text, jobject sink, jobject cancel) {
#if defined(LIVE_DRAFT_COSY)
    if (handle == 0 || text == nullptr || sink == nullptr || cancel == nullptr) {
        return;
    }
    Stream stream;
    stream.env = env;
    stream.sink = sink;
    stream.cancel = cancel;
    jclass sink_class = env->GetObjectClass(sink);
    jclass cancel_class = env->GetObjectClass(cancel);
    stream.onSamples = env->GetMethodID(sink_class, "onSamples", "([F)Z");
    stream.cancelled = env->GetMethodID(cancel_class, "cancelled", "()Z");
    stream.keep = stream.onSamples != nullptr && stream.cancelled != nullptr;
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (stream.keep) {
        crispasr_session_synthesize_streaming(reinterpret_cast<crispasr_session*>(handle), chars, onPcm, &stream);
    }
    env->ReleaseStringUTFChars(text, chars);
#else
    (void) env;
    (void) handle;
    (void) text;
    (void) sink;
    (void) cancel;
#endif
}
