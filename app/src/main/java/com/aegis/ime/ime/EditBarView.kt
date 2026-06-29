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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

/**
 * debug.16 Option A: the inline text-input bar shown above the candidate strip while editing a 常用语 / a
 * category name. The Aegis keyboard stays visible below; its output is redirected (by [PanelTextInput]) into
 * this bar's buffer rather than the target app. Shows a title, the live buffer + a caret, and 取消 / 确定.
 */
class EditBarView(context: Context) : LinearLayout(context) {

    var onCancel: () -> Unit = {}
    var onConfirm: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT

    private val title = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(12), 0, dp(6), 0)
    }
    private val field = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.START // keep the end + caret in view as it grows
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }
    private val cancel = btn("取消")
    private val confirm = btn("确定")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(field, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(cancel, LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(confirm, LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT))
        cancel.setOnClickListener { onCancel() }
        confirm.setOnClickListener { onConfirm() }
        applyPalette(palette)
    }

    private fun btn(s: String) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        title.setTextColor(p.keyHint)
        field.setTextColor(p.keyLabel)
        field.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.chipRadiusDp * density }
        cancel.setTextColor(p.keyLabelSecondary)
        confirm.setTextColor(p.candidateFirst)
    }

    /** The bar title (e.g. 编辑常用语 / 新建分类 / 重命名分类). */
    fun setTitle(t: String) { title.text = t }

    /** Mirror the capture buffer; a trailing caret marks the (end-of-text) cursor. */
    fun setText(t: String) { field.text = "$t▏" }
}
