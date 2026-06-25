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
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserWordOriginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L

    private val reading = "bielun"
    private val digitKey = "243586"
    private val word = "别轮"
    private val dictWord = "别论"
    private val digitDictWord = "车轮"

    private val letterUses = 96
    private val digitUses = 500

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun letters(um: UserModel) = PinyinDecoder(
        BinaryDict.fromFile(dictFile),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        initialsDict = BinaryDict.fromFile(jianpinFile),
    )

    private fun digits(um: UserModel) = PinyinDecoder(
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        aliasDict = BinaryDict.fromFile(dictFile),
    )

    private fun byHand(times: Int, r: String = reading, w: String = word) =
        UserModel { clock }.apply { repeat(times) { addManualWord(r, w, clock) } }

    private fun recorded(times: Int, r: String = reading, w: String = word) =
        UserModel { clock }.apply { repeat(times) { recordWord(r, w, clock, incrementCount = true) } }

    private fun onLetters(um: UserModel) = letters(um).decodeCovered(reading, 40).map { it.word }

    private fun onDigits(um: UserModel) = digits(um).decodeCovered(digitKey, 40).map { it.word }

    @Test fun aWordTheUserAddedByHandTakesTheLeadOnBothKeyboards() {
        assets()
        val letterList = onLetters(byHand(letterUses))
        assertEquals("26-key: the word the user added by hand leads, was ${letterList.take(4)}", word, letterList.first())
        val digitList = onDigits(byHand(digitUses))
        assertEquals("9-key: the word the user added by hand leads, was ${digitList.take(4)}", word, digitList.first())
    }

    @Test fun theVerySameWordRecordedFromTypingStaysBehindTheDictionaryWord() {
        assets()
        val letterList = onLetters(recorded(letterUses))
        assertEquals("26-key: the dictionary word leads, was ${letterList.take(4)}", dictWord, letterList.first())
        assertEquals("26-key: the recorded word keeps the place right behind it", 1, letterList.indexOf(word))
        val digitList = onDigits(recorded(digitUses))
        assertEquals("9-key: the dictionary word leads, was ${digitList.take(4)}", digitDictWord, digitList.first())
        assertTrue(
            "9-key: the leader really is a dictionary word of the key",
            BinaryDict.fromFile(t9File).exact(digitKey).any { it.word == digitDictWord },
        )
        assertEquals("9-key: the recorded word keeps the place right behind it", 1, digitList.indexOf(word))
    }

    @Test fun theWordTheUserAddedByHandClimbsAsItIsUsed() {
        assets()
        val early = onLetters(byHand(1))
        val late = onLetters(byHand(letterUses))
        assertEquals("one use is not enough yet, was ${early.take(4)}", 1, early.indexOf(word))
        assertEquals("using it more brings it to the front, was ${late.take(4)}", 0, late.indexOf(word))
    }

    @Test fun oneReadingCanHoldBothKindsAtOnce() {
        assets()
        val other = "憋轮"
        val um = UserModel { clock }.apply {
            repeat(letterUses) { addManualWord(reading, word, clock) }
            repeat(letterUses) { recordWord(reading, other, clock, incrementCount = true) }
        }
        val got = onLetters(um)
        assertEquals("the word added by hand leads, was ${got.take(4)}", 0, got.indexOf(word))
        assertEquals("the dictionary word follows it", 1, got.indexOf(dictWord))
        assertTrue("the recorded one stays behind the dictionary word, was ${got.take(4)}", got.indexOf(other) > 1)
    }

    @Test fun aStoreWrittenBeforeTheMarksExistedNeverBuysTheLead() {
        assets()
        val file = File(tmp.root, "userdb.txt")
        file.writeText("aegis-userdb 1\nW\t$word\t$letterUses\t$clock\nR\t$reading\t$word\n")
        val migrated = UserModel { clock }.apply { load(file) }
        val got = onLetters(migrated)
        assertEquals("the dictionary word still leads, was ${got.take(4)}", dictWord, got.first())
        assertEquals("the migrated word keeps the place right behind it", 1, got.indexOf(word))
    }

    @Test fun theLeadSurvivesTheRoundTripThroughTheFile() {
        assets()
        val file = File(tmp.root, "userdb.txt")
        byHand(letterUses).save(file)
        val got = onLetters(UserModel { clock }.apply { load(file) })
        assertEquals("the word the user added by hand still leads, was ${got.take(4)}", word, got.first())
    }

    @Test fun aWordAddedByHandKeepsTheLeadBesideARecordedWordOfTheSameReading() {
        assets()
        val um = UserModel { clock }.apply {
            repeat(500) { addManualWord("niwo", "泥卧", clock) }
            repeat(500) { recordWord("niwo", "泥沃", clock, incrementCount = true) }
        }
        val got = letters(um).decodeCovered("niwo", 40).map { it.word }
        assertEquals("26-key: the word added by hand leads, was ${got.take(4)}", 0, got.indexOf("泥卧"))
        assertTrue(
            "26-key: the recorded one stays behind the dictionary word, was ${got.take(4)}",
            got.indexOf("泥沃") > got.indexOf("你我"),
        )
        val nine = digits(um).decodeCovered("6496", 40).map { it.word }
        assertEquals("9-key: the word added by hand leads, was ${nine.take(4)}", 0, nine.indexOf("泥卧"))
    }

    @Test fun theMarkReachesTheCutAndLockedPathsToo() {
        assets()
        val hand = UserModel { clock }.apply { repeat(500) { addManualWord("nizhong", "泥重", clock) } }
        val auto = UserModel { clock }.apply {
            repeat(500) { recordWord("nizhong", "泥重", clock, incrementCount = true) }
        }
        val handDecoder = letters(hand)
        val cuts = handDecoder.syllables("nizhong").map { it.end }.dropLast(1).toSet()
        assertTrue("the reading really splits", cuts.isNotEmpty())
        val autoDecoder = letters(auto)
        for (path in listOf("cut", "locked")) {
            val h = (if (path == "cut") handDecoder.decodeCovered("nizhong", 40, cuts)
                else handDecoder.decodeCoveredAtomic("nizhong", 40, cuts)).map { it.word }
            val a = (if (path == "cut") autoDecoder.decodeCovered("nizhong", 40, cuts)
                else autoDecoder.decodeCoveredAtomic("nizhong", 40, cuts)).map { it.word }
            assertEquals("26-key/$path: the word added by hand leads, was ${h.take(4)}", 0, h.indexOf("泥重"))
            assertTrue("26-key/$path: the recorded word may not lead, was ${a.take(4)}", a.indexOf("泥重") > 0)
        }
    }

    @Test fun everyMarkReachesADecoderThatAlreadyBuiltItsIndex() {
        assets()
        val second = "biebu"
        val secondWord = "别部"
        val um = UserModel { clock }.apply {
            repeat(letterUses) { recordWord(reading, word, clock, incrementCount = true) }
            repeat(letterUses) { recordWord(second, secondWord, clock, incrementCount = true) }
        }
        val live = letters(um)
        fun rankOf(r: String, w: String) = live.decodeCovered(r, 40).map { it.word }.indexOf(w)
        assertEquals("the first recorded word starts behind the dictionary word", 1, rankOf(reading, word))
        assertTrue("so does the second", rankOf(second, secondWord) > 0)

        um.addManualWord(reading, word, clock)
        assertEquals("the first mark reaches the very same decoder", 0, rankOf(reading, word))
        assertTrue("and leaves the still unmarked one alone", rankOf(second, secondWord) > 0)

        um.addManualWord(second, secondWord, clock)
        assertEquals("a later mark reaches it too", 0, rankOf(second, secondWord))
        assertEquals("without losing the first", 0, rankOf(reading, word))
    }

    @Test fun theNineKeyMarkFollowsTheReadingsThatShareItsKey() {
        assets()
        val um = UserModel { clock }.apply {
            repeat(500) { addManualWord("miwo", "泥卧", clock) }
            repeat(500) { recordWord("niwo", "泥卧", clock, incrementCount = true) }
        }
        val onLetters = letters(um).decodeCovered("niwo", 40).map { it.word }
        assertTrue(
            "26-key: a reading the word was never added under gives it no lead, was ${onLetters.take(4)}",
            onLetters.indexOf("泥卧") > 0,
        )
        val onDigits = digits(um).decodeCovered("6496", 40).map { it.word }
        assertEquals(
            "9-key: the key reaches the reading it was added under, so it leads there, was ${onDigits.take(4)}",
            0,
            onDigits.indexOf("泥卧"),
        )
    }

    @Test fun deletingTheWordTakesTheLeadBackOnTheVerySameDecoder() {
        assets()
        val um = byHand(letterUses)
        val d = letters(um)
        assertEquals(word, d.decodeCovered(reading, 40).first().word)
        um.removeWord(reading, word)
        assertEquals(dictWord, d.decodeCovered(reading, 40).first().word)
    }
}
