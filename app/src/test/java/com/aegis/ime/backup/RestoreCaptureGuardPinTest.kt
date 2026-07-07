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

package com.aegis.ime.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestoreCaptureGuardPinTest {

    private val service = File("src/main/java/com/aegis/ime/AegisInputMethodService.kt").readText()

    private fun body(name: String): String {
        val start = service.indexOf("private fun $name(")
        assertTrue("service must define $name()", start >= 0)
        val next = service.indexOf("\n    private fun ", start + 1)
        return service.substring(start, if (next >= 0) next else service.length)
    }

    private fun assertGuardsBeforeRecording(name: String, recordCall: String) {
        val b = body(name)
        val guard = b.indexOf("\n        if (LiveUserData.restoreInProgress) return")
        val record = b.indexOf(recordCall)
        assertTrue("$name must short-circuit (at the top of its body) while a restore is in progress", guard >= 0)
        assertTrue("$name must reach $recordCall", record >= 0)
        assertTrue("$name must check the restore guard BEFORE it records a clip", guard < record)
    }

    @Test fun the_active_capture_path_stands_down_during_a_restore() {
        assertGuardsBeforeRecording("captureClip", "clipboardStore.record(")
    }

    @Test fun the_passive_system_clip_listener_stands_down_during_a_restore() {
        assertGuardsBeforeRecording("onSystemClipChanged", "recordTextClip(")
    }
}
