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

package com.aegis.ime.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImeShapes

internal val aegisShapes = Shapes(medium = RoundedCornerShape(ImeShapes.cardRadiusDp.dp))

@Composable
fun AegisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = aegisColorScheme(context, darkTheme)
    MaterialTheme(colorScheme = colorScheme, shapes = aegisShapes, content = content)
}

internal fun aegisColorScheme(context: Context, darkTheme: Boolean): ColorScheme = if (darkTheme) {
    dynamicDarkColorScheme(context)
} else {
    dynamicLightColorScheme(context)
}.copy(background = settingsBackgroundColor(context, darkTheme))

internal fun settingsBackgroundArgb(context: Context): Int = context.getColor(R.color.settings_window_background)

internal fun settingsBackgroundArgb(context: Context, darkTheme: Boolean): Int {
    val configuration = Configuration(context.resources.configuration)
    val nightMode = if (darkTheme) {
        Configuration.UI_MODE_NIGHT_YES
    } else {
        Configuration.UI_MODE_NIGHT_NO
    }
    configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    return context.createConfigurationContext(configuration).getColor(R.color.settings_window_background)
}

internal fun settingsBackgroundColor(context: Context, darkTheme: Boolean): Color =
    Color(settingsBackgroundArgb(context, darkTheme))
