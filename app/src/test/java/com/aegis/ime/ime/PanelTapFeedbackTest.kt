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
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun send(view: View, action: Int, x: Float = view.width / 2f, y: Float = view.height / 2f) {
        MotionEvent.obtain(0, 0, action, x, y, 0).let { view.dispatchTouchEvent(it); it.recycle() }
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
