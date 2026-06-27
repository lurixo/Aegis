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
import android.graphics.Color
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

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)
    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)
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
    private fun click(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    private fun bgColor(v: View): Int? = when (val background = v.background) {
        is GradientDrawable -> background.color?.defaultColor
        is ImeKeySurface -> background.faceColor
        else -> null
    }
    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun descs(root: View): List<String> = allViews(root).mapNotNull { it.contentDescription?.toString() }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
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

    @Test fun category_long_press_popup_uses_action_style_and_preserves_actions() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply {
            onRenameCategory = { renamed = it }
            onDeleteCategory = { deleted = it }
        }
        layout(v)
        val category = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作")
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")
        assertTrue(category.performLongClick())
        assertEquals(
            listOf(ctx.getString(com.aegis.ime.R.string.clip_rename), ctx.getString(com.aegis.ime.R.string.clip_delete)),
            labels(overlayOf(v)),
        )
        val actions = textViews(overlayOf(v)).filter { it.hasOnClickListeners() }
        assertEquals(listOf(rename, delete), actions.map { it.contentDescription?.toString() })
        assertTrue(actions.all { v.isImmediateActionForTest(it) && bgColor(it) == Color.TRANSPARENT && it.currentTextColor == pal.keyLabel })
        assertTrue(clickDesc(overlayOf(v), rename))
        assertEquals("工作", renamed)
        assertNull(deleted)
        assertTrue(category.performLongClick())
        assertTrue(clickDesc(overlayOf(v), delete))
        assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("工作", deleted)
    }

    @Test fun clipboard_item_delete_cancels_and_confirms_without_early_mutation() {
        val deleted = ArrayList<List<String>>()
        val v = clipView().apply { onDeleteClips = { deleted.add(it) }; expandForTest("hello") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_clip_confirm) in labels(overlayOf(v)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf(listOf("hello")), deleted)
    }

    @Test fun clipboard_longpress_delete_opens_the_same_confirmation() {
        val deleted = ArrayList<List<String>>()
        val v = clipView().apply { onDeleteClips = { deleted.add(it) } }
        assertTrue(textViews(v).first { it.text?.toString() == "hello" }.performLongClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_item)))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_clip_confirm) in labels(overlayOf(v)))
        overlayOf(v).performClick()
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertTrue(deleted.isEmpty())
        assertTrue(textViews(v).first { it.text?.toString() == "hello" }.performLongClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_item)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertTrue(deleted.isEmpty())
        assertTrue(textViews(v).first { it.text?.toString() == "hello" }.performLongClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_item)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf(listOf("hello")), deleted)
    }

    @Test fun phrase_item_delete_cancels_and_confirms_without_early_mutation() {
        val deleted = ArrayList<Pair<String, List<String>>>()
        val v = phraseView().apply { onDeletePhrasesFrom = { category, items -> deleted.add(category to items) }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_phrase_confirm) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf("默认" to listOf("你好")), deleted)
    }



    private fun deleteTargetInChooser(v: ClipboardView, name: String) {
        fun groups(x: View): Sequence<ViewGroup> = sequence {
            if (x is ViewGroup) { yield(x); for (i in 0 until x.childCount) yieldAll(groups(x.getChildAt(i))) }
        }
        val row = groups(overlayOf(v)).firstOrNull { g ->
            val kids = (0 until g.childCount).map { g.getChildAt(it) }
            kids.any { it is TextView && it.text?.toString() == name } && kids.any { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete_category) }
        } ?: error("no chooser row for $name")
        (0 until row.childCount).map { row.getChildAt(it) }.first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete_category) }.performClick()
    }

    private fun moveChooserView(cats: MutableList<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { cats }
        phrasesInProvider = { _ -> listOf("你好") }
        onDeleteCategory = { cats.remove(it) }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        showMoveChooserForTest("默认")
    }

    @Test fun move_chooser_delete_category_reuses_onDeleteCategory_and_refreshes() {
        val cats = mutableListOf("默认", "工作", "私人")
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }; phrasesInProvider = { _ -> listOf("你好") }
            onDeleteCategory = { deleted.add(it); cats.remove(it) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showMoveChooserForTest("默认")
        }
        assertTrue("工作 + 私人 listed", "工作" in labels(overlayOf(v)) && "私人" in labels(overlayOf(v)))
        deleteTargetInChooser(v, "工作")
        assertTrue("trash only opens the confirmation", deleted.isEmpty())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("delete reuses onDeleteCategory", listOf("工作"), deleted)
        val ls = labels(overlayOf(v))
        assertFalse("工作 gone after refresh", "工作" in ls)
        assertTrue("私人 still there", "私人" in ls)
        assertFalse("panel categoryBar chip also refreshed (no stale 工作 chip)", "工作" in labels(mainOf(v)))
    }

    @Test fun move_chooser_trash_does_not_trigger_a_move() {
        val cats = mutableListOf("默认", "工作")
        var moved: Triple<String, String, String>? = null
        val v = moveChooserView(cats)
        v.onMovePhrase = { f, t, to -> moved = Triple(f, t, to) }
        v.showMoveChooserForTest("默认")
        deleteTargetInChooser(v, "工作")
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertNull("🗑 must delete only, never move", moved)
    }

    @Test fun category_menu_delete_requires_confirmation_before_onDeleteCategory() {
        var deleted: String? = null
        val v = phraseView().apply { onDeleteCategory = { deleted = it } }
        val chip = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")))
        assertTrue(
            "the confirmation names the category and its phrases",
            ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "工作") in labels(overlayOf(v)),
        )
        assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertNull(deleted)
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("工作", deleted)
    }

    @Test fun move_chooser_trash_requires_confirmation_and_returns_to_the_chooser() {
        val cats = mutableListOf("默认", "工作", "私人")
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }; phrasesInProvider = { _ -> listOf("你好") }
            onDeleteCategory = { deleted.add(it); cats.remove(it) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showMoveChooserForTest("默认")
        }
        deleteTargetInChooser(v, "工作")
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "工作") in labels(overlayOf(v)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertTrue(deleted.isEmpty())
        assertTrue("cancel returns to the move chooser", "工作" in labels(overlayOf(v)))
        deleteTargetInChooser(v, "工作")
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf("工作"), deleted)
        assertTrue("confirm returns to the chooser", "私人" in labels(overlayOf(v)))
        assertFalse("the deleted target is gone from the chooser", "工作" in labels(overlayOf(v)))
    }

    @Test fun move_chooser_name_tap_still_moves() {
        var moved: Triple<String, String, String>? = null
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "工作") }; phrasesInProvider = { _ -> listOf("你好") }
            onMovePhrase = { f, t, to -> moved = Triple(f, t, to) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showMoveChooserForTest("默认")
        }
        assertTrue("tap the target name", click(overlayOf(v), "工作"))
        assertEquals("name tap still moves (unchanged)", Triple("默认", "", "工作"), moved)
    }

    @Test fun move_chooser_offers_new_category_alongside_targets() {
        val v = phraseView()
        v.expandForTest("你好"); assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        val ls = labels(overlayOf(v))
        assertTrue("target 工作 present", "工作" in ls)
        assertTrue("＋ 新建分类… available in the non-empty chooser too", ctx.getString(com.aegis.ime.R.string.clip_new_category) in ls)
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

    @Test fun expanded_phrase_card_has_a_note_action() {
        var note: Pair<String, String>? = null
        val v = phraseView().apply { onEditNote = { c, t -> note = c to t }; expandForTest("你好") }
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_note) in labels(v))
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_note)))
        assertEquals("默认" to "你好", note)
    }

    @Test fun phrase_tab_last_top_icon_clears_current_category_with_confirm() {
        var cleared: String? = null
        val v = phraseView().apply { onClearCategory = { cleared = it } }
        assertTrue("phrase tab top bar carries the clear-category icon", ctx.getString(com.aegis.ime.R.string.clip_clear_category) in descs(mainOf(v)))
        assertFalse("⚙ gear is NOT on the phrase tab", "设置" in descs(mainOf(v)))
        v.confirmClearForTest()
        val ls = labels(overlayOf(v))
        assertTrue("confirm overlay (二次确认)", ctx.getString(com.aegis.ime.R.string.clip_clear) in ls && ctx.getString(com.aegis.ime.R.string.clip_cancel) in ls)
        assertNull("nothing cleared until confirmed", cleared)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_clear))); assertEquals("clears the CURRENT category", "默认", cleared)
    }

    @Test fun clipboard_tab_top_right_icon_confirms_before_clearing_history() {
        var clears = 0
        val v = clipView().apply { onClearHistory = { clears++; true } }
        assertTrue("clipboard tab top bar carries clear-history", ctx.getString(com.aegis.ime.R.string.clip_clear_history) in descs(mainOf(v)))
        assertFalse("old settings gear is not present", "设置" in descs(mainOf(v)))
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_clear_history)))
        assertEquals("tap only opens confirmation", 0, clears)
        assertTrue("confirmation offers clear", ctx.getString(com.aegis.ime.R.string.clip_clear) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_clear)))
        assertEquals("confirmed clear fires once", 1, clears)
    }

    @Test fun clipboard_recording_toggle_is_visible_and_clear_has_no_long_press_action() {
        val v = clipView()
        assertTrue(
            "history recording toggle is visible in the former blank slot",
            ctx.getString(com.aegis.ime.R.string.clip_pause_history) in descs(mainOf(v)),
        )
        val clear = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_clear_history)
        }
        assertFalse("clear-history no longer owns the recording toggle", clear.isLongClickable)
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

    @Test fun clear_confirmation_title_and_actions_use_body_text_color() {
        val phrase = phraseView()
        phrase.confirmClearForTest()
        val phraseViews = textViews(overlayOf(phrase)).filter {
            it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clear_category_confirm, "默认"), ctx.getString(com.aegis.ime.R.string.clip_clear), ctx.getString(com.aegis.ime.R.string.clip_cancel))
        }
        assertEquals(3, phraseViews.size)
        assertTrue(phraseViews.all { it.currentTextColor == pal.keyLabel })
        val phraseActions = phraseViews.filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clear), ctx.getString(com.aegis.ime.R.string.clip_cancel)) }
        assertEquals(2, phraseActions.size)

        val clip = clipView()
        clip.confirmClearHistoryForTest()
        val clipViews = textViews(overlayOf(clip)).filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clear_history_confirm), ctx.getString(com.aegis.ime.R.string.clip_clear), ctx.getString(com.aegis.ime.R.string.clip_cancel)) }
        assertEquals(3, clipViews.size)
        assertTrue(clipViews.all { it.currentTextColor == pal.keyLabel })
        val clipActions = clipViews.filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_clear), ctx.getString(com.aegis.ime.R.string.clip_cancel)) }
        assertEquals(2, clipActions.size)
    }

    @Test fun chooser_titles_use_body_text_color() {
        val move = phraseView().apply { showMoveChooserForTest("默认") }
        assertEquals(pal.keyLabel, textViews(overlayOf(move)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_move_to_category) }.currentTextColor)

        val singleTarget = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { _ -> listOf("你好") }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            showMoveChooserForTest("默认")
        }
        assertEquals(pal.keyLabel, textViews(overlayOf(singleTarget)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_no_other_categories) }.currentTextColor)

        val category = clipView().apply { expandForTest("hello") }
        textViews(category)
            .first { tv -> tv.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) && tv.compoundDrawables.any { d -> d != null } }
            .performClick()
        assertEquals(pal.keyLabel, textViews(overlayOf(category)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_choose_category) }.currentTextColor)
    }
}
