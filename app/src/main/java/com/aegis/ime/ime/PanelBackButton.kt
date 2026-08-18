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

package com.aegis.ime.ime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

object PanelBackButton {

    const val HIT_DP = 48
    const val ICON_DP = 16
    const val GLYPH_SCALE = 0.56f
    const val GAP_DP = 6
    const val EDGE_DP = 12

    private fun icon(density: Float): EditPanelView.GlyphDrawable =
        EditPanelView.GlyphDrawable(
            (ICON_DP * density).toInt(),
            GLYPH_SCALE,
            2f * density,
            0f,
        ) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }

    internal fun control(
        context: Context,
        label: String,
        tint: Int,
        onBack: () -> Unit,
    ): PanelHeaderBackControl = PanelHeaderBackControl(context, label, tint, onBack)

    internal fun newIcon(density: Float): EditPanelView.GlyphDrawable = icon(density)
}

internal class PanelHeaderBackControl(
    context: Context,
    label: String,
    tint: Int,
    onBack: () -> Unit,
) : TextView(context) {

    private val density = resources.displayMetrics.density
    private val glyph = PanelBackButton.newIcon(density)

    init {
        text = label
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        contentDescription = context.getString(R.string.panel_back)
        setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
        compoundDrawablePadding = (PanelBackButton.GAP_DP * density).toInt()
        val edge = (PanelBackButton.EDGE_DP * density).toInt()
        setPadding(edge, 0, edge, 0)
        minWidth = (PanelBackButton.HIT_DP * density).toInt()
        minHeight = (PanelBackButton.HIT_DP * density).toInt()
        isClickable = true
        setOnClickListener { onBack() }
        applyTint(tint)
    }

    fun applyTint(tint: Int) {
        setTextColor(tint)
        glyph.applyTint(tint)
        Motion.applyTapFeedback(this, tint, radiusDp = ImeShapes.keyRadiusDp)
    }

    internal fun glyphForTest(): EditPanelView.GlyphDrawable = glyph
}
