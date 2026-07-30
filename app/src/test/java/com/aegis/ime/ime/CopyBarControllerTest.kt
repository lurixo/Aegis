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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyBarControllerTest {

    private val commits = mutableListOf<String>()
    private val selectionChanges = mutableListOf<String>()
    private var selectionFinishes = 0
    private var dismissed = 0
    private fun ctl() = CopyBarController(
        commit = { commits.add(it) },
        selectionChanged = { selectionChanges.add(it) },
        selectionFinished = { selectionFinishes++ },
        dismiss = { dismissed++ },
    )

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun copy_enters_the_bar() {
        val c = ctl()
        c.show("你好hello,world!")
        assertTrue(c.active)
        assertEquals("你好hello,world!", c.content)
        assertFalse(c.splitMode)
        c.show("   ")
        assertEquals("你好hello,world!", c.content)
    }

    @Test fun split_uses_clipsplitter_blocks() {
        val c = ctl()
        c.show("你好hello,world!")
        c.toggleSplit()
        assertTrue(c.splitMode)
        assertEquals(listOf("你好", "hello", ",", "world", "!"), c.blocks)
        c.toggleSplit()
        assertFalse(c.splitMode)
        assertTrue(c.blocks.isEmpty())
    }

    @Test fun split_matches_clipboard_url_boundaries_and_has_no_copy_all_action() {
        val c = ctl()
        c.show("read https://x.com now")
        c.toggleSplit()
        assertEquals(listOf("read", "https://", "x.com", "now"), c.blocks)

        val context = RuntimeEnvironment.getApplication()
        val view = CopyBarView(context)
        view.show("read https://x.com now")
        view.toggleSplitForTest()
        assertEquals(c.blocks, view.splitBlocksForTest())
        assertFalse(
            context.getString(com.aegis.ime.R.string.clip_copy_all) in labels(view),
        )
    }

    @Test fun tapping_blocks_rebuilds_the_projection_in_source_order_and_toggles_by_index() {
        val c = ctl()
        c.show("检查一下，检查")
        c.toggleSplit()
        assertEquals(listOf("检查", "一下", "，", "检查"), c.blocks)

        assertTrue(c.tapBlock(0) == true)
        assertEquals("检查", selectionChanges.last())
        assertTrue(c.tapBlock(2) == true)
        assertEquals("检查，", selectionChanges.last())
        assertTrue(c.tapBlock(1) == true)
        assertEquals("检查一下，", selectionChanges.last())
        assertTrue(c.tapBlock(0) == false)
        assertEquals("一下，", selectionChanges.last())
        assertEquals(setOf(1, 2), c.selectedIndices())

        assertTrue("split selection does not commit the copied content", commits.isEmpty())
        assertEquals("a block must not dismiss the bar", 0, dismissed)
        assertTrue("bar stays open while selecting blocks", c.active)
    }

    @Test fun leaving_split_mode_finishes_one_selection_session() {
        val c = ctl()
        c.show("甲，乙")
        c.toggleSplit()
        c.tapBlock(1)
        assertEquals(0, selectionFinishes)

        c.toggleSplit()
        assertEquals(1, selectionFinishes)
        c.close()
        assertEquals("the finished session cannot finish twice", 1, selectionFinishes)
    }

    @Test fun tapping_the_content_commits_then_leaves() {
        val c = ctl()
        c.show("整条内容")
        c.tapContent()
        assertEquals(listOf("整条内容"), commits)
        assertTrue("content tap does not create a split projection", selectionChanges.isEmpty())
        assertEquals("⑤ leaves the copy-bar (symmetric with ×)", 1, dismissed)
        assertFalse("bar is gone after 上屏", c.active)
    }

    @Test fun close_dismisses_without_committing() {
        val c = ctl()
        c.show("x")
        c.close()
        assertEquals(1, dismissed)
        assertTrue(commits.isEmpty() && selectionChanges.isEmpty())
        assertFalse(c.active)
    }
}
