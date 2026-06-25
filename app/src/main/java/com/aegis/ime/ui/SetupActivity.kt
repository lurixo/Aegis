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

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .background(MaterialTheme.colorScheme.background)
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))
    .verticalScroll(scrollState)

internal fun resolveTopInsetPx(liveTop: Int, seedTop: Int, rootTop: Int?): Int = when {
    liveTop > 0 -> liveTop
    rootTop != null -> rootTop
    else -> seedTop
}

internal fun synchronousTopInsetPx(
    visibleTop: Int,
    ignoringVisibilityTop: Int,
    maximumIgnoringVisibilityTop: Int,
    isAttachedToDisplayTop: Boolean,
): Int = when {
    visibleTop > 0 -> visibleTop
    ignoringVisibilityTop > 0 -> ignoringVisibilityTop
    !isAttachedToDisplayTop -> 0
    maximumIgnoringVisibilityTop > 0 -> maximumIgnoringVisibilityTop
    else -> 0
}
