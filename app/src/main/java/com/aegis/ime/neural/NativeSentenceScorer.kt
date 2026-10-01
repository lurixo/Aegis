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

package com.aegis.ime.neural

class NativeSentenceScorer private constructor(private var handle: Long) : SentenceScorer {

    override fun score(context: String, candidates: List<String>): DoubleArray? {
        val h = handle
        check(h != 0L) { "the scorer is closed" }
        val texts = Array(candidates.size) { candidates[it].encodeToByteArray() }
        return nativeScore(h, context.encodeToByteArray(), texts)
    }

    @Synchronized
    override fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeClose(h)
    }

    companion object {
        fun open(path: String, threads: Int): NativeSentenceScorer {
            if (!NeuralCpu.supported) throw UnsupportedOperationException("the CPU lacks the dot product and FP16 instructions")
            System.loadLibrary("aegis_neural")
            return NativeSentenceScorer(nativeOpenPath(path, threads))
        }

        @JvmStatic private external fun nativeOpenPath(path: String, threads: Int): Long

        @JvmStatic private external fun nativeScore(handle: Long, context: ByteArray, candidates: Array<ByteArray>): DoubleArray?

        @JvmStatic private external fun nativeCancel(handle: Long)

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
