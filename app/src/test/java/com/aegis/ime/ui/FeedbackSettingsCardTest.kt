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

package com.aegis.ime.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.ime.KeySound
import com.aegis.ime.ime.KeyHaptic
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FeedbackSettingsCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private fun node(id: Int) = compose.onNodeWithText(context.getString(id))

    private fun recordedSegments(): List<Any> = org.robolectric.util.ReflectionHelpers.getStaticField<List<Any>>(
        org.robolectric.shadows.ShadowVibrator::class.java, "vibrationEffectSegments",
    ).toList()

    @Test fun selecting_and_reselecting_a_haptic_style_previews_its_distinct_pattern() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        fun clearEffects() = org.robolectric.util.ReflectionHelpers.getStaticField<MutableList<Any>>(
            org.robolectric.shadows.ShadowVibrator::class.java, "vibrationEffectSegments",
        ).clear()
        prefs.edit().putBoolean(PREF_KEY_HAPTICS, false).putString(PREF_KEY_HAPTIC_STYLE, "crisp").commit()
        compose.setContent { AegisTheme { Column(Modifier.verticalScroll(rememberScrollState())) { KeyVibrationToggleCard() } } }
        node(R.string.key_haptic_crisp).assertDoesNotExist()
        node(R.string.key_vibration_title).performScrollTo().performClick()
        assertTrue(recordedSegments().isNotEmpty())
        val signatures = mutableSetOf<List<Any>>()
        for (style in KeyHaptic.entries.filter { it != KeyHaptic.SYSTEM }) {
            var previous: List<Any>? = null
            repeat(2) {
                vibrator.cancel()
                clearEffects()
                node(style.labelRes).performScrollTo().performClick().assertIsSelected()
                node(style.descriptionRes).assertExists()
                assertEquals(style.value, prefs.getString(PREF_KEY_HAPTIC_STYLE, null))
                val data = recordedSegments()
                assertTrue(data.isNotEmpty())
                if (previous != null) assertEquals(previous, data)
                previous = data
                signatures.add(data)
            }
        }
        assertEquals(6, signatures.size)
        vibrator.cancel()
        clearEffects()
        compose.waitForIdle()
        assertTrue(recordedSegments().isEmpty())
        node(R.string.key_vibration_title).performScrollTo().performClick()
        node(R.string.key_haptic_double).assertDoesNotExist()
        assertTrue(recordedSegments().isEmpty())
        assertFalse(prefs.getBoolean(PREF_KEY_HAPTICS, true))
    }

    @Test fun strength_slider_saves_previews_restores_and_follows_the_master_and_system_style() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true)
        shadow.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_CLICK))
        val generation = mutableIntStateOf(0)
        prefs.edit().putBoolean(PREF_KEY_HAPTICS, true).putString(PREF_KEY_HAPTIC_STYLE, "crisp").commit()
        compose.setContent {
            AegisTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    key(generation.intValue) { KeyVibrationToggleCard() }
                }
            }
        }
        val slider = compose.onNodeWithContentDescription(context.getString(R.string.key_haptic_strength))
        compose.onNodeWithText("80.0%").assertExists()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(25.375f) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(25.375f, prefs.getFloat(PREF_KEY_HAPTIC_STRENGTH, -1f), 0f)
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0, range.steps)
        assertEquals(25.375f, range.current, 0f)
        compose.onNodeWithText("25.4%").assertExists()
        assertTrue(recordedSegments().isNotEmpty())
        vibrator.cancel()
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithText("25.4%").assertExists()
        assertFalse(shadow.isVibrating)
        node(R.string.key_haptic_system).performScrollTo().performClick()
        slider.assertIsNotEnabled()
        node(R.string.key_haptic_double).performScrollTo().performClick().assertIsSelected()
        assertEquals(2, recordedSegments().count { (it.javaClass.getMethod("getAmplitude").invoke(it) as Float) != 0f })
        node(R.string.key_vibration_title).performScrollTo().performClick()
        slider.assertDoesNotExist()
        node(R.string.key_haptic_double).assertDoesNotExist()
        compose.runOnIdle { generation.intValue++ }
        node(R.string.key_vibration_title).performScrollTo().performClick()
        compose.onNodeWithText("25.4%").assertExists()
        node(R.string.key_haptic_double).performScrollTo().assertIsSelected()
    }

    @Test fun sound_switch_collapses_options_and_restores_the_last_choice_after_recreation() {
        val generation = mutableIntStateOf(0)
        prefs.edit().putString(PREF_KEY_SOUND, "off").commit()
        compose.setContent {
            AegisTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    key(generation.intValue) { KeySoundCard() }
                }
            }
        }
        node(R.string.key_sound_off).assertDoesNotExist()
        node(R.string.key_sound_blue).assertDoesNotExist()
        node(R.string.key_sound_title).performClick()
        for (choice in KeySound.entries.filter { it != KeySound.OFF }) {
            node(choice.labelRes).performScrollTo().performClick()
            assertEquals(choice.value, prefs.getString(PREF_KEY_SOUND, null))
        }
        node(R.string.key_sound_title).performScrollTo().performClick()
        node(R.string.key_sound_cream).assertDoesNotExist()
        assertEquals("off", prefs.getString(PREF_KEY_SOUND, null))
        compose.runOnIdle { generation.intValue++ }
        node(R.string.key_sound_title).performClick()
        node(R.string.key_sound_cream).performScrollTo().assertIsSelected()
        assertEquals("cream", prefs.getString(PREF_KEY_SOUND, null))
    }

    @Test fun an_existing_sound_selection_survives_the_new_master_switch() {
        prefs.edit().putString(PREF_KEY_SOUND, "purple").commit()
        compose.setContent { AegisTheme { KeySoundCard() } }
        node(R.string.key_sound_purple).assertIsSelected()
        node(R.string.key_sound_title).performClick()
        node(R.string.key_sound_purple).assertDoesNotExist()
        node(R.string.key_sound_title).performClick()
        node(R.string.key_sound_purple).assertIsSelected()
        assertEquals("purple", prefs.getString(PREF_KEY_SOUND, null))
    }

    @Test fun fuzzy_rules_hide_when_off_and_keep_their_choices() {
        prefs.edit().putBoolean("fuzzy", false).commit()
        compose.setContent {
            AegisTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { FuzzySettingsCard() }
            }
        }
        node(R.string.fuzzy_rule_zh_title).assertDoesNotExist()
        node(R.string.fuzzy_master_title).performClick()
        node(R.string.fuzzy_rule_zh_title).performScrollTo().performClick()
        node(R.string.fuzzy_master_title).performScrollTo().performClick()
        node(R.string.fuzzy_rule_zh_title).assertDoesNotExist()
        node(R.string.fuzzy_master_title).performClick()
        node(R.string.fuzzy_rule_zh_title).assertExists()
        assertFalse(prefs.getBoolean(Fuzzy.prefKey("zh"), true))
    }
}
