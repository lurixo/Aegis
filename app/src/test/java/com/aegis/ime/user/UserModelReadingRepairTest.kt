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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserModelReadingRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L

    private fun db(name: String = "userdb.txt") = File(tmp.root, name)

    private fun model() = UserModel { clock }

    private fun reloaded(file: File) = model().apply { load(file, sweepStale = false) }

    private fun drop(reading: String, word: String) = UserModel.ReadingRepair(reading, word, null)

    private fun respell(reading: String, word: String, spelled: String) = UserModel.ReadingRepair(reading, word, spelled)

    @Test fun theSnapshotLeavesOutEveryPairAddedByHand() {
        val m = model().apply {
            addManualWord("zwm", "张伟明", clock)
            recordWord("zwm", "张伟明", clock, incrementCount = true)
            recordWord("jiu", "就是", clock, incrementCount = true)
            addManualWord("yi", "一个", clock)
            recordWord("yi", "一下", clock, incrementCount = true)
            recordWord("zhangweiming", "张伟明", clock, incrementCount = true)
        }
        assertEquals(
            setOf("jiu" to "就是", "yi" to "一下", "zhangweiming" to "张伟明"),
            m.unmarkedReadings().toSet(),
        )
    }

    @Test fun everyReadingOfAStoreWrittenBeforeTheMarksExistedIsInTheSnapshot() {
        db().writeText("aegis-userdb 1\nW\t就是\t3\t$clock\nW\t张伟明\t4\t$clock\nR\tjiu\t就是\nR\tzwm\t张伟明\n")
        assertEquals(setOf("jiu" to "就是", "zwm" to "张伟明"), reloaded(db()).unmarkedReadings().toSet())
    }

    @Test fun aRepairChangesOnlyReadingRowsAndKeepsTheHeader() {
        db().writeText(
            "aegis-userdb 4\n" +
                "G\t7\n" +
                "D\t旧词\tjiuci\n" +
                "W\t就是\t5\t$clock\n" +
                "W\t片面\t2\t$clock\n" +
                "W\t我们\t9\t$clock\n" +
                "B\t我们\t就是\t3\n" +
                "R\tjiu\t就是\n" +
                "R\tqianmian\t片面\n" +
                "R\twm\t我们\n" +
                "M\twm\t我们\n",
        )
        val m = reloaded(db())
        val before = db().readLines()
        assertTrue(m.repairReadings(listOf(drop("jiu", "就是"), respell("qianmian", "片面", "pianmian"))))
        m.save(db("after.txt"))
        val after = db("after.txt").readLines()

        assertEquals("the header stays", before.first(), after.first())
        assertEquals(
            "every row but the readings is written back unchanged",
            before.filterNot { it.startsWith("R\t") }.toSet(),
            after.filterNot { it.startsWith("R\t") }.toSet(),
        )
        assertEquals(setOf("R\twm\t我们", "R\tpianmian\t片面"), after.filter { it.startsWith("R\t") }.toSet())

        val back = reloaded(db("after.txt"))
        assertEquals(7, back.forgottenCount)
        assertEquals(listOf("旧词" to "jiuci"), back.tombstones())
        assertEquals(mapOf("wm" to setOf("我们")), back.manualSnapshot())
        assertTrue("the usage of a word whose reading was dropped stays", back.wordBoost("就是") > 0.0)
        assertEquals(listOf("就是"), back.successors("我们", 4))
    }

    @Test fun aPairTheUserAddsByHandBeforeTheRepairLandsIsLeftAlone() {
        val m = model().apply { recordWord("jiu", "就是", clock, incrementCount = true) }
        val snapshot = m.unmarkedReadings()
        m.addManualWord("jiu", "就是", clock)
        val version = m.version
        assertFalse(m.repairReadings(snapshot.map { (r, w) -> drop(r, w) }))
        assertEquals(listOf("就是"), m.readingSnapshot()["jiu"])
        assertEquals(version, m.version)
    }

    @Test fun respellingOntoAReadingTheWordAlreadyHasLeavesASingleRow() {
        val m = model().apply {
            recordWord("qianmian", "片面", clock, incrementCount = true)
            recordWord("pianmian", "片面", clock, incrementCount = true)
            recordWord("pianmian", "偏面", clock, incrementCount = true)
        }
        assertTrue(m.repairReadings(listOf(respell("qianmian", "片面", "pianmian"))))
        assertEquals(setOf("pianmian"), m.readingSnapshot().keys)
        assertEquals(setOf("片面", "偏面"), m.readingSnapshot().getValue("pianmian").toSet())
        m.save(db())
        assertEquals(
            "the file holds the pair once and loads again",
            1,
            db().readLines().count { it == "R\tpianmian\t片面" },
        )
        assertEquals(setOf("片面", "偏面"), reloaded(db()).readingSnapshot().getValue("pianmian").toSet())
    }

    @Test fun respellingOntoANewReadingAddsIt() {
        val m = model().apply { recordWord("qianmian", "片面", clock, incrementCount = true) }
        assertTrue(m.repairReadings(listOf(respell("qianmian", "片面", "pianmian"))))
        assertEquals(mapOf("pianmian" to listOf("片面")), m.readingSnapshot())
    }

    @Test fun nothingToChangeLeavesTheStoreUntouched() {
        val m = model().apply {
            recordWord("jiushi", "就是", clock, incrementCount = true)
            addManualWord("yi", "一个", clock)
        }
        m.save(db())
        val version = m.version
        val readingsVersion = m.readingsVersion
        assertFalse(
            m.repairReadings(
                listOf(
                    drop("jiu", "就是"),
                    drop("yi", "一个"),
                    respell("jiushi", "就是", "jiushi"),
                    respell("jiushi", "就是", ""),
                    respell("jiushi", "就是", "JiuShi"),
                ),
            ),
        )
        assertFalse(m.dirty)
        assertEquals(version, m.version)
        assertEquals(readingsVersion, m.readingsVersion)
        assertEquals(setOf("jiushi", "yi"), m.readingSnapshot().keys)
    }

    @Test fun aBatchOfChangesBumpsEachVersionOnce() {
        val m = model().apply {
            recordWord("jiu", "就是", clock, incrementCount = true)
            recordWord("yi", "一个", clock, incrementCount = true)
            recordWord("qianmian", "片面", clock, incrementCount = true)
        }
        m.save(db())
        val version = m.version
        val readingsVersion = m.readingsVersion
        assertTrue(
            m.repairReadings(listOf(drop("jiu", "就是"), drop("yi", "一个"), respell("qianmian", "片面", "pianmian"))),
        )
        assertTrue(m.dirty)
        assertEquals(version + 1, m.version)
        assertEquals(readingsVersion + 1, m.readingsVersion)
        assertEquals(mapOf("pianmian" to listOf("片面")), m.readingSnapshot())
    }

    @Test fun aStoreThatCouldNotBeReadIsNeitherSnapshottedNorRepaired() {
        val m = model().apply { recordWord("jiu", "就是", clock, incrementCount = true) }
        db().writeText("this is not an aegis user dictionary\n")
        runCatching { m.load(db()) }
        assertFalse(m.readable)
        assertEquals(emptyList<Pair<String, String>>(), m.unmarkedReadings())
        assertFalse(m.repairReadings(listOf(drop("jiu", "就是"))))
        assertEquals(listOf("就是"), m.readingSnapshot()["jiu"])
    }
}
