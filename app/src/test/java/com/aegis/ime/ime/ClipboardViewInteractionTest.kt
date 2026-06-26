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

import java.nio.file.Files
import java.io.File
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.asClipEntries
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.MotionEvent
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
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardViewInteractionTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun layout(v: View, w: Int = 480, h: Int = 700) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun send(v: View, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun rootTap(root: View, target: View) {
        val bounds = boundsInRoot(root as ViewGroup, target)
        send(root, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        send(root, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rootTap(root: View, x: Float, y: Float) {
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_UP, x, y, 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        root.offsetDescendantRectToMyCoords(target, it)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun bodyOf(root: View, text: String): TextView =
        textViews(root).first { it.text?.toString() == text }
    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun draw(view: View) {
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }
    private fun rippleMask(view: View): GradientDrawable =
        ((view.foreground as RippleDrawable).findDrawableByLayerId(android.R.id.mask) as GradientDrawable)

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }; applyPalette(pal); refresh()
    }
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }

    @Test fun a_tap_commits_the_clip_body_verbatim_including_edge_whitespace() {
        var picked: String? = null
        val text = " \u7b2c\u4e00\u6761\t\u6362\n\u884c "
        val v = clipView(listOf(text)).apply { onPick = { picked = it } }
        layout(v)
        assertTrue(bodyOf(v, text).performClick())
        assertEquals(text, picked)
    }

    @Test fun refresh_renders_new_history_items_without_reopening_panel() {
        val history = mutableListOf("old")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            applyPalette(pal)
            refresh()
        }
        assertTrue("initial item is visible", "old" in labels(v))
        history.add(0, "new")
        v.refresh()
        assertTrue("new item appears in the existing panel", "new" in labels(v))
        assertTrue("existing item remains visible", "old" in labels(v))
    }

    @Test fun tabs_and_categories_dispatch_only_inside_their_own_targets() {
        val clipboard = ctx.getString(com.aegis.ime.R.string.clip_clipboard)
        val phrases = ctx.getString(com.aegis.ime.R.string.clip_phrases)
        val v = phraseView(listOf("你好"))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v, w = 600)
            var tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            val tray = tabs.first().parent as View
            assertEquals(tray.width / 2, tabs[0].width)
            assertEquals(tray.width / 2, tabs[1].width)
            assertEquals(tabs[0].right, tabs[1].left)
            tabs.forEach { tab ->
                draw(tab)
                val hit = Rect()
                tab.getHitRect(hit)
                assertEquals(Rect(tab.left, tab.top, tab.right, tab.bottom), hit)
                assertEquals(Rect(0, 0, tab.width, tab.height), tab.foreground.bounds)
            }
            val leftMaskRadii = requireNotNull(rippleMask(tabs[0]).cornerRadii)
            val rightMaskRadii = requireNotNull(rippleMask(tabs[1]).cornerRadii)
            assertTrue(leftMaskRadii[0] > 0f && leftMaskRadii[2] == 0f && leftMaskRadii[4] == 0f && leftMaskRadii[6] > 0f)
            assertTrue(rightMaskRadii[0] == 0f && rightMaskRadii[2] > 0f && rightMaskRadii[4] > 0f && rightMaskRadii[6] == 0f)
            rootTap(v, tabs.first { it.text?.toString() == clipboard })
            assertTrue(v.isClipboardTabForTest())
            layout(v, w = 600)
            tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            rootTap(v, tabs.first { it.text?.toString() == phrases })
            assertFalse(v.isClipboardTabForTest())
            layout(v, w = 600)
            val defaults = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            val work = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
            val defaultBounds = boundsInRoot(v, defaults)
            val workBounds = boundsInRoot(v, work)
            draw(defaults)
            draw(work)
            for (cell in listOf(defaults, work)) {
                val hit = Rect()
                cell.getHitRect(hit)
                assertEquals(Rect(cell.left, cell.top, cell.right, cell.bottom), hit)
            }
            assertEquals("rail cells meet edge to edge", defaultBounds.right, workBounds.left)
            rootTap(v, work)
            assertEquals("工作", v.phraseCatForTest())
            layout(v, w = 600)
            rootTap(v, workBounds.left - 1f, defaultBounds.exactCenterY())
            assertEquals("the pixel left of the seam belongs to the first cell", "默认", v.phraseCatForTest())
            layout(v, w = 600)
            rootTap(v, workBounds.left + 1f, workBounds.exactCenterY())
            assertEquals("the pixel right of the seam belongs to the second cell", "工作", v.phraseCatForTest())
            layout(v, w = 600)
            val refreshedDefault = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            rootTap(v, refreshedDefault)
            assertEquals("默认", v.phraseCatForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun a_full_rebuild_re_applies_scroll_once_the_deferred_rows_land() {
        val v = clipView((1..40).map { "item$it" })
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            val viewport = v.listViewportForTest() as ViewGroup
            val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
            assertTrue("precondition: the long list overflows the viewport", maxScroll > 0)
            val target = maxScroll / 2
            viewport.scrollTo(0, target)
            assertEquals(target, v.listScrollYForTest())
            v.applyPalette(ImePalette.STATIC_DARK)
            layout(v, h = 220)
            assertTrue("the intermediate short list cannot reach the old offset", v.listScrollYForTest() < target)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            assertEquals("scroll is re-applied after the deferred rows append", target, v.listScrollYForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun a_rapid_scroll_reversal_overrides_deferred_restoration_on_both_tabs() {
        val cases = listOf(
            "clipboard" to clipView((1..40).map { "clip$it" }),
            "phrases" to phraseView((1..40).map { "phrase$it" }),
        )
        for ((name, v) in cases) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                activity.get().setContentView(v)
                layout(v, h = 220)
                val viewport = v.listViewportForTest() as ViewGroup
                val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
                assertTrue("$name precondition: initial rows overflow", maxScroll > 0)
                val target = maxScroll / 2
                send(viewport, MotionEvent.ACTION_DOWN, viewport.width / 2f, viewport.height / 2f, 0)
                viewport.scrollTo(0, target)
                send(viewport, MotionEvent.ACTION_CANCEL, viewport.width / 2f, viewport.height / 2f, 16)
                val reversedTarget = target / 2
                send(viewport, MotionEvent.ACTION_DOWN, viewport.width / 2f, viewport.height / 2f, 32)
                viewport.scrollTo(0, reversedTarget)
                send(viewport, MotionEvent.ACTION_CANCEL, viewport.width / 2f, viewport.height / 2f, 48)
                while (v.runPendingListAppendForTest()) {}
                layout(v, h = 220)
                assertEquals("$name keeps the reversed user offset", reversedTarget, v.listScrollYForTest())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test fun an_entries_refresh_does_not_replace_a_pressed_back_button_on_either_tab() {
        val clips = (1..40).map { "clip$it" }.toMutableList()
        val phrases = (1..40).map { "phrase$it" }.toMutableList()
        val cases = listOf(
            "clipboard" to (clipView(clips) to { clips.add(0, "new clip") }),
            "phrases" to (phraseView(phrases) to { phrases.add(0, "new phrase") }),
        )
        for ((name, pair) in cases) {
            val (v, mutate) = pair
            var backs = 0
            v.onBack = { backs++ }
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                activity.get().setContentView(v)
                layout(v, h = 220)
                val desc = ctx.getString(com.aegis.ime.R.string.clip_back)
                val before = allViews(v).single { it.contentDescription?.toString() == desc }
                val bounds = boundsInRoot(v, before)
                send(v, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                mutate()
                v.refresh()
                layout(v, h = 220)
                val after = allViews(v).single { it.contentDescription?.toString() == desc }
                assertTrue("$name keeps the pressed back target attached", before === after)
                send(v, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 16)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("$name delivers the back click", 1, backs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test fun a_split_copy_style_refresh_preserves_scroll_and_reuses_existing_rows() {
        val history = (1..40).map { "item$it" }.toMutableList()
        val v = clipView(history)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            val viewport = v.listViewportForTest() as ViewGroup
            val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
            assertTrue("precondition: the long list overflows the viewport", maxScroll > 0)
            val target = maxScroll / 2
            viewport.scrollTo(0, target)
            assertEquals(target, v.listScrollYForTest())
            val keptRow = requireNotNull(v.listRowViewForTest(5))
            history.add(0, "copied-block")
            v.refresh()
            layout(v, h = 220)
            assertEquals("the split→copy refresh keeps the scroll offset, no jump to top", target, v.listScrollYForTest())
            assertTrue("existing rows are reused in place, not rebuilt from scratch", keptRow === v.listRowViewForTest(6))
            assertTrue("the copied block is prepended", "copied-block" in v.listRowTextsForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun reopen_after_inline_from_the_clipboard_tab_stays_on_the_clipboard_tab() {
        val v = clipView(listOf("clip")).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { emptyList() }
        }
        layout(v)
        assertTrue(v.isClipboardTabForTest())
        v.reopenAfterInline("新建分类")
        layout(v)
        assertTrue("a clipboard-launched inline return does not jump to phrases", v.isClipboardTabForTest())
        assertTrue("the clipboard content is intact", "clip" in labels(mainOf(v)))
    }

    @Test fun reopen_after_inline_on_the_phrase_tab_retargets_the_category() {
        val v = phraseView(listOf("你好"))
        layout(v)
        assertFalse(v.isClipboardTabForTest())
        assertEquals("默认", v.phraseCatForTest())
        v.reopenAfterInline("工作")
        layout(v)
        assertFalse("an inline return keeps the phrase tab", v.isClipboardTabForTest())
        assertEquals("an inline return retargets the phrase category", "工作", v.phraseCatForTest())
    }


    private val bigBody = "第一段。第二段，第三段！".repeat(8000)

    private fun storeDir(): File = Files.createTempDirectory("clipview").toFile()

    private fun lazyStore(dir: File, vararg bodies: String): ClipboardStore {
        ClipboardStore(dir).apply {
            load()
            bodies.forEach { record(it) }
            flushPendingWrites()
        }
        return ClipboardStore(dir).apply { load() }
    }

    private fun storeView(store: ClipboardStore): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { store.history() }
        categoriesProvider = { listOf("默认") }
        applyPalette(pal)
        refresh()
    }

    private fun bigRow(v: ClipboardView): TextView =
        textViews(mainOf(v)).first { it.text?.toString()?.startsWith("第一段。") == true }

    @Test fun a_big_clipboard_row_renders_from_a_bounded_preview() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        val v = storeView(store)
        layout(v)
        val row = bigRow(v)
        assertEquals("the row shows the panel's capped preview", v.displayCapForTest() + 1, row.text.length)
        assertTrue("listing a big clip keeps its body out of memory", store.residentBodyChars() <= ClipEntry.PREVIEW_CHARS)
        assertTrue(
            "the panel cap must fit inside the preview the store hands out",
            v.displayCapForTest() < ClipEntry.PREVIEW_CHARS,
        )
        dir.deleteRecursively()
    }

    @Test fun picking_a_big_clipboard_row_commits_the_whole_body() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        var picked: String? = null
        val v = storeView(store).apply { onPick = { picked = it } }
        layout(v)
        assertTrue(bigRow(v).performClick())
        assertEquals("上屏 gets the whole original string", bigBody, picked)
        dir.deleteRecursively()
    }

    @Test fun a_row_whose_sidecar_vanished_is_marked_and_commits_nothing() {
        val dir = storeDir()
        val hash = "d".repeat(64)
        File(dir, "clipboard.txt").writeText("B\t$hash\n")
        val store = ClipboardStore(dir).apply { load() }
        var picked: String? = null
        val v = storeView(store).apply { onPick = { picked = it } }
        layout(v)
        val row = textViews(mainOf(v)).first { it.text?.toString()?.startsWith("⚠") == true }
        assertTrue("the missing row is marked, never shown as clip text", row.text.toString().startsWith("⚠ "))
        assertTrue("no row impersonates the reference line", labels(mainOf(v)).none { it.startsWith("B\t") })
        assertTrue(row.performClick())
        assertNull("a missing body must never be committed as a substitute", picked)
        dir.deleteRecursively()
    }

    @Test fun a_category_chip_keeps_a_multiline_name_to_one_line() {
        val broken = "多行\n名字"
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", broken) }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        layout(v)
        val chip = textViews(v).single { it.text?.toString() == broken }
        assertEquals("the chip may not grow a second line", 1, chip.maxLines)
        assertTrue("an overlong name is ellipsized, not clipped", chip.ellipsize != null)
    }
}
