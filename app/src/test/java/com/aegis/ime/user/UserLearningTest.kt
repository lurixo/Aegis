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

import com.aegis.ime.decoder.T9Pinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class UserLearningTest {

    private val day = 24L * 60L * 60L * 1000L
    private var now = 1_000_000_000_000L
    private val store = UserLearning { now }

    private fun typeRun(l: UserLearning, vararg commits: Pair<String, String>) {
        var prev: String? = null
        for ((word, reading) in commits) {
            l.observeCommit(prev, word, reading, now)
            prev = word
        }
        l.observeBreak()
    }

    private fun tempFile(prefix: String): File =
        File.createTempFile(prefix, ".txt").also { it.deleteOnExit() }

    @Test
    fun formsRecurringSingleCharRunUnderLetterAndDigitKeys() {
        val name = arrayOf("张" to "zhang", "伟" to "wei", "明" to "ming")
        repeat(2) { typeRun(store, *name) }
        assertTrue("no promotion before threshold", store.formedWordsFor("zhangweiming").isEmpty())
        val versionBefore = store.version
        typeRun(store, *name)
        assertTrue("promotion bumps version", store.version > versionBefore)
        assertEquals(listOf("张伟明"), store.formedWordsFor("zhangweiming"))
        assertEquals(listOf("张伟明"), store.formedWordsFor(T9Pinyin.toT9("zhangweiming")))
        assertEquals(listOf("张伟明"), store.readingSnapshot()["zhangweiming"])
        assertTrue("formed word carries a positive boost", store.formedWeight("张伟明") > 0.0)
        assertTrue("prefix pair is not promoted", store.formedWordsFor("zhangwei").isEmpty())
        assertTrue("suffix pair is not promoted", store.formedWordsFor("weiming").isEmpty())
        assertTrue(store.dirty)
    }

    @Test
    fun nullPrevWordClosesTheChainAndPromotes() {
        val name = arrayOf("李" to "li", "雷" to "lei")
        var prev: String? = null
        repeat(3) {
            prev = null
            for ((word, reading) in name) {
                store.observeCommit(prev, word, reading, now)
                prev = word
            }
            store.observeCommit(null, "好", "hao", now)
        }
        assertEquals(listOf("李雷"), store.formedWordsFor("lilei"))
    }

    @Test
    fun independentSubsequenceStillForms() {
        repeat(3) { typeRun(store, "伟" to "wei", "明" to "ming") }
        assertEquals(listOf("伟明"), store.formedWordsFor("weiming"))
        assertEquals(listOf("伟明"), store.formedWordsFor(T9Pinyin.toT9("weiming")))
    }

    @Test
    fun multiCharCommitBreaksFormationChain() {
        repeat(5) { typeRun(store, "张" to "zhang", "伟" to "wei", "你好" to "nihao", "明" to "ming") }
        assertEquals(listOf("张伟"), store.formedWordsFor("zhangwei"))
        assertTrue(store.formedWordsFor("weiming").isEmpty())
        assertTrue(store.formedWordsFor("zhangweiming").isEmpty())
    }

    @Test
    fun rejectsNonHanWordsAndNonSyllableReadings() {
        repeat(5) { typeRun(store, "x" to "ma", "y" to "ma") }
        repeat(5) { typeRun(store, "张" to "zh", "伟" to "wei") }
        repeat(5) { typeRun(store, "张" to "z", "伟" to "w") }
        repeat(5) { typeRun(store, "张" to "", "伟" to "wei") }
        assertTrue(store.readingSnapshot().isEmpty())
        assertTrue(store.formedWordsFor("zhangwei").isEmpty())
    }

    @Test
    fun reinforcementAfterPromotionDoesNotResurrectSubWindows() {
        val name = arrayOf("张" to "zhang", "伟" to "wei", "明" to "ming")
        repeat(3) { typeRun(store, *name) }
        val weightAtPromotion = store.formedWeight("张伟明")
        repeat(4) { typeRun(store, *name) }
        assertTrue("full word keeps reinforcing", store.formedWeight("张伟明") > weightAtPromotion)
        assertTrue("prefix stays suppressed", store.formedWordsFor("zhangwei").isEmpty())
        assertTrue("suffix stays suppressed", store.formedWordsFor("weiming").isEmpty())
    }

    @Test
    fun weeklyCadencePromotesOnFourthSightingUnderDecay() {
        val pair = arrayOf("李" to "li", "雷" to "lei")
        repeat(3) {
            typeRun(store, *pair)
            now += 7 * day
        }
        assertTrue("three weekly sightings decay below threshold", store.formedWordsFor("lilei").isEmpty())
        typeRun(store, *pair)
        assertEquals(listOf("李雷"), store.formedWordsFor("lilei"))
    }

    @Test
    fun farPastTheOldFormedCapNothingIsEvicted() {
        val first = String(Character.toChars(0x4E00)) + String(Character.toChars(0x5E00))
        var last = ""
        val total = 700
        for (i in 0 until total) {
            val a = String(Character.toChars(0x4E00 + i))
            val b = String(Character.toChars(0x5E00 + i))
            last = a + b
            repeat(3) { typeRun(store, a to "ma", b to "ma") }
            now += 60_000L
        }
        val kept = store.formedWordsFor("mama")
        assertEquals("every glued word is kept once the cap is gone", total, kept.size)
        assertTrue("the oldest one is still there", first in kept)
        assertTrue("and so is the newest", last in kept)
    }

    @Test
    fun pendingFloodStillAllowsFreshPromotion() {
        for (i in 0 until 2500) {
            val a = String(Character.toChars(0x4E00 + i))
            val b = String(Character.toChars(0x7000 + i))
            typeRun(store, a to "ma", b to "ma")
        }
        repeat(3) { typeRun(store, "张" to "zhang", "伟" to "wei") }
        assertEquals(listOf("张伟"), store.formedWordsFor("zhangwei"))
    }

    @Test
    fun collocationDecayFadesStaleHabit() {
        repeat(5) { store.observeCommit("旧", "早安", "", now) }
        assertEquals(listOf("早安"), store.follows("旧").map { it.first })
        now += 140 * day
        store.observeCommit("旧", "晚安", "", now)
        assertEquals(listOf("晚安"), store.follows("旧").map { it.first })
        assertTrue(store.followBoost("又旧", "晚安") > 0.0)
        assertEquals(0.0, store.followBoost("又旧", "早安"), 0.0)
    }

    @Test
    fun collocationPerPrevCapPrefersEstablishedOverNew() {
        val successors = (0 until UserLearning.FOLLOW_PER_PREV).map { String(Character.toChars(0x4E8C + it)) }
        repeat(2) { for (s in successors) store.observeCommit("你好", s, "", now) }
        store.observeCommit("你好", "新", "", now)
        assertFalse("fresh pair cannot evict active pairs", store.follows("你好").any { it.first == "新" })
        assertEquals(UserLearning.FOLLOW_PER_PREV, store.follows("你好").size)
        now += 30 * day
        store.observeCommit("你好", "新", "", now)
        assertTrue("stale pair is replaced", store.follows("你好").any { it.first == "新" })
        assertEquals(UserLearning.FOLLOW_PER_PREV, store.follows("你好").size)
    }

    @Test
    fun followBoostMatchesContextSuffixOnly() {
        repeat(2) { store.observeCommit("张伟明", "你好", "", now) }
        val weights = store.follows("张伟明")
        assertEquals(1, weights.size)
        assertEquals("你好", weights[0].first)
        assertEquals(2.0, weights[0].second, 1e-9)
        assertTrue(store.followBoost("在张伟明", "你好") > 0.0)
        assertEquals(0.0, store.followBoost("张伟", "你好"), 0.0)
        assertEquals(0.0, store.followBoost("", "你好"), 0.0)
        assertEquals(0.0, store.followBoost("abc张伟", "你好"), 0.0)
    }

    @Test
    fun collocationPreviousWordIsCappedAtFourHanCharacters() {
        store.observeCommit("甲乙丙丁", "允许", "", now)
        store.observeCommit("甲乙丙丁戊", "拒绝", "", now)
        assertEquals(listOf("允许"), store.follows("甲乙丙丁").map { it.first })
        assertTrue(store.follows("甲乙丙丁戊").isEmpty())
    }

    @Test
    fun aUserDictionaryThatCouldNotBeReadIsNeverWrittenBackOverTheSameFile() {
        val f = tempFile("userdb-unreadable")
        f.writeText("aegis-userdb 99\nW\t坏\t1\t1\n")
        val before = f.readText()
        val m = UserModel { now }
        assertTrue("precondition: this file does not parse", runCatching { m.load(f) }.isFailure)
        assertFalse("a file that would not parse must not pass for a readable store", m.readable)

        m.record(null, "新词", now)
        assertTrue("writing back over what could not be read must fail loudly", runCatching { m.save(f) }.isFailure)
        assertEquals("the unreadable file is left byte for byte as it was", before, f.readText())
        assertTrue("the data stays queued for a store that can take it", m.dirty)

        val elsewhere = tempFile("userdb-elsewhere")
        m.save(elsewhere)
        assertTrue(UserModel { now }.apply { load(elsewhere) }.wordBoost("新词") > 0.0)
    }

    @Test
    fun aStoreThatCouldNotBeReadIsNeverWrittenBackOverTheSameFile() {
        val f = tempFile("userlearn-unreadable")
        f.writeText("garbage that is not a learning store\n")
        val before = f.readText()
        val l = UserLearning { now }
        l.load(f)
        assertFalse("a file that would not parse must not pass for a readable store", l.readable)

        repeat(3) { typeRun(l, "张" to "zhang", "伟" to "wei") }
        val refused = runCatching { l.save(f) }
        assertTrue("writing back over what could not be read must fail loudly", refused.isFailure)
        assertEquals("the unreadable file is left byte for byte as it was", before, f.readText())
        assertTrue("the data stays queued for a store that can take it", l.dirty)

        val elsewhere = tempFile("userlearn-elsewhere")
        l.save(elsewhere)
        assertEquals(listOf("张伟"), UserLearning { now }.apply { load(elsewhere) }.formedWordsFor("zhangwei"))

        l.clear()
        assertTrue("discarding it on purpose is an explicit decision, so saving works again", l.readable)
        l.save(f)
        assertTrue(f.readText().startsWith("aegis-userlearn"))
    }

    @Test
    fun corruptStoreFilesLoadEmpty() {
        val variants = listOf(
            "garbage\n",
            "aegis-userlearn 1\nX\ta\tb\tc\td\n",
            "aegis-userlearn 1\nF\tzhangwei\t张伟\tNaN\t5\n",
            "aegis-userlearn 1\nF\tzhangwei\t张伟\t-1.0\t5\n",
            "aegis-userlearn 1\nF\tzhangwei\tabc\t3.0\t5\n",
            "aegis-userlearn 1\nF\tzhangwei\t张伟\t3.0\t-2\n",
            "aegis-userlearn 1\nC\t张\t伟\t2.0\n",
            "aegis-userlearn 1\nF\tzhangwei\t张伟\t3.0\t5\nF\tzh",
        )
        for (content in variants) {
            val f = tempFile("userlearn-bad")
            f.writeText(content)
            val l = UserLearning { now }
            l.load(f)
            assertTrue("corrupt content must load empty: $content", l.isEmpty())
            repeat(3) { typeRun(l, "张" to "zhang", "伟" to "wei") }
            assertEquals(listOf("张伟"), l.formedWordsFor("zhangwei"))
            val out = tempFile("userlearn-out")
            l.save(out)
            val reloaded = UserLearning { now }
            reloaded.load(out)
            assertEquals(listOf("张伟"), reloaded.formedWordsFor("zhangwei"))
        }
    }

    @Test
    fun missingOrEmptyFileLoadsEmpty() {
        val missing = File(tempFile("userlearn-dir").parentFile, "userlearn-missing-${System.nanoTime()}.txt")
        store.load(missing)
        assertTrue(store.isEmpty())
        val empty = tempFile("userlearn-empty")
        store.load(empty)
        assertTrue(store.isEmpty())
        assertFalse(store.dirty)
    }

    @Test
    fun persistenceRoundTripPreservesAllThreeStores() {
        repeat(3) { typeRun(store, "张" to "zhang", "伟" to "wei", "明" to "ming") }
        repeat(2) { store.observeCommit("你好", "世界", "", now) }
        repeat(2) { typeRun(store, "李" to "li", "雷" to "lei") }
        val f = tempFile("userlearn-rt")
        store.save(f)
        assertFalse(store.dirty)

        val b = UserLearning { now }
        b.load(f)
        assertEquals(listOf("张伟明"), b.formedWordsFor("zhangweiming"))
        assertEquals(listOf("张伟明"), b.formedWordsFor(T9Pinyin.toT9("zhangweiming")))
        assertEquals(store.formedWeight("张伟明"), b.formedWeight("张伟明"), 1e-9)
        assertEquals(listOf("世界" to 2.0), b.follows("你好").map { it.first to it.second })
        assertTrue("pending counters are not yet words", b.formedWordsFor("lilei").isEmpty())
        typeRun(b, "李" to "li", "雷" to "lei")
        assertEquals("pending counters survive the round trip", listOf("李雷"), b.formedWordsFor("lilei"))
    }

    @Test
    fun saveClosesOpenChainAndPromotes() {
        repeat(2) { typeRun(store, "张" to "zhang", "伟" to "wei") }
        store.observeCommit(null, "张", "zhang", now)
        store.observeCommit("张", "伟", "wei", now)
        val f = tempFile("userlearn-close")
        store.save(f)
        assertEquals(listOf("张伟"), store.formedWordsFor("zhangwei"))
        val b = UserLearning { now }
        b.load(f)
        assertEquals(listOf("张伟"), b.formedWordsFor("zhangwei"))
    }

    @Test
    fun removeWordScrubsFormedPendingAndCollocations() {
        repeat(3) { typeRun(store, "张" to "zhang", "伟" to "wei", "明" to "ming") }
        store.observeCommit("张伟明", "你好", "", now)
        store.observeCommit("你好", "张伟明", "", now)
        store.removeWord("张伟明")
        assertTrue(store.formedWordsFor("zhangweiming").isEmpty())
        assertTrue(store.formedWordsFor(T9Pinyin.toT9("zhangweiming")).isEmpty())
        assertEquals(0.0, store.formedWeight("张伟明"), 0.0)
        assertTrue(store.follows("张伟明").isEmpty())
        assertTrue(store.follows("你好").isEmpty())

        repeat(2) { typeRun(store, "李" to "li", "雷" to "lei") }
        store.removeWord("李雷")
        typeRun(store, "李" to "li", "雷" to "lei")
        assertTrue("pending counter was scrubbed", store.formedWordsFor("lilei").isEmpty())
    }

    @Test
    fun concurrentCommitBurstsDoNotCorrupt() {
        val seed = tempFile("userlearn-seed")
        val seeded = UserLearning { now }
        repeat(3) { typeRun(seeded, "张" to "zhang", "伟" to "wei") }
        repeat(2) { seeded.observeCommit("你好", "世界", "", now) }
        seeded.save(seed)

        val m = UserLearning { now }
        m.load(seed)
        val errors = ConcurrentLinkedQueue<Throwable>()
        fun spin(body: () -> Unit) = Thread {
            try {
                body()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        val saveOut = tempFile("userlearn-save")
        val threads = listOf(
            spin {
                repeat(1500) { i ->
                    val a = String(Character.toChars(0x4E00 + (i % 300)))
                    val b = String(Character.toChars(0x5E00 + (i % 300)))
                    m.observeCommit(null, a, "ma", now + i)
                    m.observeCommit(a, b, "ma", now + i)
                    m.observeBreak()
                }
            },
            spin { repeat(1500) { i -> m.observeCommit("你好", String(Character.toChars(0x6E00 + (i % 200))), "", now + i) } },
            spin { repeat(500) { m.load(seed) } },
            spin {
                repeat(800) {
                    m.follows("你好")
                    m.followBoost("在张伟", "明")
                    m.formedWordsFor("zhangwei")
                    m.readingSnapshot()
                    m.formedWeight("张伟")
                }
            },
            spin { repeat(200) { m.save(saveOut) } },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("no concurrency exception expected, got: ${errors.toList()}", errors.isEmpty())

        val finalOut = tempFile("userlearn-final")
        m.save(finalOut)
        val reloaded = UserLearning { now }
        reloaded.load(finalOut)
        assertFalse(reloaded.dirty)
    }
}
