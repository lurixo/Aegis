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

import org.json.JSONObject

data class NeuralModelSpec(
    val name: String,
    val alphaLetters: Double,
    val alphaNine: Double,
    val candidates: Int,
) {
    fun alpha(nine: Boolean): Double = if (nine) alphaNine else alphaLetters

    companion object {
        const val MAX_CANDIDATES = 16

        fun parse(json: String): NeuralModelSpec {
            val o = JSONObject(json)
            val rerank = o.getJSONObject("rerank")
            return NeuralModelSpec(
                name = o.getString("name"),
                alphaLetters = rerank.getDouble("alphaLetters"),
                alphaNine = rerank.getDouble("alphaNine"),
                candidates = rerank.optInt("candidates", 10).coerceIn(2, MAX_CANDIDATES),
            )
        }
    }
}
