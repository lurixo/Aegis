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
import com.aegis.ime.ime.theme.ImeType

object PanelBackButton {

    const val HIT_DP = 48
    const val ICON_DP = 16
    const val GLYPH_SCALE = 0.56f
    const val GAP_DP = 6
    const val EDGE_DP = 12

    internal fun icon(density: Float): EditPanelView.GlyphDrawable =
        EditPanelView.GlyphDrawable(
            (ICON_DP * density).toInt(),
            GLYPH_SCALE,
            2f * density,
            0f,
        ) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }

    internal fun control(
        context: Context,
        label: String,
        glyph: EditPanelView.GlyphDrawable,
        tint: Int,
        edgeDp: Int = EDGE_DP,
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(tint)
            contentDescription = context.getString(R.string.clip_back)
            glyph.applyTint(tint)
            setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
            compoundDrawablePadding = (GAP_DP * density).toInt()
            setPadding((edgeDp * density).toInt(), 0, (edgeDp * density).toInt(), 0)
        }
    }
}
