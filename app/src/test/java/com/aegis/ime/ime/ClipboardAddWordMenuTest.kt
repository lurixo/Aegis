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

package com.aegis.ime.ime

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.user.ClipEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardAddWordMenuTest {
    private val context = RuntimeEnvironment.getApplication()
    private val hosts = ArrayList<ActivityController<Activity>>()
    private val longWord = "长".repeat(33)
    private val history = listOf("行好", "银行", "hello", "一", "银行2", longWord, "长".repeat(32))
    private val added = mutableListOf<String>()

    @After fun close() { hosts.forEach { it.pause().stop().destroy() } }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun panel(): ClipboardView = ClipboardView(context).apply {
        historyProvider = { history.map(ClipEntry::of) }
        onAddWord = { added += it }
        applyPalette(ImePalette.STATIC_LIGHT)
        refresh()
    }.also { view ->
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        hosts.add(host)
        host.get().setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1600)
    }

    private fun menuFor(view: ClipboardView, entry: String): List<TextView> {
        val label = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == entry }
        val row = generateSequence<View>(label) { it.parent as? View }.first { it.isLongClickable }
        row.performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        return descendants(view.getChildAt(1)).filterIsInstance<TextView>()
    }

    private val addWord get() = context.getString(R.string.copybar_add_word)

    @Test fun only_two_to_thirty_two_han_characters_offer_to_join_the_dictionary() {
        val view = panel()
        for (entry in history) {
            val offered = menuFor(view, entry).any { it.text.toString() == addWord }
            val expected = entry in setOf("行好", "银行", "长".repeat(32))
            assertEquals("\"$entry\" offers to join the dictionary", expected, offered)
            view.closeInnerLayer()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun the_menu_item_hands_the_word_to_the_add_flow_and_closes() {
        val view = panel()
        menuFor(view, "银行").single { it.text.toString() == addWord }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("银行"), added)
        assertFalse("the menu closes", view.hasInnerLayer())
        assertTrue(menuFor(view, "hello").none { it.text.toString() == addWord })
    }
}
