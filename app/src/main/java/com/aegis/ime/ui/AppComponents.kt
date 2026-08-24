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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppIconMetrics
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing

@Composable
internal fun AppPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .appPageInsets(
                bottomInsets = WindowInsets.safeDrawing,
                topInsets = settingsTopInset(),
            ),
    ) {
        AppTopBar(title = title, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
        bottomBar()
    }
}

internal fun Modifier.appPageInsets(
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))

@Composable
internal fun AppSettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppPageScaffold(title = title, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenHorizontal)
                .padding(top = AppSpacing.compactGap, bottom = AppSpacing.pageBottom),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
            content = content,
        )
    }
}

@Composable
internal fun AppTopBar(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.settings_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSpacing.topBarHeight)
            .padding(start = AppSpacing.compactGap, end = AppSpacing.screenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = AppIconMetrics.touchTarget)
                .widthIn(min = AppIconMetrics.touchTarget)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 12.dp)
                .testTag("app_back_button")
                .semantics { contentDescription = backLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppChevron(back = true)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("app_page_title"),
            )
        }
    }
}

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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.section,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun AppSectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = AppSpacing.rowHorizontal))
}

@Composable
internal fun AppNavigationRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
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
            Text(title, style = MaterialTheme.typography.titleMedium)
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
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
