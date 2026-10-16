// [T-today-murmur] JNI bridge to llama.cpp for the 碎碎念 tagger
// (docs/design/today/MURMUR_MODEL.md, SPEC §19). One small model, one call at
// a time: Kotlin serialises access (MurmurLlm), so no locking here.
//
// run() decodes the prompt, reports the probability of a probe token at the
// first output position (the intent score: 1 - P("记录")), then generates
// greedily under a GBNF grammar so the line always parses. Where the second
// field (the mood) starts, it also reads the probability of each option given,
// so the app knows how sure the model is, not only what it chose.
#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MurmurLlm"

namespace {

struct Handle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
};

void log_cb(ggml_log_level level, const char * text, void *) {
    // Warnings and errors only; CONT (progress dots) is numerically higher, so not >=.
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string str(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, special);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, false, special) < 0) toks.clear();
    return toks;
}

/** Softmax over the whole vocabulary at [logits], for the given tokens only. */
std::vector<double> probs(const float * logits, int n_vocab, const std::vector<llama_token> & want) {
    float mx = logits[0];
    for (int i = 1; i < n_vocab; i++) mx = std::max(mx, logits[i]);
    double sum = 0;
    for (int i = 0; i < n_vocab; i++) sum += std::exp((double) (logits[i] - mx));
    std::vector<double> out;
    for (llama_token t : want) out.push_back(t >= 0 ? std::exp((double) (logits[t] - mx)) / sum : 0.0);
    return out;
}

std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[64];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        llama_token_to_piece(vocab, t, big.data(), (int32_t) big.size(), 0, false);
        return big;
    }
    return std::string(buf, n);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_openminis_app_data_murmur_MurmurLlm_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint threads, jint nCtx) {
    static bool inited = false;
    if (!inited) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        inited = true;
    }
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Read into memory, no mmap: the weights get repacked for the CPU's int8
    // matmul anyway, and with mmap the file pages would stay resident on top
    // (~1.4 GB instead of ~0.75 GB, measured on an 8 Elite).
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    std::string path = str(env, jpath);
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return 0;

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = (uint32_t) nCtx;
    cp.n_ubatch = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto * h = new Handle{model, ctx, llama_model_get_vocab(model)};
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openminis_app_data_murmur_MurmurLlm_nativeFree(JNIEnv *, jclass, jlong ptr) {
    auto * h = reinterpret_cast<Handle *>(ptr);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

/**
 * Returns "<probability of probe>\n<generated line>\n<p1,p2,…>", or null on
 * failure. The last line has, for each of [jfield]'s newline-separated
 * options, the probability of its first token where the second field begins
 * (empty if that point was not seen). All are read from the raw logits,
 * before the grammar.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_openminis_app_data_murmur_MurmurLlm_nativeRun(
        JNIEnv * env, jclass, jlong ptr, jstring jprompt, jstring jgrammar, jstring jprobe, jstring jfield, jint maxTokens) {
    auto * h = reinterpret_cast<Handle *>(ptr);
    if (!h) return nullptr;
    llama_memory_clear(llama_get_memory(h->ctx), true);

    std::vector<llama_token> toks = tokenize(h->vocab, str(env, jprompt), true);
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (toks.empty() || (int) toks.size() + maxTokens > n_ctx) return nullptr;
    if (llama_decode(h->ctx, llama_batch_get_one(toks.data(), (int32_t) toks.size())) != 0) return nullptr;

    const int n_vocab = llama_vocab_n_tokens(h->vocab);
    float probe = 0.f;
    std::vector<llama_token> probeToks = tokenize(h->vocab, str(env, jprobe), false);
    if (!probeToks.empty()) probe = (float) probs(llama_get_logits_ith(h->ctx, -1), n_vocab, {probeToks[0]})[0];

    // The second field's options, by first token (-1 for one that won't tokenize).
    std::vector<llama_token> fieldToks;
    {
        std::string all = str(env, jfield);
        size_t at = 0;
        while (at <= all.size() && !all.empty()) {
            size_t nl = all.find('\n', at);
            std::string opt = all.substr(at, nl == std::string::npos ? std::string::npos : nl - at);
            auto t = tokenize(h->vocab, opt, false);
            fieldToks.push_back(t.empty() ? -1 : t[0]);
            if (nl == std::string::npos) break;
            at = nl + 1;
        }
    }
    std::string field;

    std::string grammar = str(env, jgrammar);
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(sp);
    llama_sampler * g = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
    if (!g) {
        llama_sampler_free(chain);
        return nullptr;
    }
    llama_sampler_chain_add(chain, g);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string out;
    for (int i = 0; i < maxTokens; i++) {
        // Just past the first '|': the second field is about to start.
        if (field.empty() && !fieldToks.empty() && !out.empty() && out.back() == '|' && out.find('|') == out.size() - 1) {
            for (double p : probs(llama_get_logits_ith(h->ctx, -1), n_vocab, fieldToks)) {
                if (!field.empty()) field += ",";
                field += std::to_string(p);
            }
        }
        llama_token t = llama_sampler_sample(chain, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, t)) break;
        out += piece(h->vocab, t);
        if (out.find('\n') != std::string::npos) break;
        if (llama_decode(h->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(chain);

    // The line only: a token may have carried the newline and more after it.
    out = out.substr(0, out.find('\n'));
    std::string res = std::to_string(probe) + "\n" + out + "\n" + field;
    return env->NewStringUTF(res.c_str());
}
