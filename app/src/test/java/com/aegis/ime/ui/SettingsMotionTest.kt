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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * §2 (settings motion). The first-run hint card is wrapped in an [AnimatedVisibility] so it
 * expand/shrink + fades on the MD3 state-change token (spec: 展开折叠), and the NavHost carries the shared-axis
 * transitions. These prove the motion wrappers do not change the underlying behaviour: the hint still shows on
 * first run and dismisses for good on ack, and forward/back navigation still settles on the right page through
 * the transitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    @Test fun first_run_hint_reveals_then_collapses_away_on_ack() {
        // Fresh install → the AnimatedVisibility hint is shown.
        compose.onNodeWithText(s(R.string.setup_first_run_title)).assertIsDisplayed()
        // Dismiss → the collapse (shrink + fade) runs and the node is gone once it settles.
        compose.onNodeWithText(s(R.string.setup_first_run_ack)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_first_run_title)).assertDoesNotExist()
    }

    @Test fun shared_axis_navigation_settles_on_the_target_page_and_back_home() {
        // Forward through the shared-axis X transition lands on the input page…
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.fuzzy_card_title)).assertExists()
        // …and the mirrored back transition returns home.
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_summary)).assertIsDisplayed()
    }
}
