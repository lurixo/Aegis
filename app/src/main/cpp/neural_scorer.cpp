// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

#include "neural_scorer.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>

#include "llama.h"

namespace aegis {

namespace {

constexpr int kContextCells = 512;
constexpr int kBatchTokens = 512;

std::once_flag gBackendOnce;
std::mutex gLogMutex;
std::string gLastError;

void logSink(ggml_log_level level, const char * text, void *) {
    if (level != GGML_LOG_LEVEL_ERROR || text == nullptr) return;
    std::lock_guard<std::mutex> lock(gLogMutex);
    gLastError = text;
    while (!gLastError.empty() && (gLastError.back() == '\n' || gLastError.back() == ' ')) gLastError.pop_back();
}

std::string takeLastError(const char * fallback) {
    std::lock_guard<std::mutex> lock(gLogMutex);
    std::string out = gLastError.empty() ? std::string(fallback) : gLastError;
    gLastError.clear();
    return out;
}

void initBackend() {
    std::call_once(gBackendOnce, [] {
        llama_log_set(logSink, nullptr);
        llama_backend_init();
    });
}

bool abortRequested(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load(std::memory_order_relaxed);
}

double logprobOf(const float * logits, int nVocab, llama_token token) {
    float peak = logits[0];
    for (int i = 1; i < nVocab; ++i) peak = std::max(peak, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < nVocab; ++i) sum += std::exp(static_cast<double>(logits[i] - peak));
    return static_cast<double>(logits[token] - peak) - std::log(sum);
}

void logSoftmax(const float * logits, int nVocab, std::vector<float> * out) {
    float peak = logits[0];
    for (int i = 1; i < nVocab; ++i) peak = std::max(peak, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < nVocab; ++i) sum += std::exp(static_cast<double>(logits[i] - peak));
    const double norm = static_cast<double>(peak) + std::log(sum);
    out->resize(nVocab);
    for (int i = 0; i < nVocab; ++i) (*out)[i] = static_cast<float>(static_cast<double>(logits[i]) - norm);
}

void addToken(llama_batch & batch, llama_token token, llama_pos pos, int firstSeq, int seqCount, bool output) {
    const int at = batch.n_tokens;
    batch.token[at] = token;
    batch.pos[at] = pos;
    batch.n_seq_id[at] = seqCount;
    for (int s = 0; s < seqCount; ++s) batch.seq_id[at][s] = firstSeq + s;
    batch.logits[at] = output ? 1 : 0;
    batch.n_tokens = at + 1;
}

}  // namespace

struct NeuralScorer::Impl {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int nVocab = 0;
    llama_token start = LLAMA_TOKEN_NULL;
    llama_batch batch{};
    bool batchReady = false;
    std::atomic<bool> abort{false};
    std::vector<llama_token> prefix;
    std::vector<float> afterPrefix;
    FILE * file = nullptr;

    ~Impl() {
        if (batchReady) llama_batch_free(batch);
        if (ctx != nullptr) llama_free(ctx);
        if (model != nullptr) llama_model_free(model);
        if (file != nullptr) std::fclose(file);
    }

    bool tokenize(const std::string & text, std::vector<llama_token> * out) const {
        out->clear();
        if (text.empty()) return true;
        const int32_t len = static_cast<int32_t>(text.size());
        const int32_t need = -llama_tokenize(vocab, text.data(), len, nullptr, 0, false, false);
        if (need <= 0) return need == 0;
        out->resize(need);
        const int32_t got = llama_tokenize(vocab, text.data(), len, out->data(), need, false, false);
        if (got < 0) return false;
        out->resize(got);
        return true;
    }

    void reset() {
        llama_memory_clear(llama_get_memory(ctx), true);
        prefix.clear();
        afterPrefix.clear();
    }

    Status decode() {
        const int32_t rc = llama_decode(ctx, batch);
        if (rc == 0) return Status::Ok;
        reset();
        return rc == 2 ? Status::Aborted : Status::Failed;
    }

    Status loadPrefix(const std::vector<llama_token> & want) {
        reset();
        const int total = static_cast<int>(want.size());
        int lastIndex = -1;
        for (int from = 0; from < total; from += kBatchTokens) {
            const int to = std::min(total, from + kBatchTokens);
            batch.n_tokens = 0;
            for (int i = from; i < to; ++i) addToken(batch, want[i], i, 0, kMaxCandidates, i == total - 1);
            const Status status = decode();
            if (status != Status::Ok) return status;
            lastIndex = to - from - 1;
        }
        const float * logits = llama_get_logits_ith(ctx, lastIndex);
        if (logits == nullptr) {
            reset();
            return Status::Failed;
        }
        logSoftmax(logits, nVocab, &afterPrefix);
        prefix = want;
        return Status::Ok;
    }
};

NeuralScorer::NeuralScorer(Impl * impl) : impl_(impl) {}

NeuralScorer::~NeuralScorer() { delete impl_; }

namespace {

NeuralScorer::Impl * createImpl(llama_model * model, int threads, std::string * error) {
    auto * impl = new NeuralScorer::Impl();
    impl->model = model;
    impl->vocab = llama_model_get_vocab(model);
    impl->nVocab = llama_vocab_n_tokens(impl->vocab);
    impl->start = llama_vocab_bos(impl->vocab);
    if (impl->start == LLAMA_TOKEN_NULL) impl->start = llama_vocab_eos(impl->vocab);
    if (impl->start == LLAMA_TOKEN_NULL) {
        if (error != nullptr) *error = "the model declares neither a BOS nor an EOS token";
        delete impl;
        return nullptr;
    }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = kContextCells;
    params.n_batch = kBatchTokens;
    params.n_ubatch = kBatchTokens;
    params.n_seq_max = NeuralScorer::kMaxCandidates;
    params.kv_unified = true;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.no_perf = true;
    params.abort_callback = abortRequested;
    params.abort_callback_data = &impl->abort;
    impl->ctx = llama_init_from_model(model, params);
    if (impl->ctx == nullptr) {
        if (error != nullptr) *error = takeLastError("could not create the inference context");
        delete impl;
        return nullptr;
    }
    impl->batch = llama_batch_init(kBatchTokens, 0, NeuralScorer::kMaxCandidates);
    impl->batchReady = true;
    return impl;
}

llama_model_params modelParams() {
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    return params;
}

}  // namespace

NeuralScorer * NeuralScorer::open(FILE * file, int threads, std::string * error) {
    initBackend();
    llama_model * model = llama_model_load_from_file_ptr(file, modelParams());
    if (model == nullptr) {
        if (error != nullptr) *error = takeLastError("could not load the model");
        std::fclose(file);
        return nullptr;
    }
    Impl * impl = createImpl(model, threads, error);
    if (impl == nullptr) {
        std::fclose(file);
        return nullptr;
    }
    impl->file = file;
    return new NeuralScorer(impl);
}

NeuralScorer * NeuralScorer::openPath(const char * path, int threads, std::string * error) {
    initBackend();
    llama_model * model = llama_model_load_from_file(path, modelParams());
    if (model == nullptr) {
        if (error != nullptr) *error = takeLastError("could not load the model");
        return nullptr;
    }
    Impl * impl = createImpl(model, threads, error);
    return impl == nullptr ? nullptr : new NeuralScorer(impl);
}

void NeuralScorer::cancel() { impl_->abort.store(true, std::memory_order_relaxed); }

NeuralScorer::Status NeuralScorer::score(const std::string & context,
                                         const std::vector<std::string> & candidates,
                                         std::vector<double> * logprobs,
                                         std::vector<int> * tokenCounts) {
    Impl & m = *impl_;
    m.abort.store(false, std::memory_order_relaxed);
    logprobs->assign(candidates.size(), 0.0);
    tokenCounts->assign(candidates.size(), 0);
    if (candidates.empty()) return Status::Ok;
    if (candidates.size() > static_cast<size_t>(kMaxCandidates)) return Status::Failed;

    std::vector<llama_token> contextTokens;
    if (!m.tokenize(context, &contextTokens)) return Status::Failed;
    if (contextTokens.size() > static_cast<size_t>(kMaxContextTokens)) {
        contextTokens.erase(contextTokens.begin(), contextTokens.end() - kMaxContextTokens);
    }
    std::vector<llama_token> want;
    want.reserve(contextTokens.size() + 1);
    want.push_back(m.start);
    want.insert(want.end(), contextTokens.begin(), contextTokens.end());

    std::vector<std::vector<llama_token>> tokens(candidates.size());
    for (size_t i = 0; i < candidates.size(); ++i) {
        if (!m.tokenize(candidates[i], &tokens[i])) return Status::Failed;
        (*tokenCounts)[i] = static_cast<int>(tokens[i].size());
    }

    if (want != m.prefix) {
        const Status status = m.loadPrefix(want);
        if (status != Status::Ok) return status;
    }
    const int base = static_cast<int>(m.prefix.size());
    const int room = std::min(kBatchTokens, kContextCells - base);
    llama_memory_t memory = llama_get_memory(m.ctx);

    size_t next = 0;
    while (next < candidates.size()) {
        const size_t first = next;
        int used = 0;
        while (next < candidates.size() && used + static_cast<int>(tokens[next].size()) <= room) {
            used += static_cast<int>(tokens[next].size());
            ++next;
        }
        if (next == first) {
            if (!tokens[first].empty()) return Status::Failed;
            ++next;
            continue;
        }
        m.batch.n_tokens = 0;
        std::vector<std::vector<int>> outputAt(next - first);
        for (size_t c = first; c < next; ++c) {
            const auto & toks = tokens[c];
            const int seq = static_cast<int>(c - first);
            for (size_t k = 0; k < toks.size(); ++k) {
                const bool output = k + 1 < toks.size();
                if (output) outputAt[c - first].push_back(m.batch.n_tokens);
                addToken(m.batch, toks[k], base + static_cast<int>(k), seq, 1, output);
            }
        }
        if (m.batch.n_tokens > 0) {
            const Status status = m.decode();
            if (status != Status::Ok) return status;
        }
        for (size_t c = first; c < next; ++c) {
            const auto & toks = tokens[c];
            if (toks.empty()) continue;
            double sum = m.afterPrefix[toks[0]];
            for (size_t k = 1; k < toks.size(); ++k) {
                const float * logits = llama_get_logits_ith(m.ctx, outputAt[c - first][k - 1]);
                if (logits == nullptr) {
                    m.reset();
                    return Status::Failed;
                }
                sum += logprobOf(logits, m.nVocab, toks[k]);
            }
            (*logprobs)[c] = sum;
        }
        for (size_t c = first; c < next; ++c) {
            if (!llama_memory_seq_rm(memory, static_cast<llama_seq_id>(c - first), base, -1)) {
                m.reset();
                return Status::Failed;
            }
        }
    }
    return Status::Ok;
}

}  // namespace aegis
