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
import com.aegis.ime.user.PhraseChange
import com.aegis.ime.user.PhraseEdit
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

    private fun zhString(name: String): String {
        val found = Regex("<string name=\"$name\">(.*?)</string>")
            .find(File("src/main/res/values-zh/strings.xml").readText())
        assertTrue("values-zh must define $name", found != null)
        return found!!.groupValues[1]
    }

    private fun enString(name: String): String {
        val found = Regex("<string name=\"$name\">(.*?)</string>")
            .find(File("src/main/res/values/strings.xml").readText())
        assertTrue("values must define $name", found != null)
        return found!!.groupValues[1]
    }

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

    private fun phraseView(onDelete: (String, List<String>) -> Boolean): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { c -> if (c == "默认") listOf("要删的常用语") else emptyList() }
        onDeletePhrasesFrom = onDelete
        applyPalette(pal)
        forcePhrasesStateForTest("默认")
        refresh()
        layout(this)
    }

    private fun emptyPhraseView(readable: Boolean): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { emptyList() }
        phrasesReadableProvider = { readable }
        applyPalette(pal)
        forcePhrasesStateForTest("默认")
        refresh()
        layout(this)
    }

    private fun deleteFirstPhrase(v: ClipboardView, row: String) {
        v.expandForTest(row)
        layout(v)
        clickText(mainOf(v), text(R.string.clip_delete))
        layout(v)
        assertTrue("the confirm card must ask first", text(R.string.clip_delete_phrase_confirm) in labels(v))
        clickText(overlayOf(v), text(R.string.clip_delete))
        layout(v)
    }

    @Test fun a_phrase_delete_that_was_not_written_says_so() {
        val v = phraseView { _, _ -> false }

        deleteFirstPhrase(v, "要删的常用语")

        assertTrue(text(R.string.clip_phrase_change_not_saved) in labels(v))
        assertFalse(
            "a phrase that was not written must not point the user at the clipboard history",
            text(R.string.clip_change_not_saved) in labels(v),
        )
    }

    @Test fun a_phrase_delete_that_was_written_says_nothing() {
        val v = phraseView { _, _ -> true }

        deleteFirstPhrase(v, "要删的常用语")

        assertFalse(text(R.string.clip_phrase_change_not_saved) in labels(v))
        assertFalse(text(R.string.clip_change_not_saved) in labels(v))
    }

    @Test fun a_phrase_list_that_could_not_be_read_is_not_shown_as_an_empty_one() {
        val v = emptyPhraseView(readable = false)

        assertTrue(text(R.string.clip_phrases_unreadable) in labels(v))
        assertFalse(
            "an unreadable phrase list shown as an empty one tells the user their phrases are gone",
            text(R.string.clip_phrases_empty) in labels(v),
        )
    }

    @Test fun the_unreadable_phrase_notice_only_promises_what_the_panel_really_does() {
        val v = emptyPhraseView(readable = false)
        val en = text(R.string.clip_phrases_unreadable_hint)
        assertTrue("precondition: the notice is the one on screen", en in labels(v))

        assertTrue("the file really is left alone, so that half stays", en.contains("has not been changed or emptied"))
        assertFalse("nothing turns the controls off, so the notice must not promise it does", en.contains("Editing stays off"))
        assertTrue("it must say what becomes of an edit instead", en.contains("cannot be saved"))

        val zh = zhString("clip_phrases_unreadable_hint")
        assertTrue(zh.contains("文件没有被改动"))
        assertFalse("ZH must not promise editing is blocked either", zh.contains("不能编辑"))
        assertTrue("ZH must say the same thing EN does", zh.contains("存不进去"))
    }

    @Test fun the_unreadable_clipboard_notice_says_what_becomes_of_what_you_copy() {
        val v = view(emptyList(), readable = false)
        val en = text(R.string.clip_clipboard_unreadable_hint)
        assertTrue("precondition: the notice is the one on screen", en in labels(v))

        assertTrue("the file really is left alone, so that half stays", en.contains("has not been changed or emptied"))
        assertTrue(
            "a history that cannot be read records nothing new, and the notice must not leave that out",
            en.contains("what you copy is not added here"),
        )

        val zh = zhString("clip_clipboard_unreadable_hint")
        assertTrue(zh.contains("文件没有被改动"))
        assertTrue("ZH must say the same thing EN does", zh.contains("你复制的内容不会记到这里"))
    }

    @Test fun an_empty_phrase_list_is_still_shown_as_an_empty_one() {
        val v = emptyPhraseView(readable = true)

        assertTrue(text(R.string.clip_phrases_empty) in labels(v))
        assertFalse(text(R.string.clip_phrases_unreadable) in labels(v))
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
        assertFalse(
            "a clip that was not written must not point the user at the phrase list",
            text(R.string.clip_phrase_change_not_saved) in labels(v),
        )
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

    private fun blankPanel(): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认")
        refresh()
        layout(this)
    }

    private fun notice(edit: PhraseEdit, count: Int, requested: Int, saved: Boolean): String =
        phraseWriteNotice(ctx, PhraseChange(edit, count, requested, saved))

    @Test fun an_add_that_reached_the_list_but_not_the_file_is_never_read_as_one_that_landed() {
        assertEquals(
            "a count above zero must not be read before the write it belongs to",
            ctx.getString(R.string.clip_phrases_not_saved, 2),
            notice(PhraseEdit.ADD, 2, 3, saved = false),
        )
    }

    @Test fun an_add_names_what_landed_and_what_was_already_there() {
        assertEquals(ctx.getString(R.string.clip_phrases_saved, 2), notice(PhraseEdit.ADD, 2, 2, saved = true))
        assertEquals(
            "a write that landed leaves nothing unsaved, so its shortfall is what was already there",
            ctx.getString(R.string.clip_phrases_saved_existing, 1, 1),
            notice(PhraseEdit.ADD, 1, 2, saved = true),
        )
        assertEquals(ctx.getString(R.string.clip_phrases_exist, 3), notice(PhraseEdit.ADD, 0, 3, saved = true))
        assertEquals(ctx.getString(R.string.clip_phrases_not_saved, 3), notice(PhraseEdit.ADD, 0, 3, saved = false))
    }

    @Test fun a_landed_add_names_its_shortfall_as_entries_that_were_already_there() {
        assertEquals(
            "a write that landed left nothing unsaved, so its shortfall must read as entries found in place",
            "Saved %1\$d, %2\$d were already in this category",
            enString("clip_phrases_saved_existing"),
        )
        assertEquals(
            "已存 %1\$d 条，%2\$d 条已在该分类中",
            zhString("clip_phrases_saved_existing"),
        )
        assertFalse(
            "the wording a landed add uses for its shortfall must not be the wording for a write that failed",
            notice(PhraseEdit.ADD, 1, 2, saved = true) == notice(PhraseEdit.ADD, 1, 2, saved = false),
        )
    }

    @Test fun a_move_counts_only_the_entries_it_really_carried_across() {
        assertEquals(
            "the entries that stayed put were never moved, so they cannot come back",
            ctx.getString(R.string.clip_phrases_not_moved, 1),
            notice(PhraseEdit.MOVE, 1, 3, saved = false),
        )
        assertEquals(
            ctx.getString(R.string.clip_phrases_moved_partial, 1, 2),
            notice(PhraseEdit.MOVE, 1, 3, saved = true),
        )
        assertEquals("", notice(PhraseEdit.MOVE, 3, 3, saved = true))
        assertEquals(text(R.string.clip_phrase_change_not_saved), notice(PhraseEdit.MOVE, 0, 2, saved = false))
    }

    @Test fun each_kind_of_phrase_write_that_did_not_land_says_what_it_was() {
        assertEquals(text(R.string.clip_phrase_edit_not_saved), notice(PhraseEdit.TEXT, 1, 1, saved = false))
        assertEquals(text(R.string.clip_category_not_saved), notice(PhraseEdit.CATEGORY, 1, 1, saved = false))
        assertEquals(text(R.string.clip_phrase_change_not_saved), notice(PhraseEdit.LIST, 1, 1, saved = false))
        assertTrue(
            "a write that landed owes the user nothing to dismiss",
            listOf(PhraseEdit.TEXT, PhraseEdit.CATEGORY, PhraseEdit.LIST)
                .all { notice(it, 1, 1, saved = true).isEmpty() },
        )
    }

    @Test fun a_phrase_write_the_panel_is_told_about_puts_the_failure_on_screen() {
        val v = blankPanel()

        v.reportPhraseWrite(PhraseChange(PhraseEdit.LIST, 1, 1, saved = false))
        layout(v)

        assertTrue(text(R.string.clip_phrase_change_not_saved) in labels(v))
    }

    @Test fun a_phrase_write_that_landed_leaves_the_panel_alone() {
        val v = blankPanel()

        v.reportPhraseWrite(PhraseChange(PhraseEdit.LIST, 1, 1, saved = true))
        layout(v)

        assertFalse(text(R.string.clip_phrase_change_not_saved) in labels(v))
        assertFalse(text(R.string.clip_done) in labels(v))
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
