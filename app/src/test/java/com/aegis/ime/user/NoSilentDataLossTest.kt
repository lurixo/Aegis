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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoSilentDataLossTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L
    private var learnNow = 1_000_000_000_000L

    private fun han(codePoint: Int): String = String(Character.toChars(codePoint))

    private fun letters(index: Int): String {
        val sb = StringBuilder(4)
        var v = index
        repeat(4) {
            sb.append('a' + (v % 26))
            v /= 26
        }
        return sb.toString()
    }

    private fun countRows(file: File): Int {
        var rows = 0
        file.bufferedReader().use { r -> while (r.readLine() != null) rows++ }
        return rows - 1
    }

    private fun assertSameSequence(what: String, expected: List<String>, actual: List<String>) {
        assertEquals("$what: entry count", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("$what: entry $i is \"${actual[i]}\", expected \"${expected[i]}\"")
            }
        }
    }

    private fun clipEntries(n: Int): List<String> = List(n) { "clip-%07d".format(it) }

    private fun typeRun(l: UserLearning, vararg commits: Pair<String, String>) {
        var prev: String? = null
        for ((word, reading) in commits) {
            l.observeCommit(prev, word, reading, learnNow)
            prev = word
        }
        l.observeBreak()
    }

    @Test fun aClipboardFileLongerThanTheOldCeilingLoadsEveryEntry() {
        val dir = tmp.newFolder()
        val entries = clipEntries(OLD_HISTORY_CEILING + 100)
        File(dir, "clipboard.txt").bufferedWriter().use { w ->
            for (e in entries) {
                w.write(e)
                w.write("\n")
            }
        }
        val loaded = ClipboardStore(dir).apply { load() }.history()
        assertSameSequence("an oversized clipboard file", entries, loaded)
    }

    @Test fun recordingPastTheOldCeilingKeepsTheOldestEntry() {
        val dir = tmp.newFolder()
        val seeded = clipEntries(OLD_HISTORY_CEILING + 50)
        val store = ClipboardStore(dir).apply {
            load()
            importHistory(seeded, merge = false)
        }
        store.record("brand new")
        store.flushPendingWrites()
        val expected = listOf("brand new") + seeded
        assertSameSequence("a capture past the old ceiling", expected, store.history())
        assertSameSequence(
            "a capture past the old ceiling, reread from disk",
            expected,
            ClipboardStore(dir).apply { load() }.history(),
        )
    }

    @Test fun importingPastTheOldCeilingKeepsEveryEntry() {
        val incoming = clipEntries(OLD_HISTORY_CEILING + 60)

        val replaceDir = tmp.newFolder()
        val replaced = ClipboardStore(replaceDir).apply {
            load()
            importHistory(incoming, merge = false)
        }
        assertSameSequence("a replacing import", incoming, replaced.history())
        assertSameSequence(
            "a replacing import, reread from disk",
            incoming,
            ClipboardStore(replaceDir).apply { load() }.history(),
        )

        val mergeDir = tmp.newFolder()
        val existing = listOf("kept from before the import")
        val merged = ClipboardStore(mergeDir).apply {
            load()
            importHistory(existing, merge = false)
            importHistory(incoming, merge = true)
        }
        assertSameSequence("a merging import", existing + incoming, merged.history())
        assertSameSequence(
            "a merging import, reread from disk",
            existing + incoming,
            ClipboardStore(mergeDir).apply { load() }.history(),
        )
    }

    private fun writeTallUserDb(file: File, words: Int) {
        val m = UserModel { clock }
        for (i in 0 until words) m.recordWord(letters(i), "词$i", clock, incrementCount = true)
        m.save(file)
    }

    @Test fun aUserDictionaryPastTheOldRowAndByteCeilingsSavesAndLoadsWhole() {
        val words = OLD_USERDB_ROW_CEILING / 2 + 15_000
        val db = File(tmp.root, "userdb.txt")
        writeTallUserDb(db, words)

        assertTrue(
            "the fixture must be past the old byte ceiling, it is ${db.length()}",
            db.length() > OLD_USERDB_BYTE_CEILING,
        )
        val rows = countRows(db)
        assertTrue("the fixture must be past the old row ceiling, it has $rows", rows > OLD_USERDB_ROW_CEILING)
        assertEquals("every word is written with a reading", words * 2, rows)

        val entries = UserModel { clock }.apply { load(db) }.userWordEntries()
        assertEquals("no user word is dropped on the way back in", words, entries.size)
        val byReading = HashMap<String, String>(words * 2)
        for (e in entries) {
            if (e.count != 1) throw AssertionError("the count of ${e.word} came back as ${e.count}")
            byReading[e.reading] = e.word
        }
        for (i in 0 until words) {
            val back = byReading[letters(i)]
            if (back != "词$i") throw AssertionError("reading ${letters(i)} came back as $back")
        }
    }

    @Test fun aUserDictionaryFileWrittenPastTheOldCeilingsStillLoads() {
        val prevs = 560
        val db = File(tmp.root, "legacy.txt")
        db.bufferedWriter().use { w ->
            w.write("aegis-userdb 1\n")
            for (i in 0 until prevs) w.write("W\t词$i\t1\t$clock\n")
            for (i in 0 until prevs) w.write("R\t${letters(i)}\t词$i\n")
            for (i in 0 until prevs) for (j in 0 until prevs) w.write("B\t词$i\t词$j\t1\n")
        }

        assertTrue(
            "the fixture must be past the old byte ceiling, it is ${db.length()}",
            db.length() > OLD_USERDB_BYTE_CEILING,
        )
        val rows = countRows(db)
        assertTrue("the fixture must be past the old row ceiling, it has $rows", rows > OLD_USERDB_ROW_CEILING)

        val model = UserModel { clock }.apply { load(db) }
        assertEquals("every word in the old file is back", prevs, model.userWordEntries().size)
        for (i in 0 until prevs) {
            val successors = model.successors("词$i", prevs)
            if (successors.size != prevs) {
                throw AssertionError("词$i came back with ${successors.size} of $prevs successors")
            }
        }
    }

    private fun writeWideUserDb(file: File, words: Int) {
        val wordFiller = "漢".repeat(249)
        val readingFiller = "a".repeat(196)
        file.bufferedWriter().use { w ->
            w.write("aegis-userdb 2\n")
            for (i in 0 until words) w.write("W\t$wordFiller${han(0x4E00 + i)}\t1\t$clock\n")
            for (i in 0 until words) {
                w.write("R\t$readingFiller${letters(i)}\t$wordFiller${han(0x4E00 + i)}\n")
            }
        }
    }

    @Test fun importingAUserDictionaryPastTheOldByteCeilingKeepsEveryWord() {
        val words = 6_000
        val incoming = File(tmp.root, "incoming.txt")
        writeWideUserDb(incoming, words)
        assertTrue(
            "the fixture must be past the old byte ceiling, it is ${incoming.length()}",
            incoming.length() > OLD_USERDB_BYTE_CEILING,
        )

        val replaced = File(tmp.root, "replaced.txt")
        assertTrue(
            "a replacing import of an oversized dictionary is accepted",
            UserDictImport.apply(incoming, replaced, merge = false, now = clock),
        )
        assertEquals(
            "the replacing import keeps every word",
            words,
            UserModel { clock }.apply { load(replaced) }.userWordEntries().size,
        )

        val mergeTarget = File(tmp.root, "merged.txt")
        UserModel { clock }.apply { addManualWord("zwm", "张伟明", clock) }.save(mergeTarget)
        assertTrue(
            "a merging import of an oversized dictionary is accepted",
            UserDictImport.apply(incoming, mergeTarget, merge = true, now = clock),
        )
        assertEquals(
            "the merging import keeps both sides",
            words + 1,
            UserModel { clock }.apply { load(mergeTarget) }.userWordEntries().size,
        )
    }

    @Test fun stagingAUserDictionaryPastTheOldByteCeilingWritesEveryByte() {
        val source = File(tmp.root, "source.bin")
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        source.outputStream().buffered().use { out ->
            var written = 0L
            while (written <= OLD_USERDB_BYTE_CEILING) {
                out.write(chunk)
                written += chunk.size
            }
        }
        assertTrue(
            "the fixture must be past the old byte ceiling, it is ${source.length()}",
            source.length() > OLD_USERDB_BYTE_CEILING,
        )

        val staged = File(tmp.root, "staged.bin")
        assertTrue(
            "staging an oversized dictionary is accepted",
            source.inputStream().use { UserDictImport.stage(it, staged) },
        )
        assertEquals("every staged byte is kept", source.length(), staged.length())
    }

    @Test fun gluedWordsFarPastTheOldCapSurviveSaveAndLoad() {
        val store = UserLearning { learnNow }
        val total = OLD_FORMED_CEILING + 700
        val words = ArrayList<String>(total)
        for (i in 0 until total) {
            val a = han(0x4E00 + i)
            val b = han(0x6E00 + i)
            words.add(a + b)
            repeat(3) { typeRun(store, a to "ma", b to "ma") }
            learnNow += 60_000L
        }
        assertEquals("no glued word is evicted while learning", total, store.formedEntries().size)

        val file = File(tmp.root, "userlearn.txt")
        store.save(file)
        val reloaded = UserLearning { learnNow }.apply { load(file) }
        assertEquals("no glued word is dropped on the way back in", total, reloaded.formedEntries().size)
        val kept = reloaded.formedWordsFor("mama").toHashSet()
        for (w in words) if (w !in kept) throw AssertionError("glued word $w is gone after a reload")
    }

    @Test fun aLearningFileWithMoreGluedWordsThanTheOldCapLoadsWhole() {
        val rows = OLD_FORMED_CEILING + 400
        val file = File(tmp.root, "formed.txt")
        file.bufferedWriter().use { w ->
            w.write("aegis-userlearn 1\n")
            for (i in 0 until rows) {
                w.write("F\t${letters(i)}\t${han(0x4E00 + i)}${han(0x6E00 + i)}\t2.5\t$learnNow\n")
            }
        }
        assertTrue(
            "the fixture must stay under the old byte ceiling, it is ${file.length()}",
            file.length() < OLD_LEARN_BYTE_CEILING,
        )
        val store = UserLearning { learnNow }.apply { load(file) }
        assertEquals("every glued word in the old file is back", rows, store.formedEntries().size)
    }

    @Test fun aLearningFileWithMorePendingRunsThanTheOldCapLoadsWhole() {
        val rows = OLD_PENDING_CEILING + 500
        val a = han(0x9000)
        val b = han(0x9100)
        val file = File(tmp.root, "pending.txt")
        file.bufferedWriter().use { w ->
            w.write("aegis-userlearn 1\n")
            w.write("F\tzhaa\t${han(0x9200)}${han(0x9300)}\t2.5\t$learnNow\n")
            w.write("P\tmama\t$a$b\t2.0\t$learnNow\n")
            for (i in 1 until rows) {
                w.write("P\t${letters(i)}\t${han(0x4E00 + i)}${han(0x6E00 + i)}\t1.0\t$learnNow\n")
            }
        }
        assertTrue(
            "the fixture must stay under the old byte ceiling, it is ${file.length()}",
            file.length() < OLD_LEARN_BYTE_CEILING,
        )

        val store = UserLearning { learnNow }.apply { load(file) }
        assertEquals("the oversized file is loaded rather than thrown away", 1, store.formedEntries().size)
        typeRun(store, a to "ma", b to "ma")
        assertTrue(
            "a pending run past the old cap is still one sighting away from promotion",
            a + b in store.formedWordsFor("mama"),
        )
    }

    @Test fun aLearningFileWithMoreCollocationKeysThanTheOldCapLoadsWhole() {
        val keys = OLD_FOLLOW_PREV_CEILING + 200
        val file = File(tmp.root, "follows.txt")
        file.bufferedWriter().use { w ->
            w.write("aegis-userlearn 1\n")
            w.write("F\tzhaa\t${han(0x9200)}${han(0x9300)}\t2.5\t$learnNow\n")
            for (i in 0 until keys) {
                w.write("C\t${han(0x4E00 + i)}\t${han(0x7000 + i)}\t1.0\t$learnNow\n")
            }
        }
        assertTrue(
            "the fixture must stay under the old byte ceiling, it is ${file.length()}",
            file.length() < OLD_LEARN_BYTE_CEILING,
        )

        val store = UserLearning { learnNow }.apply { load(file) }
        assertEquals("the oversized file is loaded rather than thrown away", 1, store.formedEntries().size)
        for (i in 0 until keys) {
            val got = store.follows(han(0x4E00 + i))
            if (got.size != 1 || got[0].first != han(0x7000 + i)) {
                throw AssertionError("collocation key $i came back as $got")
            }
        }
    }

    @Test fun collocationsPastTheOldFileCeilingSaveAndReloadWhole() {
        val prevs = 10_000
        val perPrev = UserLearning.FOLLOW_PER_PREV
        val store = UserLearning { learnNow }
        for (i in 0 until prevs) {
            val prev = han(0x4E00 + i) + han(0x4E00)
            for (j in 0 until perPrev) store.observeCommit(prev, han(0x6000 + j) + han(0x7000), "", learnNow)
        }
        for (i in 0 until prevs) {
            val got = store.follows(han(0x4E00 + i) + han(0x4E00))
            if (got.size != perPrev) throw AssertionError("collocation key $i holds ${got.size} of $perPrev")
        }

        val file = File(tmp.root, "biglearn.txt")
        store.save(file)
        assertTrue(
            "the fixture must be past the old byte ceiling, it is ${file.length()}",
            file.length() > OLD_LEARN_BYTE_CEILING,
        )

        val reloaded = UserLearning { learnNow }.apply { load(file) }
        for (i in 0 until prevs) {
            val got = reloaded.follows(han(0x4E00 + i) + han(0x4E00))
            if (got.size != perPrev) throw AssertionError("collocation key $i came back with ${got.size} of $perPrev")
            for (j in 0 until perPrev) {
                val word = han(0x6000 + j) + han(0x7000)
                if (got.none { it.first == word }) throw AssertionError("collocation $i lost $word")
            }
        }
    }

    @Test fun pendingRunsPastTheOldCapAreNotEvicted() {
        val store = UserLearning { learnNow }
        val a = han(0x9000)
        val b = han(0x9100)
        typeRun(store, a to "ma", b to "ma")
        for (i in 0 until OLD_PENDING_CEILING + 500) {
            val x = han(0x4E00 + i)
            val y = han(0x6E00 + i)
            repeat(2) { typeRun(store, x to "ma", y to "ma") }
        }
        typeRun(store, a to "ma", b to "ma")
        typeRun(store, a to "ma", b to "ma")
        assertTrue(
            "a pending run that outlived the old cap still promotes on its third sighting",
            a + b in store.formedWordsFor("mama"),
        )
    }

    private companion object {
        const val OLD_HISTORY_CEILING = 100_000
        const val OLD_USERDB_ROW_CEILING = 250_000
        const val OLD_USERDB_BYTE_CEILING = 4L * 1024L * 1024L
        const val OLD_FORMED_CEILING = 500
        const val OLD_PENDING_CEILING = 2_000
        const val OLD_FOLLOW_PREV_CEILING = 1_500
        const val OLD_LEARN_BYTE_CEILING = 2L * 1024L * 1024L
    }
}
