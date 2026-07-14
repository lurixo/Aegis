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

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    private data class Group(val titleRes: Int, val activity: Class<*>, val markerRes: Int)
    private val groups = listOf(
        Group(R.string.settings_group_input_title, InputSettingsActivity::class.java, R.string.layout_card_title),
        Group(R.string.settings_group_dicts_title, DictSettingsActivity::class.java, R.string.dict_card_title),
        Group(R.string.settings_group_userdict_title, UserDictActivity::class.java, R.string.user_dict_export_button),
        Group(R.string.settings_backup_title, BackupActivity::class.java, R.string.backup_export_button),
        Group(R.string.settings_group_about_title, AboutActivity::class.java, R.string.setup_steps_title),
    )

    @Test fun home_shows_all_five_group_entries_and_none_of_the_moved_cards() {
        for (g in groups) compose.onNodeWithText(s(g.titleRes)).assertExists()
        assertEquals(
            listOf(
                SettingsRoutes.INPUT,
                SettingsRoutes.DICTS,
                SettingsRoutes.USER_DICT,
                SettingsRoutes.BACKUP,
                SettingsRoutes.ABOUT,
            ),
            SettingsRoutes.GROUPS,
        )
        for (g in groups) compose.onNodeWithText(s(g.markerRes)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.fuzzy_card_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.gram_card_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.association_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.app_version_card_title)).assertDoesNotExist()
    }

    @Test fun tapping_each_group_entry_starts_that_groups_activity() {
        for (g in groups) {
            compose.onNodeWithText(s(g.titleRes)).performScrollTo().performClick()
            compose.waitForIdle()
            val started = shadowOf(compose.activity).nextStartedActivity
            assertEquals(
                "tapping '${s(g.titleRes)}' must start ${g.activity.simpleName}",
                g.activity.name,
                started?.component?.className,
            )
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test fun a_rapid_second_tap_on_a_group_card_does_not_stack_a_second_activity() {
        val g = groups.first()
        val card = compose.onNodeWithText(s(g.titleRes))
        card.performScrollTo().performClick()
        card.performClick()
        compose.waitForIdle()
        val shadow = shadowOf(compose.activity)
        assertEquals(g.activity.name, shadow.nextStartedActivity?.component?.className)
        assertNull("a rapid second tap must not start a second Activity", shadow.nextStartedActivity)
    }

    @Test fun activityForGroup_maps_every_home_group_to_its_activity() {
        assertEquals(InputSettingsActivity::class.java, activityForGroup(SettingsRoutes.INPUT))
        assertEquals(DictSettingsActivity::class.java, activityForGroup(SettingsRoutes.DICTS))
        assertEquals(UserDictActivity::class.java, activityForGroup(SettingsRoutes.USER_DICT))
        assertEquals(BackupActivity::class.java, activityForGroup(SettingsRoutes.BACKUP))
        assertEquals(AboutActivity::class.java, activityForGroup(SettingsRoutes.ABOUT))
        assertNull(activityForGroup("nope"))
    }
}
