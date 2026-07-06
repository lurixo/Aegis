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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPredictiveBackTest {

    private fun src(path: String) = File(path).readText()

    private fun code(path: String): String {
        val noBlock = src(path).replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return noBlock.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private val subActivities = listOf(
        "InputSettingsActivity", "DictSettingsActivity", "UserDictActivity", "AboutActivity", "LicensesActivity",
    )

    @Test fun settings_navigation_is_multi_activity_not_navhost() {
        val setup = code("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        assertFalse("the settings home must not host a NavHost anymore", setup.contains("NavHost("))
        assertFalse("no NavController", setup.contains("rememberNavController"))
        assertTrue("the home opens groups by starting their Activities", setup.contains("startActivity(Intent("))
        assertFalse(setup.contains("PredictiveBackHandler"))
        assertFalse(setup.contains("SeekableTransitionState"))
        assertFalse(setup.contains("popBackStack"))
    }

    @Test fun every_sub_activity_is_declared_and_the_predictive_back_optin_is_kept() {
        val manifest = src("src/main/AndroidManifest.xml")
        for (a in subActivities) assertTrue("$a must be declared in the manifest", manifest.contains(".ui.$a"))
        assertTrue(
            "predictive-back opt-in must stay on",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\""),
        )
    }

    @Test fun sub_activities_use_a_transition_capable_theme_not_the_no_animation_one() {
        val manifest = src("src/main/AndroidManifest.xml")
        for (a in subActivities) {
            val block = Regex(""".ui.$a"[\s\S]{0,400}?/>""").find(manifest)?.value ?: ""
            assertTrue("$a must use @style/Theme.Aegis", block.contains("@style/Theme.Aegis\""))
        }
    }

    @Test fun sub_activities_do_not_intercept_back_on_the_normal_path() {
        for (a in subActivities) {
            val text = code("src/main/java/com/aegis/ime/ui/$a.kt")
            assertFalse("$a must not add a Compose BackHandler", text.contains("BackHandler"))
            assertFalse("$a must not register an OnBackPressedCallback", text.contains("OnBackPressedCallback"))
            assertFalse(
                "$a must not add a callback to the back dispatcher",
                text.contains("onBackPressedDispatcher.addCallback"),
            )
            assertTrue("$a's back must be a plain finish()", text.contains("finish()"))
        }
    }
}
