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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object AppSpacing {
    val screenHorizontal = 20.dp
    val pageBottom = 24.dp
    val topBarHeight = 56.dp
    val sectionGap = 16.dp
    val sectionPadding = 16.dp
    val rowHorizontal = 16.dp
    val rowMinHeight = 56.dp
    val touchTarget = 48.dp
    val contentGap = 12.dp
    val compactGap = 8.dp
    val textGap = 4.dp
}

internal object AppIconMetrics {
    val touchTarget = 48.dp
    val iconBox = 24.dp
    val backChevronWidth = 10.5.dp
    val backChevronHeight = 17.5.dp
    val forwardChevronWidth = 7.dp
    val forwardChevronHeight = 12.dp
    val stroke = 2.dp
}

internal object AppShapes {
    val section = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

internal val aegisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = AppShapes.section,
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val baseTypography = Typography()

internal val aegisTypography = Typography(
    bodySmall = baseTypography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = baseTypography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = baseTypography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = baseTypography.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = baseTypography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = baseTypography.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
    titleLarge = baseTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    headlineSmall = baseTypography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineMedium = baseTypography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 34.sp),
)
