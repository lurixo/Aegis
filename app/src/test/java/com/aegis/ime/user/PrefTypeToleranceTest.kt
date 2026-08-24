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

package com.aegis.ime.user

import com.aegis.ime.AegisInputMethodService
import com.aegis.ime.ui.flagOr
import com.aegis.ime.ui.textOr
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefTypeToleranceTest {

    private val app = RuntimeEnvironment.getApplication()

    @Test fun a_wrong_typed_symbol_list_reads_as_empty_instead_of_throwing() {
        val prefs = app.getSharedPreferences("aegis", 0)
        prefs.edit().putInt("custom_symbols", 7).commit()
        assertEquals(emptyList<String>(), CustomSymbolStore(prefs).list())
    }

    @Test fun a_wrong_typed_history_flag_does_not_take_the_keyboard_down() {
        app.getSharedPreferences("aegis", 0).edit().putInt("clip_history", 7).commit()
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val enabled = service.javaClass.getDeclaredMethod("historyEnabled")
            .apply { isAccessible = true }
            .invoke(service)
        assertEquals(true, enabled)
    }

    @Test fun the_settings_read_helpers_survive_wrong_typed_values() {
        val prefs = app.getSharedPreferences("aegis-ui", 0)
        prefs.edit().putString("flag", "not a flag").putInt("text", 5).commit()
        assertEquals(true, prefs.flagOr("flag", true))
        assertEquals("fallback", prefs.textOr("text", "fallback"))
        prefs.edit().putBoolean("flag", false).putString("text", "kept").commit()
        assertEquals(false, prefs.flagOr("flag", true))
        assertEquals("kept", prefs.textOr("text", "fallback"))
    }
}
