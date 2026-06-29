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
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * debug.16: the 常用语 tab's per-phrase long-press menu (编辑 / 移动到分类 / 删除) and its move-target chooser.
 * Menu items live in the ClipboardView's overlay (child 1), so interactions are scoped THERE — clicking a
 * label on the whole tree could otherwise hit a same-named category chip in the bottom category bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhrasePanelMenuTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1) // init adds main(0) then overlay(1)

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }

    /** Click the (clickable) menu/chooser item with exactly [label] inside [root]; returns false if not found. */
    private fun click(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick()
        return true
    }

    private fun phraseView(): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗") else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }

    @Test fun phrase_tab_long_press_opens_edit_move_delete_menu() {
        val v = phraseView()
        v.showCardMenuForTest("你好")
        val ls = labels(overlayOf(v))
        assertTrue("编辑" in ls); assertTrue("移动到分类" in ls); assertTrue("删除" in ls)
        assertFalse("clipboard-history menu must not appear on phrases", "拆分选词" in ls)
        assertFalse("添加常用语" in ls)
    }

    @Test fun clipboard_tab_long_press_keeps_history_menu() {
        val v = ClipboardView(ctx).apply { historyProvider = { listOf("abc") }; applyPalette(pal); refresh() }
        v.showCardMenuForTest("abc") // default tab = 剪贴板
        val ls = labels(overlayOf(v))
        assertTrue("删除此条内容" in ls); assertTrue("拆分选词" in ls)
        assertFalse("移动到分类" in ls)
    }

    @Test fun edit_invokes_onEditPhrase_with_current_category_and_phrase() {
        var got: Pair<String, String>? = null
        val v = phraseView().apply { onEditPhrase = { c, t -> got = c to t } }
        v.showCardMenuForTest("你好")
        assertTrue(click(overlayOf(v), "编辑"))
        assertEquals("默认" to "你好", got)
    }

    @Test fun move_chooser_excludes_current_category() {
        val v = phraseView()
        v.showCardMenuForTest("你好")
        assertTrue(click(overlayOf(v), "移动到分类"))
        val chooser = labels(overlayOf(v))
        assertTrue("移动到分类" in chooser) // title
        assertTrue("工作" in chooser); assertTrue("私人" in chooser)
        assertFalse("current category must be excluded as a move target", "默认" in chooser)
    }

    @Test fun move_chooser_pick_invokes_onMovePhrase_with_target() {
        var move: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> move = Triple(f, t, to) } }
        v.showCardMenuForTest("你好")
        assertTrue(click(overlayOf(v), "移动到分类"))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), move)
    }

    @Test fun delete_invokes_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, list -> del = c to list } }
        v.showCardMenuForTest("你好")
        assertTrue(click(overlayOf(v), "删除"))
        assertEquals("默认" to listOf("你好"), del)
    }
}
