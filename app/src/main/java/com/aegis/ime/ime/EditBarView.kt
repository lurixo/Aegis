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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

class EditBarView(context: Context) : LinearLayout(context) {

    var onCancel: () -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onTextChanged: (String) -> Unit = {}
    var onSelectionState: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var suppressTextCallback = false

    private val title = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(12), 0, dp(6), 0)
    }
    private val field = object : EditText(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
            return super.onTouchEvent(e)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onSelectionState?.invoke(selStart != selEnd)
        }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showSoftInputOnFocus = false
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = true
        setHorizontallyScrolling(false)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = dp(36)
        isVerticalScrollBarEnabled = true
    }
    private val cancel = btn(context.getString(R.string.editbar_cancel))
    private val confirm = btn(context.getString(R.string.editbar_confirm))

    private val fieldEditable = object : PanelEditable {
        override fun snapshot(): String = field.text.toString()

        override fun selectionStart(): Int = field.selectionStart.coerceAtLeast(0)

        override fun selectionEnd(): Int = field.selectionEnd.coerceAtLeast(0)

        override fun setSelection(start: Int, end: Int) {
            val n = field.text.length
            field.setSelection(start.coerceIn(0, n), end.coerceIn(0, n))
        }

        override fun replace(start: Int, end: Int, text: CharSequence) {
            val n = field.text.length
            val s = start.coerceIn(0, n)
            val e = end.coerceIn(s, n)
            field.text.replace(s, e, text)
            val at = (s + text.length).coerceIn(0, field.text.length)
            field.setSelection(at, at)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            field,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                val m = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
                topMargin = m; bottomMargin = m
            },
        )
        addView(cancel, btnLp(startDp = 6))
        addView(confirm, btnLp(startDp = 6, endDp = 8))
        cancel.setOnClickListener { onCancel() }
        confirm.setOnClickListener { onConfirm() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextCallback) onTextChanged(s?.toString().orEmpty())
            }
        })
        setFieldLineBudget(MAX_FIELD_LINES)
        applyPalette(palette)
    }

    private fun btnLp(startDp: Int, endDp: Int = 0) =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
            topMargin = m; bottomMargin = m; marginStart = dp(startDp); marginEnd = dp(endDp)
        }

    private fun btn(s: String) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER
        minWidth = dp(56)
        setPadding(dp(12), 0, dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        title.setTextColor(p.keyHint)
        field.setTextColor(p.keyLabel)
        field.setTextCursorDrawable(
            GradientDrawable().apply { setColor(p.accentBottom); setSize(maxOf(1, dp(2)), 0) },
        )
        field.highlightColor = Motion.withAlpha(p.accentBottom, 0x55)
        field.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.inputRadiusDp * density }
        cancel.setTextColor(p.keyLabel)
        cancel.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.toolbarFeedbackRadiusDp * density }
        confirm.setTextColor(p.accentBottom)
        confirm.background = GradientDrawable().apply { setColor(Motion.withAlpha(p.accentBottom, 0x22)); cornerRadius = ImeShapes.toolbarFeedbackRadiusDp * density }
        Motion.applyTapFeedback(cancel, p.keyLabel, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
        Motion.applyTapFeedback(confirm, p.accentBottom, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
    }

    fun setFieldLineBudget(lines: Int) {
        val limit = field.lineHeight * lines.coerceIn(1, MAX_FIELD_LINES) + field.paddingTop + field.paddingBottom
        if (field.maxHeight != limit) field.maxHeight = limit
    }

    fun setTitle(t: String) { title.text = t }

    fun setText(t: String) {
        suppressTextCallback = true
        field.setText(t)
        field.setSelection(t.length)
        suppressTextCallback = false
    }

    fun editable(): PanelEditable = fieldEditable

    fun focusField() {
        field.isFocusableInTouchMode = true
        field.requestFocus()
        field.setSelection(field.text.length)
    }

    fun releaseField() { field.clearFocus() }

    internal fun confirmButtonForTest(): TextView = confirm

    internal fun fieldForTest(): EditText = field

    companion object {
        const val MAX_FIELD_LINES = 4
    }
}
