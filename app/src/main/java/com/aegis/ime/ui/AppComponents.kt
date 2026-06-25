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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.appSectionFace
import com.aegis.ime.ui.theme.appSectionScheme

@Composable
internal fun AppSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val face = remember(context, dark) { appSectionFace(context, dark) }
    val scheme = remember(base, face) { appSectionScheme(base, face) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.section,
        color = Color(face),
        tonalElevation = 0.dp,
    ) {
        MaterialTheme(colorScheme = scheme) {
            Column(content = content)
        }
    }
}
