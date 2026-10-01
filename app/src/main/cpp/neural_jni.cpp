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


#include <jni.h>

#include <string>
#include <vector>

#include "neural_scorer.h"

namespace {

void throwState(JNIEnv * env, const std::string & message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string utf8(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

aegis::NeuralScorer * scorerOf(jlong handle) {
    return reinterpret_cast<aegis::NeuralScorer *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aegis_ime_neural_NativeSentenceScorer_nativeOpenPath(JNIEnv * env, jclass, jstring path, jint threads) {
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    std::string error;
    aegis::NeuralScorer * scorer = aegis::NeuralScorer::openPath(chars, threads, &error);
    env->ReleaseStringUTFChars(path, chars);
    if (scorer == nullptr) {
        throwState(env, error.empty() ? std::string("could not load the model") : error);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(scorer));
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_aegis_ime_neural_NativeSentenceScorer_nativeScore(
        JNIEnv * env, jclass, jlong handle, jbyteArray context, jobjectArray candidates) {
    aegis::NeuralScorer * scorer = scorerOf(handle);
    const jsize count = env->GetArrayLength(candidates);
    std::vector<std::string> texts;
    texts.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto * item = static_cast<jbyteArray>(env->GetObjectArrayElement(candidates, i));
        texts.push_back(utf8(env, item));
        env->DeleteLocalRef(item);
    }
    std::vector<double> logprobs;
    std::vector<int> counts;
    const auto status = scorer->score(utf8(env, context), texts, &logprobs, &counts);
    if (status == aegis::NeuralScorer::Status::Aborted) return nullptr;
    if (status != aegis::NeuralScorer::Status::Ok) {
        throwState(env, "scoring failed");
        return nullptr;
    }
    jdoubleArray out = env->NewDoubleArray(count);
    if (out != nullptr) env->SetDoubleArrayRegion(out, 0, count, logprobs.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aegis_ime_neural_NativeSentenceScorer_nativeCancel(JNIEnv *, jclass, jlong handle) {
    scorerOf(handle)->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aegis_ime_neural_NativeSentenceScorer_nativeClose(JNIEnv *, jclass, jlong handle) {
    delete scorerOf(handle);
}
