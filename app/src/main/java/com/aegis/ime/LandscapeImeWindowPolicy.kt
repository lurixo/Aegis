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

import android.graphics.Rect
import android.inputmethodservice.InputMethodService

internal data class ImeWindowInsetsSpec(
    val contentTop: Int,
    val visibleTop: Int,
    val touchableInsets: Int,
    val touchableRegion: Rect?,
    val touchableExtra: Rect? = null,
)

internal object LandscapeImeWindowPolicy {
    fun resolve(
        compactLandscape: Boolean,
        normalTop: Int,
        windowBottom: Int,
        surfaceBounds: Rect,
        windowBounds: Rect? = null,
        preeditTab: Rect? = null,
    ): ImeWindowInsetsSpec {
        val validFloatingSurface = compactLandscape && !surfaceBounds.isEmpty
        if (validFloatingSurface) {
            return ImeWindowInsetsSpec(
                contentTop = windowBottom,
                visibleTop = windowBottom,
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION,
                touchableRegion = Rect(surfaceBounds),
            )
        }
        val tab = preeditTab?.takeIf { !it.isEmpty && windowBounds != null && !windowBounds.isEmpty }
        if (tab != null) {
            return ImeWindowInsetsSpec(
                contentTop = normalTop,
                visibleTop = normalTop,
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION,
                touchableRegion = Rect(windowBounds!!.left, normalTop, windowBounds.right, windowBottom),
                touchableExtra = Rect(tab),
            )
        }
        return ImeWindowInsetsSpec(
            contentTop = normalTop,
            visibleTop = normalTop,
            touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE,
            touchableRegion = null,
        )
    }
}
