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
import com.aegis.ime.user.clipEntries
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private fun actionButtons(root: View): List<TextView> = textViews(root).filter {
        it.compoundDrawables[0] != null &&
            (it.background is GradientDrawable || it.background is ImeKeySurface) &&
            it.hasOnClickListeners()
    }
    private fun layout(root: View, width: Int = 480, height: Int = 400) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun click(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    private fun chip(root: View, label: String): TextView? =
        textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() }
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
    private fun categoryScroll(root: View): HorizontalScrollView =
        allViews(root).filterIsInstance<HorizontalScrollView>()
            .single { scroll -> textViews(scroll).any { it.text?.toString() == "默认" && it.hasOnClickListeners() } }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    private fun swipeActions(v: ClipboardView, text: String): List<View> {
        val body = textViews(v).first { it.text?.toString() == text }
        val scroller = ((body.parent as View).parent as ViewGroup).getChildAt(0) as ViewGroup
        val strip = scroller.getChildAt(0) as ViewGroup
        return (0 until strip.childCount).map(strip::getChildAt)
    }

    private fun assertSwipeStrip(v: ClipboardView, text: String, descriptions: List<String>) {
        layout(v)
        val actions = swipeActions(v, text)
        val strip = actions.first().parent as View
        assertEquals(descriptions, actions.map { it.contentDescription?.toString() })
        assertTrue(actions.all { it !is TextView && it.hasOnClickListeners() })
        assertTrue(actions.all { it.width == dp(48) && it.height == dp(48) })
        assertTrue(actions.all { it.background is ImeKeySurface })
        assertEquals(descriptions.size * (dp(48) + dp(4)), strip.width)
        assertEquals(dp(4), actions.first().left)
        assertEquals(strip.width, actions.last().right)
        actions.zipWithNext().forEach { (left, right) -> assertEquals(dp(4), right.left - left.right) }
        val body = textViews(v).first { it.text?.toString() == text }
        assertEquals(-strip.width.toFloat(), (body.parent as View).translationX, 0f)
    }

    private fun assertActionPopup(v: ClipboardView, expectedItems: List<String>): List<TextView> {
        layout(v)
        val overlay = overlayOf(v) as ViewGroup
        val scroll = overlay.getChildAt(0) as ScrollView
        val card = scroll.getChildAt(0)
        val items = textViews(card).filter { it.hasOnClickListeners() }
        val margin = dp(24)
        val expectedWidth = minOf(dp(320), (ctx.resources.displayMetrics.widthPixels - margin * 2).coerceAtLeast(dp(260)))
        assertEquals(expectedItems, items.map { it.text.toString() })
        assertEquals(expectedWidth, scroll.width)
        assertEquals(overlay.width, scroll.left + scroll.right)
        assertEquals(Gravity.CENTER, (scroll.layoutParams as FrameLayout.LayoutParams).gravity)
        assertTrue(scroll.background is GradientDrawable)
        assertEquals(pal.keySurface, bgColor(scroll))
        assertTrue((scroll.background as GradientDrawable).cornerRadius > 0f)
        assertTrue(scroll.clipToOutline)
        assertTrue(scroll.elevation > 0f)
        assertTrue(items.all { it.currentTextColor == pal.keyLabel })
        assertTrue(items.all { Gravity.getAbsoluteGravity(it.gravity, it.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT })
        return items
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


    @Test fun top_plus_adds_phrase_to_current_category_not_a_new_category() {
        var addPhraseCat: String? = null
        var addCategoryFired = false
        val v = phraseView().apply { onAddPhrase = { addPhraseCat = it }; onAddCategory = { addCategoryFired = true } }
        assertTrue("top ＋ present on 常用语 tab", clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_add_phrase)))
        assertEquals("＋ adds a phrase to the CURRENT category", "默认", addPhraseCat)
        assertFalse("＋ no longer creates a category", addCategoryFired)
    }


    @Test fun categorybar_edit_popup_uses_action_style_and_ends_with_cancel() {
        val v = phraseView()
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_phrases)))
        val items = assertActionPopup(
            v,
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_move_category),
                ctx.getString(com.aegis.ime.R.string.clip_add_category),
                ctx.getString(com.aegis.ime.R.string.clip_import_phrases),
                ctx.getString(com.aegis.ime.R.string.clip_export_phrases),
                ctx.getString(com.aegis.ime.R.string.clip_cancel),
            ),
        )
        assertTrue(items.last().performClick())
        assertEquals(View.GONE, overlayOf(v).visibility)
    }

    @Test fun category_long_press_popup_uses_action_style_and_preserves_actions() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply {
            onRenameCategory = { renamed = it }
            onDeleteCategory = { deleted = it }
        }
        val category = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作")
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")
        assertTrue(category.performLongClick())
        assertEquals(
            listOf(ctx.getString(com.aegis.ime.R.string.clip_rename), "工作", ctx.getString(com.aegis.ime.R.string.clip_delete), "工作"),
            labels(overlayOf(v)),
        )
        assertTrue(clickDesc(overlayOf(v), rename))
        assertEquals("工作", renamed)
        assertNull(deleted)
        assertTrue(category.performLongClick())
        assertTrue(clickDesc(overlayOf(v), delete))
        assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("工作", deleted)
    }

    @Test fun manage_menu_move_category_enters_category_sort_mode() {
        val v = phraseView()
        clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_phrases)); assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_move_category)))
        assertTrue("移动分类 → category sort mode", v.isCategorySortModeForTest())
        assertFalse("does not enter phrase sort mode", v.isSortModeForTest())
        val ls = labels(v)
        assertTrue("category sort header", ctx.getString(com.aegis.ime.R.string.clip_drag_category) in ls); assertTrue("done button", ctx.getString(com.aegis.ime.R.string.clip_done) in ls)
    }

    @Test fun manage_menu_add_category_triggers_inline_create() {
        var addCategoryFired = false
        val v = phraseView().apply { onAddCategory = { addCategoryFired = true } }
        clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_phrases)); assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_add_category)))
        assertTrue("添加分类 → inline 新建分类", addCategoryFired)
    }

    @Test fun sort_mode_done_exits() {
        val v = phraseView(); v.enterSortModeForTest()
        assertTrue(v.isSortModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_done))); assertFalse(v.isSortModeForTest())
    }

    @Test fun sort_mode_drag_reorders_current_category() {
        var reorder: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> reorder = Triple(c, f, t) } }
        v.enterSortModeForTest()
        v.dragStartForTest(0); v.dragMoveToForTest(2); v.dragDropForTest()
        assertEquals("drag in 排序模式 reorders the current category", Triple("默认", 0, 2), reorder)
    }

    @Test fun category_sort_mode_drag_reorders_categories_not_phrases() {
        var categoryReorder: Pair<Int, Int>? = null
        var phraseReorder: Triple<String, Int, Int>? = null
        val v = phraseView().apply {
            onReorderCategory = { from, to -> categoryReorder = from to to }
            onReorderPhrase = { c, f, t -> phraseReorder = Triple(c, f, t) }
        }
        v.enterCategorySortModeForTest()
        v.dragStartForTest(0); v.dragMoveToForTest(1); v.dragDropForTest()
        assertEquals("category drag calls category reorder callback", 0 to 1, categoryReorder)
        assertNull("category drag must not call phrase reorder", phraseReorder)
    }

    @Test fun category_sort_mode_updates_category_chip_order() {
        val cats = mutableListOf("默认", "工作", "私人")
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }
            phrasesInProvider = { emptyList() }
            onReorderCategory = { from, to -> cats.add(to, cats.removeAt(from)) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        v.enterCategorySortModeForTest()
        v.dragStartForTest(2); v.dragMoveToForTest(0); v.dragDropForTest()
        assertEquals(listOf("私人", "默认", "工作"), labels(v).filter { it in cats })
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_done)))
        val chipOrder = textViews(v)
            .filter { it.text?.toString() in cats && it.hasOnClickListeners() }
            .map { it.text.toString() }
        assertEquals("category chips follow persisted category order", listOf("私人", "默认", "工作"), chipOrder)
    }


    @Test fun clipboard_left_swipe_reveals_add_edit_split_delete_icon_strip() {
        val v = clipView()
        v.revealSwipeForTest("hello")
        assertEquals("hello", v.swipeRevealedForTest())
        assertSwipeStrip(
            v,
            "hello",
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_add_phrase),
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
        )
        assertTrue(actionButtons(v).isEmpty())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_expand) in descs(v))
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_collapse) in descs(v))
    }

    @Test fun a_narrow_card_scrolls_the_swipe_strip_to_reach_every_action() {
        val v = clipView()
        v.revealSwipeForTest("hello")
        layout(v, width = dp(160))
        val actions = swipeActions(v, "hello")
        val strip = actions.first().parent as View
        val scroller = strip.parent as HorizontalScrollView
        assertEquals("the strip keeps every action at full size", 4 * (dp(48) + dp(4)), strip.width)
        assertTrue("the scroller is capped to the card, not the strip", scroller.width < strip.width)
        assertFalse("the platform scrollbar stays off", scroller.isHorizontalScrollBarEnabled)
        val range = strip.width - scroller.width
        scroller.scrollTo(range, 0)
        assertEquals("the far edge of the strip lands inside the viewport", strip.width, scroller.scrollX + scroller.width)
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_delete), actions.last().contentDescription?.toString())

        val wide = clipView()
        wide.revealSwipeForTest("hello")
        layout(wide)
        val wideStrip = swipeActions(wide, "hello").first().parent as View
        assertEquals("a wide card shows the whole strip with nothing to scroll", wideStrip.width, (wideStrip.parent as View).width)
    }

    @Test fun clipboard_arrow_expansion_replaces_swipe_with_labeled_actions() {
        val v = clipView()
        v.revealSwipeForTest("hello")
        assertEquals("hello", v.swipeRevealedForTest())
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_expand)))
        assertNull(v.swipeRevealedForTest())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_collapse) in descs(v))
        assertEquals(
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_phrases),
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
            actionButtons(v).map { it.text.toString() },
        )
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_collapse)))
        assertTrue(actionButtons(v).isEmpty())
    }

    @Test fun clipboard_longpress_menu_unchanged() {
        val v = clipView()
        val body = textViews(v).first { it.text?.toString() == "hello" }
        assertTrue("body keeps its long-press menu", body.performLongClick())
        val ls = labels(overlayOf(v))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_split_title), ctx.getString(com.aegis.ime.R.string.clip_split_title) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_item), ctx.getString(com.aegis.ime.R.string.clip_delete_item) in ls)
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

    @Test fun clipboard_batch_delete_cancel_close_and_confirm_preserve_selection_until_confirmation() {
        val deleted = ArrayList<List<String>>()
        val v = clipView().apply { onDeleteClips = { deleted.add(it) }; enterSelectForTest(listOf("hello")) }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertTrue(deleted.isEmpty())
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        overlayOf(v).performClick()
        assertTrue(deleted.isEmpty())
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf(listOf("hello")), deleted)
        assertFalse(v.isSelectModeForTest())
    }


    @Test fun phrase_left_swipe_reveals_edit_note_move_delete_icon_strip() {
        val v = phraseView()
        v.revealSwipeForTest("在吗")
        assertSwipeStrip(
            v,
            "在吗",
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_note),
                ctx.getString(com.aegis.ime.R.string.clip_move),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
        )
        assertTrue(actionButtons(v).isEmpty())
        assertFalse(labels(v).any { it == "置顶" || it == "Pin to top" })
    }

    @Test fun category_switch_clears_a_stale_swipe_reveal() {
        val v = phraseView()
        v.revealSwipeForTest("在吗"); assertEquals("在吗", v.swipeRevealedForTest())
        assertTrue("switch to 工作 chip", click(v, "工作"))
        assertNull("a category switch drops the stale reveal", v.swipeRevealedForTest())
    }

    @Test fun a_decided_horizontal_gesture_never_commits() {
        val picked = ArrayList<String>()
        val v = clipView().apply { onPick = { picked.add(it) } }
        v.settleSwipeForTest(-3f, "hello"); assertEquals("any leftward swipe reveals", "hello", v.swipeRevealedForTest())
        v.hideSwipeForTest()
        v.settleSwipeForTest(0f, "hello")
        assertTrue("a decided horizontal gesture never 上屏s", picked.isEmpty())
        assertNull("a non-leftward drift does not reveal", v.swipeRevealedForTest())
    }

    @Test fun clear_left_swipe_reveals_clear_right_swipe_hides() {
        val v = clipView()
        v.settleSwipeForTest(-100f, "hello"); assertEquals("clear left → reveal", "hello", v.swipeRevealedForTest())
        v.settleSwipeForTest(100f, "hello"); assertNull("clear right → hide", v.swipeRevealedForTest())
    }

    @Test fun phrase_arrow_and_swipe_render_distinct_action_content() {
        val v = phraseView().apply { expandForTest("你好") }
        val arrowActions = actionButtons(v).map { it.text.toString() }
        v.revealSwipeForTest("你好")
        assertEquals(listOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete)), arrowActions)
        assertTrue(actionButtons(v).isEmpty())
        assertSwipeStrip(
            v,
            "你好",
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_note),
                ctx.getString(com.aegis.ime.R.string.clip_move),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
        )
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

    @Test fun split_blocks_start_neutral() {
        val v = clipView()
        v.showSplitForTest("你好abc def")
        val a = chip(overlayOf(v), "你好"); val b = chip(overlayOf(v), "abc")
        assertTrue("blocks present", a != null && b != null)
        assertEquals("block default uses enter background", pal.accentBottom, bgColor(a!!))
        assertEquals("block default uses enter background", pal.accentBottom, bgColor(b!!))
        assertEquals("block default text uses enter label", pal.accentLabel, a.currentTextColor)
        assertEquals("block default text uses enter label", pal.accentLabel, b.currentTextColor)
        assertTrue("nothing copied yet", v.splitSelectedForTest().isEmpty())
    }

    @Test fun split_block_tap_toggles_selection_without_copying_and_panel_stays_open() {
        val changed = ArrayList<String>()
        val copied = ArrayList<List<String>>()
        val v = clipView().apply {
            onSplitSelectionChanged = { changed.add(it) }
            onCopyBlocksToAegis = { copied.add(it) }
        }
        v.showSplitForTest("你好abc def")
        val a = chip(overlayOf(v), "你好")!!
        val b = chip(overlayOf(v), "abc")!!
        val defaultBg = bgColor(a)
        val defaultText = a.currentTextColor
        a.performClick()
        assertEquals(listOf("你好"), changed)
        assertTrue("single-item selection does not invoke clipboard recording", copied.isEmpty())
        assertEquals("selected block uses selected background", pal.chipBg, bgColor(a))
        assertEquals("selected block uses selected text", pal.chipText, a.currentTextColor)
        assertNotEquals("selected background differs from default", defaultBg, bgColor(a))
        assertNotEquals("selected text differs from default", defaultText, a.currentTextColor)
        assertEquals("untapped block keeps default background", defaultBg, bgColor(b))
        assertEquals("untapped block keeps default text", defaultText, b.currentTextColor)
        assertEquals(setOf(0), v.splitSelectedForTest())
        a.performClick()
        assertEquals(listOf("你好", ""), changed)
        assertEquals(defaultBg, bgColor(a))
        assertEquals(defaultText, a.currentTextColor)
        assertTrue(v.splitSelectedForTest().isEmpty())
        assertTrue(copied.isEmpty())
        assertEquals("panel stays open", View.VISIBLE.toLong(), overlayOf(v).visibility.toLong())
    }

    @Test fun split_copy_all_uses_one_batch_without_changing_selection_or_chip_styles() {
        val copied = ArrayList<List<String>>()
        val changed = ArrayList<String>()
        val v = clipView().apply {
            onCopyBlocksToAegis = { copied.add(it) }
            onSplitSelectionChanged = { changed.add(it) }
        }
        v.showSplitForTest("你好abc def")
        val blockLabels = listOf("你好", "abc", "def")
        val chips = blockLabels.map { chip(overlayOf(v), it)!! }
        chips[1].performClick()
        val stateBefore = v.splitSelectedForTest()
        val stylesBefore = chips.map { bgColor(it) to it.currentTextColor }
        val changesBefore = changed.toList()
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
        assertEquals(listOf(blockLabels), copied)
        assertEquals(stateBefore, v.splitSelectedForTest())
        assertEquals(stylesBefore, chips.map { bgColor(it) to it.currentTextColor })
        assertEquals(changesBefore, changed)
        assertEquals(View.VISIBLE, overlayOf(v).visibility)
    }

    @Test fun split_copy_all_uses_batch_callback_once_when_available() {
        val batches = ArrayList<List<String>>()
        val v = clipView().apply { onCopyBlocksToAegis = { batches.add(it) } }
        v.showSplitForTest("你好abc def")
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
        assertEquals(listOf(listOf("你好", "abc", "def")), batches)
    }

    @Test fun split_chooser_hides_original_preview_and_accents_copy_all() {
        val v = clipView()
        v.showSplitForTest("你好abc def")
        assertFalse("original preview removed", "你好abc def" in labels(overlayOf(v)))
        assertEquals(pal.keyLabel, textViews(overlayOf(v)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_back) }.currentTextColor)
        val copyAll = textViews(overlayOf(v)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_copy_all) }
        assertEquals(pal.accentBottom, copyAll.currentTextColor)
        assertEquals(Motion.withAlpha(pal.accentBottom, 0x22), bgColor(copyAll))
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

    @Test fun manage_menu_offers_import_and_export() {
        val imports = ArrayList<Boolean>(); var exp = 0
        val v = phraseView().apply { onImportPhrasesWithMode = { imports.add(it) }; onExportPhrases = { exp++ } }
        v.showPhraseManageMenuForTest()
        val ls = labels(overlayOf(v))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_import_phrases) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_export_phrases) in ls)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
        assertTrue("import opens the in-panel confirmation", ctx.getString(com.aegis.ime.R.string.clip_overwrite) in labels(overlayOf(v)) && ctx.getString(com.aegis.ime.R.string.clip_merge_recommended) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_merge_recommended)))
        assertEquals(listOf(true), imports)
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_overwrite)))
        assertEquals(listOf(true, false), imports)
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_export_phrases))); assertEquals(1, exp)
    }

    @Test fun import_confirmation_uses_normal_panel_colors() {
        val v = phraseView()
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
        val expected = setOf(
            ctx.getString(com.aegis.ime.R.string.clip_import_phrases),
            ctx.getString(com.aegis.ime.R.string.clip_import_body),
            ctx.getString(com.aegis.ime.R.string.clip_overwrite),
            ctx.getString(com.aegis.ime.R.string.clip_merge_recommended),
            ctx.getString(com.aegis.ime.R.string.clip_cancel),
        )
        val views = textViews(overlayOf(v)).filter { it.text?.toString() in expected }
        assertEquals(expected.size, views.size)
        assertTrue(views.all { it.currentTextColor == pal.keyLabel })
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

    @Test fun clipboard_and_phrase_tabs_keep_order_and_bounds_while_switching() {
        val clipboard = ctx.getString(com.aegis.ime.R.string.clip_clipboard)
        val phrases = ctx.getString(com.aegis.ime.R.string.clip_phrases)
        val view = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("clipboard body") }
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { listOf("phrase body") }
            applyPalette(pal)
            refresh()
        }
        fun layoutView() {
            view.measure(
                View.MeasureSpec.makeMeasureSpec((600 * ctx.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((400 * ctx.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        fun absoluteBounds(child: View): Rect {
            var x = 0
            var y = 0
            var current: View? = child
            while (current != null) {
                x += current.left
                y += current.top
                current = current.parent as? View
            }
            return Rect(x, y, x + child.width, y + child.height)
        }
        fun tabs(): List<Pair<String, Rect>> = textViews(view)
            .filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            .map { it.text.toString() to absoluteBounds(it) }
            .sortedBy { it.second.left }

        layoutView()
        val initial = tabs()
        assertEquals(listOf(clipboard, phrases), initial.map { it.first })
        assertEquals(2, initial.size)
        assertTrue(initial.all { it.second.width() == (76 * ctx.resources.displayMetrics.density).toInt() })
        assertTrue(initial.all { it.second.height() == (34 * ctx.resources.displayMetrics.density).toInt() })
        assertTrue(click(view, phrases))
        layoutView()
        assertFalse(view.isClipboardTabForTest())
        assertEquals(initial, tabs())
        assertTrue("phrase body" in labels(view))
        val phraseTab = textViews(view).first { it.text?.toString() == phrases }
        val tray = phraseTab.parent as View
        val plus = allViews(view).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_phrase) }
        val list = allViews(view).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit_phrases) }
        assertTrue(plus.background is ImeKeySurface)
        assertTrue(list.background is ImeKeySurface)
        val trayToPlus = absoluteBounds(plus).left - absoluteBounds(tray).right
        val plusToList = absoluteBounds(list).left - absoluteBounds(plus).right
        assertTrue(trayToPlus > 0)
        assertEquals(trayToPlus, plusToList)
        assertTrue(click(view, clipboard))
        layoutView()
        assertTrue(view.isClipboardTabForTest())
        assertEquals(initial, tabs())
        assertTrue("clipboard body" in labels(view))
    }

    @Test fun phrase_category_row_uses_a_capsule_with_edit_outside_the_scroll() {
        val v = phraseView()
        val category = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
        val inactiveCategory = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        val manage = textViews(v).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit) && it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_phrases) }
        val categoryScroll = categoryScroll(v)
        assertEquals(pal.candidateFirst, category.currentTextColor)
        assertEquals(pal.keyLabel, inactiveCategory.currentTextColor)
        assertEquals(pal.keyLabel, manage.currentTextColor)
        assertTrue(category.background is GradientDrawable)
        assertTrue(inactiveCategory.background == null)
        val manageSurface = manage.background as ImeKeySurface
        assertEquals(Color.TRANSPARENT, manageSurface.faceColor)
        assertEquals(
            ImeShapes.toolbarPillRadiusDp * ctx.resources.displayMetrics.density,
            manageSurface.cornerRadiusPx,
            0f,
        )
        assertNull(manage.foreground)
        assertTrue(categoryScroll.parent === manage.parent)
        val categorySurface = (categoryScroll.parent as View).background as GradientDrawable
        assertTrue(categorySurface.cornerRadius > 0f)
        assertFalse(allViews(categoryScroll).contains(manage))
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_phrases)))
    }

    @Test fun edit_chrome_and_action_rows_use_enabled_colors_and_rounded_surfaces() {
        val selected = clipView().apply { enterSelectForTest() }
        val selectAll = textViews(selected).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_select_all) }
        val cancel = textViews(selected).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_back) }
        assertEquals(pal.keyLabel, selectAll.currentTextColor)
        assertEquals(pal.keyLabel, cancel.currentTextColor)
        assertTrue(selectAll.background is ImeKeySurface)
        assertTrue(cancel.background is ImeKeySurface)
        assertEquals("select all is a text action without a key face", Color.TRANSPARENT, bgColor(selectAll))
        assertEquals("cancel is a text action without a key face", Color.TRANSPARENT, bgColor(cancel))

        val categorySort = phraseView().apply { enterCategorySortModeForTest() }
        val done = textViews(categorySort).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_done) }
        val dragCategories = textViews(categorySort).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_drag_category) }
        assertEquals(pal.keyLabel, done.currentTextColor)
        assertEquals(dragCategories.currentTextColor, done.currentTextColor)
        assertTrue(done.background is ImeKeySurface)

        val expanded = clipView().apply { expandForTest("hello") }
        val actions = textViews(expanded)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, actions.size)
        assertTrue(actions.all { it.currentTextColor == pal.keyLabel })
    }

    @Test fun select_mode_action_buttons_are_text_actions_when_enabled() {
        val clip = clipView().apply { enterSelectForTest(listOf("hello")) }
        for (label in listOf(ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_delete))) {
            val button = textViews(clip).first { it.text?.toString() == label }
            assertTrue("$label keeps the shared key feedback surface", button.background is ImeKeySurface)
            assertEquals("$label draws no key face", Color.TRANSPARENT, bgColor(button))
            assertEquals("$label uses body text color", pal.keyLabel, button.currentTextColor)
            assertTrue("$label remains clickable when enabled", button.hasOnClickListeners())
        }

        val phrase = phraseView().apply { enterSelectForTest(listOf("你好")) }
        for (label in listOf(ctx.getString(com.aegis.ime.R.string.clip_move_to_category), ctx.getString(com.aegis.ime.R.string.clip_delete))) {
            val button = textViews(phrase).first { it.text?.toString() == label }
            assertTrue("$label keeps the shared key feedback surface", button.background is ImeKeySurface)
            assertEquals("$label draws no key face", Color.TRANSPARENT, bgColor(button))
            assertEquals("$label uses body text color", pal.keyLabel, button.currentTextColor)
            assertTrue("$label remains clickable when enabled", button.hasOnClickListeners())
        }
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

    @Test fun dropdown_actions_expand_below_without_translating_the_foreground() {
        val v = clipView(listOf("a long clip"))
        layout(v)
        val closedBody = textViews(v).first { it.text?.toString() == "a long clip" }
        assertEquals(0f, (closedBody.parent as View).translationX, 0f)
        v.expandForTest("a long clip")
        layout(v)
        val openBody = textViews(v).first { it.text?.toString() == "a long clip" }
        assertEquals(0f, (openBody.parent as View).translationX, 0f)
        assertEquals(2, openBody.maxLines)
        assertEquals(4, actionButtons(v).size)
    }
}
