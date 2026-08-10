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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AtomicFileSwapTest {

    private val dirs = ArrayList<File>()

    @After fun letTheFilesGo() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("swap").toFile().also { dirs += it }

    private fun leftovers(dir: File): List<String> =
        dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun a_finished_swap_leaves_only_the_destination() {
        val dir = newDir()
        val dest = File(dir, "clipboard.txt").apply { writeText("旧内容") }

        AtomicFileSwap.write(dest, tag = 1, text = "新内容")

        assertEquals("新内容", dest.readText())
        assertEquals(listOf("clipboard.txt"), leftovers(dir))
    }

    @Test fun a_destination_survives_a_swap_whose_staged_copy_is_gone() {
        val dir = newDir()
        val dest = File(dir, "clipboard.txt").apply { writeText("整份剪贴板") }
        val staged = AtomicFileSwap.stagingFor(dest, 7)
        assertFalse("precondition: the staged copy is not there", staged.exists())

        try {
            AtomicFileSwap.replace(staged, dest)
            fail("expected a swap with nothing to put in place to be reported")
        } catch (e: IOException) {
            assertEquals(
                "a swap with nothing to put in place must say so, not blame the destination",
                "the staged copy of clipboard.txt is gone",
                e.message,
            )
        }

        assertTrue("a swap that cannot finish must never delete the destination", dest.isFile)
        assertEquals("整份剪贴板", dest.readText())
    }

    @Test fun a_destination_that_is_not_a_file_is_left_exactly_as_it_was() {
        val dir = newDir()
        val dest = File(dir, "phrases.txt")
        assertTrue("precondition: the destination path is occupied", dest.mkdirs())
        File(dest, "blocker").writeText("x")
        val staged = AtomicFileSwap.stagingFor(dest, 3).apply { writeText("新常用语") }

        try {
            AtomicFileSwap.replace(staged, dest)
            fail("expected a blocked destination to be reported")
        } catch (e: IOException) {
            assertEquals(
                "a destination that will not move out of the way must be named as the reason",
                "phrases.txt could not be moved out of the way",
                e.message,
            )
        }

        assertTrue(dest.isDirectory)
        assertEquals(listOf("blocker"), leftovers(dest))
        assertEquals("the staged copy must not be left behind", listOf("phrases.txt"), leftovers(dir))
    }

    @Test fun a_write_that_never_reached_the_disk_leaves_nothing_behind() {
        val dir = newDir()
        val dest = File(dir, "symbol_usage.txt").apply { writeText("★") }
        val staged = AtomicFileSwap.stagingFor(dest, 5)
        assertTrue("precondition: the staging path is occupied", staged.mkdirs())

        try {
            AtomicFileSwap.write(dest, tag = 5, text = "☆")
            fail("expected a write that could not be staged to be reported")
        } catch (e: IOException) {
            assertTrue(
                "a write that never reached the disk must be reported against the staged copy, was: ${e.message}",
                e.message.orEmpty().startsWith(staged.path),
            )
        }

        assertEquals("★", dest.readText())
        assertEquals(listOf("symbol_usage.txt"), leftovers(dir))
    }

    private class StagedCopyThatWillNotMove(
        path: String,
        private val onSecondTry: (File) -> Unit,
    ) : File(path) {
        private var tries = 0

        override fun renameTo(dest: File): Boolean {
            tries++
            if (tries == 2) onSecondTry(dest)
            return false
        }
    }

    @Test fun a_destination_that_could_not_be_put_back_is_reported_as_gone() {
        val dir = newDir()
        val dest = File(dir, "userdb.txt").apply { writeText("旧词库") }
        val staged = StagedCopyThatWillNotMove(AtomicFileSwap.stagingFor(dest, 9).path) { blocked ->
            blocked.mkdirs()
            File(blocked, "blocker").writeText("x")
        }
        staged.writeText("新词库")

        try {
            AtomicFileSwap.replace(staged, dest)
            fail("expected a destination that could not be put back to be reported")
        } catch (e: IOException) {
            assertEquals(
                "a destination that is gone must not be reported as one that was left as it was",
                "userdb.txt is gone and what it held is kept as userdb.txt.9.tmp.kept",
                e.message,
            )
        }

        assertEquals(
            "what the destination held must still be somewhere it can be found",
            "旧词库",
            File(dir, "userdb.txt.9.tmp.kept").readText(),
        )
    }

    @Test fun a_destination_that_was_put_back_is_reported_as_one_that_kept_what_it_held() {
        val dir = newDir()
        val dest = File(dir, "userdb.txt").apply { writeText("旧词库") }
        val staged = StagedCopyThatWillNotMove(AtomicFileSwap.stagingFor(dest, 9).path) { }
        staged.writeText("新词库")

        try {
            AtomicFileSwap.replace(staged, dest)
            fail("expected a swap that could not be carried out to be reported")
        } catch (e: IOException) {
            assertEquals(
                "a destination that was put back must not be reported as one that is gone",
                "userdb.txt could not be replaced",
                e.message,
            )
        }

        assertEquals("旧词库", dest.readText())
        assertEquals("nothing but the destination may be left behind", listOf("userdb.txt"), leftovers(dir))
    }

    @Test fun two_writers_over_one_destination_never_stage_through_one_path() {
        val dest = File(newDir(), "clipboard.txt")

        assertNotEquals(AtomicFileSwap.stagingFor(dest, 1), AtomicFileSwap.stagingFor(dest, 2))
        assertEquals(dest.parentFile, AtomicFileSwap.stagingFor(dest, 1).parentFile)
    }
}
