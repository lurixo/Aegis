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
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

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
    private val field = object : TextView(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            return super.onTouchEvent(e)
        }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        setHorizontallyScrolling(false)
        movementMethod = ScrollingMovementMethod()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = dp(36)
        maxHeight = lineHeight * 4 + paddingTop + paddingBottom
    }
    private val cancel = btn(context.getString(R.string.editbar_cancel))
    private val confirm = btn(context.getString(R.string.editbar_confirm))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(field, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
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
        field.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.inputRadiusDp * density }
        cancel.setTextColor(p.keyLabelSecondary)
        confirm.setTextColor(p.keyLabelSecondary)
        Motion.applyTapFeedback(cancel, p.keyLabelSecondary)
        Motion.applyTapFeedback(confirm, p.keyLabelSecondary)
    }

    fun setTitle(t: String) { title.text = t }

    fun setText(t: String) {
        field.text = "$t▏"
        field.post {
            val l = field.layout ?: return@post
            val overflow = l.height - (field.height - field.paddingTop - field.paddingBottom)
            field.scrollTo(0, overflow.coerceAtLeast(0))
        }
    }

    internal fun cancelButtonForTest(): TextView = cancel
    internal fun confirmButtonForTest(): TextView = confirm
}
