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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

internal class PanelConfirmationOverlay(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private var confirmAction: TextView? = null
    private var cancelAction: TextView? = null

    init {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { dismiss() }
    }

    fun show(title: String, confirm: String, cancel: String, palette: ImePalette, onConfirm: () -> Unit) {
        removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            minimumWidth = dp(260)
            background = GradientDrawable().apply {
                setColor(palette.keySurface)
                cornerRadius = ImeShapes.cardRadiusDp * density
                setStroke(dp(1), palette.separator)
            }
        }
        card.addView(TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(palette.keyLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        })
        card.addView(divider(palette))
        val confirmView = action(confirm, palette) {
            dismiss()
            onConfirm()
        }
        confirmAction = confirmView
        card.addView(confirmView)
        card.addView(divider(palette))
        val cancelView = action(cancel, palette, ::dismiss)
        cancelAction = cancelView
        card.addView(cancelView)
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val margin = dp(24)
            leftMargin = margin
            rightMargin = margin
        })
        visibility = View.VISIBLE
        bringToFront()
        Motion.revealIn(card, Motion.EnterFrom.BOTTOM)
    }

    fun dismiss() {
        removeAllViews()
        confirmAction = null
        cancelAction = null
        visibility = View.GONE
    }

    internal fun confirmForTest(): Boolean = confirmAction?.performClick() ?: false
    internal fun cancelForTest(): Boolean = cancelAction?.performClick() ?: false

    private fun action(label: String, palette: ImePalette, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(palette.keyLabel)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onClick() }
    }

    private fun divider(palette: ImePalette): View = View(context).apply {
        setBackgroundColor(palette.separator)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
    }
}
