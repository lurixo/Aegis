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

package com.aegis.ime

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.ime.BarFunction
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.SettingsPanelView
import com.aegis.ime.ime.SettingsShortcut
import com.aegis.ime.ui.AboutActivity
import com.aegis.ime.ui.BackupActivity
import com.aegis.ime.ui.DictSettingsActivity
import com.aegis.ime.ui.InputSettingsActivity
import com.aegis.ime.ui.KeyboardSettingsActivity
import com.aegis.ime.ui.SetupActivity
import com.aegis.ime.ui.UserDictActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class SettingsPanelLaunchTest {

    private val app = RuntimeEnvironment.getApplication()
    private val services = ArrayList<AegisInputMethodService>()

    @After fun stop() {
        services.forEach { it.onDestroy() }
        services.clear()
        shadowOf(Looper.getMainLooper()).idle()
        app.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class Started(val service: AegisInputMethodService, val view: InputView, val controller: KeyboardController)

    private fun start(): Started {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).create().get()
        services += service
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && Thread.getAllStackTraces().keys.any { it.name == "aegis-dict-load" && it.isAlive }) Thread.yield()
        shadowOf(Looper.getMainLooper()).idle()
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 7
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Started(service, view, field(service, "controller"))
    }

    private fun <T> field(service: AegisInputMethodService, name: String): T =
        service.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(service) as T
        }

    private fun launchedInOrder(): List<Intent> {
        val shadow = shadowOf(app)
        val out = ArrayList<Intent>()
        while (true) out.add(0, shadow.nextStartedActivity ?: break)
        return out
    }

    private fun assertFreshHome(intent: Intent) {
        assertEquals(SetupActivity::class.java.name, intent.component?.className)
        val fresh = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals("the settings home starts a fresh task", fresh, intent.flags and fresh)
    }

    private fun openPanel(s: Started): SettingsPanelView {
        s.controller.onBarFunction(BarFunction.BRAND)
        val panel = field<SettingsPanelView?>(s.service, "settingsPanelView")
        assertNotNull(panel)
        assertTrue(s.view.isPanelShowing(panel))
        return panel!!
    }

    @Test fun the_brand_icon_opens_the_settings_panel_without_leaving_the_editor() {
        val s = start()
        launchedInOrder()
        openPanel(s)
        assertEquals("SETTINGS", s.service.transientStateForTest().panel)
        assertTrue("opening the panel launches nothing", launchedInOrder().isEmpty())
    }

    @Test fun the_aegis_card_opens_only_the_settings_home_in_a_fresh_task() {
        val s = start()
        val panel = openPanel(s)
        launchedInOrder()
        panel.cardViewForTest(SettingsShortcut.HOME).performClick()
        val launched = launchedInOrder()
        assertEquals(1, launched.size)
        assertFreshHome(launched[0])
    }

    @Test fun every_page_card_opens_its_page_over_a_fresh_settings_home() {
        val s = start()
        val panel = openPanel(s)
        val pages = mapOf(
            SettingsShortcut.INPUT to InputSettingsActivity::class.java,
            SettingsShortcut.KEYBOARD to KeyboardSettingsActivity::class.java,
            SettingsShortcut.DICTS to DictSettingsActivity::class.java,
            SettingsShortcut.USER_DICT to UserDictActivity::class.java,
            SettingsShortcut.BACKUP to BackupActivity::class.java,
            SettingsShortcut.ABOUT to AboutActivity::class.java,
        )
        assertEquals(SettingsShortcut.entries - SettingsShortcut.HOME, pages.keys.toList())
        launchedInOrder()
        for ((shortcut, page) in pages) {
            panel.cardViewForTest(shortcut).performClick()
            val launched = launchedInOrder()
            assertEquals("$shortcut opens the home and its page", 2, launched.size)
            assertFreshHome(launched[0])
            assertEquals(page.name, launched[1].component?.className)
            assertEquals(
                "$shortcut stacks its page in the home's task",
                0,
                launched[1].flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }
}
