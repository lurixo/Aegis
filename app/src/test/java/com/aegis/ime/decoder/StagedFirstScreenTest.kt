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

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFirstScreenTest {

    private fun isSingleChar(word: String) = word.codePointCount(0, word.length) == 1

    private fun words(cands: List<Cand>) = cands.map { it.word }

    private fun minYiFixture(): BinaryDict {
        val rows = ArrayList<EngineFixture.Row>()
        listOf("民" to 900, "敏" to 850, "闽" to 800, "闵" to 700, "皿" to 600, "悯" to 500)
            .forEach { rows.add(EngineFixture.Row("min", it.first, it.second)) }
        listOf("意" to 900, "一" to 880, "以" to 860, "艺" to 700)
            .forEach { rows.add(EngineFixture.Row("yi", it.first, it.second)) }
        listOf(
            "民意" to 990, "民艺" to 980, "敏意" to 970, "敏艺" to 960, "闽意" to 950,
            "闽艺" to 940, "闵意" to 930, "闵艺" to 920, "皿意" to 910, "悯艺" to 905,
        ).forEach { rows.add(EngineFixture.Row("minyi", it.first, it.second)) }
        rows.add(EngineFixture.Row("minyihao", "民意好", 100))
        listOf("好" to 900, "号" to 800).forEach { rows.add(EngineFixture.Row("hao", it.first, it.second)) }
        return EngineFixture.build(rows)
    }

    private fun engineFixtureDecoder() = PinyinDecoder(EngineFixture.dict())

    @Test fun theRealWordSegmentLeadsAndTheFirstReadingSinglesFollowIt() {
        val decoded = PinyinDecoder(minYiFixture()).decodeCoveredAtomic("minyi", 30, setOf(3))
        assertEquals(
            "the eight highest ranked dictionary words take the real word segment",
            listOf("民意", "民艺", "敏意", "敏艺", "闽意", "闽艺", "闵意", "闵艺"),
            words(decoded).take(8),
        )
        assertEquals(
            "every dictionary single of the first reading follows the real words",
            listOf("民", "敏", "闽", "闵", "皿", "悯"),
            words(decoded).subList(8, 14),
        )
        assertEquals("the first single sits right behind the eight slots", 8, words(decoded).indexOfFirst { isSingleChar(it) })
    }

    @Test fun realWordsBeyondTheEightSlotsStayReachableBehindTheSingles() {
        val decoded = words(PinyinDecoder(minYiFixture()).decodeCoveredAtomic("minyi", 30, setOf(3)))
        for (overflow in listOf("皿意", "悯艺")) {
            val at = decoded.indexOf(overflow)
            assertTrue("$overflow stays reachable, was $decoded", at >= 0)
            assertTrue("$overflow follows the single segment, was at $at", at > 13)
        }
    }

    @Test fun wordsCoveringEveryConfirmedReadingOutrankShorterDictionaryWords() {
        val decoded = words(PinyinDecoder(minYiFixture()).decodeCoveredAtomic("minyihao", 30, setOf(3, 5)))
        assertEquals("the full cover leads the real word segment", "民意好", decoded.first())
        assertTrue(
            "the shorter dictionary words follow it inside the same segment, was $decoded",
            decoded.subList(1, 8).containsAll(listOf("民意", "民艺", "敏意")),
        )
        assertEquals("the single segment still starts right behind the eight slots", 8, decoded.indexOfFirst { isSingleChar(it) })
    }

    @Test fun stagingKeepsEveryCandidateAndItsCoverage() {
        val decoder = PinyinDecoder(minYiFixture())
        val staged = decoder.decodeCoveredAtomic("minyi", 30, setOf(3))
        val plain = decoder.decodeCovered("minyi", 30, setOf(3))
        assertEquals("staging must not change how many candidates the decode offers", plain.size, staged.size)
        assertEquals("staging must not drop or invent a candidate", plain.toSet(), staged.toSet())
    }

    @Test fun gluedCombinationsLeaveTheFirstScreenButStayInTheList() {
        val decoder = engineFixtureDecoder()
        val staged = words(decoder.decodeCoveredAtomic("diuzi", 30, setOf(3)))
        assertEquals("the glued full cover leads even when no dictionary word covers it", "丢字", staged.first())
        assertEquals("the first locked reading's singles follow it", "丢", staged[1])
    }

    @Test fun inputWithoutAConfirmedReadingBoundaryKeepsItsFirstScreen() {
        val decoder = engineFixtureDecoder()
        assertEquals(
            "letters with neither a lock nor a separator still lead with the best sentence",
            "丢字",
            decoder.decodeCoveredAtomic("diuzi", 30).first().word,
        )
        assertEquals(
            "a forced cut without a separator still leads with the best sentence",
            "丢字",
            decoder.decodeCovered("diuzi", 30, setOf(3)).first().word,
        )
    }

    @Test fun separatorCutReadingsStageLikeLockedOnes() {
        val decoder = engineFixtureDecoder()
        val separated = decoder.decodeCovered("diu'zi", 30)
        assertEquals("a typed separator confirms the reading boundary", "丢字", separated.first().word)
        assertEquals(
            "the separator path offers the locked first screen",
            words(decoder.decodeCoveredAtomic("diuzi", 30, setOf(3))),
            words(separated),
        )
    }
}
