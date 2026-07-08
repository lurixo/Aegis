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

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import com.aegis.ime.R
import com.aegis.ime.ui.theme.aegisColorScheme
import com.aegis.ime.ui.theme.settingsBackgroundArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
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
        "BackupActivity",
    )
    private val settingsActivities = listOf("SetupActivity") + subActivities

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

    @Test fun settings_theme_predeclares_predictive_back_safe_window_and_system_bars() {
        val themes = src("src/main/res/values/themes.xml")
        val nightThemes = src("src/main/res/values-night/themes.xml")
        assertTrue(
            "light settings theme must use a Material NoActionBar parent without DeviceDefault overlays",
            themes.contains("<style name=\"Theme.Aegis\" parent=\"android:Theme.Material.Light.NoActionBar\">"),
        )
        assertTrue(
            "dark settings theme must use a Material NoActionBar parent without DeviceDefault overlays",
            nightThemes.contains("<style name=\"Theme.Aegis\" parent=\"android:Theme.Material.NoActionBar\">"),
        )
        assertFalse("settings theme must not inherit DeviceDefault decorations", themes.contains("DeviceDefault"))
        assertFalse("dark settings theme must not inherit DeviceDefault decorations", nightThemes.contains("DeviceDefault"))
        assertTrue(
            "settings theme must clear platform content overlays before predictive-back animation",
            themes.contains("<item name=\"android:windowContentOverlay\">@null</item>"),
        )
        assertTrue(
            "dark settings theme must clear platform content overlays before predictive-back animation",
            nightThemes.contains("<item name=\"android:windowContentOverlay\">@null</item>"),
        )
        assertTrue(
            "settings theme must align platform colorBackground with the settings surface",
            themes.contains("<item name=\"android:colorBackground\">@color/settings_window_background</item>"),
        )
        assertTrue(
            "dark settings theme must align platform colorBackground with the settings surface",
            nightThemes.contains("<item name=\"android:colorBackground\">@color/settings_window_background</item>"),
        )
        assertTrue(
            "settings theme must draw system bar backgrounds from app-owned bars",
            themes.contains("<item name=\"android:windowDrawsSystemBarBackgrounds\">true</item>"),
        )
        assertTrue(
            "settings theme must make the status bar transparent before Activity onCreate",
            themes.contains("<item name=\"android:statusBarColor\">@android:color/transparent</item>"),
        )
        assertTrue(
            "settings theme must make the navigation bar transparent before Activity onCreate",
            themes.contains("<item name=\"android:navigationBarColor\">@android:color/transparent</item>"),
        )
        assertTrue(
            "settings theme must use day/night system-bar icon contrast resources",
            themes.contains("<item name=\"android:windowLightStatusBar\">@bool/settings_light_system_bars</item>"),
        )
        assertTrue(
            "settings theme must keep navigation-bar icon contrast aligned with status-bar contrast",
            themes.contains("<item name=\"android:windowLightNavigationBar\">@bool/settings_light_system_bars</item>"),
        )
        assertTrue(
            "settings theme must not let platform contrast scrims become a predictive-back status strip",
            themes.contains("<item name=\"android:enforceStatusBarContrast\">false</item>"),
        )
        assertTrue(
            "settings theme must not let platform contrast scrims become a predictive-back navigation strip",
            themes.contains("<item name=\"android:enforceNavigationBarContrast\">false</item>"),
        )
        assertTrue(
            "settings theme must start with the same light surface family Compose paints",
            themes.contains("<item name=\"android:windowBackground\">@color/settings_window_background</item>"),
        )
        assertTrue(
            "dark settings theme must start with the same dark surface family Compose paints",
            nightThemes.contains("<item name=\"android:windowBackground\">@color/settings_window_background</item>"),
        )
        assertTrue(
            "light settings system bars must use dark icons before Activity code runs",
            src("src/main/res/values/bools.xml").contains("<bool name=\"settings_light_system_bars\">true</bool>"),
        )
        assertTrue(
            "dark settings system bars must use light icons before Activity code runs",
            src("src/main/res/values-night/bools.xml")
                .contains("<bool name=\"settings_light_system_bars\">false</bool>"),
        )
        assertTrue(
            "light theme window background must match the validated settings surface",
            src("src/main/res/values/colors.xml").contains("<color name=\"settings_window_background\">#FAF8FF</color>"),
        )
        assertTrue(
            "dark theme window background must be dark before runtime dynamic color sync",
            src("src/main/res/values-night/colors.xml")
                .contains("<color name=\"settings_window_background\">#1C1B1F</color>"),
        )
    }

    @Test
    @Config(qualifiers = "notnight")
    fun settings_material_background_matches_window_resource_in_light_context() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            context.getColor(R.color.settings_window_background),
            aegisColorScheme(context, darkTheme = false).background.toArgb(),
        )
    }

    @Test
    @Config(qualifiers = "night")
    fun settings_material_background_matches_window_resource_in_dark_context() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            context.getColor(R.color.settings_window_background),
            aegisColorScheme(context, darkTheme = true).background.toArgb(),
        )
    }

    @Test fun settings_scroll_background_covers_status_bar_while_content_stays_below_it() {
        val body = code("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
            .substringAfter("internal fun Modifier.settingsScrollInsets")
            .substringBefore("@Composable")
        val bottomOutside = body.indexOf(
            ".windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))",
        )
        val background = body.indexOf(".background(MaterialTheme.colorScheme.background)")
        val topOutside = body.indexOf(".windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))")
        val scroll = body.indexOf(".verticalScroll(scrollState)")

        assertTrue("bottom/horizontal insets must stay outside the scroll for IME resize", bottomOutside >= 0)
        assertTrue("settings background must cover the status-bar area before top padding", background >= 0)
        assertTrue("top inset must be applied before verticalScroll so content stays below the status bar", topOutside >= 0)
        assertTrue("settings pages must still use verticalScroll", scroll >= 0)
        assertTrue("background must be the outermost settings scroll paint", background < bottomOutside)
        assertTrue("bottom/horizontal padding must stay outside the top padding and scroll", bottomOutside < topOutside)
        assertTrue("top padding must be outside verticalScroll so scrolled content is clipped below the status bar", topOutside < scroll)
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

    @Test fun settings_chrome_uses_a_full_size_compose_surface_and_syncs_runtime_background() {
        val chrome = code("src/main/java/com/aegis/ime/ui/SettingsActivityChrome.kt")
        assertTrue(
            "the Compose root surface must paint the same Material settings background",
            chrome.contains("color = MaterialTheme.colorScheme.background"),
        )
        assertTrue("the settings surface must fill the whole Activity", chrome.contains("Modifier.fillMaxSize()"))
        assertTrue("settings chrome must synchronize the Window background", chrome.contains("setBackgroundDrawable"))
        assertTrue("settings chrome must synchronize the decorView background", chrome.contains("decorView.setBackgroundColor"))
        assertTrue("settings chrome must sync when the Material background recomposes", chrome.contains("SideEffect"))
        assertTrue("settings chrome must sync the Material background color", chrome.contains("MaterialTheme.colorScheme.background.toArgb()"))
        assertTrue("settings chrome sync must operate on the Activity Window", chrome.contains("android.view.Window"))
        assertFalse("settings chrome must not repaint rootView at runtime", chrome.contains("rootView.setBackgroundColor"))

        for (a in settingsActivities) {
            val text = code("src/main/java/com/aegis/ime/ui/$a.kt")
            assertTrue(
                "$a must use SettingsActivityChrome for the full-size settings surface",
                text.contains("SettingsActivityChrome {"),
            )
        }
    }

    @Test fun bootstrap_syncs_window_and_decor_background_to_settings_resource() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.bootstrapSettingsEdgeToEdge()
        assertEquals(
            settingsBackgroundArgb(activity),
            (activity.window.decorView.background as ColorDrawable).color,
        )
    }

    @Test fun every_settings_activity_bootstraps_edge_to_edge_before_setcontent_and_on_resume() {
        val chrome = code("src/main/java/com/aegis/ime/ui/SettingsActivityChrome.kt")
        assertTrue(
            "settings bootstrap must be a shared ComponentActivity extension",
            chrome.contains("fun ComponentActivity.bootstrapSettingsEdgeToEdge()"),
        )
        assertTrue(
            "settings bootstrap must call edge-to-edge with explicit matching system-bar styles",
            chrome.contains("enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)"),
        )
        assertTrue(
            "light settings bars must be transparent over the Material settings surface",
            chrome.contains("SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)"),
        )
        assertTrue(
            "dark settings bars must be transparent over the Material settings surface",
            chrome.contains("SystemBarStyle.dark(Color.TRANSPARENT)"),
        )
        val firstSync = chrome.indexOf("window.syncSettingsBackground(settingsBackgroundArgb(this, darkTheme))")
        val edgeToEdge = chrome.indexOf("enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)")
        val secondSync = chrome.indexOf("window.syncSettingsBackground(settingsBackgroundArgb(this, darkTheme))", edgeToEdge)
        assertTrue("settings bootstrap must sync the window/decor background before edge-to-edge", firstSync >= 0)
        assertTrue("settings bootstrap must call edge-to-edge", edgeToEdge >= 0)
        assertTrue("settings bootstrap must sync the window/decor background after edge-to-edge", secondSync > edgeToEdge)

        for (a in settingsActivities) {
            val text = src("src/main/java/com/aegis/ime/ui/$a.kt")
            val onCreate = text.substringAfter("override fun onCreate")
            val bootstrap = onCreate.indexOf("bootstrapSettingsEdgeToEdge()")
            val setContent = onCreate.indexOf("setContent {")
            assertTrue("$a must call the shared bootstrap in onCreate", bootstrap >= 0)
            assertTrue("$a must install Compose content in onCreate", setContent >= 0)
            assertTrue("$a must bootstrap the settings window before setContent", bootstrap < setContent)
            assertFalse("$a must not bypass the shared settings bootstrap", onCreate.contains("enableEdgeToEdge("))

            val onResumeStart = text.indexOf("override fun onResume")
            assertTrue("$a must refresh the settings window on resume", onResumeStart >= 0)
            val onResume = text.substring(onResumeStart)
            val resumeSuper = onResume.indexOf("super.onResume()")
            val resumeBootstrap = onResume.indexOf("bootstrapSettingsEdgeToEdge()")
            assertTrue("$a must call super.onResume()", resumeSuper >= 0)
            assertTrue("$a must refresh the settings window after super.onResume()", resumeBootstrap > resumeSuper)
            assertFalse("$a must not bypass the shared settings bootstrap on resume", onResume.contains("enableEdgeToEdge("))
        }
    }
}
