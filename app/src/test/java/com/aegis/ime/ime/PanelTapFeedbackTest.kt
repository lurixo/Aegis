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

import android.media.AudioManager
import android.media.SoundPool
import android.os.Vibrator
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.translate.TranslateMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelTapFeedbackTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val vibrator = ctx.getSystemService(Vibrator::class.java)

    private fun root(id: LayoutId = LayoutId.NINE): InputView = InputView(ctx).apply {
        showKeyboard(Layouts.forId(id, Lang.CN), false, false, Lang.CN)
        setKeyHaptics(true)
        setKeyHapticStyle(KeyHaptic.DOUBLE)
        setKeyHapticStrength(50f)
        setKeySound(KeySound.BLUE)
        ctx.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(vibrator).setHasAmplitudeControl(true)
        Settings.System.putInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(ctx.contentResolver, "keyboard_vibration_enabled", 1)
        for (sample in KeySound.BLUE.sampleResources) shadowOf(pool(this)).notifyResourceLoaded(sample, true)
    }

    private fun pool(root: InputView): SoundPool {
        val player = InputView::class.java.getDeclaredField("keySoundPlayer").let { it.isAccessible = true; it.get(root) }
        return KeySoundPlayer::class.java.getDeclaredField("pool").let { it.isAccessible = true; it.get(player) as SoundPool }
    }
    private fun sounds(root: InputView): Int = KeySound.BLUE.sampleResources.sumOf { shadowOf(pool(root)).getResourcePlaybacks(it).size }
    private fun segments(): List<Any> = ReflectionHelpers.getStaticField<List<Any>>(org.robolectric.shadows.ShadowVibrator::class.java, "vibrationEffectSegments").toList()
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun send(view: View, action: Int, x: Float = view.width / 2f, y: Float = view.height / 2f) {
        MotionEvent.obtain(0, 0, action, x, y, 0).let { view.dispatchTouchEvent(it); it.recycle() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun text(view: View, label: String): View = descendants(view).first { it is TextView && it.text.toString() == label }

    private fun assertPress(root: InputView, target: View) {
        assertTrue(target.isEnabled)
        val background = target.background
        val foreground = target.foreground
        target.previewImeKeyHaptic(root.keyHapticStyle, root.keyHapticStrength)
        val expected = segments()
        vibrator.cancel()
        val before = sounds(root)
        send(target, MotionEvent.ACTION_DOWN)
        assertEquals("one sound at press", before + 1, sounds(root))
        assertTrue("custom haptics reached the vibrator", shadowOf(vibrator).isVibrating)
        assertEquals("current style and strength", expected, segments())
        assertSame(background, target.background)
        assertSame(foreground, target.foreground)
        send(target, MotionEvent.ACTION_MOVE)
        send(target, MotionEvent.ACTION_CANCEL)
        assertEquals("move and cancel do not replay", before + 1, sounds(root))
    }

    @Test fun clipboard_tabs_categories_body_and_existing_actions_play_once_on_both_layouts() {
        for (id in listOf(LayoutId.NINE, LayoutId.ALPHA)) {
            val root = root(id)
            val panel = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("first", "second") }
                phrasesInProvider = { listOf("phrase") }
            }
            root.showPanelImmediately(panel)
            layout(root)
            panel.refresh()
            layout(panel)
            assertPress(root, text(panel, ctx.getString(R.string.clip_phrases)))
            text(panel, ctx.getString(R.string.clip_phrases)).performClick()
            layout(panel)
            assertPress(root, text(panel, "second"))
            assertPress(root, text(panel, "phrase"))
            assertPress(root, text(panel, ctx.getString(R.string.clip_clipboard)))
        }
    }

    @Test fun bar_actions_custom_symbols_and_popup_choices_use_the_same_current_feedback() {
        val root = root()
        val edit = EditBarView(ctx); root.addView(edit); layout(edit)
        assertPress(root, edit.confirmButtonForTest())
        assertPress(root, descendants(edit).filterIsInstance<PanelHeaderBackControl>().single())
        val translate = TranslateBarView(ctx); root.addView(translate); layout(translate)
        assertPress(root, translate.modeButtonForTest())
        val choice = translate.choiceForTest(TranslateMode.AUTO); layout(choice)
        assertPress(root, choice)
        val custom = CustomSymbolPanel(ctx).apply { current = { listOf("!") }; addPalette = listOf("!", "?") }
        root.addView(custom); layout(custom)
        assertPress(root, requireNotNull(custom.paletteChipForTest("?")))
        assertPress(root, requireNotNull(custom.addedChipForTest("!")))
        val copy = CopyBarView(ctx); root.addView(copy); copy.show("sample"); layout(copy)
        assertPress(root, text(copy, "sample"))
        assertPress(root, text(copy, ctx.getString(R.string.copybar_split)))
        val confirmation = PanelConfirmationOverlay(ctx); root.addView(confirmation)
        confirmation.show("Remove?", "Remove", "Cancel", com.aegis.ime.ime.theme.ImePalette.STATIC_LIGHT) {}
        layout(confirmation)
        assertPress(root, requireNotNull(confirmation.confirmActionForTest()))
    }

    @Test fun toggles_disabled_controls_and_preedit_taps_do_not_duplicate_feedback() {
        val root = root()
        val edit = EditBarView(ctx); root.addView(edit); layout(edit)
        val target = edit.confirmButtonForTest()
        root.setKeyHaptics(false); vibrator.cancel()
        val before = sounds(root)
        send(target, MotionEvent.ACTION_DOWN); send(target, MotionEvent.ACTION_UP)
        assertEquals(before + 1, sounds(root)); assertFalse(shadowOf(vibrator).isVibrating)
        val loadedPool = shadowOf(pool(root))
        root.setKeySound(KeySound.OFF)
        send(target, MotionEvent.ACTION_DOWN); send(target, MotionEvent.ACTION_UP)
        assertEquals(before + 1, KeySound.BLUE.sampleResources.sumOf { loadedPool.getResourcePlaybacks(it).size })
        assertFalse(shadowOf(vibrator).isVibrating)
        root.setKeyHaptics(true); target.isEnabled = false
        send(target, MotionEvent.ACTION_DOWN); send(target, MotionEvent.ACTION_UP)
        assertFalse(shadowOf(vibrator).isVibrating)
        val preedit = PreeditView(ctx); root.addView(preedit); preedit.setText("ni"); layout(preedit)
        vibrator.cancel()
        val bounds = preedit.tabBounds()
        send(preedit, MotionEvent.ACTION_DOWN, bounds.centerX(), 10f)
        assertTrue(shadowOf(vibrator).isVibrating)
        send(preedit, MotionEvent.ACTION_CANCEL, bounds.centerX(), 10f)
    }
}
