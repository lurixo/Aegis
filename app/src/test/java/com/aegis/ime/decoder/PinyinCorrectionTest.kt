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

package com.aegis.ime.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinCorrectionTest {

    @Test fun acceptable_distinguishes_full_partial_and_broken_inputs() {
        assertTrue(PinyinCorrection.fullySegmentable("zhongguo"))
        assertTrue(PinyinCorrection.fullySegmentable("64426"))
        assertFalse(PinyinCorrection.fullySegmentable("zhonguo"))
        assertFalse(PinyinCorrection.fullySegmentable("77"))
        assertTrue(PinyinCorrection.acceptable("zhongg"))
        assertTrue(PinyinCorrection.acceptable("zh"))
        assertTrue(PinyinCorrection.acceptable("644"))
        assertFalse(PinyinCorrection.fullySegmentable("644"))
        assertFalse(PinyinCorrection.acceptable("6444"))
        assertFalse(PinyinCorrection.acceptable("zhonguo"))
        assertFalse(PinyinCorrection.acceptable("nih1"))
        assertFalse(PinyinCorrection.acceptable(""))
        assertEquals(PinyinCorrection.Split("zhong", "g"), PinyinCorrection.partialSplit("zhongg"))
        assertEquals(PinyinCorrection.Split("", "zh"), PinyinCorrection.partialSplit("zh"))
        assertEquals(PinyinCorrection.Split("ni", "h"), PinyinCorrection.partialSplit("nih"))
    }

    @Test fun keyboard_adjacency_matches_the_qwerty_and_nine_key_layouts() {
        for ((a, b) in listOf('g' to 't', 'g' to 'y', 'g' to 'f', 'g' to 'h', 'g' to 'v', 'g' to 'b', 'q' to 'a', 'p' to 'l', 'm' to 'k')) {
            assertTrue("$a should neighbour $b", PinyinCorrection.isNeighbour(a, b))
            assertTrue("$b should neighbour $a", PinyinCorrection.isNeighbour(b, a))
        }
        for ((a, b) in listOf('g' to 'r', 'g' to 'n', 'q' to 's', 'z' to 'q', 'a' to 'a')) {
            assertFalse("$a should not neighbour $b", PinyinCorrection.isNeighbour(a, b))
        }
        val nineKey = mapOf(
            '2' to "35", '3' to "26", '4' to "57", '5' to "2468",
            '6' to "359", '7' to "48", '8' to "579", '9' to "68",
        )
        for ((k, near) in nineKey) for (d in "23456789") {
            assertEquals("$k -> $d", d in near, PinyinCorrection.isNeighbour(k, d))
        }
    }

    @Test fun variants_are_acceptable_deduplicated_and_ordered_by_penalty() {
        val vs = PinyinCorrection.variants("zhonguo")
        assertTrue(vs.isNotEmpty())
        assertTrue(vs.size <= PinyinCorrection.DEFAULT_LIMIT)
        assertEquals(vs.size, vs.map { it.input }.toSet().size)
        assertTrue(vs.none { it.input == "zhonguo" })
        assertTrue(vs.all { PinyinCorrection.acceptable(it.input) })
        assertEquals(vs.map { it.edit.penalty }, vs.map { it.edit.penalty }.sorted())
        val zhongguo = vs.first { it.input == "zhongguo" }
        assertEquals(PinyinCorrection.Edit.INSERT, zhongguo.edit)
        assertTrue(vs.first().edit.penalty <= zhongguo.edit.penalty)
    }

    @Test fun transposition_ranks_ahead_of_insertion_and_near_substitution_ahead_of_far() {
        val transposed = PinyinCorrection.variants("zhognguo")
        assertEquals("zhongguo", transposed.first().input)
        assertEquals(PinyinCorrection.Edit.TRANSPOSE, transposed.first().edit)

        val near = PinyinCorrection.variants("nihap")
        val nihao = near.first { it.input == "nihao" }
        assertEquals(PinyinCorrection.Edit.SUBSTITUTE_NEAR, nihao.edit)
        assertTrue("far substitutions stay disabled by default", near.none { it.edit == PinyinCorrection.Edit.SUBSTITUTE_FAR })
        val withFar = PinyinCorrection.variants("nihap", includeFarSubstitutions = true)
        val far = withFar.firstOrNull { it.input == "nihan" }
        if (far != null) assertEquals(PinyinCorrection.Edit.SUBSTITUTE_FAR, far.edit)
    }

    @Test fun nine_key_variants_stay_in_digit_space() {
        val vs = PinyinCorrection.variants("94664476")
        assertTrue(vs.isNotEmpty())
        assertTrue(vs.all { v -> v.input.all { it in '2'..'9' } })
        assertTrue(vs.all { PinyinCorrection.acceptable(it.input) })
        val near = vs.first { it.input == "94664486" }
        assertEquals(PinyinCorrection.Edit.SUBSTITUTE_NEAR, near.edit)
        val transposed = PinyinCorrection.variants("96464486").first()
        assertEquals("94664486", transposed.input)
        assertEquals(PinyinCorrection.Edit.TRANSPOSE, transposed.edit)
    }

    @Test fun length_bounds_mixed_input_and_limit_are_respected() {
        assertTrue(PinyinCorrection.variants("z").isEmpty())
        assertTrue(PinyinCorrection.variants("zhongguorenminab").isEmpty())
        assertTrue(PinyinCorrection.variants("ni2").isEmpty())
        assertTrue(PinyinCorrection.variants("zhonguo", 0).isEmpty())
        assertEquals(4, PinyinCorrection.variants("zhonguo", 4).size)
    }
}
