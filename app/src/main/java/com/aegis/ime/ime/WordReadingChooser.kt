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
import com.aegis.ime.decoder.WordReading
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class WordReadingChooser(context: Context) : ScrollView(context), KeyHapticsAware {

    var onBack: () -> Unit = {}
    var onAdd: (String) -> Unit = {}
    override var hapticEnabled = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var word = ""
    private var choices: List<List<String>> = emptyList()
    private val picked = ArrayList<String>()
    private val options = ArrayList<List<TextView>>()

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    val reading: String get() = picked.joinToString("")

    fun bind(word: String, reading: WordReading) {
        this.word = word
        choices = reading.choices
        picked.clear()
        picked.addAll(reading.syllables)
        render()
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        render()
    }

    private fun render() {
        content.removeAllViews()
        options.clear()
        val inset = ImeType.popupInsetPx(resources.displayMetrics)
        content.setPadding(0, dp(14), 0, dp(16))
        content.addView(TextView(context).apply {
            text = word
            gravity = Gravity.CENTER
            setTextColor(palette.keyLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTypeface(null, Typeface.BOLD)
            setPadding(inset, 0, inset, dp(10))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chars = word.codePoints().toArray()
        for (i in chars.indices) columns.addView(column(i, String(chars, i, 1)), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.addView(
            HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(columns) },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                leftMargin = inset
                rightMargin = inset
            },
        )
        val footerSide = (inset - dp(12)).coerceAtLeast(0)
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(footerSide, dp(12), footerSide, 0)
        }
        footer.addView(action(context.getString(R.string.word_readings_back)) { onBack() }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        footer.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
        footer.addView(action(context.getString(R.string.word_readings_add)) { onAdd(reading) }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
        content.addView(footer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun column(index: Int, char: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), 0, dp(4), 0)
        addView(TextView(context).apply {
            text = char
            gravity = Gravity.CENTER
            setTextColor(palette.keyLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setPadding(0, 0, 0, dp(6))
        })
        val choice = choices.getOrElse(index) { emptyList() }
        val views = choice.map { syllable ->
            TextView(context).apply {
                text = syllable
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                if (choice.size > 1) {
                    setOnClickListener { pick(index, syllable) }
                } else {
                    setTextColor(palette.keyLabel)
                }
            }.also { addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }) }
        }
        options.add(views)
        if (choice.size > 1) style(index)
    }

    private fun pick(index: Int, syllable: String) {
        picked[index] = syllable
        style(index)
    }

    private fun style(index: Int) {
        for (view in options[index]) {
            val selected = view.text.toString() == picked[index]
            val text = if (selected) palette.accentLabel else palette.keyLabel
            view.setTextColor(text)
            view.background = if (selected) {
                GradientDrawable().apply {
                    setColor(palette.accentBottom)
                    cornerRadius = ImeShapes.chipRadiusDp * density
                }
            } else {
                null
            }
            view.isSelected = selected
            Motion.applyTapFeedback(view, text, radiusDp = ImeShapes.chipRadiusDp)
        }
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(palette.keyLabel)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        ImeKeyFeedback(this, Color.TRANSPARENT, palette.keyLabel, radiusDp = ImeShapes.toolbarFeedbackRadiusDp).bind { hapticEnabled }
    }

    internal fun pickForTest(index: Int, syllable: String) {
        options[index].first { it.text.toString() == syllable }.performClick()
    }
}
