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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhrasePanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) { if (x is TextView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun click(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun categoryScroll(root: View): HorizontalScrollView =
        allViews(root).filterIsInstance<HorizontalScrollView>()
            .single { scroll ->
                allViews(scroll).filterIsInstance<TextView>().any(View::isLongClickable)
            }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    private fun clickAction(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() && it.compoundDrawables.any { d -> d != null } } ?: return false
        tv.performClick(); return true
    }
    private fun layout(v: View, w: Int = 480, h: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }
    private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        root.offsetDescendantRectToMyCoords(target, it)
    }
    private fun phraseView(): ClipboardView = phraseView(listOf("你好", "在吗", "稍等"))
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }


    @Test fun expanded_phrase_card_action_row_is_edit_note_move_delete() {
        val v = phraseView().apply { expandForTest("你好") }
        layout(v)
        val expected = listOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete))
        val actions = textViews(v).filter { it.text?.toString() in expected && it.compoundDrawables[0] != null && it.hasOnClickListeners() }
        assertEquals(expected, actions.map { it.text.toString() })
        assertTrue(actions.all { it.compoundDrawables[0] != null && it.text.isNotEmpty() })
        assertFalse(labels(v).any { it == "置顶" || it == "Pin to top" })
    }

    @Test fun expanded_clipboard_card_keeps_add_split_delete() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        layout(v)
        val expected = listOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete))
        val actions = textViews(v).filter { it.text?.toString() in expected && it.compoundDrawables[0] != null && it.hasOnClickListeners() }
        assertEquals(expected, actions.map { it.text.toString() })
        assertTrue(actions.all { it.compoundDrawables[0] != null && it.text.isNotEmpty() })
    }

    @Test fun clipboard_and_phrase_action_buttons_share_height_rounding_and_spacing() {
        val clip = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        layout(clip)
        val phrase = phraseView().apply { expandForTest("你好") }
        layout(phrase)
        val clipActions = textViews(clip)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        val phraseActions = textViews(phrase)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, clipActions.size)
        assertEquals(4, phraseActions.size)
        val all = clipActions + phraseActions
        assertEquals(1, all.map { it.layoutParams.height }.toSet().size)
        assertTrue(all.all { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT })
        assertTrue(all.all { it.compoundDrawablePadding == it.paint.measureText(" ").roundToInt().coerceAtLeast(1) })
        assertTrue(all.all { Gravity.getAbsoluteGravity(it.gravity, it.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT })
        val heightTolerance = 2 * ctx.resources.displayMetrics.density + 1f
        assertTrue(all.all { abs(it.compoundDrawables[0].intrinsicHeight - it.textSize) <= heightTolerance })
        assertTrue(clipActions.all { clip.isImmediateActionForTest(it) })
        assertTrue(phraseActions.all { phrase.isImmediateActionForTest(it) })
        assertTrue(clipActions.all { it.background === clip.immediateActionDrawableForTest(it) })
        assertTrue(phraseActions.all { it.background === phrase.immediateActionDrawableForTest(it) })
        assertTrue(all.all { it.foreground == null && it.height == dp(48) })
        for (action in all) {
            action.draw(Canvas(Bitmap.createBitmap(action.width, action.height, Bitmap.Config.ARGB_8888)))
            val hit = Rect()
            action.getHitRect(hit)
            assertEquals(Rect(action.left, action.top, action.right, action.bottom), hit)
        }
        val gap = (4 * ctx.resources.displayMetrics.density).toInt()
        assertEquals(listOf(0, gap, gap), clipActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
        assertEquals(listOf(0, gap, gap, gap), phraseActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
        for ((view, body, actions) in listOf(Triple(clip, "abc", clipActions), Triple(phrase, "你好", phraseActions))) {
            val row = actions.first().parent as View
            val surface = row.parent as View
            val header = textViews(view).first { it.text?.toString() == body }.parent as View
            val headerFrame = header.parent as View
            assertTrue(headerFrame.parent === surface)
            assertTrue(surface.background is GradientDrawable)
            assertTrue((surface.background as GradientDrawable).cornerRadius > 0f)
            assertTrue(header.background == null)
            assertEquals((row as ViewGroup).paddingLeft, actions.first().left)
        }
    }

    @Test fun edit_action_invokes_onEditPhrase() {
        var got: Pair<String, String>? = null
        val v = phraseView().apply { onEditPhrase = { c, t -> got = c to t }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_edit)))
        assertEquals("默认" to "你好", got)
    }

    @Test fun move_action_opens_chooser_excluding_current_then_invokes_onMovePhrase() {
        var move: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> move = Triple(f, t, to) }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        val chooser = labels(overlayOf(v))
        assertTrue("工作" in chooser); assertTrue("私人" in chooser)
        assertFalse("current category excluded", "默认" in chooser)
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), move)
    }

    @Test fun closing_delete_confirmation_does_not_invoke_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l; true }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertNull(del)
        overlayOf(v).performClick()
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertNull(del)
    }


    @Test fun phrase_select_mode_title_and_batch_actions() {
        val v = phraseView().apply { enterSelectForTest(listOf("你好", "在吗")) }
        val ls = labels(v)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_edit_phrases) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_edit_clipboard) in ls)
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 2, 2) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls)

        v.toggleSelectForTest("你好")
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 1, 1) in labels(v))
    }

    @Test fun clipboard_select_mode_keeps_add_phrase_action() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
        }
        val ls = labels(v)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_edit_clipboard) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 1, 1) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)

        v.toggleSelectForTest("b")
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 2, 2) in labels(v))
    }

    @Test fun select_mode_top_actions_keep_physical_order_and_symmetry_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val clipboard = ClipboardView(ctx).apply {
                this.layoutDirection = layoutDirection
                historyProvider = { clipEntries("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
            }
            val phrases = phraseView().apply {
                this.layoutDirection = layoutDirection
                enterSelectForTest(listOf("你好"))
            }
            val geometries = listOf(clipboard, phrases).map { view ->
                layout(view, w = 480, h = 400)
                val selectAll = checkNotNull(view.selectAllActionForTest())
                val cancel = checkNotNull(view.cancelSelectActionForTest())
                val topBar = selectAll.parent as ViewGroup
                val title = topBar.getChildAt(1)
                assertTrue(cancel.parent === topBar)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, selectAll.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, cancel.layoutParams.width)
                assertEquals(dp(48), selectAll.height)
                assertEquals(dp(48), cancel.height)
                assertTrue(selectAll.left < cancel.left)
                assertEquals(topBar.paddingLeft, selectAll.left)
                assertEquals(topBar.width - topBar.paddingRight, cancel.right)
                assertEquals(selectAll.left, topBar.width - cancel.right)
                assertEquals(selectAll.top, cancel.top)
                assertEquals(selectAll.bottom, cancel.bottom)
                assertTrue(selectAll.right <= title.left)
                assertTrue(title.right <= cancel.left)
                assertEquals(Gravity.CENTER_VERTICAL or Gravity.START, selectAll.gravity)
                assertEquals(Gravity.CENTER, cancel.gravity)
                assertTrue(selectAll.hasOnClickListeners())
                assertTrue(cancel.hasOnClickListeners())
                assertTrue(view.isImmediateActionForTest(selectAll))
                assertTrue(view.isImmediateActionForTest(cancel))
                assertTrue(selectAll.background === view.immediateActionDrawableForTest(selectAll))
                assertTrue(cancel.background === view.immediateActionDrawableForTest(cancel))
                assertTrue(selectAll.foreground == null && cancel.foreground == null)
                assertEquals(selectAll.paint.measureText(" ").roundToInt().coerceAtLeast(1), selectAll.compoundDrawablePadding)

                val bottomLeftLabel = if (view.isClipboardTabForTest()) {
                    ctx.getString(com.aegis.ime.R.string.clip_add_phrase)
                } else {
                    ctx.getString(com.aegis.ime.R.string.clip_move_to_category)
                }
                val bottomLeft = textViews(view).single { it.text?.toString() == bottomLeftLabel }
                val bottom = bottomLeft.parent as ViewGroup
                val bottomRight = textViews(bottom).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete) }
                val selectBounds = boundsInRoot(view, selectAll)
                val cancelBounds = boundsInRoot(view, cancel)
                val bottomLeftBounds = boundsInRoot(view, bottomLeft)
                val bottomRightBounds = boundsInRoot(view, bottomRight)
                assertEquals(dp(48), bottomLeft.height)
                assertEquals(dp(48), bottomRight.height)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bottomLeft.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bottomRight.layoutParams.width)
                assertEquals(selectBounds.left, bottomLeftBounds.left)
                assertEquals(cancelBounds.right, bottomRightBounds.right)
                assertTrue(bottomLeftBounds.right <= bottomRightBounds.left)
                assertTrue(view.isImmediateActionForTest(bottomLeft))
                assertTrue(view.isImmediateActionForTest(bottomRight))
                assertTrue(bottomLeft.background === view.immediateActionDrawableForTest(bottomLeft))
                assertTrue(bottomRight.background === view.immediateActionDrawableForTest(bottomRight))
                assertEquals(selectAll.currentTextColor, bottomLeft.currentTextColor)
                assertEquals(cancel.currentTextColor, bottomRight.currentTextColor)

                val firstRow = checkNotNull(view.listRowViewForTest(0)) as ViewGroup
                val itemCircle = firstRow.getChildAt(0)
                val itemLabel = firstRow.getChildAt(1) as TextView
                val selectAllCircleCenterX = selectBounds.left + selectAll.paddingLeft + selectAll.compoundDrawables[0].intrinsicWidth / 2
                assertEquals("the per-item circle center lines up with the select-all circle center", selectAllCircleCenterX, boundsInRoot(view, itemCircle).left + itemCircle.width / 2)
                assertTrue(boundsInRoot(view, itemCircle).right <= boundsInRoot(view, itemLabel).left)
                listOf(selectAll.left, selectAll.top, selectAll.right, selectAll.bottom, cancel.left, cancel.top, cancel.right, cancel.bottom)
            }
            assertEquals(1, geometries.toSet().size)
        }
    }

    @Test fun select_mode_row_circle_and_text_line_up_with_the_select_all_control() {
        val select = phraseView().apply { enterSelectForTest() }
        layout(select)
        val row = checkNotNull(select.listRowViewForTest(0)) as ViewGroup
        val radio = row.getChildAt(0)
        val label = row.getChildAt(1) as TextView
        val selectAll = checkNotNull(select.selectAllActionForTest())
        val selectAllBounds = boundsInRoot(select, selectAll)

        val selectAllCircleCenterX = selectAllBounds.left + selectAll.paddingLeft + selectAll.compoundDrawables[0].intrinsicWidth / 2
        assertEquals("the per-item circle center lines up with the select-all circle center", selectAllCircleCenterX, boundsInRoot(select, radio).left + radio.width / 2)
        val selectAllTextLeft = selectAllBounds.left + selectAll.totalPaddingLeft
        val rowTextLeft = boundsInRoot(select, label).left + label.totalPaddingLeft
        assertEquals("the row text lines up with the select-all button text", selectAllTextLeft, rowTextLeft)
        assertTrue("the radio stays left of the text", boundsInRoot(select, radio).right <= boundsInRoot(select, label).left)
    }

    @Test fun batch_rows_fill_the_same_edges_as_the_list_cards() {
        val list = phraseView()
        layout(list, w = 480, h = 400)
        val card = boundsInRoot(list, checkNotNull(list.listRowViewForTest(0)))
        val select = phraseView().apply { enterSelectForTest() }
        layout(select, w = 480, h = 400)
        val row = checkNotNull(select.listRowViewForTest(0))
        val bounds = boundsInRoot(select, row)
        val face = (row.background as ImeKeySurface).faceBoundsForTest(row.width, row.height)
        assertEquals((com.aegis.ime.ime.theme.ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt(), card.left)
        assertEquals(card.left.toFloat(), bounds.left + face.left, 0.5f)
        assertEquals(card.right.toFloat(), bounds.left + face.right, 0.5f)
        assertEquals("a row fills its height like a card", RectF(0f, 0f, row.width.toFloat(), row.height.toFloat()), face)
        assertEquals("a row stands where a card does", card, bounds)
        val nextCard = boundsInRoot(list, checkNotNull(list.listRowViewForTest(1)))
        val nextRow = boundsInRoot(select, checkNotNull(select.listRowViewForTest(1)))
        assertEquals("rows keep the cards' spacing", nextCard.top - card.bottom, nextRow.top - bounds.bottom)
        assertEquals(nextCard, nextRow)
    }

    @Test fun batch_move_invokes_onMovePhrasesTo_with_selection_and_target() {
        var batch: Triple<String, List<String>, String>? = null
        val v = phraseView().apply {
            onMovePhrasesTo = { f, list, to -> batch = Triple(f, list, to) }
            enterSelectForTest(listOf("你好", "稍等"))
        }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move_to_category)))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals("默认", batch?.first)
        assertEquals(listOf("你好", "稍等"), batch?.second)
        assertEquals("工作", batch?.third)
    }

    @Test fun batch_delete_requires_confirmation_before_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l; true }; enterSelectForTest(listOf("你好")) }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertNull(del)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_phrase_confirm) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertNull(del)
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        overlayOf(v).performClick()
        assertNull(del)
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("默认", del?.first)
        assertEquals(listOf("你好"), del?.second)
        assertFalse(v.isSelectModeForTest())
    }

    @Test fun category_chip_long_press_offers_rename_and_delete_in_one_row() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply { onRenameCategory = { renamed = it }; onDeleteCategory = { deleted = it } }
        layout(v)
        val chip = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        layout(v)
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename)
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete)
        assertEquals("the menu holds just the two actions", listOf(rename, delete), labels(overlayOf(v)))
        val renameAction = textViews(overlayOf(v)).single { it.text?.toString() == rename }
        val deleteAction = textViews(overlayOf(v)).single { it.text?.toString() == delete }
        val renameBox = boundsInRoot(v, renameAction)
        val deleteBox = boundsInRoot(v, deleteAction)
        assertEquals("the actions share one row", renameBox.top, deleteBox.top)
        assertTrue("rename comes first", renameBox.right <= deleteBox.left)
        assertEquals("rename is a 48dp target", dp(48), renameAction.height)
        assertEquals("delete is a 48dp target", dp(48), deleteAction.height)
        assertEquals("one 48dp row inside the card padding", dp(6) + dp(48) + dp(6), (overlayOf(v) as ViewGroup).getChildAt(0).height)
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"), renameAction.contentDescription?.toString())
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作"), deleteAction.contentDescription?.toString())
        assertTrue(v.isImmediateActionForTest(renameAction))
        assertTrue(v.isImmediateActionForTest(deleteAction))
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"))); assertEquals("工作", renamed)
        layout(v)
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作"))); assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete))); assertEquals("工作", deleted)
    }

    @Test fun the_category_menu_stacks_under_its_name_when_one_row_does_not_fit() {
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename)
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete)
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        fun open(w: Int): ClipboardView = phraseView().also { v ->
            layout(v, w, 320)
            assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
            layout(v, w, 320)
        }
        val wide = open(480)
        val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val natural = listOf(rename, delete).sumOf { label -> textViews(overlayOf(wide)).single { it.text?.toString() == label }.width } +
            2 * (inset - dp(4)) + 2 * side
        assertEquals("a row that just fits stays one row", listOf(rename, delete), labels(overlayOf(open(natural))))
        val narrow = open(natural - 1)
        assertEquals("the name heads the stacked menu once", listOf("工作", rename, delete), labels(overlayOf(narrow)))
        val title = textViews(overlayOf(narrow)).single { it.text?.toString() == "工作" }
        val actions = listOf(rename, delete).map { label -> textViews(overlayOf(narrow)).single { it.text?.toString() == label } }
        assertFalse("the name is a heading, not an action", title.hasOnClickListeners())
        assertEquals("the name is centered over the actions", Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals("the name spans the menu", (title.parent as View).width, title.width)
        assertEquals(
            "the stacked menu keeps the one popup width inside the panel",
            minOf(ImeShapes.popupWidthPx(ctx.resources.displayMetrics), narrow.width - 2 * side),
            (overlayOf(narrow) as ViewGroup).getChildAt(0).width,
        )
        assertTrue("the actions stack", boundsInRoot(narrow, actions[1]).top >= boundsInRoot(narrow, actions[0]).bottom)
        assertTrue("each stacked action keeps a 48dp row", actions.all { it.height == dp(48) })
        assertEquals(
            listOf(ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")),
            actions.map { it.contentDescription?.toString() },
        )
    }
    @Test fun a_long_name_heading_the_stacked_category_menu_stays_on_one_line() {
        val long = "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试标题"
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", long) }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        layout(v, dp(120), 320)
        assertTrue(textViews(v).first { it.text?.toString() == long && it.isLongClickable }.performLongClick())
        layout(v, dp(120), 320)
        val title = textViews(overlayOf(v)).single { it.text?.toString() == long }
        assertFalse("precondition: the menu is stacked under the name", title.hasOnClickListeners())
        assertEquals("the name keeps one line", 1, title.lineCount)
        assertEquals(android.text.TextUtils.TruncateAt.END, title.ellipsize)
        assertTrue("the name is cut short with an ellipsis", title.layout.getEllipsisCount(0) > 0)
        assertTrue("the name stays inside the menu", title.width <= (overlayOf(v) as ViewGroup).getChildAt(0).width)
    }

    @Test fun a_long_category_name_leaves_the_menu_one_row_inside_the_panel() {
        for (long in listOf(
            "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试弹窗宽度",
            "An extremely long category name that keeps going well past the width of the panel",
        )) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", long) }
                phrasesInProvider = { emptyList() }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            }
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == long && it.hasOnClickListeners() }.performLongClick())
            layout(v)
            val card = (overlayOf(v) as ViewGroup).getChildAt(0)
            val cardBox = boundsInRoot(v, card)
            val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
            assertTrue("$long: the menu stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side)
            assertEquals("$long: one row", dp(6) + dp(48) + dp(6), card.height)
            assertEquals(
                "$long: the actions name the category for accessibility",
                listOf(ctx.getString(com.aegis.ime.R.string.clip_rename_named, long), ctx.getString(com.aegis.ime.R.string.clip_delete_named, long)),
                textViews(overlayOf(v)).filter { it.hasOnClickListeners() }.map { it.contentDescription?.toString() },
            )
        }
    }
    @Test fun the_category_menu_opens_just_above_the_pressed_tab() {
        val names = listOf("默认", "工作", "私人", "这是一个很长很长很长的分类名称")
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { names }
            phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗", "稍等") else emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        for ((w, h) in listOf(480 to 320, 480 to 170)) {
            for (name in names) {
                layout(v, w, h)
                val bar = boundsInRoot(v, allViews(v).filterIsInstance<ImePanelCategoryBar>().single())
                val tab = textViews(v).first { it.text?.toString() == name && it.isLongClickable }
                val tabBox = boundsInRoot(v, tab)
                assertTrue(tab.performLongClick())
                layout(v, w, h)
                val card = (overlayOf(v) as ViewGroup).getChildAt(0)
                val cardBox = boundsInRoot(v, card)
                val where = "$name at ${w}x$h"
                assertEquals("$where: sits just above the category bar", bar.top - dp(4), cardBox.bottom)
                val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
                assertTrue("$where: stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side && cardBox.top >= dp(8))
                assertEquals("$where: one row", dp(6) + dp(48) + dp(6), card.height)
                assertEquals(
                    "$where: centred over the pressed tab",
                    (tabBox.left + (tabBox.width() - card.width) / 2).coerceIn(side, v.width - side - card.width),
                    cardBox.left,
                )
                v.hideOverlayForTest()
            }
        }
    }

    @Test fun confirmations_center_the_question_and_pin_actions_to_the_card_edges() {
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        val deleteCategory = phraseView().also { v ->
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
            assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")))
        }
        val deletePhrase = phraseView().apply { expandForTest("你好") }.also { v ->
            assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        }
        val clearCategory = phraseView().apply { confirmClearForTest() }
        val clearHistory = clipboardView(listOf("第一条"), listOf("默认")).apply { confirmClearHistoryForTest() }
        for ((name, v) in listOf(
            "delete category" to deleteCategory,
            "delete phrase" to deletePhrase,
            "clear category" to clearCategory,
            "clear history" to clearHistory,
        )) {
            layout(v)
            val card = (overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup
            val cardBox = boundsInRoot(v, card)
            val column = card.getChildAt(0) as ViewGroup
            val question = column.getChildAt(0) as TextView
            val actions = column.getChildAt(1) as ViewGroup
            val action = actions.getChildAt(0) as TextView
            val cancel = actions.getChildAt(actions.childCount - 1) as TextView
            val actionBox = boundsInRoot(v, action)
            val cancelBox = boundsInRoot(v, cancel)
            assertEquals(ctx.getString(com.aegis.ime.R.string.clip_cancel), cancel.text.toString())
            assertEquals("$name: the question stays centered", Gravity.CENTER_HORIZONTAL, question.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            assertEquals("$name: the question starts two characters in", inset, question.paddingLeft)
            assertEquals("$name: the card keeps the one popup width", ImeShapes.popupWidthPx(ctx.resources.displayMetrics), card.width)
            assertEquals("$name: the action starts two characters in", cardBox.left + inset, actionBox.left + action.totalPaddingLeft)
            assertEquals("$name: cancel ends two characters in", cardBox.right - inset, cancelBox.right - cancel.totalPaddingRight)
            assertTrue("$name: the actions stay apart", cancelBox.left - actionBox.right >= dp(48))
        }
    }

    @Test fun menus_that_fall_back_to_a_column_leave_no_press_feedback_behind() {
        val narrow = dp(120)
        fun menuCard(v: ClipboardView): ViewGroup = ((overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val menus = listOf(
            "category menu" to (phraseView() to "工作"),
            "clipboard item menu" to (clipboardView(listOf("第一条"), listOf("默认")) to "第一条"),
        )
        for ((name, pair) in menus) {
            val (v, label) = pair
            layout(v, w = narrow)
            fun open() = assertTrue(textViews(v).first { it.text?.toString() == label && it.isLongClickable }.performLongClick())
            open()
            assertTrue("$name: precondition: the menu falls back to a column", (0 until menuCard(v).childCount).all { menuCard(v).getChildAt(it) is TextView })
            val settled = v.immediateActionFeedbackCountForTest()
            repeat(3) { open() }
            assertEquals("$name: reopening the menu leaves no feedback behind", settled, v.immediateActionFeedbackCountForTest())
        }
    }

    @Test fun a_one_row_menu_with_no_room_above_or_below_keeps_its_whole_row() {
        val edge = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        fun menuCard(v: ClipboardView): ViewGroup = ((overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        fun open(v: ClipboardView) = assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
        val roomy = phraseView()
        layout(roomy)
        open(roomy)
        layout(roomy)
        val row = menuCard(roomy).getChildAt(0)
        assertTrue("precondition: the menu is one row", row is android.widget.LinearLayout && (row as android.widget.LinearLayout).orientation == android.widget.LinearLayout.HORIZONTAL)
        val natural = row.width
        val width = natural + 2 * edge + 2
        val cramped = phraseView()
        layout(cramped, w = width, h = dp(10))
        open(cramped)
        layout(cramped, w = width, h = dp(10))
        val card = menuCard(cramped)
        assertTrue("precondition: the menu is still one row", card.getChildAt(0) is android.widget.LinearLayout)
        val box = boundsInRoot(cramped, (overlayOf(cramped) as ViewGroup).getChildAt(0))
        assertTrue("the row keeps its natural width: ${card.getChildAt(0).width} of $natural", card.getChildAt(0).width >= natural)
        assertTrue("the card stays within the edge insets: $box in $width", box.left >= edge && box.right <= width - edge)
    }

    @Test fun category_pickers_keep_long_names_on_one_line() {
        val longNames = listOf(
            "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试弹窗宽度",
            "An extremely long category name that keeps going well past the width of the panel",
        )
        val clip = clipboardView(listOf("第一条"), listOf("默认") + longNames)
        layout(clip)
        assertTrue(textViews(clip).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
        assertTrue(click(overlayOf(clip), ctx.getString(com.aegis.ime.R.string.clip_add_phrase)))
        layout(clip)

        val move = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") + longNames }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            showMoveChooserForTest("默认")
        }
        layout(move)

        for ((picker, v) in listOf("add to category" to clip, "move to category" to move)) {
            val cardBox = boundsInRoot(v, (overlayOf(v) as ViewGroup).getChildAt(0))
            for (name in longNames) {
                val item = textViews(overlayOf(v)).single { it.text?.toString() == name }
                val itemBox = boundsInRoot(v, item)
                assertEquals("$picker: $name stays on one line", 1, item.lineCount)
                assertTrue("$picker: $name is cut with an ellipsis", item.layout.getEllipsisCount(0) > 0)
                assertEquals("$picker: $name keeps a single row", dp(48), item.height)
                assertTrue("$picker: $name stays inside the card", itemBox.left >= cardBox.left && itemBox.right <= cardBox.right)
            }
        }
        for (trash in allViews(overlayOf(move)).filter { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete_category) }) {
            val cardBox = boundsInRoot(move, (overlayOf(move) as ViewGroup).getChildAt(0))
            val trashBox = boundsInRoot(move, trash)
            assertTrue("the delete glyph stays inside the card", trashBox.left >= cardBox.left && trashBox.right <= cardBox.right)
        }
    }

    @Test fun the_clipboard_item_menu_lays_its_actions_in_one_row_and_stacks_when_too_narrow() {
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        val actionLabels = listOf(
            com.aegis.ime.R.string.clip_delete_item,
            com.aegis.ime.R.string.clip_add_phrase,
            com.aegis.ime.R.string.clip_split_title,
        ).map(ctx::getString)
        fun open(w: Int): Pair<ClipboardView, List<TextView>> {
            val v = clipboardView(listOf("第一条"), listOf("默认"))
            layout(v, w, 320)
            assertTrue(textViews(v).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
            layout(v, w, 320)
            return v to actionLabels.map { label -> textViews(overlayOf(v)).single { it.text?.toString() == label } }
        }

        val (wide, wideActions) = open(480)
        val wideCard = boundsInRoot(wide, (overlayOf(wide) as ViewGroup).getChildAt(0))
        val wideBoxes = wideActions.map { boundsInRoot(wide, it) }
        assertTrue("one row", wideBoxes.all { it.top == wideBoxes[0].top })
        assertTrue("in reading order", wideBoxes.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertEquals("the first action starts two characters in", wideCard.left + inset, wideBoxes.first().left + wideActions.first().totalPaddingLeft)
        assertEquals("the last action ends two characters in", wideCard.right - inset, wideBoxes.last().right - wideActions.last().totalPaddingRight)
        assertTrue("roomy gaps on a wide panel", wideBoxes.zipWithNext().all { (a, b) -> b.left - a.right == dp(16) })
        for (action in wideActions) {
            assertTrue(wide.isImmediateActionForTest(action))
            assertEquals(dp(48), action.height)
        }

        val tight = wideActions.sumOf { it.width } + 2 * (inset - dp(4)) + 2 * (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val (squeezed, squeezedActions) = open(tight + 4)
        val squeezedCard = boundsInRoot(squeezed, (overlayOf(squeezed) as ViewGroup).getChildAt(0))
        val squeezedBoxes = squeezedActions.map { boundsInRoot(squeezed, it) }
        assertTrue("a narrow panel keeps one row", squeezedBoxes.all { it.top == squeezedBoxes[0].top })
        assertTrue("by closing the gaps", squeezedBoxes.zipWithNext().all { (a, b) -> b.left - a.right in 0 until dp(16) })
        assertEquals(squeezedCard.left + inset, squeezedBoxes.first().left + squeezedActions.first().totalPaddingLeft)
        assertEquals(squeezedCard.right - inset, squeezedBoxes.last().right - squeezedActions.last().totalPaddingRight)

        val (narrow, narrowActions) = open(tight - 20)
        val narrowBoxes = narrowActions.map { boundsInRoot(narrow, it) }
        assertTrue("too narrow for one row: the actions stack", narrowBoxes.zipWithNext().all { (a, b) -> b.top >= a.bottom })

        assertTrue(wideActions[1].performClick())
        assertTrue(
            "the add action still opens the category chooser",
            ctx.getString(com.aegis.ime.R.string.clip_choose_category) in labels(overlayOf(wide)),
        )
    }

    @Test fun the_clipboard_item_menu_opens_beside_the_pressed_item() {
        val entries = (1..8).map { "第${it}条剪贴板内容" }
        for ((w, h) in listOf(480 to 320, 480 to 200)) {
            val v = clipboardView(entries, listOf("默认"))
            layout(v, w, h)
            val actionLabels = listOf(
                com.aegis.ime.R.string.clip_delete_item,
                com.aegis.ime.R.string.clip_add_phrase,
                com.aegis.ime.R.string.clip_split_title,
            ).map(ctx::getString)
            var above = 0
            var below = 0
            for (i in 0 until v.listRowCountForTest()) {
                layout(v, w, h)
                val viewport = boundsInRoot(v, v.listViewportForTest())
                val row = requireNotNull(v.listRowViewForTest(i))
                val rowBox = boundsInRoot(v, row)
                if (rowBox.top < viewport.top || rowBox.bottom > viewport.bottom) continue
                assertTrue(textViews(row).first { it.isLongClickable }.performLongClick())
                layout(v, w, h)
                val card = (overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup
                val cardBox = boundsInRoot(v, card)
                val full = card.getChildAt(0).height
                val roomAbove = rowBox.top - dp(4) - dp(8)
                val roomBelow = v.height - dp(8) - rowBox.bottom - dp(4)
                val where = "entry $i at ${w}x$h"
                val actions = actionLabels.map { label -> textViews(card).single { it.text?.toString() == label } }
                assertTrue("$where: the actions share one row", actions.all { boundsInRoot(v, it).top == boundsInRoot(v, actions[0]).top })
                assertEquals("$where: one 48dp row inside the card padding", dp(6) + dp(48) + dp(6), full)
                val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
                assertTrue("$where: stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side)
                assertEquals("$where: stays centred", (v.width - card.width) / 2, cardBox.left)
                assertFalse("$where: leaves the pressed entry uncovered", Rect.intersects(cardBox, rowBox))
                when {
                    full <= roomAbove -> {
                        assertEquals("$where: sits just above the entry", rowBox.top - dp(4), cardBox.bottom); above++
                        assertEquals("$where: shows every action", full, card.height)
                    }
                    full <= roomBelow -> {
                        assertEquals("$where: sits just below the entry", rowBox.bottom + dp(4), cardBox.top); below++
                        assertEquals("$where: shows every action", full, card.height)
                    }
                    roomAbove >= roomBelow -> {
                        assertEquals("$where: takes the roomier side above", rowBox.top - dp(4), cardBox.bottom); above++
                        assertEquals("$where: scrolls within the room above", roomAbove, card.height)
                    }
                    else -> {
                        assertEquals("$where: takes the roomier side below", rowBox.bottom + dp(4), cardBox.top); below++
                        assertEquals("$where: scrolls within the room below", roomBelow, card.height)
                    }
                }
                if (cardBox.top < dp(8) || cardBox.bottom > v.height - dp(8)) fail("$where: the menu leaves the panel: $cardBox")
                v.hideOverlayForTest()
            }
            assertTrue("${w}x$h: a lower entry opens the menu above it", above > 0)
            assertTrue("${w}x$h: the top entry opens the menu below it", below > 0)
        }
    }

    @Test fun deleting_the_only_category_explains_instead_of_confirming() {
        var deleted: String? = null
        val v = singleCatPhraseView().apply { onDeleteCategory = { deleted = it } }
        val chip = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "默认")))
        val shown = labels(overlayOf(v))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_keep_one_category) in shown)
        assertFalse(
            "the last category never reaches the delete confirmation",
            ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "默认") in shown,
        )
        assertFalse("the notice offers no delete action", ctx.getString(com.aegis.ime.R.string.clip_delete) in shown)
        assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_done)))
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertNull(deleted)
    }

    @Test fun the_default_category_is_renamed_and_deleted_like_any_other_while_another_remains() {
        val d = com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID
        val shown = ctx.getString(com.aegis.ime.R.string.clip_default_category)
        var renamed: String? = null
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf(d, "工作") }
            phrasesInProvider = { emptyList() }
            onRenameCategory = { renamed = it }
            onDeleteCategory = { deleted.add(it) }
            applyPalette(pal); forcePhrasesStateForTest(d); refresh()
        }
        val chip = textViews(v).first { it.text?.toString() == shown && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_rename_named, shown)))
        assertEquals("the rename is handed the stable id, not the label", d, renamed)
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, shown)))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, shown) in labels(overlayOf(v)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf(d), deleted)
    }

    @Test fun the_list_scrollbar_sits_inside_the_shared_edge_inset() {
        val v = phraseView()
        layout(v)
        val inset = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val viewport = v.listViewportForTest()
        assertEquals(inset, viewport.paddingLeft)
        assertEquals("the scrollbar is drawn inside the edge inset", inset, viewport.paddingRight)
        assertEquals(View.SCROLLBARS_INSIDE_OVERLAY, viewport.scrollBarStyle)
        assertEquals("the cards keep their edges", inset, boundsInRoot(v, checkNotNull(v.listRowViewForTest(0))).left)
        assertEquals(v.width - inset, boundsInRoot(v, checkNotNull(v.listRowViewForTest(0))).right)
    }

    @Test fun the_selected_tab_press_keeps_its_rounded_bottom_over_the_underline() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作", "私人") }
                phrasesInProvider = { emptyList() }
                applyPalette(palette); forcePhrasesStateForTest("工作"); refresh()
            }
            layout(v)
            val rail = allViews(v).filterIsInstance<ImePanelCategoryRail>().single()
            val tab = rail.getChildAt(rail.selectedIndex)
            val surface = tab.background as ImeKeySurface
            val faceBounds = surface.faceBoundsForTest(tab.width, tab.height)
            val box = boundsInRoot(v, tab)
            val underline = requireNotNull(rail.underlineBoundsForTest())
            val face = Rect(box.left + faceBounds.left.roundToInt(), box.top + faceBounds.top.roundToInt(), box.left + faceBounds.right.roundToInt(), box.top + faceBounds.bottom.roundToInt())
            val row = face.bottom - 1
            assertTrue("precondition: the face's bottom row lies on the underline", row - box.top >= underline.top)
            val resting = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, tab.width / 2f, tab.height / 2f, 0))
            val pressed = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            assertTrue("the press shows over the underline", pressed.getPixel(face.centerX(), row) != resting.getPixel(face.centerX(), row))
            assertEquals("the press keeps its rounded corner on the underline", resting.getPixel(face.left, row), pressed.getPixel(face.left, row))
            tab.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_CANCEL, tab.width / 2f, tab.height / 2f, 0))
        }
    }


    private fun manyCategoryView(cats: MutableList<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { cats.toList() }
        phrasesInProvider = { emptyList() }
        applyPalette(pal); forcePhrasesStateForTest(cats.first()); refresh()
    }

    private fun assertTabInView(scroll: HorizontalScrollView, tab: View) {
        val strip = scroll.getChildAt(0)
        val start = strip.left + tab.left
        val end = strip.left + tab.right
        val shownStart = scroll.scrollX + scroll.paddingLeft
        val shownEnd = scroll.scrollX + scroll.width - scroll.paddingRight
        assertTrue(
            "tab [$start, $end] must lie inside the rail viewport [$shownStart, $shownEnd]",
            start >= shownStart && end <= shownEnd,
        )
    }

    @Test fun returning_with_a_new_category_scrolls_the_rail_to_it() {
        val cats = (0..10).map { "分类" + it.toString().padStart(2, '0') }.toMutableList()
        val v = manyCategoryView(cats)
        layout(v, w = 320, h = 400)
        assertEquals(0, categoryScroll(v).scrollX)
        v.resetToDefault()
        cats.add("新分类")
        v.showPhraseTab("新分类")
        layout(v, w = 320, h = 400)
        val scroll = categoryScroll(v)
        val tab = textViews(scroll).single { it.text?.toString() == "新分类" }
        assertEquals("新分类", v.phraseCatForTest())
        assertTrue(tab.isSelected)
        assertTrue("the rail had to scroll to reach the new category", scroll.scrollX > 0)
        assertTabInView(scroll, tab)

        scroll.scrollTo(0, 0)
        v.applyPalette(ImePalette.STATIC_DARK)
        layout(v, w = 320, h = 400)
        assertEquals("the reveal happens once; a later rebuild keeps where the user left the rail", 0, categoryScroll(v).scrollX)
    }

    @Test fun reopening_the_phrase_tab_after_an_inline_edit_scrolls_the_rail_to_its_category() {
        val cats = (0..10).map { "分类" + it.toString().padStart(2, '0') }.toMutableList()
        val v = manyCategoryView(cats)
        layout(v, w = 320, h = 400)
        cats.add("新分类")
        v.reopenAfterInline("新分类")
        layout(v, w = 320, h = 400)
        val scroll = categoryScroll(v)
        assertEquals("新分类", v.phraseCatForTest())
        assertTrue(scroll.scrollX > 0)
        assertTabInView(scroll, textViews(scroll).single { it.text?.toString() == "新分类" })
    }

    @Test fun top_bar_icons_are_uniform_size() {
        val v = phraseView()
        val wanted = setOf(ctx.getString(com.aegis.ime.R.string.clip_back), ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_edit_phrases), ctx.getString(com.aegis.ime.R.string.clip_clear_category))
        val icons = allViews(v).filter { it.contentDescription?.toString() in wanted && it.hasOnClickListeners() }
        assertEquals("all 4 phrase-tab top icons present", 4, icons.size)
        assertTrue("返回 is no longer a '‹' text glyph", textViews(v).none { it.text?.toString() == "‹" })
        val back = icons.single { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_back) }
        val actions = icons.filterNot { it === back }
        assertTrue("返回 uses the shared panel back control", back is TextView)
        assertEquals(
            "返回 carries its own label",
            ctx.getString(com.aegis.ime.R.string.clip_back),
            (back as TextView).text.toString(),
        )
        val backTarget = (48 * ctx.resources.displayMetrics.density).toInt()
        assertEquals("返回 keeps a 48dp hit target", backTarget, back.layoutParams.height)
        assertEquals(
            "返回 is sized by its label",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            back.layoutParams.width,
        )
        layout(v)
        assertTrue("返回 keeps a 48dp hit target however its label is sized", back.width >= backTarget)
        assertEquals("all top action icons share one width (item7)", 1, actions.map { it.layoutParams.width }.toSet().size)
        assertEquals("all top action icons share one height (item7)", 1, actions.map { it.layoutParams.height }.toSet().size)
        val surfaced = icons.filter { it.contentDescription?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_edit_phrases), ctx.getString(com.aegis.ime.R.string.clip_clear_category)) }
        assertTrue(surfaced.all(v::isImmediateActionForTest))
        val iconSize = (48 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(surfaced.all { it.layoutParams.width == iconSize && it.layoutParams.height == iconSize })
        assertTrue(surfaced.all { it.background === v.immediateActionDrawableForTest(it) && it.foreground == null })
        assertTrue(icons.filterNot { it in surfaced }.all { it.background == null })
    }


    private fun clipboardView(history: List<String>, cats: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }
        categoriesProvider = { cats }
        applyPalette(pal); refresh()
    }

    @Test fun clipboard_add_to_new_category_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_new_category)))
        assertEquals("the clip rides the inline new-category flow", listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_new_category_when_none_exist_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), emptyList()).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
        assertEquals(listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_existing_category_still_works() {
        var saved: Pair<String, List<String>>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onSaveAsPhrasesTo = { c, l -> saved = c to l } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
        assertTrue(click(overlayOf(v), "默认"))
        assertEquals("默认" to listOf("hello"), saved)
    }


    private fun singleCatPhraseView(): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗") else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }

    @Test fun move_to_new_category_carries_the_move() {
        var carried: Pair<String, List<String>>? = null
        val v = singleCatPhraseView().apply { onAddCategoryThenMove = { from, texts -> carried = from to texts } }
        v.expandForTest("你好")
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        assertTrue("no other category → offers 新建", ctx.getString(com.aegis.ime.R.string.clip_no_other_categories) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_new_category)))
        assertEquals("默认" to listOf("你好"), carried)
    }

    @Test fun move_to_existing_category_still_works() {
        var moved: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> moved = Triple(f, t, to) } }
        v.expandForTest("你好")
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), moved)
    }
}
