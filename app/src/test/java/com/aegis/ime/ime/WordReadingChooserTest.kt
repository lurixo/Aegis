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
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.aegis.ime.R
import com.aegis.ime.decoder.WordReading
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
class WordReadingChooserTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val polyphonic = WordReading(listOf("xing", "hao"), listOf(listOf("xing", "hang"), listOf("hao")), ambiguous = true)

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun action(root: View, label: Int): TextView =
        texts(root).single { it.text.toString() == ctx.getString(label) && it.hasOnClickListeners() }

    @Test fun the_chooser_starts_on_the_default_reading_and_offers_only_polyphonic_characters() {
        val iv = InputView(ctx)
        iv.showCopyBar("行好")
        iv.showWordReadingChooser("行好", polyphonic) {}
        val chooser = iv.wordReadingChooserForTest()!!
        assertEquals("xinghao", chooser.reading)
        val shown = texts(chooser)
        assertEquals("the word is the title", "行好", shown.first().text.toString())
        assertTrue(shown.single { it.text.toString() == "xing" }.isSelected)
        assertTrue(shown.single { it.text.toString() == "hang" }.hasOnClickListeners())
        assertFalse("a single reading is shown fixed", shown.single { it.text.toString() == "hao" }.hasOnClickListeners())
    }

    @Test fun a_character_settled_by_the_dictionary_can_still_be_changed() {
        val iv = InputView(ctx)
        val added = mutableListOf<String>()
        val settled = WordReading(
            listOf("chao", "le", "xing"),
            listOf(listOf("chao", "zhao"), listOf("le", "yue"), listOf("xing", "hang", "heng")),
            ambiguous = true,
        )
        iv.showWordReadingChooser("朝乐行", settled) { added += it }
        val chooser = iv.wordReadingChooserForTest()!!
        val shown = texts(chooser)
        for (preset in listOf("chao", "le", "xing")) assertTrue(shown.single { it.text.toString() == preset }.isSelected)
        for (other in listOf("zhao", "yue", "hang", "heng")) {
            val label = shown.single { it.text.toString() == other }
            assertFalse(label.isSelected)
            assertTrue(label.hasOnClickListeners())
        }
        chooser.pickForTest(1, "yue")
        chooser.pickForTest(2, "hang")
        assertEquals("chaoyuehang", chooser.reading)
        action(chooser, R.string.word_readings_add).performClick()
        assertEquals(listOf("chaoyuehang"), added)
    }

    @Test fun a_long_word_with_many_readings_scrolls_inside_the_panel() {
        val readings = listOf("zhong", "chong", "zhuang", "chuang", "shuang", "zheng")
        val word = "重".repeat(32)
        val chooser = WordReadingChooser(ctx)
        chooser.bind(word, WordReading(List(32) { readings[0] }, List(32) { readings }, ambiguous = true))
        val width = 720
        val height = 160
        chooser.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        chooser.layout(0, 0, width, height)
        fun find(root: View): HorizontalScrollView? = when (root) {
            is HorizontalScrollView -> root
            is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { find(root.getChildAt(it)) }
            else -> null
        }
        val row = find(chooser)!!
        val content = chooser.getChildAt(0)
        assertEquals(width, content.width)
        assertTrue("the character row stays inside the panel", row.left >= 0 && row.right <= width)
        assertTrue("the characters scroll sideways", row.getChildAt(0).width > row.width)
        assertTrue("the readings scroll down to the actions", content.height > height)
        assertEquals(32 * readings.size, texts(row).count { it.hasOnClickListeners() })
        for (label in listOf(R.string.word_readings_back, R.string.word_readings_add)) {
            val button = action(chooser, label)
            assertTrue(button.width > 0 && button.left >= 0 && button.right <= (button.parent as View).width)
            assertTrue(button.bottom <= (button.parent as View).height)
        }
    }

    @Test fun adding_writes_the_chosen_reading_and_closes() {
        val iv = InputView(ctx)
        val added = mutableListOf<String>()
        iv.showCopyBar("行好")
        iv.showWordReadingChooser("行好", polyphonic) { added += it }
        val chooser = iv.wordReadingChooserForTest()!!
        chooser.pickForTest(0, "hang")
        assertEquals("hanghao", chooser.reading)
        action(chooser, R.string.word_readings_add).performClick()
        assertEquals(listOf("hanghao"), added)
        assertNull(iv.wordReadingChooserForTest())
        assertTrue(iv.copyBarShown)
    }

    @Test fun back_action_closes_without_writing() {
        val iv = InputView(ctx)
        val added = mutableListOf<String>()
        iv.showWordReadingChooser("行好", polyphonic) { added += it }
        val chooser = iv.wordReadingChooserForTest()!!
        chooser.pickForTest(0, "hang")
        action(chooser, R.string.word_readings_back).performClick()
        assertEquals(emptyList<String>(), added)
        assertNull(iv.wordReadingChooserForTest())
        assertFalse(iv.hasOverlay())
    }

    @Test fun inside_the_clipboard_panel_the_chooser_adds_and_keeps_the_panel() {
        val iv = InputView(ctx)
        val panel = ClipboardView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val added = mutableListOf<String>()
        iv.showPanel(panel)
        iv.showWordReadingChooser("行好", polyphonic) { added += it }
        assertNull("the clipboard panel stays the panel", iv.wordReadingChooserForTest())
        val chooser = panel.wordReadingChooserForTest()!!
        chooser.pickForTest(0, "hang")
        action(chooser, R.string.word_readings_add).performClick()
        assertEquals(listOf("hanghao"), added)
        assertNull(panel.wordReadingChooserForTest())
        assertTrue(iv.panelShown)
    }

    @Test fun every_label_keeps_readable_contrast_in_light_and_dark() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val iv = InputView(ctx)
            iv.applyPalette(palette)
            iv.showWordReadingChooser("行好", polyphonic) {}
            val onKeyboard = iv.wordReadingChooserForTest()!!
            val panel = ClipboardView(ctx).apply { applyPalette(palette) }
            iv.showPanel(panel)
            iv.showWordReadingChooser("行好", polyphonic) {}
            val onCard = panel.wordReadingChooserForTest()!!
            for ((chooser, surface) in listOf(onKeyboard to palette.keyboardBg, onCard to palette.keySurface)) {
                for (label in texts(chooser)) {
                    val bg = if (label.isSelected) palette.accentBottom else surface
                    val contrast = ColorUtils.calculateContrast(label.currentTextColor, bg)
                    assertTrue("\"${label.text}\" contrast $contrast", contrast >= 4.5)
                }
            }
        }
    }
}
