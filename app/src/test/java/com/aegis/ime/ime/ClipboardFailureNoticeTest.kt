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
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.asClipEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardFailureNoticeTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun text(id: Int) = ctx.getString(id)

    private fun layout(v: View, w: Int = 480, h: Int = 700) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root)
        return out
    }

    private fun labels(root: View): List<String> =
        allViews(root).filterIsInstance<TextView>().mapNotNull { it.text?.toString() }

    private fun clickText(root: View, label: String) {
        val tv = allViews(root).filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() }
        assertTrue("no clickable text labelled $label", tv != null)
        tv!!.performClick()
    }

    private fun clickDesc(root: View, desc: String) {
        val v = allViews(root)
            .firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() }
        assertTrue("no clickable view described as $desc", v != null)
        v!!.performClick()
    }

    private fun view(history: List<ClipEntry>, readable: Boolean = true): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }
        historyReadableProvider = { readable }
        applyPalette(pal)
        refresh()
        layout(this)
    }

    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)

    private fun deleteFirstRow(v: ClipboardView, row: String) {
        v.expandForTest(row)
        layout(v)
        clickText(mainOf(v), text(R.string.clip_delete))
        layout(v)
        assertTrue("the confirm card must ask first", text(R.string.clip_delete_clip_confirm) in labels(v))
        clickText(overlayOf(v), text(R.string.clip_delete))
        layout(v)
    }

    private fun clearHistory(v: ClipboardView) {
        clickDesc(mainOf(v), text(R.string.clip_clear_history))
        layout(v)
        clickText(overlayOf(v), text(R.string.clip_clear))
        layout(v)
    }

    @Test fun a_clipboard_that_could_not_be_read_is_not_shown_as_an_empty_one() {
        val v = view(emptyList(), readable = false)

        assertTrue(text(R.string.clip_clipboard_unreadable) in labels(v))
        assertFalse(
            "an unreadable history shown as an empty one tells the user their clips are gone",
            text(R.string.clip_clipboard_empty) in labels(v),
        )
    }

    @Test fun an_empty_clipboard_is_still_shown_as_an_empty_one() {
        val v = view(emptyList())

        assertTrue(text(R.string.clip_clipboard_empty) in labels(v))
        assertFalse(text(R.string.clip_clipboard_unreadable) in labels(v))
    }

    @Test fun a_delete_that_was_not_written_says_so() {
        var history = listOf("要删的")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            onDeleteClips = { texts -> history = history - texts.toSet(); false }
            historyReadableProvider = { true }
            applyPalette(pal)
            refresh()
            layout(this)
        }

        deleteFirstRow(v, "要删的")

        assertTrue(text(R.string.clip_change_not_saved) in labels(v))
    }

    @Test fun a_delete_that_was_written_says_nothing() {
        var history = listOf("要删的")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            onDeleteClips = { texts -> history = history - texts.toSet(); true }
            applyPalette(pal)
            refresh()
            layout(this)
        }

        deleteFirstRow(v, "要删的")

        assertFalse(text(R.string.clip_change_not_saved) in labels(v))
    }

    @Test fun a_clear_that_was_not_written_says_so() {
        var history = listOf("要清的")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            onClearHistory = { history = emptyList(); false }
            applyPalette(pal)
            refresh()
            layout(this)
        }

        clearHistory(v)

        assertTrue(text(R.string.clip_change_not_saved) in labels(v))
    }

    @Test fun a_clear_that_was_written_says_nothing() {
        var history = listOf("要清的")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            onClearHistory = { history = emptyList(); true }
            applyPalette(pal)
            refresh()
            layout(this)
        }

        clearHistory(v)

        assertFalse(text(R.string.clip_change_not_saved) in labels(v))
    }

    @Test fun tapping_a_clip_whose_contents_are_gone_says_so_instead_of_doing_nothing() {
        val dir = Files.createTempDirectory("cliplost").toFile()
        try {
            val lost = ClipEntry.stored(File(dir, "clips"), "a".repeat(64))
            var picked: String? = null
            val v = view(listOf(lost)).apply { onPick = { picked = it } }

            val row = allViews(v).filterIsInstance<TextView>()
                .first { it.text?.toString()?.startsWith("⚠") == true && it.hasOnClickListeners() }
            row.performClick()
            layout(v)

            assertNull("a clip with nothing behind it must not be committed", picked)
            assertTrue(text(R.string.clip_entry_lost_body) in labels(v))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tapping_a_clip_the_device_still_holds_but_cannot_read_does_not_call_it_gone() {
        val dir = Files.createTempDirectory("clipunreadable").toFile()
        val clips = File(dir, "clips").apply { mkdirs() }
        val sidecar = File(clips, "${"b".repeat(64)}.txt").apply { writeText("still on the device") }
        try {
            assertTrue(
                "precondition: the clip must be one the process cannot read",
                sidecar.setReadable(false) && !sidecar.canRead(),
            )
            val held = ClipEntry.stored(clips, "b".repeat(64))
            assertTrue("precondition: the device still holds the clip", held.available)
            var picked: String? = null
            val v = view(listOf(held)).apply { onPick = { picked = it } }

            val row = allViews(v).filterIsInstance<TextView>()
                .first { it.text?.toString()?.startsWith("⚠") == true && it.hasOnClickListeners() }
            row.performClick()
            layout(v)

            assertNull("a clip that could not be read must not be committed", picked)
            assertTrue(text(R.string.clip_entry_unreadable_body) in labels(v))
            assertFalse(
                "a clip the device is still holding must not be reported as gone from it",
                text(R.string.clip_entry_lost_body) in labels(v),
            )
        } finally {
            sidecar.setReadable(true)
            dir.deleteRecursively()
        }
    }

    @Test fun tapping_a_clip_that_is_still_there_still_commits_it() {
        var picked: String? = null
        val v = view(listOf("还在的").asClipEntries()).apply { onPick = { picked = it } }

        val row = allViews(v).filterIsInstance<TextView>()
            .first { it.text?.toString() == "还在的" && it.hasOnClickListeners() }
        row.performClick()
        layout(v)

        assertEquals("还在的", picked)
        assertFalse(text(R.string.clip_entry_lost_body) in labels(v))
    }
}
