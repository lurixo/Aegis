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

import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.user.asClipEntries
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopupInsetTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun inset(): Int =
        (2 * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.body, ctx.resources.displayMetrics)).roundToInt()

    private fun layout(v: View, w: Int = 480, h: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }

    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun text(root: View, label: String): TextView = textViews(root).first { it.text?.toString() == label }
    private fun overlayOf(v: ClipboardView): ViewGroup = (v as ViewGroup).getChildAt(1) as ViewGroup
    private fun cardOf(v: ClipboardView): View = overlayOf(v).getChildAt(0)
    private fun box(root: ViewGroup, view: View): Rect =
        Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    private fun textLeft(root: ViewGroup, tv: TextView): Int = box(root, tv).left + tv.totalPaddingLeft
    private fun textRight(root: ViewGroup, tv: TextView): Int = box(root, tv).right - tv.totalPaddingRight

    private fun assertPaddedTwoCharacters(name: String, tv: TextView) {
        assertEquals("$name starts two characters in", inset(), tv.paddingLeft)
        assertEquals("$name ends two characters in", inset(), tv.paddingRight)
    }

    private fun assertStartsTwoCharactersIn(name: String, v: ClipboardView, tv: TextView) =
        assertEquals("$name text starts two characters in", box(v, cardOf(v)).left + inset(), textLeft(v, tv))

    private fun assertEndsTwoCharactersIn(name: String, v: ClipboardView, tv: TextView) =
        assertEquals("$name text ends two characters in", box(v, cardOf(v)).right - inset(), textRight(v, tv))

    private fun phraseView(categories: List<String> = listOf("默认", "工作")): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { categories }
        phrasesInProvider = { c -> if (c == categories.first()) listOf("你好") else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest(categories.first()); refresh()
    }

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { emptyList() }
        applyPalette(pal); refresh()
    }

    private fun longPressTab(v: ClipboardView, name: String) {
        layout(v)
        assertTrue(textViews(v).first { it.text?.toString() == name && it.isLongClickable }.performLongClick())
        layout(v)
    }

    private fun clickDesc(root: View, desc: String) =
        allViews(root).first { it.contentDescription?.toString() == desc && it.hasOnClickListeners() }.performClick()

    @Test fun the_category_menu_sits_two_characters_in() {
        val v = phraseView()
        longPressTab(v, "工作")
        val menu = overlayOf(v)
        val rename = text(menu, ctx.getString(R.string.clip_rename))
        val delete = text(menu, ctx.getString(R.string.clip_delete))
        assertStartsTwoCharactersIn("category menu ${rename.text}", v, rename)
        assertEndsTwoCharactersIn("category menu ${delete.text}", v, delete)
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh")
    fun one_row_menu_actions_are_at_least_48dp_wide_and_keep_the_two_character_inset() {
        val touch = (48 * ctx.resources.displayMetrics.density).toInt()
        val categories = phraseView()
        longPressTab(categories, "工作")
        val clips = clipView(listOf("第一条"))
        layout(clips)
        assertTrue(textViews(clips).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
        layout(clips)
        for ((name, v) in listOf("category menu" to categories, "clipboard item menu" to clips)) {
            val actions = textViews(overlayOf(v)).filter { it.hasOnClickListeners() }.sortedBy { box(v, it).left }
            assertTrue("$name: precondition: one row of actions", actions.size >= 2 && actions.all { box(v, it).top == box(v, actions[0]).top })
            for (action in actions) assertTrue("$name: ${action.text} is at least 48dp wide", action.width >= touch)
            assertStartsTwoCharactersIn("$name ${actions.first().text}", v, actions.first())
            assertEndsTwoCharactersIn("$name ${actions.last().text}", v, actions.last())
        }
    }

    @Test fun confirmations_put_their_text_and_actions_two_characters_in() {
        val deleteCategory = phraseView().also { v ->
            longPressTab(v, "工作")
            assertTrue(clickDesc(overlayOf(v), ctx.getString(R.string.clip_delete_named, "工作")))
        }
        val clearCategory = phraseView().apply { confirmClearForTest() }
        val clearHistory = clipView(listOf("第一条")).apply { confirmClearHistoryForTest() }
        for ((name, v, question, action) in listOf(
            Quad("delete category", deleteCategory, ctx.getString(R.string.clip_delete_category_confirm, "工作"), ctx.getString(R.string.clip_delete)),
            Quad("clear category", clearCategory, ctx.getString(R.string.clip_clear_category_confirm, "默认"), ctx.getString(R.string.clip_clear)),
            Quad("clear history", clearHistory, ctx.getString(R.string.clip_clear_history_confirm), ctx.getString(R.string.clip_clear)),
        )) {
            layout(v)
            val overlay = overlayOf(v)
            assertPaddedTwoCharacters("$name question", text(overlay, question))
            assertStartsTwoCharactersIn("$name $action", v, text(overlay, action))
            assertEndsTwoCharactersIn("$name cancel", v, text(overlay, ctx.getString(R.string.clip_cancel)))
        }
    }

    private data class Quad(val name: String, val view: ClipboardView, val question: String, val action: String)

    @Test fun the_split_panel_sits_two_characters_in() {
        val entry = "今天天气很好我们一起去公园散步吧"
        val v = clipView(listOf(entry)).apply { showSplitForTest(entry) }
        layout(v)
        val overlay = overlayOf(v)
        val card = box(v, cardOf(v))
        assertStartsTwoCharactersIn("split title", v, text(overlay, ctx.getString(R.string.clip_split_title)))
        val chips = box(v, allViews(overlay).filterIsInstance<HorizontalScrollView>().single())
        assertEquals("the words start two characters in", card.left + inset(), chips.left)
        assertEquals("the words stop two characters in", card.right - inset(), chips.right)
        assertStartsTwoCharactersIn("split back", v, text(overlay, ctx.getString(R.string.clip_back)))
        assertEndsTwoCharactersIn("split copy all", v, text(overlay, ctx.getString(R.string.clip_copy_all)))
    }

    @Test fun panel_confirmations_put_their_question_two_characters_in() {
        val overlay = PanelConfirmationOverlay(ctx)
        overlay.show("Clear recent items?", "Clear", "Cancel", pal) {}
        layout(overlay)
        assertPaddedTwoCharacters("panel confirmation question", text(overlay, "Clear recent items?"))
    }

    @Test fun translate_mode_choices_sit_two_characters_in() {
        val bar = TranslateBarView(ctx).apply { applyPalette(pal) }
        layout(bar, 480, 200)
        bar.modeButtonForTest().performClick()
        val card = requireNotNull(bar.dialogForTest()).contentView as ViewGroup
        val choices = (0 until card.childCount).map { card.getChildAt(it) as TextView }
        assertTrue(choices.isNotEmpty())
        for (choice in choices) assertPaddedTwoCharacters("translate choice ${choice.text}", choice)
        bar.dismissModeDialog()
    }

    @Test fun the_two_character_inset_follows_the_system_font_size() {
        try {
            for (scale in listOf(1f, 1.3f, 2f)) {
                RuntimeEnvironment.setFontScale(scale)
                assertEquals("precondition: the system font is at $scale", scale, ctx.resources.configuration.fontScale, 0.001f)
                val v = phraseView()
                longPressTab(v, "工作")
                val rename = text(overlayOf(v), ctx.getString(R.string.clip_rename))
                assertEquals("x$scale: the menu inset is two body characters", (2 * rename.textSize).roundToInt(), textLeft(v, rename) - box(v, cardOf(v)).left)
                val overlay = PanelConfirmationOverlay(ctx)
                overlay.show("Clear recent items?", "Clear", "Cancel", pal) {}
                val action = requireNotNull(overlay.confirmActionForTest()) as TextView
                assertEquals(
                    "x$scale: the confirmation inset is two body characters",
                    (2 * action.textSize).roundToInt(),
                    text(overlay, "Clear recent items?").paddingLeft,
                )
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
