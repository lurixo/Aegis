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

class UserModelTombstoneFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun db(name: String = "userdb.txt") = File(tmp.root, name)

    private fun model() = UserModel { clock }

    private fun reloaded(file: File) = model().apply { load(file, sweepStale = false) }

    @Test fun aPromiseRoundTripsWordForWord() {
        val long = "词".repeat(256)
        val m = model()
        assertTrue(m.addTombstone("你呢嗯", "ninen"))
        assertTrue("a promise with no reading clears the whole word", m.addTombstone("孤词", ""))
        assertTrue("a reading is stored the way the store spells readings", m.addTombstone("大写", "NI'neN"))
        assertTrue("a word at the limit the format holds still fits", m.addTombstone(long, "chang"))
        m.save(db())

        val lines = db().readLines()
        assertEquals("aegis-userdb 4", lines.first())
        assertTrue("D\t你呢嗯\tninen" in lines)
        assertTrue("D\t孤词\t" in lines)
        assertTrue("D\t大写\tninen" in lines)
        assertTrue("D\t$long\tchang" in lines)

        assertEquals(
            listOf("你呢嗯" to "ninen", "孤词" to "", "大写" to "ninen", long to "chang"),
            reloaded(db()).tombstones(),
        )
    }

    @Test fun aPromiseIsMadeOnlyOnceAndOnlyForAWordTheStoreCanHold() {
        val m = model()
        assertTrue(m.addTombstone("你呢嗯", "ninen"))
        assertFalse("the same promise twice is still one promise", m.addTombstone("你呢嗯", "ninen"))
        assertFalse("a word carrying a delimiter can never be written down", m.addTombstone("你\t呢", "ninen"))
        assertFalse("nor can one longer than the format holds", m.addTombstone("词".repeat(257), "ninen"))
        assertFalse("nor one whose reading is longer than it holds", m.addTombstone("短词", "a".repeat(257)))
        assertEquals(listOf("你呢嗯" to "ninen"), m.tombstones())
    }

    @Test fun aPromiseKeptIsATotalGone() {
        val m = model()
        m.addTombstone("你呢嗯", "ninen")
        m.addTombstone("孤词", "")
        m.addManualWord("zwm", "张伟明", clock)
        m.save(db())
        assertEquals("aegis-userdb 4", db().readLines().first())

        val back = reloaded(db())
        assertTrue(back.hasTombstones())
        assertTrue(back.dropTombstones(listOf("你呢嗯" to "ninen", "孤词" to "")))
        assertFalse(back.hasTombstones())
        assertTrue("a kept promise leaves work to write out", back.dirty)
        back.save(db())

        assertEquals(
            "once nothing is promised the file stops claiming the fourth format",
            "aegis-userdb 2",
            db().readLines().first(),
        )
        assertTrue(db().readLines().none { it.startsWith("D\t") })
        assertFalse(reloaded(db()).hasTombstones())
    }

    @Test fun aStoreCarryingNoPromiseIsNotStampedWithTheFourthFormat() {
        model().apply { addManualWord("zwm", "张伟明", clock) }.save(db())
        assertEquals(
            "a build that only knows the first two formats must still be able to read this file",
            "aegis-userdb 2",
            db().readLines().first(),
        )

        val aged = db("aged.txt")
        aged.writeText("aegis-userdb 2\nW\t旧词\t1\t${clock - 400L * day}\nR\tjiuci\t旧词\n")
        val swept = model().apply { load(aged) }
        assertEquals("precondition: this store really did forget something", 1, swept.forgottenCount)
        swept.save(aged)
        assertEquals(
            "forgetting alone stays on the third format",
            "aegis-userdb 3",
            aged.readLines().first(),
        )

        swept.addTombstone("你呢嗯", "ninen")
        swept.save(aged)
        val lines = aged.readLines()
        assertEquals("only a promise on the page moves it to the fourth", "aegis-userdb 4", lines.first())
        assertTrue("the running total is still there beside it", lines.any { it.startsWith("G\t") })
        assertEquals(1, reloaded(aged).forgottenCount)
    }

    @Test fun aPromiseInAStoreWrittenBeforePromisesExistedIsRejected() {
        val one = db("v1.txt")
        one.writeText("aegis-userdb 1\nW\t张伟明\t1\t$clock\nR\tzwm\t张伟明\nD\t你呢嗯\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(one) }

        val two = db("v2.txt")
        two.writeText("aegis-userdb 2\nW\t张伟明\t1\t$clock\nR\tzwm\t张伟明\nD\t你呢嗯\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(two) }

        val three = db("v3.txt")
        three.writeText("aegis-userdb 3\nG\t1\nW\t张伟明\t1\t$clock\nR\tzwm\t张伟明\nD\t你呢嗯\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(three) }
    }

    @Test fun aDuplicatedPromiseIsRejected() {
        val file = db("twice.txt")
        file.writeText("aegis-userdb 4\nD\t你呢嗯\tninen\nD\t你呢嗯\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(file) }

        val twiceWithoutReading = db("twice-blank.txt")
        twiceWithoutReading.writeText("aegis-userdb 4\nD\t孤词\t\nD\t孤词\t\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(twiceWithoutReading) }
    }

    @Test fun aPromiseRowIsRejectedInEveryShapeItCannotHave() {
        val ragged = db("ragged.txt")
        ragged.writeText("aegis-userdb 4\nD\t你呢嗯\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(ragged) }

        val extra = db("extra.txt")
        extra.writeText("aegis-userdb 4\nD\t你呢嗯\tninen\t9\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(extra) }

        val empty = db("empty-word.txt")
        empty.writeText("aegis-userdb 4\nD\t\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(empty) }

        val shouted = db("shouted.txt")
        shouted.writeText("aegis-userdb 4\nD\t你呢嗯\tNINEN\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(shouted) }

        val longWord = db("long-word.txt")
        longWord.writeText("aegis-userdb 4\nD\t${"词".repeat(257)}\tninen\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(longWord) }

        val longReading = db("long-reading.txt")
        longReading.writeText("aegis-userdb 4\nD\t你呢嗯\t${"a".repeat(257)}\n")
        assertThrows(IllegalArgumentException::class.java) { reloaded(longReading) }
    }

    @Test fun aPromiseSurvivesAWordListReloadedFromDisk() {
        val replacement = db("replacement.txt")
        model().apply { addManualWord("dz", "地址", clock) }.save(replacement)

        val m = model().apply { addManualWord("zwm", "张伟明", clock) }
        m.addTombstone("你呢嗯", "ninen")
        m.reload(replacement)

        assertEquals("a word list read in wholesale cannot discharge a promise", listOf("你呢嗯" to "ninen"), m.tombstones())
        assertEquals(listOf("地址"), m.userWordEntries().map { it.word })

        m.save(db())
        assertEquals(listOf("你呢嗯" to "ninen"), reloaded(db()).tombstones())
    }

    @Test fun anImportedWordListDoesNotBringItsOwnPromises() {
        val donor = db("donor.txt")
        model().apply {
            addManualWord("yx", "我的邮箱", clock)
            addTombstone("外来词", "wailaici")
        }.save(donor)
        assertTrue("precondition: the donor really carries a promise", reloaded(donor).hasTombstones())

        val m = model()
        assertTrue(m.importFrom(donor, clock))

        assertTrue("merging in someone else's list must not delete this phone's data", m.tombstones().isEmpty())
        assertEquals(listOf("我的邮箱"), m.userWordEntries().map { it.word })
    }

    @Test fun aStoreThatCarriesNothingButPromisesStillReadsAsEmpty() {
        val m = model().apply { addTombstone("你呢嗯", "ninen") }
        assertTrue("a promise is not a word, so it cannot make an empty list look full", m.isEmpty())
        m.save(db())
        assertTrue(reloaded(db()).isEmpty())
    }
}
