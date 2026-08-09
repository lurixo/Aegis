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

package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class UserModelTest {

    @Test
    fun learnsBoostAndPredicts() {
        val m = UserModel()
        assertEquals(0.0, m.wordBoost("你好"), 0.0)
        m.record(null, "你好", 1000)
        m.record("你好", "世界", 1001)
        m.record("你好", "世界", 1002)
        m.record("你好", "啊", 1003)
        assertTrue("seen word gets positive boost", m.wordBoost("你好") > 0.0)
        assertEquals(listOf("世界", "啊"), m.successors("你好", 8))
        assertTrue(m.dirty)
    }

    @Test
    fun recentUseRaisesEqualFrequencyWordsAndSuccessorsThenDecays() {
        val day = 24L * 60L * 60L * 1000L
        var now = 60L * day
        val m = UserModel { now }
        m.record("前", "旧词", now - 28L * day)
        m.record("前", "新词", now)

        assertTrue(m.wordBoost("新词") > m.wordBoost("旧词"))
        assertEquals(listOf("新词", "旧词"), m.successors("前", 2))

        now += 140L * day
        assertTrue(abs(m.wordBoost("新词") - m.wordBoost("旧词")) < 0.001)
    }

    @Test
    fun malformedReloadLeavesTheLiveModelUnchanged() {
        val m = UserModel().apply { recordWord("ceshi", "测试", 10, incrementCount = true) }
        val malformed = File.createTempFile("userdb-bad", ".txt").apply {
            writeText("wrong header\nW\t毒化\t-2\t0\n")
        }

        assertThrows(IllegalArgumentException::class.java) { m.reload(malformed) }
        assertEquals(listOf("测试"), m.readingSnapshot()["ceshi"])
        assertTrue(m.wordBoost("测试").isFinite())
        malformed.delete()
    }

    @Test
    fun saveLoadRoundtrip() {
        val m = UserModel()
        m.record(null, "测试", 5)
        m.record("测试", "用例", 6)
        val f = File.createTempFile("userdb", ".txt")
        m.save(f)
        assertFalse("save clears dirty", m.dirty)
        val m2 = UserModel().apply { load(f) }
        assertEquals(m.wordBoost("测试"), m2.wordBoost("测试"), 1e-9)
        assertEquals(listOf("用例"), m2.successors("测试", 8))
        f.delete()
    }

    @Test
    fun importOverwriteReplaces() {
        val userDb = File.createTempFile("userdb", ".txt")
        UserModel().apply { record(null, "旧", 1); record("旧", "话", 1) }.save(userDb)
        val importFile = File.createTempFile("imp", ".txt")
        UserModel().apply { record(null, "新", 3); record("新", "词", 3) }.save(importFile)

        UserModel().apply { load(importFile) }.save(userDb)

        val reloaded = UserModel().apply { load(userDb) }
        assertEquals("overwrite drops old entries", 0.0, reloaded.wordBoost("旧"), 0.0)
        assertTrue("overwrite keeps imported entries", reloaded.wordBoost("新") > 0.0)
        assertEquals(listOf("词"), reloaded.successors("新", 8))
        assertTrue("no old successors survive", reloaded.successors("旧", 8).isEmpty())
        userDb.delete(); importFile.delete()
    }

    @Test
    fun importMerges() {
        val a = UserModel().apply { record(null, "词", 1) }
        val b = UserModel().apply { record(null, "词", 1); record("词", "条", 1) }
        val f = File.createTempFile("udb", ".txt")
        b.save(f)
        val before = a.wordBoost("词")
        a.importFrom(f, 9)
        assertTrue("merged count raises boost", a.wordBoost("词") > before)
        assertEquals(listOf("条"), a.successors("词", 8))
        f.delete()
    }

    @Test
    fun recordWord_indexesReadingAndBoosts() {
        val m = UserModel()
        m.recordWord("ceshi", "测试", 10, incrementCount = true)
        assertEquals("reading -> word is indexed", listOf("测试"), m.readingSnapshot()["ceshi"])
        assertTrue("a recorded self-created word is boosted", m.wordBoost("测试") > 0.0)
        assertFalse("model with a recall entry is not empty", m.isEmpty())
    }

    @Test
    fun recordWord_incrementCountFalse_stillGivesABoostButDoesNotDoubleCount() {
        val m = UserModel()
        m.record(null, "测试", 10)
        val boostAfterOne = m.wordBoost("测试")
        m.recordWord("ceshi", "测试", 11, incrementCount = false)
        assertEquals("recall entry added", listOf("测试"), m.readingSnapshot()["ceshi"])
        assertEquals("count not double-bumped", boostAfterOne, m.wordBoost("测试"), 1e-9)
    }

    @Test
    fun readingEntries_persistAcrossSaveLoad() {
        val m = UserModel { 10L }
        m.recordWord("ceshi", "测试", 1, incrementCount = true)
        m.recordWord("beijing", "北京", 2, incrementCount = true)
        val f = File.createTempFile("userdb-r", ".txt")
        m.save(f)
        val loaded = UserModel { 10L }.apply { load(f) }
        assertEquals(listOf("测试"), loaded.readingSnapshot()["ceshi"])
        assertEquals(listOf("北京"), loaded.readingSnapshot()["beijing"])
        assertEquals("boost survives the round trip", m.wordBoost("测试"), loaded.wordBoost("测试"), 1e-9)
        f.delete()
    }

    @Test
    fun importMerges_readingEntries() {
        val userDb = UserModel().apply { recordWord("ceshi", "测试", 1, incrementCount = true) }
        val other = UserModel().apply { recordWord("ceyong", "测用", 2, incrementCount = true) }
        val f = File.createTempFile("imp-r", ".txt")
        other.save(f)
        userDb.importFrom(f, 3)
        assertEquals(listOf("测试"), userDb.readingSnapshot()["ceshi"])
        assertEquals("imported recall entry merged", listOf("测用"), userDb.readingSnapshot()["ceyong"])
        f.delete()
    }

    @Test
    fun addManualWord_thenRemove_reflectsInEntriesAndRecall() {
        val m = UserModel()
        m.addManualWord("ceshi", "测试", 100)
        m.addManualWord("BEI'jing", "北京", 101)
        assertEquals(listOf("北京"), m.readingSnapshot()["beijing"])
        assertTrue("manual word boosted", m.wordBoost("测试") > 0.0)
        val entries = m.userWordEntries().map { it.word }
        assertTrue("both manual words listed", entries.containsAll(listOf("测试", "北京")))

        m.removeWord("测试")
        assertEquals("removed word gone from recall", null, m.readingSnapshot()["ceshi"])
        assertEquals("removed word no longer boosted", 0.0, m.wordBoost("测试"), 0.0)
        assertFalse("removed word not listed", m.userWordEntries().any { it.word == "测试" })
    }

    @Test
    fun blankReadingManualAdd_boostsButDoesNotIndexRecall() {
        val m = UserModel()
        m.addManualWord("   ", "孤词", 1)
        assertTrue("still boosted", m.wordBoost("孤词") > 0.0)
        assertTrue("no recall entry without a reading", m.readingSnapshot().isEmpty())
    }

    @Test
    fun wordsWithDelimiterCharsAreRejected_soTheFileFormatCannotCorrupt() {
        val m = UserModel()
        m.recordWord("ceshi", "测\t试", 1, incrementCount = true)
        m.addManualWord("beijing", "北\n京", 2)
        assertTrue("delimiter-bearing words are not stored", m.readingSnapshot().isEmpty())
        assertEquals("and carry no boost", 0.0, m.wordBoost("测\t试"), 0.0)
    }

    @Test
    fun a_word_the_file_format_cannot_hold_is_reported_as_not_added() {
        val m = UserModel()
        assertFalse("a word carrying a delimiter can never be stored", m.addManualWord("ceshi", "测\t试", 1))
        assertFalse("nor can one longer than the format holds", m.addManualWord("ceshi", "词".repeat(257), 2))
        assertTrue("a word that was refused leaves nothing behind", m.isEmpty())
        assertFalse("and queues nothing for the next save", m.dirty)
        assertTrue("a word that does fit is still added", m.addManualWord("ceshi", "词".repeat(256), 3))
    }

    @Test
    fun a_reading_too_long_to_store_is_reported_as_not_added_and_leaves_no_half_entry() {
        val m = UserModel()
        assertFalse(m.addManualWord("a".repeat(257), "测试", 1))
        assertTrue("a reading that cannot be stored indexes nothing", m.readingSnapshot().isEmpty())
        assertEquals("and the word behind it is not half written either", 0.0, m.wordBoost("测试"), 0.0)
        assertTrue(m.isEmpty())
        assertFalse(m.dirty)
    }

    @Test
    fun readingScopedRemove_keepsOtherReadingsOfSameWord() {
        val m = UserModel()
        m.recordWord("chang", "长", 1, incrementCount = true)
        m.recordWord("zhang", "长", 2, incrementCount = true)
        m.removeWord("chang", "长")
        assertEquals("only the named reading drops", null, m.readingSnapshot()["chang"])
        assertEquals("the other reading survives", listOf("长"), m.readingSnapshot()["zhang"])
        assertTrue("boost kept while a reading still recalls it", m.wordBoost("长") > 0.0)
        m.removeWord("zhang", "长")
        assertEquals("boost gone once no reading recalls it", 0.0, m.wordBoost("长"), 0.0)
    }
}
