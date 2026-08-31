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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.translate.TranslateMode

class TranslateBarView(context: Context) : LinearLayout(context) {

    var onClose: () -> Unit = {}
    var onTextChanged: (String) -> Unit = {}
    var onModeChanged: (TranslateMode) -> Unit = {}
    var onSelectionState: ((Boolean) -> Unit)? = null
    var onDialogVisibilityChanged: () -> Unit = {}
    var onFieldTap: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private val cursorDrawable = GradientDrawable().apply { setSize(maxOf(1, dp(2)), 0) }
    private var mode = TranslateMode.AUTO
    private var dialog: PopupWindow? = null
    private var suppressTextCallback = false

    private val modeButton = TextView(context).apply {
        gravity = Gravity.CENTER
        minWidth = dp(56)
        setPadding(dp(12), 0, dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setOnClickListener { if (dialog != null) dismissDialog() else showDialog() }
    }
    private val field = object : EditText(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onFieldTap()
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
        setPadding(dp(12), 0, dp(12), dp(FIELD_INSET_DP))
        isVerticalScrollBarEnabled = true
    }
    private val label = TextView(context).apply {
        text = context.getString(R.string.translate_caption)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
        setPadding(dp(12), dp(FIELD_INSET_DP), dp(12), 0)
    }
    private val fieldBox = LinearLayout(context).apply {
        orientation = VERTICAL
        minimumHeight = dp(LABELED_FIELD_HEIGHT_DP)
        addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CAPTION_ROW_DP)))
        addView(field, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val choices = TranslateMode.entries.map { choice ->
        TextView(context).apply {
            text = context.getString(labelOf(choice))
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            minWidth = dp(168)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            compoundDrawablePadding = dp(12)
            setOnClickListener { choose(choice) }
        }
    }
    private val back = PanelBackButton.control(context, context.getString(R.string.panel_back), palette.keyLabel) { onClose() }.apply {
        minHeight = 0
    }

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
        addView(back, sideLp(startDp = 0))
        addView(
            fieldBox,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                val m = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
                topMargin = m; bottomMargin = m
            },
        )
        addView(modeButton, sideLp(startDp = 6, endDp = 8))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextCallback) onTextChanged(s?.toString().orEmpty())
            }
        })
        setFieldLineBudget(MAX_FIELD_LINES)
        renderMode()
        applyPalette(palette)
    }

    private fun sideLp(startDp: Int, endDp: Int = 0) =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
            topMargin = m; bottomMargin = m; marginStart = dp(startDp); marginEnd = dp(endDp)
        }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        field.setTextColor(p.keyLabel)
        cursorDrawable.setColor(p.accentBottom)
        field.setTextCursorDrawable(cursorDrawable)
        field.highlightColor = Motion.withAlpha(p.accentBottom, 0x55)
        fieldBox.background = GradientDrawable().apply { setColor(p.keySurface); cornerRadius = ImeShapes.inputRadiusDp * density }
        field.background = null
        label.setTextColor(p.keyHint)
        modeButton.setTextColor(p.keyLabel)
        modeButton.background = null
        Motion.applyTapFeedback(modeButton, p.keyLabel, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
        back.applyTint(p.keyLabel)
        renderChoices()
    }

    fun setFieldLineBudget(lines: Int) {
        val compact = lines <= 1
        label.visibility = if (compact) GONE else VISIBLE
        val top = if (compact) dp(COMPACT_FIELD_INSET_DP) else 0
        val bottom = dp(if (compact) COMPACT_FIELD_INSET_DP else FIELD_INSET_DP)
        if (field.paddingTop != top || field.paddingBottom != bottom) field.setPadding(dp(12), top, dp(12), bottom)
        fieldBox.minimumHeight = dp(if (compact) COMPACT_FIELD_HEIGHT_DP else LABELED_FIELD_HEIGHT_DP)
        val limit = field.lineHeight * lines.coerceIn(1, MAX_FIELD_LINES) + field.paddingTop + field.paddingBottom
        if (field.maxHeight != limit) field.maxHeight = limit
    }

    fun mode(): TranslateMode = mode

    fun setMode(m: TranslateMode) {
        if (mode == m) return
        mode = m
        renderMode()
        renderChoices()
    }

    fun setText(t: String) {
        suppressTextCallback = true
        field.setText(t)
        field.setSelection(t.length)
        suppressTextCallback = false
    }

    fun text(): String = field.text.toString()

    fun editable(): PanelEditable = fieldEditable

    fun focusField() {
        field.isFocusableInTouchMode = true
        field.requestFocus()
        field.setSelection(field.text.length)
    }

    fun releaseField() { field.clearFocus() }

    fun isModeDialogShowing(): Boolean = dialog != null

    fun dismissModeDialog() = dismissDialog()

    internal fun modeAnchor(): View = modeButton

    override fun onDetachedFromWindow() {
        dismissDialog()
        super.onDetachedFromWindow()
    }

    private fun choose(choice: TranslateMode) {
        val changed = choice != mode
        setMode(choice)
        dismissDialog()
        focusField()
        if (changed) onModeChanged(choice)
    }

    private fun showDialog() {
        if (dialog != null) return
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { setColor(palette.floatSurface); cornerRadius = ImeShapes.floatRadiusDp * density }
            setPadding(0, dp(6), 0, dp(6))
            elevation = 8f * density
        }
        renderChoices()
        for (choice in choices) {
            (choice.parent as? ViewGroup)?.removeView(choice)
            card.addView(choice, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setOnDismissListener {
                dialog = null
                onDialogVisibilityChanged()
            }
            showAsDropDown(modeButton, 0, dp(4), Gravity.END)
        }
        onDialogVisibilityChanged()
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun renderMode() {
        modeButton.text = context.getString(labelOf(mode))
    }

    private fun renderChoices() {
        for ((i, choice) in TranslateMode.entries.withIndex()) {
            val view = choices[i]
            view.setTextColor(palette.keyLabel)
            view.background = null
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(radioDrawable(choice == mode), null, null, null)
            Motion.applyTapFeedback(view, palette.keyLabel, radiusDp = ImeShapes.chipRadiusDp)
        }
    }

    private fun radioDrawable(selected: Boolean): Drawable {
        val size = dp(16)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), if (selected) palette.accentBottom else palette.keyHint)
            setSize(size, size)
        }
        if (!selected) return LayerDrawable(arrayOf<Drawable>(ring))
        val inset = dp(4)
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accentBottom)
            setSize(size - inset * 2, size - inset * 2)
        }
        return LayerDrawable(arrayOf<Drawable>(ring, dot)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    internal fun fieldForTest(): EditText = field
    internal fun fieldBoxForTest(): View = fieldBox
    internal fun modeButtonForTest(): TextView = modeButton
    internal fun closeButtonForTest(): TextView = back
    internal fun dialogForTest(): PopupWindow? = dialog
    internal fun choiceForTest(m: TranslateMode): TextView = choices[TranslateMode.entries.indexOf(m)]

    companion object {
        const val MAX_FIELD_LINES = 4
        const val LABELED_FIELD_HEIGHT_DP = 46
        const val COMPACT_FIELD_HEIGHT_DP = 36
        const val CAPTION_ROW_DP = 21
        private const val FIELD_INSET_DP = 5
        private const val COMPACT_FIELD_INSET_DP = 6

        fun labelOf(mode: TranslateMode): Int = when (mode) {
            TranslateMode.AUTO -> R.string.translate_mode_auto
            TranslateMode.ZH_EN -> R.string.translate_mode_zh_en
            TranslateMode.ZH_JA -> R.string.translate_mode_zh_ja
        }
    }
}
