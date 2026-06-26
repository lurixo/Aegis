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

import com.aegis.ime.user.asClipEntries
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Debug17PanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) { if (x is TextView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun layout(root: View, width: Int = 480, height: Int = 400) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun phraseView(phrases: List<String> = listOf("你好", "在吗", "稍等")): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else listOf("已收到") }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }
    private fun clipView(history: List<String> = listOf("hello")): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }; categoriesProvider = { listOf("默认") }
        applyPalette(pal); refresh()
    }

    @Test fun phrase_note_is_displayed_but_pick_commits_the_original() {
        val picked = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { _ -> listOf("longoriginal") }
            phraseNoteProvider = { _, t -> if (t == "longoriginal") "别名" else "" }
            onPick = { picked.add(it) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        assertTrue("list shows the NOTE", "别名" in labels(v))
        assertFalse("list does NOT show the original text", "longoriginal" in labels(v))
        textViews(v).first { it.text?.toString() == "别名" }.performClick()
        assertEquals("tapping the note commits the ORIGINAL text", listOf("longoriginal"), picked)
    }

    @Test fun phrase_without_note_shows_original_text() {
        assertTrue("你好" in labels(phraseView()))
    }

    @Test fun clipboard_and_phrase_tabs_share_a_capsule_and_highlight_the_selected_half() {
        val clip = clipView()
        layout(clip)
        val tabs = textViews(clip).filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clipboard), ctx.getString(com.aegis.ime.R.string.clip_phrases)) }
        assertEquals(2, tabs.size)
        val clipTab = tabs.first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_clipboard) }
        val phraseTab = tabs.first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) }
        assertTrue(clipTab.parent === phraseTab.parent)
        assertTrue(clipTab.parent is View)
        val tray = clipTab.parent as View
        assertTrue(tray.background is GradientDrawable)
        assertEquals((34 * ctx.resources.displayMetrics.density).toInt(), tray.layoutParams.height)
        assertTrue((tray.background as GradientDrawable).cornerRadius >= 17 * ctx.resources.displayMetrics.density)
        assertTrue(clipTab.background is GradientDrawable)
        assertNull(phraseTab.background)
        assertEquals(0, clipTab.left)
        assertEquals(clipTab.right, phraseTab.left)
        assertEquals(tray.width, phraseTab.right)
        val leftRadii = (clipTab.background as GradientDrawable).cornerRadii!!
        assertTrue(leftRadii[0] > 0f && leftRadii[2] == 0f && leftRadii[4] == 0f && leftRadii[6] > 0f)
        assertEquals(pal.candidateFirst, clipTab.currentTextColor)
        assertEquals(pal.keyLabel, phraseTab.currentTextColor)

        val phrase = phraseView()
        layout(phrase)
        val phraseTabs = textViews(phrase).filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clipboard), ctx.getString(com.aegis.ime.R.string.clip_phrases)) }
        val phraseClipTab = phraseTabs.first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_clipboard) }
        val selectedPhraseTab = phraseTabs.first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) }
        assertNull(phraseClipTab.background)
        assertTrue(selectedPhraseTab.background is GradientDrawable)
        val rightRadii = (selectedPhraseTab.background as GradientDrawable).cornerRadii!!
        assertTrue(rightRadii[0] == 0f && rightRadii[2] > 0f && rightRadii[4] > 0f && rightRadii[6] == 0f)
        assertEquals(pal.keyLabel, phraseClipTab.currentTextColor)
        assertEquals(pal.candidateFirst, selectedPhraseTab.currentTextColor)
    }
}
