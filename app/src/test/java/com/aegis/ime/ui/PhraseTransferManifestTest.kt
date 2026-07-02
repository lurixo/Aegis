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

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseTransferManifestTest {

    @Test fun phraseTransferActivity_is_hidden_task_bridge_that_survives_activity_result_callbacks() {
        val activity = phraseTransferActivity()

        assertAndroidAttrEquals("false", activity, "exported")
        assertAndroidAttrEquals("true", activity, "excludeFromRecents")
        assertAndroidAttrEquals("true", activity, "autoRemoveFromRecents")
        assertAndroidAttrEquals("true", activity, "finishOnTaskLaunch")
        assertAndroidAttrEquals(
            "PhraseTransferActivity must survive SAF picker handoff so export/import callbacks can run",
            "false",
            activity,
            "noHistory",
        )
        assertAndroidAttrEquals(
            "empty taskAffinity keeps FLAG_ACTIVITY_NEW_TASK from surfacing the main Aegis app task",
            "",
            activity,
            "taskAffinity",
        )
        assertAndroidAttrEquals("@style/Theme.Aegis.Transparent", activity, "theme")
    }

    @Test fun phraseTransferTheme_disables_preview_and_bridge_animations() {
        val theme = style("Theme.Aegis.Transparent")

        assertStyleItem("@android:color/transparent", theme, "android:windowBackground")
        assertStyleItem("true", theme, "android:windowIsTranslucent")
        assertStyleItem("false", theme, "android:backgroundDimEnabled")
        assertStyleItem("true", theme, "android:windowDisablePreview")
        assertStyleItem("@style/Animation.Aegis.None", theme, "android:windowAnimationStyle")

        val animation = style("Animation.Aegis.None")
        for (item in ZERO_ANIMATION_ITEMS) {
            assertStyleItem("@anim/aegis_no_animation", animation, item)
        }
        assertTrue(File("src/main/res/anim/aegis_no_animation.xml").isFile)
    }

    @Test fun phraseTransferLaunchIntent_uses_hidden_bridge_flags() {
        val intent = PhraseTransferActivity.launchIntent(RuntimeEnvironment.getApplication(), export = true, merge = false)
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        assertEquals(expectedFlags, PhraseTransferActivity.LAUNCH_FLAGS)
        assertEquals(expectedFlags, intent.flags)
        assertEquals(PhraseTransferActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(PhraseTransferActivity.EXTRA_EXPORT, false))
        assertFalse(intent.getBooleanExtra(PhraseTransferActivity.EXTRA_IMPORT_MERGE, true))
    }

    private fun phraseTransferActivity(): Element {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            if (activity.androidAttr("name") == ".ui.PhraseTransferActivity") return activity
        }
        error("PhraseTransferActivity is missing from AndroidManifest.xml")
    }

    private fun style(name: String): Element {
        val themes = parseXml("src/main/res/values/themes.xml")
        val styles = themes.getElementsByTagName("style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as Element
            if (style.getAttribute("name") == name) return style
        }
        error("$name is missing from themes.xml")
    }

    private fun parseXml(path: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))

    private fun assertAndroidAttrEquals(expected: String, element: Element, name: String) {
        assertAndroidAttrEquals(expected, expected, element, name)
    }

    private fun assertAndroidAttrEquals(message: String, expected: String, element: Element, name: String) {
        assertTrue("$message: android:$name must be explicit", element.hasAttributeNS(ANDROID_NS, name))
        assertEquals(message, expected, element.androidAttr(name))
    }

    private fun assertStyleItem(expected: String, style: Element, name: String) {
        val items = style.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            if (item.getAttribute("name") == name) {
                assertEquals("style ${style.getAttribute("name")} item $name", expected, item.textContent)
                return
            }
        }
        error("style ${style.getAttribute("name")} is missing item $name")
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val ZERO_ANIMATION_ITEMS = listOf(
            "android:activityOpenEnterAnimation",
            "android:activityOpenExitAnimation",
            "android:activityCloseEnterAnimation",
            "android:activityCloseExitAnimation",
            "android:taskOpenEnterAnimation",
            "android:taskOpenExitAnimation",
            "android:taskCloseEnterAnimation",
            "android:taskCloseExitAnimation",
            "android:taskToFrontEnterAnimation",
            "android:taskToFrontExitAnimation",
            "android:taskToBackEnterAnimation",
            "android:taskToBackExitAnimation",
        )
    }
}
