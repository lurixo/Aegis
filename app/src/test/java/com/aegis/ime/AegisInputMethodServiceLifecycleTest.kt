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

import com.aegis.ime.ime.BackspaceGesture
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class AegisInputMethodServiceLifecycleTest {

    private enum class AnchorEffect { RESYNC, HOST_NEUTRAL, SELECTION_OWNED }

    private val editActionAnchorEffect: Map<EditAction, AnchorEffect> = mapOf(
        EditAction.UNDO to AnchorEffect.SELECTION_OWNED,
        EditAction.DELETE to AnchorEffect.RESYNC,
        EditAction.TAB to AnchorEffect.RESYNC,
        EditAction.FORWARD_DELETE to AnchorEffect.RESYNC,
        EditAction.CUT to AnchorEffect.RESYNC,
        EditAction.SELECT_ALL to AnchorEffect.RESYNC,
        EditAction.PASTE to AnchorEffect.RESYNC,
        EditAction.COPY to AnchorEffect.HOST_NEUTRAL,
        EditAction.UP to AnchorEffect.SELECTION_OWNED,
        EditAction.DOWN to AnchorEffect.SELECTION_OWNED,
        EditAction.LEFT to AnchorEffect.SELECTION_OWNED,
        EditAction.RIGHT to AnchorEffect.SELECTION_OWNED,
        EditAction.HOME to AnchorEffect.SELECTION_OWNED,
        EditAction.END to AnchorEffect.SELECTION_OWNED,
        EditAction.START_SELECT to AnchorEffect.SELECTION_OWNED,
        EditAction.BACK to AnchorEffect.SELECTION_OWNED,
    )

    @Test fun every_edit_action_declares_its_selection_anchor_effect() {
        assertEquals(
            "a new EditAction must declare whether it invalidates the selection anchor",
            EditAction.entries.toSet(),
            editActionAnchorEffect.keys,
        )
    }

    @Test fun the_panel_input_surface_stays_within_the_classified_paths() {
        assertEquals(
            "a new edit panel callback must be classified in the selection anchor inventory",
            setOf("onAction", "onBackspaceSwipe", "backspaceSwipeAvailable"),
            callbackNames(EditPanelView::class.java),
        )
        assertEquals(
            "a new backspace gesture outcome must be classified in the selection anchor inventory",
            setOf("onRepeat", "onSwipe", "canSwipe"),
            callbackNames(BackspaceGesture::class.java),
        )
    }

    private fun callbackNames(type: Class<*>): Set<String> = type.declaredMethods
        .filter { method ->
            method.name.startsWith("set") &&
                method.parameterTypes.size == 1 &&
                kotlin.Function::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        .map { it.name.removePrefix("set").replaceFirstChar { first -> first.lowercase() } }
        .toSet()

}
