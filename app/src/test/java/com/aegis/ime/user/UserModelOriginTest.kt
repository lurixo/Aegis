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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserModelOriginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L

    private fun db() = File(tmp.root, "userdb.txt")

    private fun model() = UserModel { clock }

    private fun reloaded(file: File) = model().apply { load(file) }

    @Test fun aWordAddedByHandIsMarkedAndAWordRecordedFromTypingIsNot() {
        val m = model()
        m.addManualWord("zwm", "张伟明", clock)
        m.recordWord("ninen", "你呢嗯", clock, incrementCount = true)
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
        assertEquals(
            "both words stay in the dictionary either way",
            setOf("张伟明", "你呢嗯"),
            m.userWordEntries().map { it.word }.toSet(),
        )
    }

    @Test fun theMarkSurvivesSaveAndLoad() {
        model().apply {
            addManualWord("zwm", "张伟明", clock)
            recordWord("ninen", "你呢嗯", clock, incrementCount = true)
        }.save(db())
        assertEquals(mapOf("zwm" to setOf("张伟明")), reloaded(db()).manualSnapshot())
    }

    @Test fun typingAWordTheUserAddedByHandKeepsTheMark() {
        val m = model().apply { addManualWord("zwm", "张伟明", clock) }
        repeat(20) { m.recordWord("zwm", "张伟明", clock, incrementCount = true) }
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
        m.save(db())
        assertEquals(mapOf("zwm" to setOf("张伟明")), reloaded(db()).manualSnapshot())
    }

    @Test fun addingByHandAWordThatWasOnlyRecordedBeforeMarksIt() {
        val m = model().apply { recordWord("zwm", "张伟明", clock, incrementCount = true) }
        assertTrue("recording alone leaves it unmarked", m.manualSnapshot().isEmpty())
        m.addManualWord("zwm", "张伟明", clock)
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
    }

    @Test fun theMarkIsPerReadingNotPerWord() {
        val m = model().apply {
            addManualWord("zwm", "张伟明", clock)
            recordWord("zhangweiming", "张伟明", clock, incrementCount = true)
        }
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
    }

    @Test fun deletingAWordDropsItsMarkOnBothRemovalPaths() {
        val one = model().apply { addManualWord("zwm", "张伟明", clock) }
        one.removeWord("zwm", "张伟明")
        assertTrue("removing the entry drops the mark", one.manualSnapshot().isEmpty())

        val two = model().apply { addManualWord("zwm", "张伟明", clock) }
        two.removeWord("张伟明")
        assertTrue("removing the word drops the mark", two.manualSnapshot().isEmpty())

        two.addManualWord("zwm", "张伟明", clock)
        two.removeWord("张伟明")
        two.save(db())
        assertTrue("nothing is left on disk either", reloaded(db()).manualSnapshot().isEmpty())
    }

    @Test fun aStoreWrittenBeforeTheMarksExistedCountsAsEntirelyAutomatic() {
        db().writeText("aegis-userdb 1\nW\t你呢嗯\t1\t$clock\nR\tninen\t你呢嗯\nR\tzwm\t张伟明\nW\t张伟明\t4\t$clock\n")
        val m = reloaded(db())
        assertTrue("an unmarked store carries no marks at all", m.manualSnapshot().isEmpty())
        assertEquals(
            "and it loses nothing",
            setOf("你呢嗯", "张伟明"),
            m.userWordEntries().map { it.word }.toSet(),
        )
    }

    @Test fun anOldStoreIsWrittenBackInTheMarkedFormat() {
        db().writeText("aegis-userdb 1\nW\t张伟明\t4\t$clock\nR\tzwm\t张伟明\n")
        val m = reloaded(db())
        m.addManualWord("yx", "我的邮箱", clock)
        m.save(db())
        val lines = db().readLines()
        assertEquals("aegis-userdb 2", lines.first())
        assertTrue("the word added by hand is marked", "M\tyx\t我的邮箱" in lines)
        assertFalse("the migrated one is not", "M\tzwm\t张伟明" in lines)
        assertEquals(mapOf("yx" to setOf("我的邮箱")), reloaded(db()).manualSnapshot())
    }

    @Test fun aMarkWithoutTheEntryItMarksIsRejected() {
        db().writeText("aegis-userdb 2\nW\t张伟明\t1\t$clock\nM\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(db()) }
    }

    @Test fun aMarkInAStoreWrittenBeforeTheMarksExistedIsRejected() {
        db().writeText("aegis-userdb 1\nW\t张伟明\t1\t$clock\nR\tzwm\t张伟明\nM\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(db()) }
    }

    @Test fun aDuplicatedMarkIsRejected() {
        db().writeText(
            "aegis-userdb 2\nW\t张伟明\t1\t$clock\nR\tzwm\t张伟明\nM\tzwm\t张伟明\nM\tzwm\t张伟明\n",
        )
        assertThrows(IllegalArgumentException::class.java) { reloaded(db()) }
    }

    @Test fun importingCarriesTheMarksAndAnOldImportAddsNone() {
        val marked = File(tmp.root, "marked.txt")
        model().apply { addManualWord("yx", "我的邮箱", clock) }.save(marked)
        val old = File(tmp.root, "old.txt")
        old.writeText("aegis-userdb 1\nW\t你呢嗯\t1\t$clock\nR\tninen\t你呢嗯\n")

        val m = model()
        assertTrue(m.importFrom(marked, clock))
        assertTrue(m.importFrom(old, clock))
        assertEquals(mapOf("yx" to setOf("我的邮箱")), m.manualSnapshot())
        assertEquals(
            setOf("我的邮箱", "你呢嗯"),
            m.userWordEntries().map { it.word }.toSet(),
        )
    }

    @Test fun theMarkSnapshotIsACopyTheCallerCannotWriteThrough() {
        val m = model().apply { addManualWord("zwm", "张伟明", clock) }
        val snapshot = m.manualSnapshot()
        (snapshot as MutableMap)["yx"] = setOf("我的邮箱")
        (snapshot.getValue("zwm") as MutableSet).clear()
        assertEquals("the model keeps its own marks", mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
    }

    @Test fun aStoreTooLargeToReadBackIsNeverWritten() {
        fun key(i: Int) = buildString {
            var v = i
            repeat(4) { append('a' + v % 26); v /= 26 }
        }
        val m = model()
        val fits = 83_333
        repeat(fits) { m.addManualWord(key(it), "词" + key(it), 0L) }
        m.save(db())
        assertTrue("the file stays inside the size limit", db().length() <= UserModel.MAX_FILE_BYTES)
        assertEquals("what it wrote it must read back", fits, reloaded(db()).userWordEntries().size)

        m.addManualWord(key(fits), "词" + key(fits), 0L)
        val before = db().readBytes()
        assertThrows(IllegalArgumentException::class.java) { m.save(db()) }
        assertTrue("the store it could not read back never replaced the good one", before.contentEquals(db().readBytes()))
    }

    @Test fun turningAutomaticLearningOffStopsRecordingAndLeavesHandAddingAlone() {
        val m = model()
        m.autoLearnEnabled = false
        m.recordWord("ninen", "你呢嗯", clock, incrementCount = true)
        m.record("你", "呢", clock)
        assertTrue("nothing at all was recorded", m.isEmpty())
        assertFalse("and nothing needs saving", m.dirty)

        m.addManualWord("zwm", "张伟明", clock)
        assertEquals(listOf("张伟明"), m.userWordEntries().map { it.word })
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())

        m.autoLearnEnabled = true
        m.recordWord("ninen", "你呢嗯", clock, incrementCount = true)
        assertTrue("switching back on records again", m.userWordEntries().any { it.word == "你呢嗯" })
    }

    @Test fun turningAutomaticLearningOffKeepsWhatWasAlreadyRecorded() {
        val m = model().apply { recordWord("ninen", "你呢嗯", clock, incrementCount = true) }
        m.autoLearnEnabled = false
        assertEquals(listOf("你呢嗯"), m.userWordEntries().map { it.word })
        assertEquals(listOf("你呢嗯"), m.readingSnapshot()["ninen"])
    }
}
