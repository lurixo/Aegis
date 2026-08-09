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

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.AegisInputMethodService
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.user.asClipEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelResetOnExitTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    private class SpyPanel(ctx: Context) : View(ctx), ResettablePanel {
        var resets = 0
        override fun resetToDefault() { resets++ }
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun maxScrollOf(viewport: ScrollView): Int =
        ((viewport.getChildAt(0)?.height ?: 0) - viewport.height).coerceAtLeast(0)

    private fun <T> hosted(body: (Activity) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            return body(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun flingSurvivingDismissal(
        label: String,
        panel: View,
        viewport: ScrollView,
        width: Int,
        height: Int,
        dismiss: () -> Unit,
        reopen: () -> Unit,
    ): Int {
        fun frame(ms: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            viewport.computeScroll()
            layout(panel, width, height)
        }
        layout(panel, width, height)
        assertTrue("$label precondition: the content must overflow its viewport", maxScrollOf(viewport) > 0)
        viewport.scrollTo(0, 0)
        layout(panel, width, height)
        assertEquals("$label precondition: parked at the top before the fling", 0, viewport.scrollY)
        viewport.fling(9000)
        frame(48)
        assertTrue("$label precondition: the fling actually moves the content", viewport.scrollY > 0)
        dismiss()
        reopen()
        layout(panel, width, height)
        assertEquals("$label precondition: the reopened panel starts at the top", 0, viewport.scrollY)
        assertTrue("$label precondition: the reopened content still overflows", maxScrollOf(viewport) > 0)
        repeat(30) { frame(16) }
        return viewport.scrollY
    }

    @Test fun dismissing_a_panel_resets_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        assertEquals("opening must not reset", 0, spy.resets)
        iv.showPanel(null)
        assertEquals(1, spy.resets)
    }

    @Test fun switching_directly_to_another_panel_resets_the_outgoing_one() {
        val iv = InputView(ctx)
        val a = SpyPanel(ctx)
        val b = SpyPanel(ctx)
        iv.showPanel(a)
        iv.showPanel(b)
        assertEquals("outgoing panel reset", 1, a.resets)
        assertEquals("incoming panel untouched", 0, b.resets)
    }

    @Test fun re_showing_the_same_panel_does_not_reset_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        iv.showPanel(spy)
        assertEquals(0, spy.resets)
    }


    @Test fun symbols_panel_resets_to_the_common_tab_unlocked_and_scrolled_up() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(4)
        sv.toggleLockForTest()
        assertEquals(4, sv.selectedCategoryForTest())
        assertTrue(sv.lockedForTest())

        sv.resetToDefault()

        assertEquals("back to 常用 (index 0)", 0, sv.selectedCategoryForTest())
        assertFalse("lock cleared (P3 spirit)", sv.lockedForTest())
        assertEquals("grid scrolled to top", 0, sv.gridScrollYForTest())
    }

    @Test fun emoji_panel_resets_to_the_first_category() {
        val ev = EmojiView(ctx)
        ev.applyPalette(light)
        ev.openCategoryForTest(2)
        assertEquals(2, ev.selectedCategoryForTest())

        ev.resetToDefault()

        assertEquals(0, ev.selectedCategoryForTest())
    }

    @Test fun clipboard_panel_resets_to_the_clipboard_tab_and_clears_the_category_picker() {
        val cv = ClipboardView(ctx)
        cv.applyPalette(light)
        cv.forcePhrasesStateForTest("我的分类")
        assertFalse("on the 常用语 tab", cv.isClipboardTabForTest())
        assertEquals("我的分类", cv.phraseCatForTest())

        cv.resetToDefault()

        assertTrue("back to the 剪贴板 tab", cv.isClipboardTabForTest())
        assertEquals("category picker cleared", "", cv.phraseCatForTest())
    }

    @Test fun reopening_after_an_input_view_recreate_still_starts_default() {
        val stale = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(3); toggleLockForTest() }
        assertTrue("precondition: stale lock", stale.lockedForTest())
        assertEquals("precondition: stale category", 3, stale.selectedCategoryForTest())

        val freshIv = InputView(ctx)
        freshIv.showPanel(null)
        stale.resetToDefault()
        freshIv.showPanel(stale)

        assertEquals("reopens on 常用", 0, stale.selectedCategoryForTest())
        assertFalse("reopens unlocked", stale.lockedForTest())
    }

    @Test fun edit_panel_resets_selection_mode() {
        val ep = EditPanelView(ctx)
        ep.applyPalette(light)
        ep.setSelecting(true)
        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_end_select), ep.selectingLabelForTest())

        ep.resetToDefault()

        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_start_select), ep.selectingLabelForTest())
    }

    private val clips = (1..60).map { "剪贴板条目-$it" }
    private val phrases = (1..60).map { "常用语条目-$it" }

    private fun seedClipboard(cv: ClipboardView) {
        cv.historyProvider = { clips.asClipEntries() }
        cv.categoriesProvider = { listOf("默认", "工作") }
        cv.phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        cv.phraseNoteProvider = { _, _ -> "" }
    }

    private fun settleClipboard(cv: ClipboardView) {
        repeat(4) {
            while (cv.runPendingListAppendForTest()) {
            }
            layout(cv, 480, 220)
        }
    }

    private fun openClipboardLikeTheService(iv: InputView, cv: ClipboardView) {
        cv.resetToDefault()
        cv.applyPalette(light)
        iv.showPanelImmediately(cv)
        idle()
    }

    private fun openPhrasesLikeTheService(iv: InputView, cv: ClipboardView) {
        openClipboardLikeTheService(iv, cv)
        cv.showPhraseTab("")
        idle()
    }

    private fun send(v: View, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun scrollClipboardDown(cv: ClipboardView, label: String): Int {
        val vp = cv.listViewportForTest() as ScrollView
        var guard = 0
        while (guard++ < 40) {
            val reach = maxScrollOf(vp)
            send(vp, MotionEvent.ACTION_DOWN, vp.width / 2f, vp.height / 2f, guard * 32L)
            vp.scrollTo(0, reach)
            send(vp, MotionEvent.ACTION_CANCEL, vp.width / 2f, vp.height / 2f, guard * 32L + 16)
            settleClipboard(cv)
            if (maxScrollOf(vp) == reach) break
        }
        val reach = maxScrollOf(vp)
        assertTrue("$label precondition: the list must overflow the viewport", reach > 0)
        val target = reach / 2
        assertTrue("$label precondition: the scroll target must be non-zero", target > 0)
        send(vp, MotionEvent.ACTION_DOWN, vp.width / 2f, vp.height / 2f, 4000)
        vp.scrollTo(0, target)
        send(vp, MotionEvent.ACTION_CANCEL, vp.width / 2f, vp.height / 2f, 4016)
        settleClipboard(cv)
        assertEquals("$label precondition: the scroll must take effect", target, cv.listScrollYForTest())
        return target
    }

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun tapFullyVisibleRow(cv: ClipboardView, label: String, at: Long): String {
        val vp = cv.listViewportForTest() as ScrollView
        val viewport = Rect(0, 0, vp.width, vp.height)
        cv.offsetDescendantRectToMyCoords(vp, viewport)
        viewport.offset(vp.scrollX, vp.scrollY)
        for (text in cv.listRowTextsForTest()) {
            val body = textViews(cv).firstOrNull { it.text?.toString() == text } ?: continue
            val row = Rect(0, 0, body.width, body.height)
            cv.offsetDescendantRectToMyCoords(body, row)
            if (!viewport.contains(row)) continue
            send(cv, MotionEvent.ACTION_DOWN, row.exactCenterX(), row.exactCenterY(), at)
            send(cv, MotionEvent.ACTION_UP, row.exactCenterX(), row.exactCenterY(), at + 16)
            idle()
            return text
        }
        throw AssertionError("$label: no row is fully inside the viewport")
    }

    private fun clipboardFlingResidue(label: String, phrasesTab: Boolean): Int = hosted { activity ->
        val iv = InputView(ctx)
        activity.setContentView(iv)
        val cv = ClipboardView(ctx).also(::seedClipboard)
        fun open() = if (phrasesTab) openPhrasesLikeTheService(iv, cv) else openClipboardLikeTheService(iv, cv)
        open()
        settleClipboard(cv)
        scrollClipboardDown(cv, label)
        val vp = cv.listViewportForTest() as ScrollView
        vp.scrollTo(0, 0)
        settleClipboard(cv)
        assertEquals("$label precondition: parked at the top before the fling", 0, cv.listScrollYForTest())
        fun frame(ms: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            vp.computeScroll()
            layout(cv, 480, 220)
        }
        vp.fling(9000)
        frame(48)
        assertTrue("$label precondition: the fling actually moves the list", cv.listScrollYForTest() > 0)
        iv.showPanel(null)
        idle()
        open()
        settleClipboard(cv)
        assertEquals("$label precondition: the reopened list starts at the top", 0, cv.listScrollYForTest())
        repeat(30) { frame(16) }
        cv.listScrollYForTest()
    }

    @Test fun a_clipboard_fling_does_not_outlive_the_panel() {
        assertEquals(
            "the reopened clipboard list stays at the top",
            0,
            clipboardFlingResidue("clipboard fling", phrasesTab = false),
        )
    }

    @Test fun a_phrases_fling_does_not_outlive_the_panel() {
        assertEquals(
            "the reopened phrases list stays at the top",
            0,
            clipboardFlingResidue("phrases fling", phrasesTab = true),
        )
    }

    @Test fun the_first_tap_after_a_clipboard_reopen_still_commits() = hosted { activity ->
        val iv = InputView(ctx)
        activity.setContentView(iv)
        var picked: String? = null
        val cv = ClipboardView(ctx).also(::seedClipboard)
        cv.onPick = { t -> picked = t; iv.showPanel(null) }
        openClipboardLikeTheService(iv, cv)
        settleClipboard(cv)
        scrollClipboardDown(cv, "first tap")
        val vp = cv.listViewportForTest() as ScrollView
        vp.scrollTo(0, 0)
        settleClipboard(cv)
        vp.fling(9000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
        vp.computeScroll()
        layout(cv, 480, 220)
        assertTrue("precondition: the fling actually moves the list", cv.listScrollYForTest() > 0)
        iv.showPanel(null)
        idle()
        openClipboardLikeTheService(iv, cv)
        settleClipboard(cv)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val entry = tapFullyVisibleRow(cv, "first tap", 9000)
        assertEquals("the first tap after reopening must commit the entry", entry, picked)
    }
}
