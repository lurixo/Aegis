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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LicensesTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    private val licenseNameIds = listOf(
        R.string.license_wanxiang_name,
        R.string.license_octagram_name,
        R.string.license_opencc_name,
        R.string.license_emoji_name,
        R.string.license_androidx_name,
    )

    @Test fun third_party_licenses_doc_covers_every_component_and_carries_the_apache_full_text() {
        val doc = File("../THIRD_PARTY_LICENSES.md").readText()
        for (needle in listOf(
            "rime-wanxiang", "amzxyz", "CC BY 4.0", "creativecommons.org/licenses/by/4.0",
            "v16.0.1", "7db7c588",
            "aegis_{dict,t9,jianpin,lm}.bin", "aegis_lm.bin", "character-bigram",
            "RIME-LMDG", "OpenCC", "BYVoid", "Apache-2.0",
            "Unicode", "unicode.org/license",
            "AndroidX", "Compose", "Material 3", "Kotlin",
            "Apache License", "Version 2.0",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            "Limitation of Liability",
        )) {
            assertTrue("THIRD_PARTY_LICENSES.md must contain '$needle'", doc.contains(needle))
        }
    }

    @Test fun license_strings_exist_in_both_locales() {
        val en = File("src/main/res/values/strings.xml").readText()
        val zh = File("src/main/res/values-zh/strings.xml").readText()
        for (key in listOf(
            "settings_about_licenses_title", "settings_about_licenses_desc",
            "licenses_page_title", "licenses_intro", "licenses_modified", "licenses_footer",
            "license_wanxiang_name", "license_wanxiang_note",
            "license_octagram_name", "license_octagram_note",
            "license_opencc_name", "license_opencc_note",
            "license_emoji_name", "license_emoji_note",
            "license_androidx_name", "license_androidx_note",
        )) {
            assertTrue("EN strings.xml must define '$key'", en.contains("name=\"$key\""))
            assertTrue("ZH strings.xml must define '$key'", zh.contains("name=\"$key\""))
        }
    }

    @Test fun opening_licenses_from_about_lists_every_component_with_a_modified_mark_then_returns() {
        compose.onNodeWithText(s(R.string.settings_group_about_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.settings_about_licenses_title)).performScrollTo().performClick()
        compose.waitForIdle()
        for (id in licenseNameIds) {
            compose.onNodeWithText(s(id)).performScrollTo().assertIsDisplayed()
        }
        assertTrue(
            "a component must show the Modified mark",
            compose.onAllNodesWithText(s(R.string.licenses_modified), substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_steps_title)).assertExists()
    }

    @Test fun double_tapping_back_on_the_licenses_page_pops_only_one_level_to_about() {
        compose.onNodeWithText(s(R.string.settings_group_about_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.settings_about_licenses_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.license_wanxiang_name)).assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_steps_title)).assertExists()
    }
}
