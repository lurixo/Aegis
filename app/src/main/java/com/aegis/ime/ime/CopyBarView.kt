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

import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class CopyBarView(context: Context) : LinearLayout(context) {

    var onCommit: (String) -> Unit = {}
    var onCopyBlock: (String) -> Unit = {}
    var onDismiss: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT

    private val ctl = CopyBarController(
        commit = { onCommit(it) },
        copyToAegis = { onCopyBlock(it) },
        dismiss = { onDismiss() },
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = capsuleBg()
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun capsuleBg() = InsetDrawable(
        GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.chipRadiusDp * density },
        dp(8), dp(5), dp(8), dp(5),
    )

    fun applyPalette(p: ImePalette) {
        palette = p
        background = capsuleBg()
        setPadding(dp(14), 0, dp(14), 0)
        render()
    }

    fun show(text: String) { ctl.show(text); render() }

    private fun render() {
        removeAllViews()
        addView(icon(), lp(dp(26), dp(26)))
        if (!ctl.splitMode) {
            addView(content(ctl.content.orEmpty()), lp(0, WC, 1f))
            addView(divider(), lp(dp(1), dp(18)))
            addView(pill(context.getString(R.string.copybar_split)) { ctl.toggleSplit(); render() }, lp(WC, WC))
        } else {
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (ctl.blocks.isEmpty()) chips.addView(TextView(context).apply {
                text = context.getString(R.string.copybar_no_splittable_content); setTextColor(palette.keyHint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(8), 0, dp(8), 0)
            })
            for (b in ctl.blocks) chips.addView(chip(b) { ctl.tapBlock(b) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, WC, 1f))
            addView(pill(context.getString(R.string.copybar_collapse)) { ctl.toggleSplit(); render() }, lp(WC, WC))
        }
        addView(pill("×") { ctl.close() }, lp(dp(34), WC))
    }

    private fun icon(): View = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.icon
        }
        override fun onDraw(canvas: Canvas) =
            Glyphs.drawClipboard(canvas, p, width / 2f, height / 2f, 9f * density)
    }.apply {
        Motion.applyTapFeedback(this, palette.icon)
        setOnClickListener { ctl.tapContent() }
    }

    private fun content(s: String): TextView = TextView(context).apply {
        text = if (s.length > DISPLAY_CAP) s.substring(0, DISPLAY_CAP) else s
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.candidateText)
        setPadding(dp(8), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        Motion.applyTapFeedback(this, palette.candidateText)
        setOnClickListener { ctl.tapContent() }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.chipText)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply { setColor(palette.chipBg); cornerRadius = ImeShapes.chipRadiusDp * density }
        Motion.applyTapFeedback(this, palette.chipText)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(6) }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.icon)
        setPadding(dp(10), 0, dp(10), 0)
        Motion.applyTapFeedback(this, palette.icon)
        setOnClickListener { onClick() }
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(palette.separator) }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private companion object {
        const val WC = LinearLayout.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000
    }
}
