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

    private companion object {
        const val OLD_USERDB_ROW_CEILING = 250_000
        const val OLD_USERDB_BYTE_CEILING = 4L * 1024L * 1024L
    }
}
