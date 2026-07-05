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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette

@Composable
fun AegisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = aegisColorScheme(context, darkTheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = aegisTypography,
        shapes = aegisShapes,
        content = content,
    )
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

internal fun appSectionFace(context: Context, darkTheme: Boolean): Int =
    ImePalette.from(context, darkTheme).functionSurface

internal fun appSectionScheme(base: ColorScheme, face: Int): ColorScheme = base.copy(
    onSurfaceVariant = Color(
        sectionInk(face, base.onSurfaceVariant.toArgb(), base.onSurface.toArgb(), SECTION_SUPPORT_CONTRAST),
    ),
    outlineVariant = Color(
        sectionInk(face, base.outlineVariant.toArgb(), base.onSurface.toArgb(), SECTION_DIVIDER_CONTRAST),
    ),
    surfaceContainerHighest = Color(
        sectionInk(face, base.surfaceContainerHighest.toArgb(), base.onSurface.toArgb(), SECTION_CONTROL_CONTRAST),
    ),
)

internal const val SECTION_SUPPORT_CONTRAST = 7.0

internal const val SECTION_DIVIDER_CONTRAST = 1.6

internal const val SECTION_CONTROL_CONTRAST = 1.25

private const val SECTION_INK_STEPS = 20

private fun sectionInk(face: Int, base: Int, ink: Int, target: Double): Int {
    for (step in 0..SECTION_INK_STEPS) {
        val color = ColorUtils.blendARGB(base, ink, step.toFloat() / SECTION_INK_STEPS) or 0xFF000000.toInt()
        if (ColorUtils.calculateContrast(color, face) >= target) return color
    }
    return ink
}
