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

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.PREF_KEY_SOUND
import com.aegis.ime.ui.PREF_KEY_HAPTICS
import com.aegis.ime.ui.PREF_KEY_HAPTIC_STYLE
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeySoundPlayerTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun pool(player: KeySoundPlayer): SoundPool = KeySoundPlayer::class.java.getDeclaredField("pool").let {
        it.isAccessible = true
        it.get(player) as SoundPool
    }

    @Test fun each_switch_plays_its_own_sample_on_media_in_every_ringer_mode() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        for (sound in KeySound.entries.filter { it != KeySound.OFF }) {
            for (mode in listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
                audio.ringerMode = mode
                val player = KeySoundPlayer(context)
                player.select(sound)
                val soundPool = pool(player)
                val attributes = org.robolectric.util.ReflectionHelpers.getField<AudioAttributes>(soundPool, "mAttributes")
                assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
                assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
                assertEquals(AudioManager.STREAM_MUSIC, attributes.volumeControlStream)
                val shadow = shadowOf(soundPool)
                shadow.notifyResourceLoaded(sound.sampleRes, true)
                player.play()
                assertEquals(1, shadow.getResourcePlaybacks(sound.sampleRes).size)
                assertEquals(0, shadow.getResourcePlaybacks(sound.sampleRes).single().loop)
                assertEquals(1f, shadow.getResourcePlaybacks(sound.sampleRes).single().leftVolume, 0f)
                assertEquals(1f, shadow.getResourcePlaybacks(sound.sampleRes).single().rightVolume, 0f)
                for (other in KeySound.entries.filter { it != sound && it != KeySound.OFF }) assertFalse(shadow.wasResourcePlayed(other.sampleRes))
                player.select(KeySound.OFF)
                player.play()
                assertEquals(1, shadow.getResourcePlaybacks(sound.sampleRes).size)
                assertNull(org.robolectric.util.ReflectionHelpers.getField<SoundPool?>(player, "pool"))
            }
        }
    }

    @Test fun loading_does_not_replay_an_old_press_or_a_disabled_sound() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val player = KeySoundPlayer(context)
        player.select(KeySound.BLUE)
        val original = shadowOf(pool(player))
        player.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        original.notifyResourceLoaded(KeySound.BLUE.sampleRes, true)
        assertFalse(original.wasResourcePlayed(KeySound.BLUE.sampleRes))
        player.select(KeySound.BROWN)
        player.play()
        player.select(KeySound.OFF)
        original.notifyResourceLoaded(KeySound.BROWN.sampleRes, true)
        assertFalse(original.wasResourcePlayed(KeySound.BROWN.sampleRes))
    }

    @Test fun continuous_typing_rotates_recorded_keys_without_replaying_on_switch_change() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val player = KeySoundPlayer(context)
        for (sound in KeySound.entries.filter { it != KeySound.OFF }) {
            player.select(sound)
            val shadow = shadowOf(pool(player))
            assertEquals(4, sound.sampleResources.size)
            for (res in sound.sampleResources) shadow.notifyResourceLoaded(res, true)
            val played = ArrayList<Int>()
            repeat(12) {
                val before = sound.sampleResources.associateWith { res -> shadow.getResourcePlaybacks(res).size }
                player.select(sound)
                player.play()
                played.add(sound.sampleResources.single { res -> shadow.getResourcePlaybacks(res).size > before.getValue(res) })
            }
            assertEquals(listOf(3, 3, 3, 3), sound.sampleResources.map { shadow.getResourcePlaybacks(it).size })
            assertTrue(played.zipWithNext().all { (a, b) -> a != b })
            assertTrue(played.chunked(4).all { it.toSet().size == 4 })
            player.select(KeySound.OFF)
        }
    }

    @Test fun platform_haptics_are_not_blocked_by_redundant_legacy_settings() {
        val requested = mutableListOf<Int>()
        val view = object : View(context) {
            override fun performHapticFeedback(constant: Int): Boolean {
                requested.add(constant)
                return true
            }
        }
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 1)
        for (feedback in listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)) view.performImeKeyHaptic(true, feedback, KeyHaptic.SYSTEM)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK), requested)
        requested.clear()
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 0)
        view.playImeKeyFeedback(true)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), requested)
        requested.clear()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        view.playImeKeyFeedback(true)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP), requested)
        requested.clear()
        view.playImeKeyFeedback(false)
        assertTrue(requested.isEmpty())
    }

    @Test fun sound_choice_hot_applies_and_invalid_values_default_to_off() {
        val prefs = context.getSharedPreferences("aegis", 0)
        val applied = mutableListOf<KeySound>()
        val listener = SettingsHotApply({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { applied.add(it) })
        prefs.edit().putString(PREF_KEY_SOUND, "brown").commit()
        listener.onSharedPreferenceChanged(prefs, PREF_KEY_SOUND)
        assertEquals(listOf(KeySound.BROWN), applied)
        prefs.edit().putInt(PREF_KEY_SOUND, 1).commit()
        assertEquals(KeySound.OFF, SettingsHotApply.keySound(prefs))
    }

    @Test fun system_style_fallback_matches_a_keyboard_click_and_obeys_legacy_system_settings() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        fun recordedEffectId(): Int {
            val segments = org.robolectric.util.ReflectionHelpers.getStaticField<List<Any>>(
                org.robolectric.shadows.ShadowVibrator::class.java, "vibrationEffectSegments",
            )
            val effect = segments.single()
            return effect.javaClass.getMethod("getEffectId").invoke(effect) as Int
        }
        val view = object : View(context) {
            var accepted = false
            override fun performHapticFeedback(constant: Int): Boolean = accepted
        }
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 1)
        view.performImeKeyHaptic(false, style = KeyHaptic.SYSTEM)
        assertFalse(shadow.isVibrating)
        view.performImeKeyHaptic(true, style = KeyHaptic.SYSTEM)
        assertTrue(shadow.isVibrating)
        assertEquals(VibrationEffect.EFFECT_CLICK, recordedEffectId())
        vibrator.cancel()
        view.performImeKeyHaptic(true, HapticFeedbackConstants.LONG_PRESS, KeyHaptic.SYSTEM)
        assertEquals(VibrationEffect.EFFECT_CLICK, recordedEffectId())
        vibrator.cancel()
        view.performImeKeyHaptic(true, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, KeyHaptic.SYSTEM)
        assertFalse(shadow.isVibrating)
        vibrator.cancel()
        view.accepted = true
        view.performImeKeyHaptic(true, style = KeyHaptic.SYSTEM)
        assertFalse(shadow.isVibrating)
        view.accepted = false
        for (key in listOf(Settings.System.HAPTIC_FEEDBACK_ENABLED, "keyboard_vibration_enabled")) {
            Settings.System.putInt(context.contentResolver, key, 0)
            view.performImeKeyHaptic(true, style = KeyHaptic.SYSTEM)
            assertFalse(shadow.isVibrating)
            Settings.System.putInt(context.contentResolver, key, 1)
        }
    }

    @Test fun vibration_style_hot_applies_without_changing_the_existing_enable_switch() {
        val prefs = context.getSharedPreferences("aegis", 0)
        val applied = mutableListOf<KeyHaptic>()
        val listener = SettingsHotApply({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onKeyHapticStyle = { applied.add(it) })
        prefs.edit().putBoolean(PREF_KEY_HAPTICS, true).commit()
        assertEquals(KeyHaptic.CRISP, SettingsHotApply.keyHapticStyle(prefs))
        for (style in KeyHaptic.entries) {
            prefs.edit().putString(PREF_KEY_HAPTIC_STYLE, style.value).commit()
            listener.onSharedPreferenceChanged(prefs, PREF_KEY_HAPTIC_STYLE)
            assertEquals(style, applied.last())
            assertTrue(SettingsHotApply.keyHaptics(prefs))
        }
        prefs.edit().putBoolean(PREF_KEY_HAPTICS, false).commit()
        assertEquals(KeyHaptic.DOUBLE, SettingsHotApply.keyHapticStyle(prefs))
        assertFalse(SettingsHotApply.keyHaptics(prefs))
        for (invalid in listOf("unknown", 1)) {
            val editor = prefs.edit()
            if (invalid is String) editor.putString(PREF_KEY_HAPTIC_STYLE, invalid)
            else editor.putInt(PREF_KEY_HAPTIC_STYLE, invalid as Int)
            editor.commit()
            assertEquals(KeyHaptic.CRISP, SettingsHotApply.keyHapticStyle(prefs))
        }
    }

    @Test fun each_style_matches_its_preview_and_switches_immediately_on_both_keyboards() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_THUD))
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 1)
        val previewView = object : View(context) {
            var platformRequests = 0
            override fun performHapticFeedback(constant: Int): Boolean { platformRequests++; return true }
        }
        for (id in listOf(LayoutId.NINE, LayoutId.ALPHA)) {
            val input = InputView(context)
            input.setKeyHaptics(true)
            input.showKeyboard(Layouts.forId(id, Lang.CN), false, false, Lang.CN)
            input.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            input.layout(0, 0, input.measuredWidth, input.measuredHeight)
            val playedStyles = mutableSetOf<List<Any>>()
            for (style in KeyHaptic.entries.filter { it != KeyHaptic.SYSTEM }) {
                val strengths = mutableSetOf<List<Any>>()
                for (strength in listOf(10f, 80f, 100f)) {
                    previewView.previewImeKeyHaptic(style, strength)
                    val preview = recordedSegments()
                    vibrator.cancel()
                    input.setKeyHapticStyle(style)
                    input.setKeyHapticStrength(strength)
                    assertTrue(input.tapKeyboardLabelForTest(if (id == LayoutId.NINE) "ABC" else "q"))
                    assertEquals(preview, recordedSegments())
                    strengths.add(preview)
                    if (strength == 80f) playedStyles.add(preview)
                }
                assertEquals(3, strengths.size)
            }
            assertEquals(6, playedStyles.size)
            vibrator.cancel()
            input.setKeyHapticStyle(KeyHaptic.SYSTEM)
            input.tapKeyboardLabelForTest(if (id == LayoutId.NINE) "ABC" else "q")
            assertFalse(shadow.isVibrating)
        }
        assertEquals(0, previewView.platformRequests)
        previewView.previewImeKeyHaptic(KeyHaptic.SYSTEM)
        assertEquals(0, previewView.platformRequests)
        assertTrue(shadow.isVibrating)
    }

    @Test fun explicit_style_preview_plays_directly_and_falls_back_without_primitives() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK))
        val view = object : View(context) {
            var requests = 0
            override fun performHapticFeedback(constant: Int): Boolean { requests++; return false }
        }
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 0)
        view.performImeKeyHaptic(true, style = KeyHaptic.FIRM)
        assertFalse(shadow.isVibrating)
        view.requests = 0
        view.isHapticFeedbackEnabled = false
        view.previewImeKeyHaptic(KeyHaptic.FIRM)
        assertEquals(.8f, pulseData().maxOf { it.second }, .001f)
        vibrator.cancel()
        shadow.setSupportedPrimitives(emptyList())
        view.previewImeKeyHaptic(KeyHaptic.SYSTEM)
        val system = recordedSegments().single()
        assertEquals(VibrationEffect.EFFECT_CLICK, system.javaClass.getMethod("getEffectId").invoke(system))
        vibrator.cancel()
        for (style in KeyHaptic.entries.filter { it != KeyHaptic.SYSTEM }) {
            view.previewImeKeyHaptic(style)
            assertTrue(shadow.isVibrating)
            assertTrue(recordedSegments().all { it.javaClass.simpleName == "StepSegment" })
            vibrator.cancel()
        }
        assertEquals(0, view.requests)
        vibrator.cancel()
        shadow.setHasVibrator(false)
        view.previewImeKeyHaptic(KeyHaptic.FIRM)
        assertFalse(shadow.isVibrating)
    }

    private fun recordedSegments(): List<Any> = org.robolectric.util.ReflectionHelpers.getStaticField<List<Any>>(
        org.robolectric.shadows.ShadowVibrator::class.java, "vibrationEffectSegments",
    ).toList()

    @Test fun unsupported_primitives_keep_distinct_matching_and_adjustable_preview_and_key_effects() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(emptyList())
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        val view = object : View(context) {
            override fun performHapticFeedback(constant: Int): Boolean = error("Custom feedback must honor strength")
        }
        for (amplitudeControl in listOf(false, true)) {
            shadow.setHasAmplitudeControl(amplitudeControl)
            val styles = mutableSetOf<List<Any>>()
            for (style in KeyHaptic.entries.filter { it != KeyHaptic.SYSTEM }) {
                val strengths = mutableSetOf<List<Any>>()
                for (strength in listOf(10f, 80f, 100f)) {
                    view.previewImeKeyHaptic(style, strength)
                    val preview = recordedSegments()
                    vibrator.cancel()
                    view.performImeKeyHaptic(true, style = style, strength = strength)
                    assertEquals(preview, recordedSegments())
                    strengths.add(preview)
                    if (strength == 80f) styles.add(preview)
                    vibrator.cancel()
                }
                assertEquals("$style has three distinct strengths", 3, strengths.size)
            }
            assertEquals("All custom fallback styles are distinct", 6, styles.size)
        }
    }

    private fun pulseData(): List<Pair<Long, Float>> = recordedSegments().map {
        (it.javaClass.getMethod("getDuration").invoke(it) as Long) to
            (it.javaClass.getMethod("getAmplitude").invoke(it) as Float)
    }

    @Test fun full_strength_uses_maximum_amplitude_and_distinct_rhythms_instead_of_reduced_primitives() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(vibrator).setHasAmplitudeControl(true)
        shadowOf(vibrator).setSupportedPrimitives((1..8).toList())
        val view = View(context)
        val signatures = mutableSetOf<List<Pair<Long, Float>>>()
        for ((style, pulses) in listOf(KeyHaptic.SOFT to 1, KeyHaptic.CRISP to 1, KeyHaptic.FIRM to 1,
            KeyHaptic.TICK to 3, KeyHaptic.THUD to 3, KeyHaptic.DOUBLE to 2)) {
            view.previewImeKeyHaptic(style, 100f)
            val data = pulseData()
            assertEquals("$style reaches device amplitude 255", 1f, data.maxOf { it.second }, .0001f)
            assertEquals(0f, data.first().second, 0f)
            assertEquals(0f, data.last().second, 0f)
            assertEquals(pulses, data.zipWithNext().count { (a, b) -> a.second == 0f && b.second > 0f })
            assertTrue("Effects remain bounded", data.sumOf { it.first } in 30L..120L)
            signatures.add(data)
        }
        assertEquals(6, signatures.size)
        view.previewImeKeyHaptic(KeyHaptic.FIRM, 100f)
        assertTrue("Hammer has a sustained full-amplitude body", pulseData().any { it.first >= 60L && it.second == 1f })
        view.previewImeKeyHaptic(KeyHaptic.DOUBLE, 100f)
        assertTrue("Double tap has a discernible gap", pulseData().any { it.first >= 50L && it.second == 0f })
    }

    @Test fun strength_preserves_fractional_values_migrates_integers_and_hot_applies() {
        val prefs = context.getSharedPreferences("aegis", 0)
        val applied = mutableListOf<Float>()
        val listener = SettingsHotApply({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onKeyHapticStrength = { applied.add(it) })
        val key = com.aegis.ime.ui.PREF_KEY_HAPTIC_STRENGTH
        assertEquals(80f, SettingsHotApply.keyHapticStrength(prefs), 0f)
        prefs.edit().putBoolean(PREF_KEY_HAPTICS, true).putString(PREF_KEY_HAPTIC_STYLE, "double").commit()
        prefs.edit().putInt(key, 95).commit()
        assertEquals("Existing preferences survive the storage change", 95f, SettingsHotApply.keyHapticStrength(prefs), 0f)
        for ((stored, expected) in listOf(-1f to 0f, 0f to 0f, 25.375f to 25.375f, 80.125f to 80.125f,
            100f to 100f, 999f to 100f, Float.NaN to 80f, Float.POSITIVE_INFINITY to 80f)) {
            prefs.edit().putFloat(key, stored).commit()
            listener.onSharedPreferenceChanged(prefs, key)
            assertEquals(expected, applied.last(), 0f)
        }
        prefs.edit().putString(key, "invalid").commit()
        assertEquals(80f, SettingsHotApply.keyHapticStrength(prefs), 0f)
        prefs.edit().remove(key).commit()
        listener.onSharedPreferenceChanged(prefs, key)
        assertEquals(80f, applied.last(), 0f)
        assertEquals(KeyHaptic.DOUBLE, SettingsHotApply.keyHapticStyle(prefs))
        assertTrue(SettingsHotApply.keyHaptics(prefs))
    }

    @Test fun zero_strength_stops_preview_and_silences_custom_keys_without_platform_fallback() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        val view = object : View(context) {
            override fun performHapticFeedback(constant: Int): Boolean = error("Zero strength must remain silent")
        }
        for (style in KeyHaptic.entries.filter { it != KeyHaptic.SYSTEM }) {
            view.previewImeKeyHaptic(style, 100f)
            assertTrue(shadow.isVibrating)
            view.previewImeKeyHaptic(style, 0f)
            assertFalse(shadow.isVibrating)
            for (event in listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)) {
                view.performImeKeyHaptic(true, event, style, 0f)
                assertFalse(shadow.isVibrating)
            }
        }
    }

    @Test fun android_16_uses_ime_vibration_policy_for_both_preview_and_typing() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK))
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        Settings.System.putString(context.contentResolver, "keyboard_vibration_enabled", null)
        val view = object : View(context) {
            var requests = 0
            override fun performHapticFeedback(constant: Int): Boolean { requests++; return true }
        }
        val sdk = android.os.Build.VERSION.SDK_INT
        try {
            for (version in listOf(34, 35, 36, 37)) {
                org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", version)
                val expectedUsage = if (version >= 36) 0x52 else VibrationAttributes.USAGE_TOUCH
                for (style in listOf(KeyHaptic.SOFT, KeyHaptic.CRISP, KeyHaptic.FIRM)) {
                    view.previewImeKeyHaptic(style)
                    val preview = recordedSegments()
                    val attrs = shadow.vibrationAttributesFromLastVibration as VibrationAttributes
                    assertEquals(expectedUsage, attrs.usage)
                    assertEquals(0, attrs.flags)
                    vibrator.cancel()
                    view.requests = 0
                    view.performImeKeyHaptic(true, style = style)
                    if (version >= 36) {
                        assertEquals(0, view.requests)
                        assertEquals(preview, recordedSegments())
                        assertEquals(expectedUsage, (shadow.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
                    } else {
                        assertEquals(1, view.requests)
                        assertFalse(shadow.isVibrating)
                    }
                    vibrator.cancel()
                }
            }
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @Test fun custom_haptics_keep_selection_brief_and_confirmation_stronger_without_duplicate_feedback() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 1)
        val view = object : View(context) {
            override fun performHapticFeedback(constant: Int): Boolean = error("Custom effects must play once")
        }
        view.performImeKeyHaptic(true)
        val press = pulseData().sumOf { it.first }
        view.performImeKeyHaptic(true, HapticFeedbackConstants.LONG_PRESS)
        assertTrue(pulseData().sumOf { it.first } > press)
        view.performImeKeyHaptic(true, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        assertTrue(pulseData().sumOf { it.first } < press)
        assertTrue(pulseData().maxOf { it.second } < .5f)
        assertEquals(VibrationAttributes.USAGE_TOUCH, (shadow.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
        vibrator.cancel()
        view.performImeKeyHaptic(false)
        assertFalse(shadow.isVibrating)
        view.isHapticFeedbackEnabled = false
        view.performImeKeyHaptic(true)
        assertFalse(shadow.isVibrating)
    }

    @Test fun opening_and_sliding_a_long_press_adds_haptics_without_extra_key_sounds() {
        val input = InputView(context)
        input.setKeyPreviewNine(true)
        input.setKeyHapticStyle(KeyHaptic.SYSTEM)
        input.setKeySound(KeySound.BLUE)
        val player = InputView::class.java.getDeclaredField("keySoundPlayer").let { it.isAccessible = true; it.get(input) as KeySoundPlayer }
        val sounds = shadowOf(pool(player))
        sounds.notifyResourceLoaded(KeySound.BLUE.sampleRes, true)
        input.showKeyboard(Layouts.forId(LayoutId.NINE, Lang.CN), false, false, Lang.CN)
        input.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        input.layout(0, 0, input.measuredWidth, input.measuredHeight)
        val keyboard = InputView::class.java.getDeclaredField("keyboardView").let { it.isAccessible = true; it.get(input) as KeyboardView }
        keyboard.hapticEnabled = true
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Settings.System.putInt(context.contentResolver, "keyboard_vibration_enabled", 1)
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val key = requireNotNull(keyboard.boundsOfLabelForTest("ABC"))
        fun send(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(0, 0, action, x, y, 0).let { keyboard.dispatchTouchEvent(it); it.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(keyboard).lastHapticFeedbackPerformed())
        val box = requireNotNull(keyboard.caseBoxBoundsForTest())
        send(MotionEvent.ACTION_MOVE, box.left + box.width() / 14f, box.centerY())
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, shadowOf(keyboard).lastHapticFeedbackPerformed())
        assertEquals(1, sounds.getResourcePlaybacks(KeySound.BLUE.sampleRes).size)
        send(MotionEvent.ACTION_CANCEL, key.centerX(), key.centerY())
        player.release()
    }

    @Test fun both_layouts_and_the_nine_symbol_column_play_once_on_press() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        for (id in listOf(LayoutId.ALPHA, LayoutId.NINE)) {
            val input = InputView(context)
            input.setKeySound(KeySound.RED)
            input.setKeyHapticStyle(KeyHaptic.SYSTEM)
            val player = InputView::class.java.getDeclaredField("keySoundPlayer").let { it.isAccessible = true; it.get(input) as KeySoundPlayer }
            val shadow = shadowOf(pool(player))
            shadow.notifyResourceLoaded(KeySound.RED.sampleRes, true)
            input.showKeyboard(Layouts.forId(id, Lang.CN), false, false, Lang.CN)
            input.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            input.layout(0, 0, input.measuredWidth, input.measuredHeight)
            assertTrue(input.tapKeyboardLabelForTest(if (id == LayoutId.ALPHA) "q" else "ABC"))
            assertEquals(1, shadow.getResourcePlaybacks(KeySound.RED.sampleRes).size)
            if (id == LayoutId.NINE) {
                val keyboard = InputView::class.java.getDeclaredField("keyboardView").let { it.isAccessible = true; it.get(input) as KeyboardView }
                keyboard.hapticEnabled = true
                val region = keyboard.scrollRegionForTest()
                val x = region.centerX()
                val y = region.top + keyboard.scrollCellHeightForTest() / 2f
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 10, action, x, y, 0)
                    keyboard.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertEquals(2, shadow.getResourcePlaybacks(KeySound.RED.sampleRes).size)
                assertEquals(android.view.HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(keyboard).lastHapticFeedbackPerformed())
            }
            player.release()
        }
    }
}
