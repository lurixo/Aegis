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

class UserModelForgettingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 60L * 60L * 1000L
    private val t0 = 1_700_000_000_000L
    private val onceSeenDies = 121L * day
    private val onceSeenLives = 119L * day

    private fun db(name: String = "userdb.txt") = File(tmp.root, name)

    private fun at(now: Long, file: File) = UserModel { now }.apply { load(file) }

    private fun letters(index: Int): String {
        val sb = StringBuilder(4)
        var v = index
        repeat(4) {
            sb.append('a' + (v % 26))
            v /= 26
        }
        return sb.toString()
    }

    @Test fun aWordPickedUpOnceFadesOutOnlyAfterItsStrengthDropsBelowTheFloor() {
        val file = db()
        UserModel { t0 }.apply { recordWord("ninen", "你呢嗯", t0, incrementCount = true) }.save(file)

        val early = at(t0 + onceSeenLives, file)
        assertEquals("just short of the threshold it is still there", listOf("你呢嗯"), early.userWordEntries().map { it.word })
        assertEquals(0, early.forgottenCount)
        assertFalse("nothing was dropped, so nothing needs writing back", early.dirty)

        val late = at(t0 + onceSeenDies, file)
        assertTrue("past the threshold it is gone", late.userWordEntries().isEmpty())
        assertEquals(1, late.forgottenCount)
    }

    @Test fun aWordTheUserAddedByHandOutlivesTenTimesTheThreshold() {
        val file = db()
        UserModel { t0 }.apply { addManualWord("zwm", "张伟明", t0) }.save(file)

        val far = t0 + 10L * onceSeenDies
        val m = at(far, file)
        assertEquals("an explicit word is never forgotten", listOf("张伟明"), m.userWordEntries().map { it.word })
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
        assertEquals(0, m.forgottenCount)
        assertFalse(m.dirty)
        assertTrue("it still scores as a known word", m.wordBoost("张伟明") > 0.0)

        val rewritten = db("rewritten.txt")
        m.save(rewritten)
        val evenLater = at(far + 10L * onceSeenDies, rewritten)
        assertEquals("and it survives every later pass too", listOf("张伟明"), evenLater.userWordEntries().map { it.word })
        assertEquals(0, evenLater.forgottenCount)
    }

    @Test fun aWordAutomaticOnOneReadingAndExplicitOnAnotherLosesOnlyTheAutomaticReading() {
        val file = db()
        UserModel { t0 }.apply {
            recordWord("zhangweiming", "张伟明", t0, incrementCount = true)
            addManualWord("zwm", "张伟明", t0)
        }.save(file)

        val m = at(t0 + 400L * day, file)
        assertEquals("only the explicit reading is left", listOf("zwm"), m.userWordEntries().map { it.reading })
        assertEquals(1, m.forgottenCount)
        assertTrue("the word itself lives on through the explicit reading", m.wordBoost("张伟明") > 0.0)
        assertEquals(mapOf("zwm" to setOf("张伟明")), m.manualSnapshot())
        assertEquals(listOf("张伟明"), m.readingSnapshot()["zwm"])
        assertEquals(null, m.readingSnapshot()["zhangweiming"])
    }

    @Test fun usingTheWordAgainRestartsTheClock() {
        val file = db()
        UserModel { t0 }.apply {
            recordWord("ninen", "你呢嗯", t0, incrementCount = true)
            recordWord("ninen", "你呢嗯", t0 + 100L * day, incrementCount = true)
        }.save(file)

        val whenTheUntouchedOneWouldBeGone = at(t0 + onceSeenDies, file)
        assertEquals(
            "the second sighting pushed the deadline out",
            listOf("你呢嗯"),
            whenTheUntouchedOneWouldBeGone.userWordEntries().map { it.word },
        )
        assertEquals(0, whenTheUntouchedOneWouldBeGone.forgottenCount)

        val longAfterThat = at(t0 + 100L * day + 151L * day, file)
        assertTrue("but it is not immortal either", longAfterThat.userWordEntries().isEmpty())
        assertEquals(1, longAfterThat.forgottenCount)
    }

    @Test fun forgettingLeavesNoDanglingCountLastUsedOrBigramBehind() {
        val file = db()
        UserModel { t0 }.apply {
            recordWord("ninen", "你呢嗯", t0, incrementCount = true)
            addManualWord("hao", "好", t0)
            record("你呢嗯", "好", t0)
            record("前", "你呢嗯", t0)
        }.save(file)

        val m = at(t0 + 400L * day, file)
        assertEquals("the explicit word is all that is left", listOf("好"), m.userWordEntries().map { it.word })
        assertEquals(1, m.forgottenCount)
        assertEquals("the forgotten word scores nothing", 0.0, m.wordBoost("你呢嗯"), 0.0)
        assertTrue("it predicts nothing", m.successors("你呢嗯", 8).isEmpty())
        assertTrue("and nothing predicts it", m.successors("前", 8).isEmpty())

        val rewritten = db("rewritten.txt")
        m.save(rewritten)
        assertTrue(
            "no row may point at a word that is gone",
            rewritten.readLines().none { it.startsWith("B\t") && it.endsWith("\t你呢嗯\t1") },
        )
        val back = at(t0 + 400L * day, rewritten)
        assertEquals(listOf("好"), back.userWordEntries().map { it.word })
    }

    @Test fun theRunningForgottenTotalIsWrittenReadBackAndAddedTo() {
        val file = db()
        UserModel { t0 }.apply {
            recordWord("ninen", "你呢嗯", t0, incrementCount = true)
            recordWord("zwm", "张伟明", t0, incrementCount = true)
        }.save(file)

        val first = at(t0 + onceSeenDies, file)
        assertEquals(2, first.forgottenCount)
        assertTrue("the sweep must be written back", first.dirty)
        first.recordWord("ceshi", "测试", t0 + onceSeenDies, incrementCount = true)
        first.save(file)

        val lines = file.readLines()
        assertEquals("aegis-userdb 3", lines.first())
        assertTrue("the total is on disk", "G\t2" in lines)

        val reread = at(t0 + onceSeenDies, file)
        assertEquals("the total survives the round trip", 2, reread.forgottenCount)
        assertEquals(listOf("测试"), reread.userWordEntries().map { it.word })

        val laterStill = at(t0 + onceSeenDies + onceSeenDies, file)
        assertEquals("later sweeps add to the total, they do not reset it", 3, laterStill.forgottenCount)
        assertTrue(laterStill.userWordEntries().isEmpty())
    }

    @Test fun aStoreThatNeverForgotAnythingIsWrittenWithoutTheTotalRow() {
        val file = db()
        UserModel { t0 }.apply { addManualWord("zwm", "张伟明", t0) }.save(file)
        assertTrue("no total row is written while the total is zero", file.readLines().none { it.startsWith("G\t") })
        assertEquals(0, at(t0, file).forgottenCount)
    }

    @Test fun aStoreCarryingNothingFromTheThirdFormatIsNotStampedWithIt() {
        val file = db()
        UserModel { t0 }.apply { addManualWord("zwm", "张伟明", t0) }.save(file)
        assertEquals(
            "a build that only knows the first two formats must still be able to read this file",
            "aegis-userdb 2",
            file.readLines().first(),
        )

        val aged = db("aged.txt")
        aged.writeText("aegis-userdb 2\nW\t旧词\t1\t${t0 - 400L * day}\nR\tjiuci\t旧词\n")
        val swept = at(t0, aged)
        assertEquals("precondition: this store really did forget something", 1, swept.forgottenCount)
        swept.save(aged)
        assertEquals(
            "once the file carries a third-format row it must say so",
            "aegis-userdb 3",
            aged.readLines().first(),
        )
        assertTrue(aged.readLines().any { it.startsWith("G\t") })
    }

    @Test fun theThirdFormatStillReadsWhatTheFirstAndSecondWrote() {
        val one = db("v1.txt")
        one.writeText("aegis-userdb 1\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        val fromOne = at(t0, one)
        assertEquals(listOf("张伟明"), fromOne.userWordEntries().map { it.word })
        assertTrue(fromOne.manualSnapshot().isEmpty())
        assertEquals(0, fromOne.forgottenCount)

        val two = db("v2.txt")
        two.writeText("aegis-userdb 2\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\nM\tzwm\t张伟明\n")
        val fromTwo = at(t0, two)
        assertEquals(mapOf("zwm" to setOf("张伟明")), fromTwo.manualSnapshot())
        assertEquals(0, fromTwo.forgottenCount)

        val three = db("v3.txt")
        three.writeText("aegis-userdb 3\nG\t7\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\nM\tzwm\t张伟明\n")
        val fromThree = at(t0, three)
        assertEquals(7, fromThree.forgottenCount)
        assertEquals(mapOf("zwm" to setOf("张伟明")), fromThree.manualSnapshot())
    }

    @Test fun theTotalRowIsRejectedInEveryPlaceItDoesNotBelong() {
        val inV1 = db("g-in-v1.txt")
        inV1.writeText("aegis-userdb 1\nG\t3\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, inV1) }

        val inV2 = db("g-in-v2.txt")
        inV2.writeText("aegis-userdb 2\nG\t3\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, inV2) }

        val twice = db("g-twice.txt")
        twice.writeText("aegis-userdb 3\nG\t3\nG\t4\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, twice) }

        val negative = db("g-negative.txt")
        negative.writeText("aegis-userdb 3\nG\t-1\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, negative) }

        val ragged = db("g-ragged.txt")
        ragged.writeText("aegis-userdb 3\nG\t3\t9\nW\t张伟明\t4\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, ragged) }
    }

    @Test fun aFileFromAFormatThisBuildDoesNotKnowFailsLoudlyAndChangesNothing() {
        val newer = db("newer.txt")
        newer.writeText("aegis-userdb 5\nW\t张伟明\t1\t$t0\nR\tzwm\t张伟明\n")
        assertThrows(IllegalArgumentException::class.java) { at(t0, newer) }

        val store = db()
        UserModel { t0 }.apply { addManualWord("yx", "我的邮箱", t0) }.save(store)
        assertFalse(
            "an unreadable file must be refused, not half applied",
            UserDictImport.apply(newer, store, merge = false, now = t0),
        )
        assertFalse(UserDictImport.apply(newer, store, merge = true, now = t0))
        assertEquals(
            "the store is exactly as it was",
            listOf("我的邮箱"),
            at(t0, store).userWordEntries().map { it.word },
        )
    }

    @Test fun aStaleImportFileIsBroughtInWholeRatherThanAgedOutOnTheWayIn() {
        val incoming = db("incoming.txt")
        UserModel { t0 }.apply {
            recordWord("ninen", "你呢嗯", t0, incrementCount = true)
        }.save(incoming)

        val replaced = db("replaced.txt")
        assertTrue(UserDictImport.apply(incoming, replaced, merge = false, now = t0 + 400L * day))
        assertEquals(
            "an import lands verbatim; ageing is the store's job, not the import's",
            listOf("你呢嗯"),
            UserModel { t0 }.apply { load(replaced, sweepStale = false) }.userWordEntries().map { it.word },
        )
    }

    @Test fun aFileWhoseEntriesAreAllStaleStillCountsAsCarryingData() {
        val file = db()
        UserModel { t0 }.apply { recordWord("ninen", "你呢嗯", t0, incrementCount = true) }.save(file)
        val swept = at(t0 + 400L * day, file)
        assertTrue(swept.userWordEntries().isEmpty())
        assertFalse("a file that had entries must not read as an empty file", swept.isEmpty())

        val untouched = at(t0, db("missing.txt"))
        assertTrue("a file with nothing in it really is empty", untouched.isEmpty())
    }

    @Test fun anEntryWithNoRecordedUseTimeOrCountIsNeverForgotten() {
        val noTime = db("no-time.txt")
        noTime.writeText("aegis-userdb 3\nW\t张伟明\t1\t0\nR\tzwm\t张伟明\n")
        val a = at(t0 + 4000L * day, noTime)
        assertEquals("an unknown last use is not evidence of staleness", listOf("张伟明"), a.userWordEntries().map { it.word })
        assertEquals(0, a.forgottenCount)

        val noCount = db("no-count.txt")
        noCount.writeText("aegis-userdb 3\nR\tzwm\t张伟明\n")
        val b = at(t0 + 4000L * day, noCount)
        assertEquals("an unknown strength is not evidence either", listOf("张伟明"), b.userWordEntries().map { it.word })
        assertEquals(0, b.forgottenCount)
    }

    @Test(timeout = 180_000) fun aStoreFarPastTheOldCeilingsIsSweptInASinglePass() {
        val automatic = 50_000
        val explicit = 500
        val file = db()
        UserModel { t0 }.apply {
            for (i in 0 until automatic) recordWord(letters(i), "词$i", t0, incrementCount = true)
            for (i in 0 until explicit) addManualWord("zz" + letters(i), "手$i", t0)
        }.save(file)

        val m = at(t0 + 400L * day, file)
        assertEquals("every explicit word is kept", explicit, m.userWordEntries().size)
        assertEquals("every automatic word is dropped", automatic, m.forgottenCount)
        assertTrue(m.dirty)
        for (i in 0 until explicit) {
            if (m.wordBoost("手$i") <= 0.0) throw AssertionError("explicit word 手$i lost its score")
        }

        val rewritten = db("rewritten.txt")
        m.save(rewritten)
        val back = at(t0 + 400L * day, rewritten)
        assertEquals(explicit, back.userWordEntries().size)
        assertEquals(automatic, back.forgottenCount)
    }

    @Test fun aClockJumpThatWouldWipeEverythingIsIgnored() {
        val file = db()
        UserModel { t0 }.apply {
            recordWord("jia", "甲", t0, incrementCount = true)
            recordWord("yi", "乙", t0, incrementCount = true)
        }.save(file)
        val m = at(t0 + 1000L * onceSeenDies, file)
        assertEquals("a jump past every horizon wipes nothing", 2, m.userWordEntries().size)
        assertEquals(0, m.forgottenCount)
    }
}
