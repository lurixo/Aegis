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
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

class BeamTopSelectionTest {

    private class Item(val score: Double)

    private fun assertMatchesFullSort(items: List<Item>, n: Int) {
        val expected = items.sortedByDescending { it.score }.take(n)
        val actual = PinyinDecoder.topByDescending(items, n) { it.score }
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertSame("n=$n index=$i", expected[i], actual[i])
    }

    @Test
    fun random_scores_match_full_sort() {
        val random = Random(20261018)
        repeat(3_000) {
            val size = random.nextInt(0, 60)
            val items = List(size) { Item(random.nextDouble(-200.0, 0.0)) }
            for (n in listOf(0, 1, 2, 5, PinyinDecoder.BEAM_W, size, size + 3)) assertMatchesFullSort(items, n)
        }
    }

    @Test
    fun tied_scores_keep_insertion_order() {
        val random = Random(7)
        val pool = doubleArrayOf(-3.0, -2.5, -2.5, -1.0, 0.0, -0.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)
        repeat(3_000) {
            val size = random.nextInt(0, 40)
            val items = List(size) { Item(pool[random.nextInt(pool.size)]) }
            for (n in 0..16) assertMatchesFullSort(items, n)
        }
        val same = List(30) { Item(-1.0) }
        assertEquals(same.take(PinyinDecoder.BEAM_W), PinyinDecoder.topByDescending(same, PinyinDecoder.BEAM_W) { it.score })
    }

    @Test
    fun map_entries_match_full_sort() {
        val random = Random(99)
        repeat(500) {
            val map = LinkedHashMap<Int, MutableList<Double>>()
            repeat(random.nextInt(0, 50)) { map[random.nextInt(1_000)] = mutableListOf(random.nextInt(-20, 0) / 2.0) }
            val expected = map.entries.sortedByDescending { it.value[0] }.take(PinyinDecoder.BEAM_W).map { it.key }
            val actual = PinyinDecoder.topByDescending(map.entries, PinyinDecoder.BEAM_W) { it.value[0] }.map { it.key }
            assertEquals(expected, actual)
        }
    }
}
