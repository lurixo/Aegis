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

import com.aegis.ime.user.clipEntries
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVisualPolishTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun the_candidate_bar_shares_the_board_floor_without_a_bottom_rule() {
        val palette = ImePalette.STATIC_LIGHT.copy(
            keyboardBg = android.graphics.Color.WHITE,
            gridLine = android.graphics.Color.RED,
        )
        val v = CandidateView(ctx).apply {
            applyPalette(palette)
            setContent(listOf("\u4f60", "\u597d"), "ni")
        }
        val density = ctx.resources.displayMetrics.density
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))

        assertEquals("the bar shares the board floor", palette.keyboardBg, bmp.getPixel((2 * density).toInt(), (2 * density).toInt()))
        for (x in 0 until v.width) {
            assertNotEquals("no rule closes the candidate bar at x=$x", palette.gridLine, bmp.getPixel(x, v.height - 1))
        }

        val idle = CandidateView(ctx).apply { applyPalette(palette) }
        idle.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        idle.layout(0, 0, idle.measuredWidth, idle.measuredHeight)
        val idleBmp = android.graphics.Bitmap.createBitmap(idle.width, idle.height, android.graphics.Bitmap.Config.ARGB_8888)
        idle.draw(android.graphics.Canvas(idleBmp))
        assertEquals("the toolbar shares the board floor", palette.keyboardBg, idleBmp.getPixel((2 * density).toInt(), (2 * density).toInt()))
        for (x in 0 until idle.width) {
            assertNotEquals("no rule closes the toolbar at x=$x", palette.gridLine, idleBmp.getPixel(x, idle.height - 1))
        }
    }

    @Test fun candidate_toolbar_press_radius_is_smaller_than_key_radius() {
        val v = CandidateView(ctx)
        assertEquals(ImeShapes.toolbarFeedbackRadiusDp, v.taskbarPressRadiusDpForTest(), 0f)
        assertTrue("toolbar press shape must not read as a capsule", v.taskbarPressRadiusDpForTest() < v.keyPressRadiusDpForTest())
    }

    @Test fun shared_aegis_surface_radii_keep_the_taskbar_capsule() {
        assertEquals(10f, ImeShapes.keyRadiusDp, 0f)
        assertEquals(6f, ImeShapes.toolbarFeedbackRadiusDp, 0f)
        assertEquals(8f, ImeShapes.cardRadiusDp, 0f)
        assertEquals(8f, ImeShapes.inputRadiusDp, 0f)
        assertEquals(8f, ImeShapes.chipRadiusDp, 0f)
        assertEquals(999f, ImeShapes.toolbarPillRadiusDp, 0f)
    }

    @Test fun tap_feedback_helper_installs_a_rounded_ripple_foreground() {
        val v = View(ctx)
        Motion.applyTapFeedback(v, ImePalette.STATIC_LIGHT.keyLabel)
        assertTrue("clickable helper uses RippleDrawable feedback", v.foreground is RippleDrawable)
    }

    @Test fun symbols_and_emoji_category_tabs_mark_selection_with_an_accent_underline() {
        val palette = ImePalette.STATIC_LIGHT
        val symbols = SymbolsView(ctx).apply { applyPalette(palette); refresh() }
        val emoji = EmojiView(ctx).apply { applyPalette(palette) }

        for (tab in listOf(symbols.railTabForTest(0), emoji.railTabForTest(0), symbols.railTabForTest(1), emoji.railTabForTest(1))) {
            assertRoundedRailTab(tab, "category ${tab.text}", Color.TRANSPARENT)
        }
        assertEquals("symbols keep the accent underline", palette.accentBottom, symbols.categoryRailForTest().underlineColor)
        assertEquals("emoji keep the accent underline", palette.accentBottom, emoji.categoryRailForTest().underlineColor)
        assertEquals("selected symbols tab carries the underline", 0, symbols.categoryRailForTest().selectedIndex)
        assertEquals("selected emoji tab carries the underline", 0, emoji.categoryRailForTest().selectedIndex)
    }

    @Test fun copy_bar_background_uses_the_toolbar_capsule_radius() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val view = CopyBarView(ctx).apply {
                applyPalette(palette)
                show("测试内容")
            }
            val height = (44 * view.resources.displayMetrics.density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            val inset = requireNotNull(view.background as? InsetDrawable)
            val background = requireNotNull(inset.drawable as? GradientDrawable)
            val padding = Rect()
            inset.getPadding(padding)
            val innerHeight = view.measuredHeight - padding.top - padding.bottom
            assertEquals(palette.keySurface, background.color?.defaultColor)
            assertEquals(ImeShapes.toolbarPillRadiusDp * view.resources.displayMetrics.density, background.cornerRadius, 0f)
            assertTrue(background.cornerRadius >= innerHeight / 2f)
        }
    }

    @Test fun edit_panel_controls_all_use_rounded_tap_feedback() {
        val panel = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        assertAllClickableViewsUseRoundedTapFeedback(panel, "text edit panel")
    }

    @Test fun major_panel_click_targets_use_shared_rounded_tap_feedback() {
        assertAllClickableViewsUseRoundedTapFeedback(
            CandidateGridView(ctx).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                setReadings(listOf("ni", "hao"), selected = 0)
                setCandidates(listOf("你", "好"))
            },
            "expanded candidates",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。") }
                applyPalette(ImePalette.STATIC_LIGHT)
                refresh()
            },
            "symbols panel",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            EmojiView(ctx).apply {
                recentProvider = { listOf("😀", "😂") }
                applyPalette(ImePalette.STATIC_LIGHT)
            },
            "emoji panel",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            CustomSymbolPanel(ctx).apply {
                current = { listOf("、") }
                addPalette = listOf("、", "。", "，")
                applyPalette(ImePalette.STATIC_LIGHT)
                refresh()
            },
            "custom symbol panel",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            CopyBarView(ctx).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                show("测试内容")
            },
            "copy bar",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) },
            "inline edit bar",
        )
        assertAllClickableViewsUseRoundedTapFeedback(
            ClipboardView(ctx).apply {
                historyProvider = { clipEntries("clip") }
                categoriesProvider = { listOf("默认") }
                phrasesInProvider = { listOf("phrase") }
                applyPalette(ImePalette.STATIC_LIGHT)
                refresh()
            },
            "clipboard panel",
        )
    }

    @Test fun clipboard_select_mode_disabled_actions_keep_readable_contrast_in_light_and_dark() {
        assertDisabledActionContrast(ImePalette.STATIC_LIGHT)
        assertDisabledActionContrast(ImePalette.STATIC_DARK)
    }

    @Test fun phrase_select_mode_disabled_actions_keep_readable_contrast_in_light_and_dark() {
        assertPhraseDisabledActionContrast(ImePalette.STATIC_LIGHT)
        assertPhraseDisabledActionContrast(ImePalette.STATIC_DARK)
    }

    private fun assertRoundedRailTab(tab: TextView, label: String, faceColor: Int) {
        val surface = tab.background as? ImeKeySurface
        assertTrue("$label uses the shared rounded key surface", surface != null)
        assertEquals("$label resting face", faceColor, requireNotNull(surface).faceColor)
        assertFalse("$label does not stack a platform ripple", tab.foreground is RippleDrawable)
        assertTrue("$label remains clickable", tab.hasOnClickListeners())
    }

    private fun assertAllClickableViewsUseRoundedTapFeedback(root: View, label: String) {
        val clickable = clickableViews(root)
        assertTrue("$label exposes click targets for the audit", clickable.isNotEmpty())
        clickable.forEach { view ->
            if (view.background is ImeKeySurface) {
                assertFalse(
                    "$label click target ${view.javaClass.simpleName} must not stack a platform ripple on the shared key surface",
                    view.foreground is RippleDrawable,
                )
                return@forEach
            }
            val ripple = view.foreground as? RippleDrawable
                ?: throw AssertionError("$label click target ${view.javaClass.simpleName} lost rounded feedback")
            val mask = ripple.findDrawableByLayerId(android.R.id.mask) as? GradientDrawable
                ?: throw AssertionError("$label click target ${view.javaClass.simpleName} lost its ripple mask")
            assertTrue(
                "$label click target ${view.javaClass.simpleName} uses a rounded ripple mask",
                mask.cornerRadius > 0f || mask.cornerRadii?.any { it > 0f } == true,
            )
        }
    }

    private fun clickableViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.hasOnClickListeners()) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun assertDisabledActionContrast(palette: ImePalette) {
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("clip") }
            categoriesProvider = { listOf("默认") }
            applyPalette(palette)
            enterSelectForTest()
        }
        val add = textView(v, ctx.getString(com.aegis.ime.R.string.clip_add_phrase))
        val delete = textView(v, ctx.getString(com.aegis.ime.R.string.clip_delete))
        assertDisabledButton(add, palette)
        assertDisabledButton(delete, palette)
        assertEquals(palette.keyLabel, v.disabledActionTextColorForTest())
        assertEquals(palette.keyboardBg, v.disabledActionBackgroundColorForTest())
    }

    private fun assertPhraseDisabledActionContrast(palette: ImePalette) {
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "工作") }
            phrasesInProvider = { listOf("你好") }
            applyPalette(palette)
            forcePhrasesStateForTest("默认")
            enterSelectForTest()
        }
        val move = textView(v, ctx.getString(com.aegis.ime.R.string.clip_move_to_category))
        val delete = textView(v, ctx.getString(com.aegis.ime.R.string.clip_delete))
        assertDisabledButton(move, palette)
        assertDisabledButton(delete, palette)
    }

    private fun assertDisabledButton(tv: TextView, palette: ImePalette) {
        val surface = tv.background as? ImeKeySurface
            ?: throw AssertionError("disabled immediate action keeps the shared key surface")
        assertEquals("a text action draws no key face", Color.TRANSPARENT, surface.faceColor)
        val bg = palette.keyboardBg
        assertEquals(palette.keyLabel, tv.currentTextColor)
        assertTrue("disabled action text contrast is readable", contrastRatio(tv.currentTextColor, bg) >= 4.5)
        assertTrue("disabled action stays disabled", !tv.hasOnClickListeners())
    }

    private fun textView(root: View, label: String): TextView =
        textViews(root).first { it.text?.toString() == label }

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
