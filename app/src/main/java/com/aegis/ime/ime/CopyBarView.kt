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
import android.graphics.drawable.GradientDrawable
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

    private val ctl = CopyBarController(
        commit = { onCommit(it) },
        copyToAegis = { onCopyBlock(it) },
        dismiss = { onDismiss() },
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFFE9ECF1.toInt())
        setPadding(dp(10), 0, dp(6), 0)
    }

    fun show(text: String) { ctl.show(text); render() }

    private fun render() {
        removeAllViews()
        addView(icon(), lp(dp(26), dp(26)))
        if (!ctl.splitMode) {
            addView(content(ctl.content.orEmpty()), lp(0, WC, 1f))
            addView(divider(), lp(dp(1), dp(18)))
            addView(pill("拆") { ctl.toggleSplit(); render() }, lp(WC, WC))
        } else {
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (ctl.blocks.isEmpty()) chips.addView(TextView(context).apply {
                text = "无可拆分内容"; setTextColor(0xFF9AA0A6.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(8), 0, dp(8), 0)
            })
            for (b in ctl.blocks) chips.addView(chip(b) { ctl.tapBlock(b) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, WC, 1f))
            addView(pill("收") { ctl.toggleSplit(); render() }, lp(WC, WC))
        }
        addView(pill("×") { ctl.close() }, lp(dp(34), WC))
    }

    private fun icon(): View = TextView(context).apply {
        text = "📋"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener { ctl.tapContent() }
    }

    private fun content(s: String): TextView = TextView(context).apply {
        text = s
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0xFF202124.toInt())
        setPadding(dp(8), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { ctl.tapContent() }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(0xFF202124.toInt())
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply { setColor(0xFFDDE1E6.toInt()); cornerRadius = 999f * density }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(6) }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(0xFF455A64.toInt())
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(0xFFC9CED4.toInt()) }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private companion object { const val WC = LinearLayout.LayoutParams.WRAP_CONTENT }
}
