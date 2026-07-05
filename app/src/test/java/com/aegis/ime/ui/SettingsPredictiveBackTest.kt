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

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import com.aegis.ime.ui.theme.SettingsMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * SETTINGS-SIDE PREDICTIVE BACK. Predictive back is done ONLY on the app (settings) side: under
 * gesture navigation an edge-swipe on a sub-page makes the current page follow the finger out while the
 * previous page peeks in. nav-compose 2.9.8's NavHost wires that PredictiveBackHandler + SeekableTransitionState
 * itself and seeks the pop transitions the app supplies ([SettingsMotion.backEnter]/[backExit]) — so the
 * seekable peek is proven on the emulator (see the ticket evidence), and what IS unit-testable is the wiring:
 * that the NavHost is given those pop transitions, that reduced motion is detected and collapses navigation to
 * an instant cut (直达), and that navigation still settles on the right page with animations off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPredictiveBackTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun src(path: String) = File(path).readText()

    @Test fun animations_enabled_reflects_the_system_animator_scale() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue("animations on → navigation animates (and the peek can seek)", SettingsMotion.animationsEnabled(ctx))
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse("scale 0 → reduced motion (navigation goes direct)", SettingsMotion.animationsEnabled(ctx))
    }

    @Test fun nav_host_seeks_the_pop_transitions_and_goes_direct_under_reduced_motion() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        // The seekable predictive-back peek rides the pop transitions the NavHost is handed — these must stay
        // wired, and they must be the mirrored shared-axis pop pair a refactor could silently drop.
        assertTrue("NavHost must supply popEnterTransition", setup.contains("popEnterTransition ="))
        assertTrue("NavHost must supply popExitTransition", setup.contains("popExitTransition ="))
        assertTrue("pop enter is the shared-axis back-enter", setup.contains("SettingsMotion.backEnter(this)"))
        assertTrue("pop exit is the shared-axis back-exit", setup.contains("SettingsMotion.backExit(this)"))
        // Reduced motion 直达: the graph consults animationsEnabled and swaps every transition to None when off.
        assertTrue("the graph must consult reduced motion", setup.contains("SettingsMotion.animationsEnabled("))
        assertTrue("reduced motion → enter goes direct", setup.contains("EnterTransition.None"))
        assertTrue("reduced motion → exit goes direct", setup.contains("ExitTransition.None"))
    }
}

/**
 * Reduced motion is set BEFORE the compose rule launches [SetupActivity], so its NavHost is composed with
 * animate=false from the first frame — the settings navigation goes straight to the destination (直达). This
 * proves the reduced-motion path does not stall: forward/back navigation still settles on the right page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsReducedMotionNavTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    init {
        // Set before the @get:Rule launches the activity, so onCreate's first composition already reads it.
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    @Test fun navigation_settles_on_the_target_page_and_back_home_with_reduced_motion() {
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.fuzzy_card_title)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_summary)).assertIsDisplayed()
    }
}
