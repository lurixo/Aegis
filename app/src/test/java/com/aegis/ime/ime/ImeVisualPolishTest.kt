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

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.pow
import org.junit.Assert.assertEquals
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

    @Test fun candidate_toolbar_press_radius_is_smaller_than_key_radius() {
        val v = CandidateView(ctx)
        assertEquals(ImeShapes.toolbarFeedbackRadiusDp, v.taskbarPressRadiusDpForTest(), 0f)
        assertTrue("toolbar press shape must not read as a capsule", v.taskbarPressRadiusDpForTest() < v.keyPressRadiusDpForTest())
    }

    @Test fun shared_aegis_surface_radii_keep_the_taskbar_capsule() {
        assertEquals(8f, ImeShapes.keyRadiusDp, 0f)
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

    @Test fun symbols_and_emoji_recent_tabs_use_rounded_selected_and_pressed_shapes() {
        val symbols = SymbolsView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); refresh() }
        val emoji = EmojiView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }

        assertRoundedRailTab(symbols.railTabForTest(0), "symbols 常用")
        assertRoundedRailTab(emoji.railTabForTest(0), "emoji 最近")
        assertTrue("symbols unselected tabs also use rounded ripple", symbols.railTabForTest(1).foreground is RippleDrawable)
        assertTrue("emoji unselected tabs also use rounded ripple", emoji.railTabForTest(1).foreground is RippleDrawable)
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
            ClipboardView(ctx).apply {
                historyProvider = { listOf("clip") }
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

    private fun assertRoundedRailTab(tab: TextView, label: String) {
        assertTrue("$label selected background is rounded", tab.background is GradientDrawable)
        assertTrue("$label press state is a rounded ripple", tab.foreground is RippleDrawable)
        assertTrue("$label remains clickable", tab.hasOnClickListeners())
    }

    private fun assertAllClickableViewsUseRoundedTapFeedback(root: View, label: String) {
        val clickable = clickableViews(root)
        assertTrue("$label exposes click targets for the audit", clickable.isNotEmpty())
        clickable.forEach {
            assertTrue("$label click target ${it.javaClass.simpleName} uses rounded ripple feedback", it.foreground is RippleDrawable)
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
            historyProvider = { listOf("clip") }
            categoriesProvider = { listOf("默认") }
            applyPalette(palette)
            enterSelectForTest()
        }
        val add = textView(v, ctx.getString(com.aegis.ime.R.string.clip_add_phrase))
        val delete = textView(v, ctx.getString(com.aegis.ime.R.string.clip_delete))
        assertDisabledButton(add, palette)
        assertDisabledButton(delete, palette)
        assertEquals(palette.keyLabelSecondary, v.disabledActionTextColorForTest())
        assertEquals(palette.chipBg, v.disabledActionBackgroundColorForTest())
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
        val bg = (tv.background as GradientDrawable).color?.defaultColor
        assertEquals(palette.chipBg, bg)
        assertEquals(palette.keyLabelSecondary, tv.currentTextColor)
        assertTrue("disabled action text contrast is readable", contrastRatio(tv.currentTextColor, bg!!) >= 4.5)
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
