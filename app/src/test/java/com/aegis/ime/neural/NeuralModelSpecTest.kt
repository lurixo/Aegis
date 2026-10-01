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

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralModelSpecTest {

    private fun manifest(rerank: String) =
        """{"schema":1,"id":"m","name":"Qwen3","file":{"name":"m.gguf"},"rerank":$rerank}"""

    @Test fun parses_the_name_and_the_rerank_parameters_of_the_manifest() {
        val parsed = NeuralModelSpec.parse(manifest("""{"alphaLetters":5,"alphaNine":3.5,"candidates":8}"""))
        assertEquals("Qwen3", parsed.name)
        assertEquals(5.0, parsed.alpha(nine = false), 0.0)
        assertEquals(3.5, parsed.alpha(nine = true), 0.0)
        assertEquals(8, parsed.candidates)
    }

    @Test fun clamps_the_candidate_count() {
        assertEquals(NeuralModelSpec.MAX_CANDIDATES, NeuralModelSpec.parse(manifest("""{"alphaLetters":1,"alphaNine":1,"candidates":40}""")).candidates)
        assertEquals(2, NeuralModelSpec.parse(manifest("""{"alphaLetters":1,"alphaNine":1,"candidates":0}""")).candidates)
        assertEquals(10, NeuralModelSpec.parse(manifest("""{"alphaLetters":1,"alphaNine":1}""")).candidates)
    }

    @Test fun a_manifest_without_rerank_parameters_is_rejected() {
        assertThrows(JSONException::class.java) { NeuralModelSpec.parse("""{"schema":1,"name":"Qwen3"}""") }
    }
}
