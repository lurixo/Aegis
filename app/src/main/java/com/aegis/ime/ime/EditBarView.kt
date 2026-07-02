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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

/**
  * Chinese IME behavior note.
 * category name. The Aegis keyboard stays visible below; its output is redirected (by [PanelTextInput]) into
  * Chinese IME behavior note.
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
    // Chinese IME behavior note.
    // grows to at most 4 wrapped lines and scrolls vertically beyond that, claiming the drag (disallow-intercept)
    // so an ancestor can't steal it while it can still scroll.
    private val field = object : TextView(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            return super.onTouchEvent(e)
        }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        setHorizontallyScrolling(false) // wrap onto multiple lines instead of a single clipped line
        movementMethod = ScrollingMovementMethod() // drag to scroll when content exceeds the 4-line viewport
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = dp(36) // keep the single-line rounded-rect look for short content
        // Chinese IME behavior note.
        // overflow can't be scrolled to). The full text still lays out; ScrollingMovementMethod scrolls it.
        maxHeight = lineHeight * 4 + paddingTop + paddingBottom
    }
    private val cancel = btn("取消")
    private val confirm = btn("确定")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // debug.17: the field is a fixed-height rounded RECTANGLE centred in the (taller) bar → equal top/bottom
        // margins inside its row (the bar's gravity is CENTER_VERTICAL), not a full-height stadium pill.
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
        field.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.inputRadiusDp * density } // Chinese IME behavior note.
        cancel.setTextColor(p.keyLabelSecondary)
        confirm.setTextColor(p.candidateFirst)
        Motion.applyTapFeedback(cancel, p.keyLabelSecondary)
        Motion.applyTapFeedback(confirm, p.candidateFirst)
    }

    /** Chinese IME behavior note. */
    fun setTitle(t: String) { title.text = t }

    /** Mirror the capture buffer; a trailing caret marks the (end-of-text) cursor. */
    fun setText(t: String) {
        field.text = "$t▏"
        // Chinese IME behavior note.
        field.post {
            val l = field.layout ?: return@post
            val overflow = l.height - (field.height - field.paddingTop - field.paddingBottom)
            field.scrollTo(0, overflow.coerceAtLeast(0))
        }
    }
}
