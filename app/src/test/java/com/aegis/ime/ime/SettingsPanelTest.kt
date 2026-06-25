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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ui.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class SettingsPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val light = ImePalette.STATIC_LIGHT

    private class FakeHost : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun panel(): SettingsPanelView = SettingsPanelView(ctx).apply { applyPalette(light) }

    private fun layoutPanel(panel: SettingsPanelView, widthDp: Int = 360, heightDp: Int = 290) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun bounds(panel: SettingsPanelView, shortcut: SettingsShortcut): Rect {
        val card = panel.cardViewForTest(shortcut)
        return Rect(0, 0, card.width, card.height).also { panel.offsetDescendantRectToMyCoords(card, it) }
    }

    private fun send(view: View, action: Int, x: Float, y: Float, downTime: Long = 0L, eventTime: Long = downTime) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun labels(panel: SettingsPanelView): List<String> =
        panel.shortcutsForTest().map { panel.cardViewForTest(it).text.toString() }

    @Test fun the_panel_lists_aegis_then_every_settings_home_page_in_order() {
        val panel = panel()
        assertEquals(SettingsShortcut.entries, panel.shortcutsForTest())
        assertNull(SettingsShortcut.HOME.route)
        assertEquals(SettingsRoutes.GROUPS, panel.shortcutsForTest().drop(1).map { it.route })
        assertEquals(listOf("Aegis", "输入设置", "键盘设置", "词库与下载", "用户词库", "数据备份", "关于与启用"), labels(panel))
        assertEquals("设置", panel.titleButtonForTest().text.toString())
    }

    @Config(sdk = [34], qualifiers = "en")
    @Test fun english_labels_reuse_the_settings_home_titles() {
        val panel = panel()
        assertEquals(
            listOf(
                "Aegis", "Input settings", "Keyboard settings", "Dictionaries & downloads",
                "User dictionary", "Data backup", "About & enable",
            ),
            labels(panel),
        )
        assertEquals("Settings", panel.titleButtonForTest().text.toString())
    }

    @Test fun cards_reuse_the_layout_card_face_and_press_timeline_without_haptics() {
        val panel = panel()
        layoutPanel(panel)
        for (shortcut in SettingsShortcut.entries) {
            val card = panel.cardViewForTest(shortcut)
            assertSame(panel.cardFeedbackDrawableForTest(shortcut), card.background)
            assertFalse(card.background is RippleDrawable)
            assertNull(card.foreground)

            val x = card.width / 2f
            val y = card.height / 2f
            send(card, MotionEvent.ACTION_DOWN, x, y)
            assertEquals(1f, panel.cardFeedbackLevelForTest(shortcut), 0f)
            assertEquals(0x22, Color.alpha(Motion.stateLayerColor(card.currentTextColor, panel.cardFeedbackLevelForTest(shortcut))))
            assertEquals("settings cards never use keyboard haptics", -1, shadowOf(card).lastHapticFeedbackPerformed())
            send(card, MotionEvent.ACTION_UP, x, y, eventTime = 16L)
            assertEquals(0f, panel.cardFeedbackLevelForTest(shortcut), 0f)
        }
    }

    @Test fun holding_or_releasing_outside_a_card_never_opens_it() {
        val panel = panel()
        layoutPanel(panel)
        val picks = ArrayList<SettingsShortcut>()
        panel.onPick = picks::add
        val card = panel.cardViewForTest(SettingsShortcut.KEYBOARD)
        val x = card.width / 2f
        val y = card.height / 2f

        send(card, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("holding a card never repeats or opens it", picks.isEmpty())
        send(card, MotionEvent.ACTION_MOVE, card.width + 100f, y, eventTime = 610L)
        assertEquals(0f, panel.cardFeedbackLevelForTest(SettingsShortcut.KEYBOARD), 0f)
        send(card, MotionEvent.ACTION_UP, card.width + 100f, y, eventTime = 620L)
        assertTrue("releasing outside the card opens nothing", picks.isEmpty())

        assertTrue(card.performClick())
        assertEquals(listOf(SettingsShortcut.KEYBOARD), picks)
    }

    @Test fun a_phone_width_panel_lays_the_cards_out_in_four_columns_and_two_rows() {
        for (widthDp in listOf(360, 411)) {
            val panel = panel()
            layoutPanel(panel, widthDp)
            assertEquals(4, panel.columnsForTest())
            val all = SettingsShortcut.entries.map { bounds(panel, it) }
            val w = all[0].width()
            val h = all[0].height()
            all.forEach {
                assertEquals("every card shares one width at ${widthDp}dp", w, it.width())
                assertEquals("every card shares one height at ${widthDp}dp", h, it.height())
            }
            for ((i, b) in all.withIndex()) {
                assertEquals(dp(4) + (i % 4) * (w + dp(8)), b.left)
                assertEquals(dp(56) + dp(8) + (i / 4) * (h + dp(8)), b.top)
            }
            assertTrue("the last column ends at the edge inset", all[3].right <= dp(widthDp) - dp(4))
            assertTrue("the right edge inset leaves at most the rounding remainder", dp(widthDp) - dp(4) - all[3].right < 4)
            val scroller = panel.scrollerForTest()
            assertTrue("both rows fit without scrolling", scroller.getChildAt(0).height <= scroller.height)
        }
    }

    @Test fun a_wide_panel_lays_all_cards_out_in_one_row() {
        val wide = panel()
        layoutPanel(wide, widthDp = 616)
        assertEquals(7, wide.columnsForTest())
        val row = SettingsShortcut.entries.map { bounds(wide, it) }
        row.forEach { assertEquals(dp(56) + dp(8), it.top) }
        row.zipWithNext().forEach { (a, b) -> assertEquals(dp(8), b.left - a.right) }

        val narrow = panel()
        layoutPanel(narrow, widthDp = 615)
        assertEquals(4, narrow.columnsForTest())
    }

    @Test fun chinese_labels_share_one_size_and_stay_on_one_line() {
        for (widthDp in listOf(360, 411)) {
            val panel = panel()
            layoutPanel(panel, widthDp)
            val cards = SettingsShortcut.entries.map { panel.cardViewForTest(it) }
            assertEquals(1, cards.map { it.textSize }.distinct().size)
            cards.forEach {
                assertEquals("${it.text} stays on one line at ${widthDp}dp", 1, it.layout.lineCount)
                assertEquals(0, it.layout.getEllipsisCount(0))
            }
        }
    }

    @Config(sdk = [34], qualifiers = "en")
    @Test fun english_labels_wrap_between_words_within_two_lines() {
        val panel = panel()
        layoutPanel(panel, widthDp = 360)
        val cards = SettingsShortcut.entries.map { panel.cardViewForTest(it) }
        assertEquals(1, cards.map { it.textSize }.distinct().size)
        cards.forEach { card ->
            val text = card.text.toString()
            val layout = card.layout
            assertTrue("$text fits in two lines", layout.lineCount <= 2)
            for (line in 0 until layout.lineCount) {
                assertEquals("$text is never cut short", 0, layout.getEllipsisCount(line))
                val end = layout.getLineEnd(line)
                assertTrue("$text only breaks between words", end == text.length || text[end - 1] == ' ')
            }
        }
    }

    @Test fun a_short_panel_scrolls_to_the_last_row_and_resets_to_the_top() {
        val panel = panel()
        layoutPanel(panel, heightDp = 150)
        val scroller = panel.scrollerForTest()
        val content = scroller.getChildAt(0)
        assertTrue("the rows overflow a short panel", content.height > scroller.height)
        scroller.scrollTo(0, content.height)
        val last = bounds(panel, SettingsShortcut.ABOUT)
        assertTrue("the last row can be scrolled into view", last.bottom <= panel.height && last.top >= dp(56))
        panel.resetToDefault()
        assertEquals(0, scroller.scrollY)
    }

    @Test fun every_card_icon_matches_the_layout_card_icon_box_and_inks_its_keyline() {
        val panel = panel()
        val box = dp(34) + 2 * dp(6)
        val unit = 32f * density / 24f
        val keylines = mapOf(
            SettingsShortcut.HOME to (16f to 19.5f),
            SettingsShortcut.INPUT to (20f to 16f),
            SettingsShortcut.KEYBOARD to (20f to 15f),
            SettingsShortcut.DICTS to (16f to 20f),
            SettingsShortcut.USER_DICT to (16f to 20f),
            SettingsShortcut.BACKUP to (15.5f to 20f),
            SettingsShortcut.ABOUT to (20f to 20f),
        )
        assertEquals(SettingsShortcut.entries.toSet(), keylines.keys)
        for (shortcut in SettingsShortcut.entries) {
            val icon = panel.cardIconForTest(shortcut)
            assertEquals(box, icon.intrinsicWidth)
            assertEquals(box, icon.intrinsicHeight)
            icon.setBounds(0, 0, box, box)
            val bitmap = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
            icon.draw(Canvas(bitmap))
            var minX = box; var minY = box; var maxX = -1; var maxY = -1
            for (y in 0 until box) {
                for (x in 0 until box) {
                    if (Color.alpha(bitmap.getPixel(x, y)) <= 128) continue
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
            assertTrue("$shortcut draws ink", maxX >= 0)
            val (width, height) = keylines.getValue(shortcut)
            assertEquals("$shortcut ink width", width * unit, (maxX - minX + 1).toFloat(), 1.5f * density)
            assertEquals("$shortcut ink height", height * unit, (maxY - minY + 1).toFloat(), 1.5f * density)
            assertEquals("$shortcut ink is centered horizontally", box / 2f, (minX + maxX + 1) / 2f, 1.5f * density)
            assertEquals("$shortcut ink is centered vertically", box / 2f, (minY + maxY + 1) / 2f, 1.5f * density)
        }
    }

    @Test fun the_palette_tints_the_panel_cards_icons_and_title() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val panel = SettingsPanelView(ctx).apply { applyPalette(palette) }
            assertEquals(palette.keyboardBg, (panel.background as android.graphics.drawable.ColorDrawable).color)
            assertEquals(palette.keyLabel, panel.titleButtonForTest().currentTextColor)
            for (shortcut in SettingsShortcut.entries) {
                assertEquals(palette.keyLabel, panel.cardViewForTest(shortcut).currentTextColor)
                assertEquals(palette.keyLabel, panel.cardIconForTest(shortcut).tintForTest())
            }
        }
    }

    private class Fixture(val input: InputView, val panel: SettingsPanelView)

    private fun fixture(): Fixture {
        val controller = KeyboardController(FakeHost(), engine)
        val input = InputView(ctx)
        controller.attachView(input)
        controller.reset()
        val panel = panel()
        panel.onBack = { input.showPanel(null) }
        return Fixture(input, panel)
    }

    @Test fun back_and_predictive_back_close_the_panel() {
        val f = fixture()
        f.input.showPanel(f.panel)
        assertTrue(f.input.isPanelShowing(f.panel))
        f.panel.titleButtonForTest().performClick()
        assertFalse(f.input.panelShown)

        f.input.showPanel(f.panel)
        assertEquals("PANEL", f.input.backTargetKindForTest())
        assertTrue(f.input.closeTopOverlay())
        assertFalse(f.input.panelShown)
    }

    @Test fun closing_and_reopening_the_panel_clears_an_active_card_press() {
        val f = fixture()
        f.input.showPanel(f.panel)
        layoutPanel(f.panel)
        val card = f.panel.cardViewForTest(SettingsShortcut.BACKUP)
        send(card, MotionEvent.ACTION_DOWN, card.width / 2f, card.height / 2f)
        assertEquals(1f, f.panel.cardFeedbackLevelForTest(SettingsShortcut.BACKUP), 0f)

        f.input.showPanel(null)
        assertEquals(0f, f.panel.cardFeedbackLevelForTest(SettingsShortcut.BACKUP), 0f)

        f.input.showPanel(f.panel)
        layoutPanel(f.panel)
        assertEquals(0f, f.panel.cardFeedbackLevelForTest(SettingsShortcut.BACKUP), 0f)
        assertTrue(f.input.isPanelShowing(f.panel))
    }
}
