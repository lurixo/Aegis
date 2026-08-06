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
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.aegis.ime.R
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearnEdit
import org.junit.After
import org.junit.Before
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
import org.robolectric.annotation.GraphicsMode

private fun ctxString(id: Int) = RuntimeEnvironment.getApplication().getString(id)

private fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.assertSetupStepActionLayout(
    expectCenteredInset: Boolean = false,
) {
    onNodeWithText(ctxString(R.string.setup_steps_title)).performScrollTo().assertExists()

    val pageHorizontalPadding = 24f
    val cardContentPadding = 16f
    val buttonHorizontalContentPadding = 24f
    val rootBounds = onRoot().getUnclippedBoundsInRoot()
    val cardBounds = onNodeWithTag("setup_steps_card").getUnclippedBoundsInRoot()
    val actionsBounds = onNodeWithTag("setup_step_actions").getUnclippedBoundsInRoot()
    val enableButtonBounds = onNodeWithTag("setup_enable_action")
        .getUnclippedBoundsInRoot()
    val switchButtonBounds = onNodeWithTag("setup_switch_action")
        .getUnclippedBoundsInRoot()
    val enableLabelBounds = onNodeWithTag("setup_enable_label_block", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
    val switchLabelBounds = onNodeWithTag("setup_switch_label_block", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    assertEquals(
        "setup steps card should fill the page content width",
        rootBounds.left.value + pageHorizontalPadding,
        cardBounds.left.value,
        1f,
    )
    assertEquals(
        "setup steps card should fill the page content width",
        rootBounds.right.value - pageHorizontalPadding,
        cardBounds.right.value,
        1f,
    )
    assertEquals(
        "setup actions should fill the padded card width",
        cardBounds.left.value + cardContentPadding,
        actionsBounds.left.value,
        1f,
    )
    assertEquals(
        "setup actions should fill the padded card width",
        cardBounds.right.value - cardContentPadding,
        actionsBounds.right.value,
        1f,
    )
    assertEquals(
        "enable setup button should fill the action width",
        actionsBounds.left.value,
        enableButtonBounds.left.value,
        0.5f,
    )
    assertEquals(
        "enable setup button should fill the action width",
        actionsBounds.right.value,
        enableButtonBounds.right.value,
        0.5f,
    )
    assertEquals(
        "switch setup button should fill the action width",
        actionsBounds.left.value,
        switchButtonBounds.left.value,
        0.5f,
    )
    assertEquals(
        "switch setup button should fill the action width",
        actionsBounds.right.value,
        switchButtonBounds.right.value,
        0.5f,
    )

    assertEquals(
        "setup label blocks should share the same left edge",
        enableLabelBounds.left.value,
        switchLabelBounds.left.value,
        0.5f,
    )
    assertEquals(
        "setup label blocks should share the same width",
        enableLabelBounds.right.value - enableLabelBounds.left.value,
        switchLabelBounds.right.value - switchLabelBounds.left.value,
        0.5f,
    )
    assertEquals(
        "enable setup label block should be centered in the button",
        (enableButtonBounds.left.value + enableButtonBounds.right.value) / 2f,
        (enableLabelBounds.left.value + enableLabelBounds.right.value) / 2f,
        1f,
    )
    assertEquals(
        "switch setup label block should be centered in the button",
        (switchButtonBounds.left.value + switchButtonBounds.right.value) / 2f,
        (switchLabelBounds.left.value + switchLabelBounds.right.value) / 2f,
        1f,
    )
    if (expectCenteredInset) {
        assertTrue(
            "setup label block should be centered beyond the button's horizontal content padding",
            enableLabelBounds.left.value > enableButtonBounds.left.value + buttonHorizontalContentPadding,
        )
        assertTrue(
            "setup label block should be centered beyond the button's horizontal content padding",
            enableLabelBounds.right.value < enableButtonBounds.right.value - buttonHorizontalContentPadding,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<InputSettingsActivity>()

    @Test fun holds_keyboard_mode_fuzzy_and_associations_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.layout_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.fuzzy_card_title)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.association_title)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }

    @Test fun back_affordance_is_the_drawn_chevron_not_a_thin_text_glyph() {
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).assertExists()
        compose.onAllNodesWithText("‹").assertCountEquals(0)
    }

    @Test fun the_auto_learning_switch_starts_on_and_writes_the_preference_both_ways() {
        val prefs = compose.activity.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        compose.onNodeWithText(ctxString(R.string.auto_learn_title)).performScrollTo().assertExists()
        compose.onNodeWithTag("auto_learn_switch").performScrollTo().assertIsOn()

        compose.onNodeWithTag("auto_learn_switch").performClick()
        compose.waitForIdle()
        assertFalse("turning it off is written down", prefs.getBoolean(PREF_AUTO_LEARN_ON, AUTO_LEARN_DEFAULT_ON))
        compose.onNodeWithTag("auto_learn_switch").assertIsOff()

        compose.onNodeWithTag("auto_learn_switch").performClick()
        compose.waitForIdle()
        assertTrue("turning it back on is written down", prefs.getBoolean(PREF_AUTO_LEARN_ON, false))
    }

    @Test fun the_clear_button_is_dead_while_there_is_nothing_learned() {
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
        val zero = RuntimeEnvironment.getApplication().getString(R.string.user_dict_auto_count_format, 0)
        compose.onNodeWithText(zero).performScrollTo().assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputSettingsAutoLearnClearTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val learn = java.io.File(ctx.filesDir, "userlearn.txt")
    private var scenario: ActivityScenario<InputSettingsActivity>? = null

    @Before fun reset() {
        UserDictHot.host = null
        learn.delete()
    }

    @After fun cleanup() {
        scenario?.close()
        learn.delete()
    }

    @Test fun the_clear_button_lives_while_only_the_next_word_data_is_left() {
        learn.writeText("aegis-userlearn 1\nC\t\u4f60\t\u597d\t3.0\t1700000000000\n")
        assertTrue("there is no glued word to count", UserLearnEdit.list(learn).isEmpty())

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()
        compose.waitForIdle()

        assertTrue("the next word data is gone", learn.readLines().none { it.startsWith("C\t") })
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
    }
}


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DictSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<DictSettingsActivity>()

    @Test fun holds_the_dict_pack_and_model_cards_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.dict_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.gram_card_title)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictActivityTest {
    @get:Rule val compose = createAndroidComposeRule<UserDictActivity>()

    @Test fun holds_the_search_field_and_tools_and_back_finishes() {
        compose.onNodeWithTag("user_dict_search").assertExists()
        compose.onNodeWithText(ctxString(R.string.user_dict_export_button)).assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AboutActivityTest {
    @get:Rule val compose = createAndroidComposeRule<AboutActivity>()

    @Test fun holds_version_enable_steps_and_the_try_field() {
        compose.onNodeWithText(ctxString(R.string.app_version_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.setup_steps_title)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.setup_try_field_label)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.settings_backup_title)).assertDoesNotExist()
    }

    @Test fun setup_step_actions_fill_card_width_and_labels_use_centered_shared_block() =
        compose.assertSetupStepActionLayout()

    @Test fun opening_licenses_starts_the_licenses_activity() {
        compose.onNodeWithText(ctxString(R.string.settings_about_licenses_title)).performScrollTo().performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, started?.component?.className)
    }

    @Test fun back_arrow_finishes_the_activity() {
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class AboutActivityChineseTest {
    @get:Rule val compose = createAndroidComposeRule<AboutActivity>()

    @Test fun setup_step_actions_align_the_chinese_labels_in_a_centered_shared_block() =
        compose.assertSetupStepActionLayout(expectCenteredInset = true)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LicensesActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LicensesActivity>()

    @Test fun lists_components_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.license_wanxiang_name)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ctxString(R.string.license_androidx_name)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}
