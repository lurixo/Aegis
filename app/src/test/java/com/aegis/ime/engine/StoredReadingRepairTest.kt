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

package com.aegis.ime.engine

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class StoredReadingRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private val dict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9 by lazy { BinaryDict.fromFile(t9File) }
    private val lm by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpin by lazy { BinaryDict.fromFile(jianpinFile) }

    private fun engine(um: UserModel) = DictEngine(dict, t9, lm, um, emptySet(), jianpin, null, UserLearning())

    private fun stored(rows: List<Pair<String, String>>) =
        UserModel { clock }.apply { rows.forEach { (r, w) -> recordWord(r, w, clock, incrementCount = true) } }

    private fun typedWithoutReadings(rows: List<Pair<String, String>>) =
        UserModel { clock }.apply { rows.forEach { (_, w) -> record(null, w, clock) } }

    private fun repaired(um: UserModel): Boolean = um.repairReadings(engine(um).storedReadingRepairs())

    private fun layout(nine: Boolean) = if (nine) "9-key" else "26-key"

    private fun typed(um: UserModel, reading: String, nine: Boolean): List<String> =
        engine(um).candidatesCovered(if (nine) T9Pinyin.toT9(reading) else reading, nine, emptySet(), "").map { it.word }

    private fun locked(um: UserModel, reading: String): List<String> =
        engine(um).candidatesForLockedReadingCovered(reading, emptySet(), "").map { it.word }

    @Test fun completionsStoredUnderACutShortReadingAreClearedAndTheirUsageStays() {
        assets()
        val rows = listOf(
            "jiu" to "就是",
            "yi" to "一个",
            "yi" to "一下",
            "yi" to "已经",
            "tian" to "天气",
            "shouji" to "手机号码",
            "yi" to "喜欢",
        )
        val um = stored(rows)
        assertTrue("precondition: 26-key yi offers the stored 喜欢", "喜欢" in typed(um, "yi", nine = false))
        assertTrue(repaired(um))
        assertEquals(emptyMap<String, List<String>>(), um.readingSnapshot())
        for ((_, word) in rows) assertTrue("the usage of $word stays", um.wordBoost(word) > 0.0)
        assertFalse("26-key yi no longer offers 喜欢", "喜欢" in typed(um, "yi", nine = false))
        val learner = typedWithoutReadings(rows)
        for (nine in listOf(false, true)) {
            for (reading in listOf("jiu", "yi", "tian", "shouji")) {
                assertEquals(
                    "${layout(nine)}: $reading decodes as if the words had only been typed",
                    typed(learner, reading, nine),
                    typed(um, reading, nine),
                )
            }
        }
        for (reading in listOf("jiu", "yi", "tian")) {
            assertEquals("locked $reading", locked(learner, reading), locked(um, reading))
        }
    }

    @Test fun aNineKeyLetterGuessIsRespelledAndStaysTypeableOnBothKeyboards() {
        assets()
        val um = stored(listOf("qianmian" to "片面"))
        assertTrue(repaired(um))
        assertEquals(mapOf("pianmian" to listOf("片面")), um.readingSnapshot())
        val learner = stored(listOf("pianmian" to "片面"))
        for (nine in listOf(false, true)) {
            val got = typed(um, "pianmian", nine)
            assertTrue("${layout(nine)}: 片面 is offered, was ${got.take(8)}", "片面" in got)
            assertEquals("${layout(nine)}", typed(learner, "pianmian", nine), got)
        }
    }

    @Test fun wordsTheUserMayHaveAddedByHandAreKept() {
        assets()
        val entries = listOf(
            "yx" to "我的邮箱",
            "zwm" to "张伟明",
            "nar" to "哪儿",
            "huar" to "花儿",
            "sifen" to "十分",
            "cesi" to "测试",
            "zidao" to "知道",
            "yyds" to "永远的神",
            "hhh" to "哈哈哈",
            "dcall" to "打call",
            "biaoqing" to "😀",
            "email" to "abc@example.com",
        )
        val um = stored(entries)
        assertEquals(emptyList<UserModel.ReadingRepair>(), engine(um).storedReadingRepairs())
        for ((reading, word) in entries) {
            assertTrue("26-key: $word stays typeable under $reading", word in typed(um, reading, nine = false))
            if (word.all { Character.isIdeographic(it.code) }) {
                assertTrue("9-key: $word stays typeable under $reading", word in typed(um, reading, nine = true))
            }
        }
        val legacy = File(tmp.root, "userdb.txt")
        legacy.writeText("aegis-userdb 1\nW\t你呢嗯\t1\t$clock\nW\t张伟明\t4\t$clock\nR\tninen\t你呢嗯\nR\tzwm\t张伟明\n")
        val old = UserModel { clock }.apply { load(legacy, sweepStale = false) }
        assertEquals(emptyList<UserModel.ReadingRepair>(), engine(old).storedReadingRepairs())
    }

    @Test fun withoutTheLetterDictionaryOrAUserStoreNothingIsJudged() {
        val um = stored(listOf("bu" to "不是"))
        assertEquals(emptyList<UserModel.ReadingRepair>(), DictEngine(null, EngineFixture.dict(), null, um).storedReadingRepairs())
        assertEquals(emptyList<UserModel.ReadingRepair>(), DictEngine(EngineFixture.dict(), null, null, null).storedReadingRepairs())
        assertEquals(listOf(UserModel.ReadingRepair("bu", "不是", null)), DictEngine(EngineFixture.dict(), null, null, um).storedReadingRepairs())
    }

    private fun waitFor(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < until) {
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    private fun workerIn(state: Thread.State): Boolean =
        Thread.getAllStackTraces().keys.any { it.name == "aegis-reading-repair" && it.state == state }

    private fun settled(): Boolean = waitFor {
        (0 until 20).all {
            Thread.sleep(5)
            !workerIn(Thread.State.RUNNABLE) && !workerIn(Thread.State.BLOCKED)
        }
    }

    @Test fun aBurstOfRequestsWhileAPassRunsCollapsesIntoOneMorePass() {
        val um = stored(listOf("bu" to "不是"))
        val engine = DictEngine(EngineFixture.dict(), null, null, um)
        val applied = Collections.synchronizedList(ArrayList<List<UserModel.ReadingRepair>>())
        val repair = StoredReadingRepair { applied += it }
        synchronized(um) {
            repair.request(engine)
            assertTrue("precondition: the first pass is taking its snapshot", waitFor { workerIn(Thread.State.BLOCKED) })
            repeat(5) { repair.request() }
            repeat(5) { repair.request(engine) }
        }
        assertTrue(waitFor { applied.size >= 2 })
        assertTrue(settled())
        assertEquals(2, applied.size)
    }

    @Test fun aPassOvertakenByANewerEngineIsNotApplied() {
        val um = stored(listOf("bu" to "不是", "biku" to "词库"))
        val older = DictEngine(
            EngineFixture.build(
                listOf(
                    EngineFixture.Row("bu", "不", 900),
                    EngineFixture.Row("shi", "是", 900),
                    EngineFixture.Row("bushi", "不是", 800),
                ),
            ),
            null,
            null,
            um,
        )
        val newer = DictEngine(
            EngineFixture.build(
                listOf(
                    EngineFixture.Row("bi", "比", 900),
                    EngineFixture.Row("ci", "词", 900),
                    EngineFixture.Row("ku", "库", 900),
                    EngineFixture.Row("ciku", "词库", 800),
                ),
            ),
            null,
            null,
            um,
        )
        val applied = Collections.synchronizedList(ArrayList<List<UserModel.ReadingRepair>>())
        val repair = StoredReadingRepair { applied += it }
        synchronized(um) {
            repair.request(older)
            assertTrue("precondition: the older pass is taking its snapshot", waitFor { workerIn(Thread.State.BLOCKED) })
            repair.request(newer)
        }
        assertTrue(waitFor { applied.isNotEmpty() })
        assertTrue(settled())
        assertEquals(listOf(listOf(UserModel.ReadingRepair("biku", "词库", "ciku"))), applied.toList())
    }

    @Test fun aRequestBeforeAnyEngineIsReadyDoesNothing() {
        val applied = Collections.synchronizedList(ArrayList<List<UserModel.ReadingRepair>>())
        val repair = StoredReadingRepair { applied += it }
        repair.request()
        assertTrue(settled())
        assertEquals(emptyList<List<UserModel.ReadingRepair>>(), applied.toList())
    }
}
