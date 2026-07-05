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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

/**
 * The pinyin preedit tab (C1): a small white rounded tab at the top-left showing the
 * in-progress pinyin (e.g. "ni'de"), sitting above the candidate row — NOT inline among candidates.
 * Hidden by the parent when there is nothing composing.
 */
open class PreeditView(context: Context) : View(context) { // open: MotionRedrawTest overrides invalidate() to pin the reduced-motion repaint

    private var text: String = ""
    private val density = resources.displayMetrics.density
    private val pad = 12f * density
    // U12: the preedit pinyin must left-align with the first candidate. The candidate strip lives inside
    // `body` (left padding = [leftInset]) and draws its first word 14dp in; this band is outside `body`,
    // so we mirror that same origin (leftInset + candPad) instead of a fixed offset that drifted right.
    private val candPad = 14f * density // == CandidateView.padding
    private var leftInset = 0f
    private val tab = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // for the soft shadow below

    // F1: Monet palette (default = static light); the IME service pushes the live one.
    private var palette = ImePalette.STATIC_LIGHT

    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.keySurface
        setShadowLayer(5f * density, 0f, 2f * density, palette.shadow) // U-polish: shadow via token (theme-aware)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.preeditText
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }

    /** F1: push a new Monet palette and repaint. */
    fun applyPalette(p: ImePalette) {
        palette = p
        tabPaint.color = p.keySurface
        tabPaint.setShadowLayer(5f * density, 0f, 2f * density, p.shadow) // U-polish: refresh shadow for the new theme
        textPaint.color = p.preeditText
        invalidate()
    }

    fun setText(s: String) {
        if (s == text) return
        // U-anim: fade the pinyin tab in only on its FIRST appearance (empty -> text); a plain content change
        // mid-composition just repaints, so there's no per-keystroke flicker. Alpha only — the band height is
        // fixed, so this never resizes the IME.
        val appearing = text.isEmpty() && s.isNotEmpty()
        text = s
        if (appearing) Motion.fadeIn(this) else invalidate() // the unified appear fade (FADE_IN), was SHORT4
    }

    /** U12: match the candidate strip's left inset (= `body` left padding) so the pinyin aligns with it. */
    fun setLeftInset(px: Float) {
        if (px == leftInset) return
        leftInset = px
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        // U12: the pinyin's first glyph sits exactly where the first candidate does (leftInset + candPad);
        // the rounded tab hugs it with the usual `pad` inset on each side.
        val textX = leftInset + candPad
        val w = textPaint.measureText(text) + pad * 2
        val r = ImeShapes.keyRadiusDp * density // U-polish: tab corner = key token (was cardRadiusDp/2 magic)
        tab.set(textX - pad, 0f, textX - pad + w, height.toFloat() + r)
        canvas.drawRoundRect(tab, r, r, tabPaint)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, textX, baseline, textPaint)
    }
}
