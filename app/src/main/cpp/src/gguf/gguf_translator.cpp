#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"

#define LOG_TAG "gguf"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_lock;
llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
bool g_backend = false;

void throw_state(JNIEnv * env, const char * message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

void release_locked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

int thread_count() {
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores < 1) {
        return 2;
    }
    if (cores > 4) {
        return 4;
    }
    return static_cast<int>(cores);
}

bool decode_tokens(llama_context * ctx, llama_token * tokens, int32_t count) {
    const int32_t chunk_size = 512;
    int32_t offset = 0;
    while (offset < count) {
        int32_t chunk = count - offset;
        if (chunk > chunk_size) {
            chunk = chunk_size;
        }
        llama_batch batch = llama_batch_get_one(tokens + offset, chunk);
        if (llama_decode(ctx, batch) != 0) {
            return false;
        }
        offset += chunk;
    }
    return true;
}

std::string generate_locked(const std::string & prompt, int max_tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        return std::string();
    }
    llama_memory_t memory = llama_get_memory(g_ctx);
    if (memory != nullptr) {
        llama_memory_clear(memory, true);
    }

    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (n_prompt < 0) {
        tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    }
    if (n_prompt <= 0) {
        LOGE("prompt tokenization failed");
        return std::string();
    }
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(g_ctx));
    if (n_prompt >= n_ctx - 8) {
        LOGE("prompt uses %d tokens and the context is %d", n_prompt, n_ctx);
        return std::string();
    }
    int32_t limit = max_tokens;
    if (limit < 1) {
        limit = 1;
    }
    if (limit > n_ctx - n_prompt - 1) {
        limit = n_ctx - n_prompt - 1;
    }
    if (!decode_tokens(g_ctx, tokens.data(), n_prompt)) {
        LOGE("prompt decode failed");
        return std::string();
    }

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::string output;
    output.reserve(static_cast<size_t>(limit) * 4);
    for (int32_t step = 0; step < limit; step++) {
        const float * logits = llama_get_logits_ith(g_ctx, -1);
        if (logits == nullptr) {
            LOGE("decoder returned no logits");
            return std::string();
        }
        int32_t best = 0;
        float best_value = -1.0e30f;
        for (int32_t i = 0; i < n_vocab; i++) {
            if (logits[i] > best_value) {
                best_value = logits[i];
                best = i;
            }
        }
        llama_token token = static_cast<llama_token>(best);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        char piece[256];
        int32_t piece_len = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (piece_len < 0) {
            return std::string();
        }
        if (piece_len > 0) {
            output.append(piece, static_cast<size_t>(piece_len));
        }
        if (llama_decode(g_ctx, llama_batch_get_one(&token, 1)) != 0) {
            LOGE("token decode failed");
            return std::string();
        }
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_translation_GgufTranslator_nativeLoad(
        JNIEnv * env, jclass, jstring path) {
    const char * utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) {
        return;
    }
    std::string model_path(utf);
    env->ReleaseStringUTFChars(path, utf);

    std::lock_guard<std::mutex> guard(g_lock);
    try {
        if (!g_backend) {
            llama_backend_init();
            g_backend = true;
        }
        release_locked();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        g_model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (g_model == nullptr) {
            throw_state(env, "GGUF model failed to load");
            return;
        }
        llama_context_params context_params = llama_context_default_params();
        const int threads = thread_count();
        context_params.n_ctx = 2048;
        context_params.n_batch = 512;
        context_params.n_ubatch = 512;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        g_ctx = llama_init_from_model(g_model, context_params);
        if (g_ctx == nullptr) {
            release_locked();
            throw_state(env, "GGUF context failed to start");
        }
    } catch (const std::exception & error) {
        release_locked();
        LOGE("load failed: %s", error.what());
        throw_state(env, "GGUF model failed to load");
    } catch (...) {
        release_locked();
        throw_state(env, "GGUF model failed to load");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_translation_GgufTranslator_nativeGenerate(
        JNIEnv * env, jclass, jstring prompt, jint max_tokens) {
    const char * utf = env->GetStringUTFChars(prompt, nullptr);
    if (utf == nullptr) {
        return nullptr;
    }
    std::string text(utf);
    env->ReleaseStringUTFChars(prompt, utf);

    std::lock_guard<std::mutex> guard(g_lock);
    if (g_model == nullptr || g_ctx == nullptr) {
        throw_state(env, "GGUF model is not loaded");
        return nullptr;
    }
    try {
        std::string generated = generate_locked(text, max_tokens);
        return env->NewStringUTF(generated.c_str());
    } catch (const std::exception & error) {
        LOGE("generate failed: %s", error.what());
        throw_state(env, "GGUF generation failed");
        return nullptr;
    } catch (...) {
        throw_state(env, "GGUF generation failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_voice_1translation_neural_1networks_translation_GgufTranslator_nativeRelease(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_locked();
}
