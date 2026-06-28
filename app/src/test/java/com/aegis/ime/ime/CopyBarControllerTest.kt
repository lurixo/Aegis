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

package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taskbar copy-bar flow: split-block → aegis clipboard (NOT 上屏 / NOT system). */
class CopyBarControllerTest {

    private val commits = mutableListOf<String>()
    private val aegisCopies = mutableListOf<String>()
    private var dismissed = 0
    private fun ctl() = CopyBarController(
        commit = { commits.add(it) },
        copyToAegis = { aegisCopies.add(it) },
        dismiss = { dismissed++ },
    )

    @Test fun copy_enters_the_bar() {
        val c = ctl()
        c.show("你好hello,world!")
        assertTrue(c.active)
        assertEquals("你好hello,world!", c.content)
        assertFalse(c.splitMode)
        c.show("   ") // blank ignored: keeps the previous content
        assertEquals("你好hello,world!", c.content)
    }

    @Test fun split_uses_clipsplitter_blocks() {
        val c = ctl()
        c.show("你好hello,world!")
        c.toggleSplit()
        assertTrue(c.splitMode)
        assertEquals(listOf("你好", "hello", ",", "world", "!"), c.blocks)
        c.toggleSplit() // fold back
        assertFalse(c.splitMode)
        assertTrue(c.blocks.isEmpty())
    }

    @Test fun tapping_a_block_writes_aegis_clipboard_only_not_commit_not_system() {
        val c = ctl()
        c.show("看这个https://x.com很好")
        c.toggleSplit()
        c.tapBlock("https://x.com")
        assertEquals("block goes to the aegis clipboard", listOf("https://x.com"), aegisCopies)
        assertTrue("a block must NOT be committed to the editor (上屏)", commits.isEmpty())
        assertEquals("a block must NOT dismiss the bar — pick several", 0, dismissed)
        assertTrue("bar stays open after copying a block", c.active)
        // (No setPrimaryClip path exists at all — copyToAegis is the only sink wired to ClipboardStore.record.)
    }

    @Test fun tapping_the_content_commits_then_leaves() {
        val c = ctl()
        c.show("整条内容")
        c.tapContent()
        assertEquals(listOf("整条内容"), commits)
        assertTrue("content tap does not write the aegis clipboard", aegisCopies.isEmpty())
        assertFalse("bar is gone after 上屏", c.active)
    }

    @Test fun close_dismisses_without_committing() {
        val c = ctl()
        c.show("x")
        c.close()
        assertEquals(1, dismissed)
        assertTrue(commits.isEmpty() && aegisCopies.isEmpty())
        assertFalse(c.active)
    }
}
