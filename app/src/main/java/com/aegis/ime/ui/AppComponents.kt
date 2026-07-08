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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aegis.ime.ui.theme.AppIconMetrics
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.ui.theme.appSectionFace
import com.aegis.ime.ui.theme.appSectionScheme

internal fun Modifier.appPageInsets(
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))

@Composable
private fun AppChevron(back: Boolean) {
    val density = LocalDensity.current
    val width = with(density) {
        (if (back) AppIconMetrics.backChevronWidth else AppIconMetrics.forwardChevronWidth).toPx()
    }
    val height = with(density) {
        (if (back) AppIconMetrics.backChevronHeight else AppIconMetrics.forwardChevronHeight).toPx()
    }
    val stroke = with(density) { AppIconMetrics.stroke.toPx() }
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(AppIconMetrics.iconBox).testTag(if (back) "app_back_icon" else "app_forward_icon")) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val path = Path().apply {
            if (back) {
                moveTo(center.x + width / 2f, center.y - height / 2f)
                lineTo(center.x - width / 2f, center.y)
                lineTo(center.x + width / 2f, center.y + height / 2f)
            } else {
                moveTo(center.x - width / 2f, center.y - height / 2f)
                lineTo(center.x + width / 2f, center.y)
                lineTo(center.x - width / 2f, center.y + height / 2f)
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

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

@Composable
internal fun rememberSettingsPressSource(): MutableInteractionSource {
    val epoch = LocalSettingsPressEpoch.current
    return remember(epoch) { MutableInteractionSource() }
}

@Composable
internal fun AppNavigationRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = rememberSettingsPressSource(),
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = AppSpacing.rowHorizontal, vertical = AppSpacing.compactGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.textGap),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(AppSpacing.compactGap))
        AppChevron(back = false)
    }
}
