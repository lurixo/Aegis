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
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.PersistedPage
import kotlin.math.roundToInt

class CustomSymbolPanel(context: Context) : LinearLayout(context), ResettablePanel {

    var current: () -> List<String> = { emptyList() }
    var currentPageProvider: ((Int, Int, Long?) -> PersistedPage<String>)? = null
    var containsCurrent: ((String) -> Boolean)? = null
    var onAdd: (String) -> Unit = {}
    var onRemove: (String) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var colors = ImePalette.STATIC_LIGHT
    private val addedRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val addedPageBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val paletteRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val contentColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val contentScroll = ScrollView(context).apply { addView(contentColumn) }
    private val headerBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val backText = TextView(context).apply {
        text = context.getString(R.string.csp_back_title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setTextColor(colors.keyLabel)
        Motion.applyTapFeedback(this, colors.keyLabel)
    }
    private val sectionLabels = mutableListOf<TextView>()
    private var measuringWidthOverride = 0
    private var lastFlowWidth = -1
    private val preferredHeaderHeight = dp(40)
    private val minimumHeaderHeight = dp(20)
    private val intactChipHeight = dp(44)
    private val normalBackVerticalPadding = dp(10)
    private var addedPage = 0
    private var addedVersion: Long? = null
    private var cachedAddedPage = -1
    private var cachedAdded: PersistedPage<String>? = null

    var backTitle: String = context.getString(R.string.csp_back_title)
        set(v) { field = v; backText.text = v }

    var addPalette: List<String> = SymbolCatalog.categories.flatMap { it.symbols }.distinct()
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.keyboardBg)
        backText.setOnClickListener { onBack() }
        headerBar.setBackgroundColor(colors.keyboardBg)
        headerBar.addView(backText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        headerBar.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(headerBar, LayoutParams(LayoutParams.MATCH_PARENT, preferredHeaderHeight))

        contentColumn.addView(sectionLabel(context.getString(R.string.csp_added_tap_to_remove)))
        contentColumn.addView(addedRows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentColumn.addView(addedPageBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
        contentColumn.addView(sectionLabel(context.getString(R.string.csp_tap_to_add)))
        contentColumn.addView(paletteRows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun resetToDefault() {
        Motion.reset(contentColumn)
        contentScroll.scrollTo(0, 0)
        addedPage = 0
        addedVersion = null
        invalidateAddedPage()
        rebuildFlows()
    }

    fun applyPalette(p: ImePalette) {
        colors = p
        setBackgroundColor(p.keyboardBg)
        headerBar.setBackgroundColor(p.keyboardBg)
        backText.setTextColor(p.keyLabel)
        Motion.applyTapFeedback(backText, p.keyLabel)
        sectionLabels.forEach { it.setTextColor(p.keyLabelSecondary) }
        rebuildFlows()
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(colors.keyLabelSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
        setPadding(dp(12), dp(6), dp(12), dp(4))
        sectionLabels.add(this)
    }

    fun refresh() {
        invalidateAddedPage()
        rebuildFlows()
    }

    private fun rebuildFlows() {
        val page = loadAddedPage()
        val added = page.items
        fillFlow(addedRows, added) { sym -> chip("$sym ✕", removable = true) { onRemove(sym) } }
        rebuildAddedPageBar(page.totalCount ?: added.size.toLong())
        val fallback = if (containsCurrent == null) current().toHashSet() else emptySet()
        fillFlow(paletteRows, addPalette.filter { sym -> containsCurrent?.invoke(sym) != true && sym !in fallback }) { sym ->
            chip(sym, removable = false) { onAdd(sym) }
        }
    }

    private fun invalidateAddedPage() {
        cachedAddedPage = -1
        cachedAdded = null
    }

    private fun loadAddedPage(): PersistedPage<String> {
        if (cachedAddedPage == addedPage) cachedAdded?.let { return it }
        val provider = currentPageProvider
        var page = if (provider == null) {
            val all = current()
            PersistedPage(
                all.drop(addedPage * ADDED_PAGE_SIZE).take(ADDED_PAGE_SIZE),
                0L,
                all.size.toLong(),
            )
        } else {
            provider(addedPage * ADDED_PAGE_SIZE, ADDED_PAGE_SIZE, addedVersion)
        }
        if (page.restartRequired && provider != null) {
            addedPage = 0
            addedVersion = null
            page = provider(0, ADDED_PAGE_SIZE, null)
        }
        addedVersion = page.version
        val maximumPage = (((page.totalCount ?: 0L) - 1L).coerceAtLeast(0L) / ADDED_PAGE_SIZE).toInt()
        if (addedPage > maximumPage && provider != null) {
            addedPage = maximumPage
            page = provider(addedPage * ADDED_PAGE_SIZE, ADDED_PAGE_SIZE, null)
            addedVersion = page.version
        }
        cachedAddedPage = addedPage
        cachedAdded = page
        return page
    }

    private fun rebuildAddedPageBar(totalCount: Long) {
        addedPageBar.removeAllViews()
        val maximumPage = ((totalCount - 1L).coerceAtLeast(0L) / ADDED_PAGE_SIZE).toInt()
        addedPageBar.visibility = if (maximumPage > 0) View.VISIBLE else View.GONE
        if (maximumPage == 0) return
        fun pageButton(label: String, description: Int, enabled: Boolean, move: () -> Unit) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            contentDescription = context.getString(description)
            setTextColor(if (enabled) colors.keyLabel else colors.keyLabelSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            isEnabled = enabled
            if (enabled) {
                Motion.applyTapFeedback(this, colors.keyLabel)
                setOnClickListener { move(); invalidateAddedPage(); rebuildFlows(); contentScroll.scrollTo(0, 0) }
            }
        }
        addedPageBar.addView(
            pageButton("‹", R.string.clip_previous_page, addedPage > 0) { addedPage-- },
            LayoutParams(dp(48), LayoutParams.MATCH_PARENT),
        )
        addedPageBar.addView(TextView(context).apply {
            text = context.getString(R.string.clip_page_format, addedPage + 1, maximumPage + 1)
            gravity = Gravity.CENTER
            setTextColor(colors.keyLabelSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addedPageBar.addView(
            pageButton("›", R.string.clip_next_page, addedPage < maximumPage) { addedPage++ },
            LayoutParams(dp(48), LayoutParams.MATCH_PARENT),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (incomingWidth > 0 && incomingWidth != lastFlowWidth) {
            measuringWidthOverride = incomingWidth
            lastFlowWidth = incomingWidth
            rebuildFlows()
            measuringWidthOverride = 0
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)

            val headerHeight = minOf(
                preferredHeaderHeight,
                maxOf(minimumHeaderHeight.coerceAtMost(available), available - intactChipHeight),
            ).coerceIn(0, available)
            (headerBar.layoutParams as LayoutParams).height = headerHeight
            (backText.layoutParams as LayoutParams).height = headerHeight
            val paddingScale = if (preferredHeaderHeight > 0) {
                headerHeight.toFloat() / preferredHeaderHeight
            } else {
                0f
            }
            val verticalPadding = (normalBackVerticalPadding * paddingScale).roundToInt()
                .coerceAtMost((headerHeight - dp(18)).coerceAtLeast(0) / 2)
            backText.setPadding(dp(12), verticalPadding, dp(12), verticalPadding)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fillFlow(container: LinearLayout, items: List<String>, make: (String) -> View) {
        container.removeAllViews()
        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val maxRowW = (liveWidth - dp(16)).coerceAtLeast(dp(56))
        var row = newRow(); var rowW = 0
        val cellW = dp(56)
        for (sym in items) {
            if (rowW + cellW > maxRowW && row.childCount > 0) { container.addView(row); row = newRow(); rowW = 0 }
            row.addView(make(sym), LayoutParams(cellW, dp(44)).apply { marginEnd = dp(4); topMargin = dp(4) })
            rowW += cellW + dp(4)
        }
        if (row.childCount > 0) container.addView(row)
    }

    internal fun contentCanScrollForwardForTest(): Boolean = contentScroll.canScrollVertically(1)
    internal fun contentScrollForTest(y: Int) {

        val viewport = (contentScroll.height - contentScroll.paddingTop - contentScroll.paddingBottom).coerceAtLeast(0)
        val maxScroll = (contentColumn.height - viewport).coerceAtLeast(0)
        contentScroll.scrollTo(0, y.coerceIn(0, maxScroll))
    }
    internal fun contentScrollYForTest(): Int = contentScroll.scrollY
    internal fun contentViewportForTest(): View = contentScroll
    internal fun addedPageForTest(): Int = addedPage
    internal fun nextAddedPageForTest(): Boolean = addedPageBar.getChildAt(2)?.performClick() ?: false
    internal fun previousAddedPageForTest(): Boolean = addedPageBar.getChildAt(0)?.performClick() ?: false
    internal fun addedChipLabelsForTest(): List<String> = (0 until addedRows.childCount).flatMap { rowIndex ->
        val row = addedRows.getChildAt(rowIndex) as? ViewGroup ?: return@flatMap emptyList()
        (0 until row.childCount).mapNotNull { (row.getChildAt(it) as? TextView)?.text?.toString() }
    }
    internal fun backButtonForTest(): TextView = backText
    internal fun paletteChipForTest(symbol: String): View? {
        for (r in 0 until paletteRows.childCount) {
            val row = paletteRows.getChildAt(r) as? ViewGroup ?: continue
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i) as? TextView ?: continue
                if (chip.text.toString() == symbol) return chip
            }
        }
        return null
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun chip(label: String, removable: Boolean, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (removable) ImeType.body else ImeType.title)
        setTextColor(if (removable) colors.deletable else colors.keyLabel)
        background = GradientDrawable().apply { setColor(this@CustomSymbolPanel.colors.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
        isClickable = true
        Motion.applyTapFeedback(this, if (removable) colors.deletable else colors.keyLabel)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val ADDED_PAGE_SIZE = 56
    }
}
