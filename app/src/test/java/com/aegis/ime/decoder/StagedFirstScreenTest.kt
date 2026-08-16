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
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StagedFirstScreenTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

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

    private fun learningChain(vararg chain: Pair<String, String>): UserLearning {
        val now = 1_700_000_000_000L
        val learning = UserLearning { now }
        repeat(8) {
            var prev: String? = null
            for ((ch, reading) in chain) {
                learning.observeCommit(prev, ch, reading, now)
                prev = ch
            }
            learning.observeBreak()
        }
        return learning
    }

    @Test fun anAutoLearnedWordNeverLeadsTheReadingExactDictionaryWord() {
        val learning = learningChain("悯" to "min", "意" to "yi")
        assertTrue("the chain forms the learned word", "悯意" in learning.formedWordsFor("minyi"))
        val decoded = words(
            PinyinDecoder(minYiFixture(), userLearning = learning).decodeCoveredAtomic("minyi", 30, setOf(3)),
        )
        assertEquals("the reading-exact dictionary word keeps the lead", "民意", decoded.first())
        val at = decoded.indexOf("悯意")
        assertTrue(
            "the auto-learned word stays on the first screen behind it, was $decoded",
            at in 1..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
        )
    }

    @Test fun aWordTheUserTaughtStaysReachableBehindTheDictionaryWord() {
        val learning = learningChain("悯" to "min", "意" to "yi")
        val decoded = words(
            PinyinDecoder(
                minYiFixture(),
                userModel = userModel("minyi" to "悯意"),
                userLearning = learning,
            ).decodeCoveredAtomic("minyi", 30, setOf(3)),
        )
        assertEquals("the dictionary word of this reading keeps the lead", "民意", decoded.first())
        val at = decoded.indexOf("悯意")
        assertTrue("a word the user committed stays on the first screen, was $decoded", at in 1..8)
    }

    @Test fun theSentenceCoveringEveryConfirmedReadingLeadsTheFirstScreen() {
        val decoder = productionLetterDecoder()
        val staged = decoder.decodeCoveredAtomic("nidepingguo", 60, setOf(2, 4, 8))
        assertEquals("the sentence covering every locked reading leads", "你的苹果", staged.first().word)
        assertEquals("and it covers every letter of the reading", 11, staged.first().coveredLen)
        val firstSingle = staged.indexOfFirst { isSingleChar(it.word) }
        assertTrue(
            "the first reading singles still start inside the real word slots, was $firstSingle",
            firstSingle in 1..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
        )
    }

    private fun userModel(vararg entries: Pair<String, String>): UserModel =
        UserModel().apply { for ((reading, word) in entries) recordWord(reading, word, 1L, incrementCount = true) }

    @Test fun learnedWordsCompeteForTheRealWordSlots() {
        val fixture = minYiFixture()
        val without = words(PinyinDecoder(fixture).decodeCoveredAtomic("minyi", 30, setOf(3)))
        assertTrue("the learned word is absent without a user model", "悯意" !in without)

        val decoded = words(PinyinDecoder(fixture, userModel = userModel("minyi" to "悯意")).decodeCoveredAtomic("minyi", 30, setOf(3)))
        val at = decoded.indexOf("悯意")
        val firstSingle = decoded.indexOfFirst { isSingleChar(it) }
        assertTrue("the learned word is recalled, was $decoded", at >= 0)
        assertTrue("the learned word sits in the real word segment, was at $at of $firstSingle", at < firstSingle)
        assertEquals("the real word segment keeps its eight slots", 8, firstSingle)
    }

    @Test fun aLearnedWordTheDictionaryAlsoHoldsAppearsOnce() {
        val decoded = words(
            PinyinDecoder(minYiFixture(), userModel = userModel("minyi" to "闵艺"))
                .decodeCoveredAtomic("minyi", 30, setOf(3)),
        )
        assertEquals("the word appears once, was $decoded", 1, decoded.count { it == "闵艺" })
        assertTrue("it stays in the real word segment", decoded.indexOf("闵艺") < decoded.indexOfFirst { isSingleChar(it) })
    }

    @Test fun aLearnedWordThatIsAlsoTheBestSentenceTakesARealWordSlot() {
        val rows = ArrayList<EngineFixture.Row>()
        listOf("民" to 900, "敏" to 850, "闽" to 800).forEach { rows.add(EngineFixture.Row("min", it.first, it.second)) }
        listOf("意" to 900, "艺" to 700).forEach { rows.add(EngineFixture.Row("yi", it.first, it.second)) }
        val fixture = EngineFixture.build(rows)
        val without = words(PinyinDecoder(fixture).decodeCoveredAtomic("minyi", 30, setOf(3)))
        assertEquals("the glued best sentence leads without a user model", "民意", without.first())
        assertEquals("the first reading singles follow it", "民", without[1])

        val decoded = words(
            PinyinDecoder(fixture, userModel = userModel("minyi" to "民意")).decodeCoveredAtomic("minyi", 30, setOf(3)),
        )
        assertEquals("the learned word leads the real word segment, was $decoded", "民意", decoded.first())
        assertEquals("the singles follow it", "民", decoded[1])
    }

    private fun productionLetterDecoder(): PinyinDecoder {
        assumeTrue(
            "26-key dict + LM + jianpin assets present",
            FullDictTestAssets.available(dictFile, lmFile, jianpinFile),
        )
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private fun productionDigitDecoder(): PinyinDecoder {
        assumeTrue("9-key dict + LM assets present", FullDictTestAssets.available(t9File, lmFile))
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    private fun cutsOf(parts: List<String>): Set<Int> {
        val out = HashSet<Int>()
        var acc = 0
        for (part in parts.dropLast(1)) { acc += part.length; out.add(acc) }
        return out
    }

    private val letterScenarios = listOf(
        listOf("wan", "shi", "wang", "lian"),
        listOf("wo", "men", "yi", "qi"),
        listOf("jin", "tian", "tian", "qi"),
        listOf("zhang", "wei", "ming"),
    )

    private val digitScenarios = listOf(
        listOf("926", "744", "9264", "5426"),
        listOf("96", "636", "94", "74"),
        listOf("546", "8426", "8426", "74"),
        listOf("94264", "934", "6464"),
    )

    @Test fun productionLockedReadingsPutTheFirstReadingSinglesOnTheFirstScreen() {
        val decoder = productionLetterDecoder()
        val dict = BinaryDict.fromFile(dictFile)
        for (readings in letterScenarios) {
            val key = readings.joinToString("")
            val cuts = cutsOf(readings)
            val cands = decoder.decodeCoveredAtomic(key, 30, cuts)
            val decoded = words(cands)
            val firstSingle = decoded.indexOfFirst { isSingleChar(it) }
            assertTrue(
                "$key: the first single must reach the first screen, was $firstSingle in ${decoded.take(12)}",
                firstSingle in 0..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
            )
            assertEquals("$key: the sentence covering every reading leads", key.length, cands.first().coveredLen)
            val bounds = decoder.syllables(key, cuts).map { it.end }
            for (lead in decoded.subList(1, firstSingle.coerceAtLeast(1))) {
                val covered = bounds.any { end -> dict.exact(key.substring(0, end)).any { it.word == lead } }
                assertTrue("$key: $lead ahead of the singles must be a dictionary word", covered)
            }
            assertEquals(
                "$key: the list holds every dictionary single of the first reading and no other",
                dict.exact(readings.first()).filter { isSingleChar(it.word) }.map { it.word }.toSet(),
                decoded.filter { isSingleChar(it) }.toSet(),
            )
            val closing = decoded.takeLastWhile { isSingleChar(it) }
            assertTrue("$key: the list closes on single characters", closing.isNotEmpty())
            assertTrue(
                "$key: every multi-char candidate precedes the closing run, was ${decoded.size - closing.size}",
                decoded.indexOfLast { !isSingleChar(it) } < decoded.size - closing.size,
            )
        }
    }

    @Test fun productionStagingKeepsEveryCandidateOnBothKeyboards() {
        val letters = productionLetterDecoder()
        val digits = productionDigitDecoder()
        for (index in letterScenarios.indices) {
            for ((decoder, parts) in listOf(letters to letterScenarios[index], digits to digitScenarios[index])) {
                val key = parts.joinToString("")
                val cuts = cutsOf(parts)
                val staged = decoder.decodeCoveredAtomic(key, 30, cuts)
                val plain = decoder.decodeCovered(key, 30, cuts)
                assertEquals("$key: staging must keep the candidate count", plain.size, staged.size)
                assertEquals("$key: staging must not drop or invent a candidate", plain.toSet(), staged.toSet())
            }
        }
    }

    @Test fun productionSeparatorInputStagesLikeTheLockedReadings() {
        val decoder = productionLetterDecoder()
        for (readings in letterScenarios) {
            val separated = decoder.decodeCovered(readings.joinToString("'"), 30)
            val locked = decoder.decodeCoveredAtomic(readings.joinToString(""), 30, cutsOf(readings))
            assertEquals(
                "${readings.joinToString("'")}: the separator path offers the locked candidates",
                words(locked),
                words(separated),
            )
        }
    }
}
