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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
internal fun AppSectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = AppSpacing.rowHorizontal))
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

@Composable
internal fun AppSettingRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    trailing: @Composable () -> Unit = {},
) {
    val rowModifier = if (onClick == null) modifier else modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick)
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(horizontal = AppSpacing.rowHorizontal, vertical = AppSpacing.compactGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.textGap),
        ) {
            Text(title, style = titleStyle)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun AppChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.selectableGroup(), content = content)
}

@Composable
internal fun AppChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .clip(MaterialTheme.shapes.extraSmall)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = AppSpacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (description == null) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        } else {
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AegisSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
internal fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = AppSpacing.touchTarget),
        contentPadding = contentPadding,
        interactionSource = rememberSettingsPressSource(),
        content = content,
    )
}

@Composable
internal fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    singleLine: Boolean = false,
) {
    AppPrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding) {
        AppButtonLabel(text, singleLine = singleLine)
    }
}

private const val LABEL_MIN_SCALE = 10f / 14f
private const val LABEL_SCALE_STEP = 0.05f
private const val LABEL_SCALE_MARGIN = 0.01f

@Composable
internal fun AppButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    var scale by remember(text, singleLine) { mutableFloatStateOf(1f) }
    val style = MaterialTheme.typography.labelLarge
    val floor = scale <= LABEL_MIN_SCALE + LABEL_SCALE_MARGIN
    Text(
        text,
        style = style.copy(
            fontSize = style.fontSize * scale,
            lineHeight = style.lineHeight * scale,
        ),
        textAlign = TextAlign.Center,
        maxLines = if (singleLine) 1 else 2,
        overflow = if (floor) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { result ->
            if (!floor) {
                val next = fittedLabelScale(result, scale, singleLine)
                if (next < scale) scale = next
            }
        },
        modifier = modifier,
    )
}

private fun fittedLabelScale(result: TextLayoutResult, scale: Float, singleLine: Boolean): Float {
    if (singleLine) {
        if (!result.hasVisualOverflow) return scale
        val maxWidth = result.layoutInput.constraints.maxWidth.toFloat()
        val lineWidth = result.getLineRight(0) - result.getLineLeft(0)
        if (lineWidth <= 0f) return scale
        val fitted = scale * maxWidth / lineWidth - LABEL_SCALE_MARGIN
        return fitted.coerceAtLeast(LABEL_MIN_SCALE)
    }
    val shrink = result.hasVisualOverflow || hasIntraWordBreak(result)
    if (!shrink) return scale
    return (scale - LABEL_SCALE_STEP).coerceAtLeast(LABEL_MIN_SCALE)
}

private fun hasIntraWordBreak(result: TextLayoutResult): Boolean {
    val text = result.layoutInput.text.text
    for (line in 0 until result.lineCount - 1) {
        val end = result.getLineEnd(line, visibleEnd = true)
        if (end <= 0 || end >= text.length) continue
        val before = text[end - 1]
        val after = text[end]
        val breaksWord = before.isLetterOrDigit() && after.isLetterOrDigit() &&
            !isIdeographic(before) && !isIdeographic(after)
        if (breaksWord) return true
    }
    return false
}

private fun isIdeographic(ch: Char): Boolean = Character.isIdeographic(ch.code)
