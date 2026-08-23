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

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal class AegisToastMessage(val text: String, val key: Long)

internal object AegisToast {

    const val HOLD_MS = 1500L
    const val FADE_IN_MS = 180
    const val FADE_OUT_MS = 180

    val current = mutableStateOf<AegisToastMessage?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { current.value = null }
    private var counter = 0L
    private var latest: String? = null
    private var shown = 0

    fun show(text: String) {
        if (text.isEmpty()) return
        latest = text
        shown += 1
        counter += 1
        current.value = AegisToastMessage(text, counter)
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, FADE_IN_MS + HOLD_MS)
    }

    internal fun textForTest(): String? = latest

    internal fun shownCountForTest(): Int = shown

    internal fun reset() {
        handler.removeCallbacks(hide)
        current.value = null
        latest = null
        shown = 0
    }
}

@Composable
internal fun AegisToastOverlay(modifier: Modifier = Modifier) {
    val message = AegisToast.current.value
    var lastText by remember { mutableStateOf("") }
    if (message != null) lastText = message.text
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(AegisToast.FADE_IN_MS)),
        exit = fadeOut(tween(AegisToast.FADE_OUT_MS)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
            AegisToastCard(lastText)
        }
    }
}

@Composable
private fun AegisToastCard(text: String) {
    val dark = isSystemInDarkTheme()
    val shadowColor = MaterialTheme.colorScheme.scrim.copy(alpha = if (dark) 0.25f else 0.13f)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .drawBehind {
                val spread = 1.dp.toPx()
                val corner = CornerRadius(12.dp.toPx() + spread, 12.dp.toPx() + spread)
                drawRoundRect(
                    color = shadowColor,
                    topLeft = Offset(-spread, 2.dp.toPx() - spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = corner,
                )
            }
            .testTag("aegis_toast"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            AegisToastMark(dark = dark, size = 20.dp)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AegisToastMark(dark: Boolean, size: Dp) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(size)) {
        val scale = this.size.minDimension / 58f
        withTransform({
            translate(this.size.width / 2f, this.size.height / 2f)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-54f, -56.5f)
        }) {
            if (dark) drawMonoMark(tint) else drawFilledMark()
        }
    }
}

private fun DrawScope.drawFilledMark() {
    val shield = Path().apply {
        moveTo(35f, 31f); lineTo(73f, 31f); lineTo(73f, 54f)
        cubicTo(73f, 68f, 64f, 78f, 54f, 82f)
        cubicTo(44f, 78f, 35f, 68f, 35f, 54f)
        close()
    }
    drawPath(shield, Color.Black)
    drawRoundRect(Color.White, topLeft = Offset(45f, 39f), size = Size(18f, 16f), cornerRadius = CornerRadius(4f, 4f))
    drawPath(markIPath(), Color.Black)
    drawRoundRect(Color.White, topLeft = Offset(44f, 60f), size = Size(20f, 7f), cornerRadius = CornerRadius(3f, 3f))
}

private fun DrawScope.drawMonoMark(tint: Color) {
    val wide = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val shield = Path().apply {
        moveTo(37f, 33f); lineTo(71f, 33f); lineTo(71f, 53f)
        cubicTo(71f, 66f, 63f, 76f, 54f, 80f)
        cubicTo(45f, 76f, 37f, 66f, 37f, 53f)
        close()
    }
    drawPath(shield, tint, style = wide)
    drawRoundRect(tint, topLeft = Offset(45f, 39f), size = Size(18f, 16f), cornerRadius = CornerRadius(4f, 4f), style = thin)
    drawPath(markIPath(), tint)
    drawRoundRect(tint, topLeft = Offset(44f, 60f), size = Size(20f, 7f), cornerRadius = CornerRadius(3f, 3f), style = thin)
}

private fun markIPath(): Path = Path().apply {
    moveTo(51f, 42f); lineTo(57f, 42f); lineTo(57f, 44f); lineTo(55.5f, 44f); lineTo(55.5f, 50f)
    lineTo(57f, 50f); lineTo(57f, 52f); lineTo(51f, 52f); lineTo(51f, 50f); lineTo(52.5f, 50f)
    lineTo(52.5f, 44f); lineTo(51f, 44f)
    close()
}
