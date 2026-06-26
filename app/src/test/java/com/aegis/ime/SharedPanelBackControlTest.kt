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

package com.aegis.ime

import com.aegis.ime.user.clipEntries
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.PanelBackButton
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.engine.CandidateEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SharedPanelBackControlTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun dp(v: Int) = (v * density).toInt()

    @Before
    @After
    fun clearStores() {
        ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun startedService(): Pair<AegisInputMethodService, InputView> {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 7
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service to view
    }

    private fun open(service: AegisInputMethodService, method: String) {
        service.javaClass.getDeclaredMethod(method).run {
            isAccessible = true
            invoke(service)
        }
    }

    private fun cached(service: AegisInputMethodService, field: String): Any? =
        service.javaClass.getDeclaredField(field).run {
            isAccessible = true
            get(service)
        }

    private fun layout(view: View, width: Int = dp(411), height: Int = dp(700)) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun editPanelBack(): TextView {
        val panel = EditPanelView(ctx).also { it.applyPalette(ImePalette.STATIC_LIGHT) }
        return (panel.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
    }

    private fun backControls(root: View): List<TextView> {
        val label = ctx.getString(R.string.clip_back)
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.contentDescription?.toString() == label) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test fun the_custom_symbol_family_is_exactly_the_punctuation_and_operator_pages() {
        val declared = AegisInputMethodService::class.java.declaredFields
            .filter { it.type == CustomSymbolPanel::class.java }
            .map { it.name }
            .toSet()
        assertEquals(
            "a new custom-X page must be added to this enumeration and given its own titles",
            setOf("customSymbolView", "customOperatorView"),
            declared,
        )
    }

    @Test fun every_custom_symbol_entry_shares_the_back_control_and_names_its_own_object() {
        val (service, view) = startedService()
        val entries = listOf(
            Triple(
                "showCustomSymbolPanel" to "customSymbolView",
                R.string.csp_punctuation_title,
                R.string.csp_section_all_punctuation,
            ),
            Triple(
                "showCustomOperatorPanel" to "customOperatorView",
                R.string.csp_operators_title,
                R.string.csp_section_all_operators,
            ),
        )
        val titles = ArrayList<String>()
        for ((entry, title, paletteTitle) in entries) {
            val (method, field) = entry
            open(service, method)
            val panel = cached(service, field) as CustomSymbolPanel
            assertTrue("$field must be the panel on screen", view.isPanelShowing(panel))
            layout(view)

            val button = panel.backButtonForTest()
            assertTrue("$field uses the edit panel title control", button is TextView)
            assertTrue("$field back hit height", button.height >= dp(48))
            assertTrue("$field back hit width", button.width >= dp(48))
            val editBack = editPanelBack()
            assertEquals("$field back text scale", editBack.textSize, (button as TextView).textSize, 0.01f)
            assertEquals(
                "$field back icon box",
                editBack.compoundDrawables[0]!!.intrinsicWidth,
                button.compoundDrawables[0]!!.intrinsicWidth,
            )
            assertEquals("$field back icon gap", editBack.compoundDrawablePadding, button.compoundDrawablePadding)
            assertEquals("$field title", ctx.getString(title), panel.titleForTest().text.toString())
            assertEquals(
                "$field added section",
                ctx.getString(R.string.csp_section_added),
                panel.addedSectionLabelForTest().text.toString(),
            )
            assertEquals(
                "$field palette section",
                ctx.getString(paletteTitle),
                panel.paletteSectionLabelForTest().text.toString(),
            )
            titles.add(panel.titleForTest().text.toString())
        }
        assertNotEquals("each custom page names its own object", titles[0], titles[1])
    }

    private fun topBarOf(clipboard: ClipboardView): HorizontalScrollView {
        fun find(view: View): HorizontalScrollView? {
            if (view is HorizontalScrollView) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return requireNotNull(find(clipboard.fixedChromeViewsForTest().first()))
    }

    private fun topBarSpacer(content: View): View =
        (0 until (content as ViewGroup).childCount)
            .map { content.getChildAt(it) }
            .single { (it.layoutParams as LinearLayout.LayoutParams).weight > 0f }

    private fun topBarTextViews(bar: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(bar)
        return out
    }

    private fun topBarTargets(bar: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.hasOnClickListeners()) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(bar)
        return out
    }

    private fun boundsIn(root: View, target: View): Rect {
        val rect = Rect(0, 0, target.width, target.height)
        var current: View = target
        while (current !== root) {
            val parent = current.parent as View
            rect.offset(current.left - parent.scrollX, current.top - parent.scrollY)
            current = parent
        }
        return rect
    }

    private fun hasAncestor(target: View, ancestorType: Class<out View>): Boolean {
        var current = target.parent
        while (current is View) {
            if (ancestorType.isInstance(current)) return true
            current = current.parent
        }
        return false
    }

    private fun clipboardView(phrase: Boolean): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { clipEntries("clip") }
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { listOf("phrase") }
        if (phrase) showPhraseTab("默认") else refresh()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun the_clipboard_keeps_back_fixed_and_named_while_only_the_remaining_toolbar_scrolls() {
        try {
            for (scale in listOf(1f, 2f)) {
                RuntimeEnvironment.setFontScale(scale)
                for (widthDp in listOf(320, 280)) {
                    for (phrase in listOf(false, true)) {
                        val clipboard = clipboardView(phrase)
                        layout(clipboard, width = dp(widthDp), height = dp(400))
                        val name = "${widthDp}dp x$scale ${if (phrase) "phrases" else "clipboard"}"
                        val bar = topBarOf(clipboard)
                        val content = bar.getChildAt(0)
                        val back = backControls(clipboard).single()
                        val fixedBounds = boundsIn(clipboard, back)
                        assertEquals("$name keeps the visible back label", ctx.getString(R.string.clip_back), back.text.toString())
                        assertEquals("$name back stays at the shared edge inset", (com.aegis.ime.ime.theme.ImeShapes.edgeInsetDp * density).toInt(), fixedBounds.left)
                        assertFalse(
                            "$name back must stay outside the horizontal scroller",
                            hasAncestor(back, HorizontalScrollView::class.java),
                        )
                        assertTrue("$name keeps the back glyph", back.compoundDrawables[0] != null)
                        assertTrue("$name keeps the back control tappable", back.hasOnClickListeners())
                        if (bar.canScrollHorizontally(1)) {
                            assertEquals("$name flexible gap gives up its room before scrolling", 0, topBarSpacer(content).width)
                        }
                        val last = topBarTargets(content).last()
                        bar.scrollTo(content.width, 0)
                        val visible = boundsIn(clipboard, last)
                        val viewport = boundsIn(clipboard, bar)
                        assertTrue(
                            "$name last scrolling target must be reachable: $visible in $viewport",
                            visible.left >= viewport.left && visible.right <= viewport.right,
                        )
                        assertEquals("$name scrolling must not move back", fixedBounds, boundsIn(clipboard, back))
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun the_back_control_keeps_its_touch_target_however_narrow_the_panel_gets() {
        for (widthDp in listOf(411, 360, 340, 320, 300, 280)) {
            for (phrase in listOf(false, true)) {
                val clipboard = clipboardView(phrase)
                layout(clipboard, width = dp(widthDp), height = dp(400))
                val back = backControls(clipboard).single()
                val name = "${widthDp}dp ${if (phrase) "phrases" else "clipboard"}"
                assertTrue(
                    "$name back control is ${back.width / density}dp wide," +
                        " under the ${PanelBackButton.HIT_DP}dp touch target",
                    back.width >= dp(PanelBackButton.HIT_DP),
                )
                assertTrue(
                    "$name back control is ${back.height / density}dp tall," +
                        " under the ${PanelBackButton.HIT_DP}dp touch target",
                    back.height >= dp(PanelBackButton.HIT_DP),
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun the_clipboard_tabs_keep_their_labels_inside_their_pills() {
        val labels = listOf(ctx.getString(R.string.clip_clipboard), ctx.getString(R.string.clip_phrases))
        for (phrase in listOf(false, true)) {
            val clipboard = clipboardView(phrase)
            layout(clipboard, width = dp(360), height = dp(400))
            val name = if (phrase) "phrases" else "clipboard"
            val content = topBarOf(clipboard).getChildAt(0)
            for (label in labels) {
                val pill = topBarTextViews(content).single { it.text.toString() == label }
                val needed = pill.paint.measureText(label)
                val available = (pill.width - pill.paddingLeft - pill.paddingRight).toFloat()
                assertTrue(
                    "$name tab '$label' must fit its pill: needs $needed in $available",
                    needed <= available,
                )
                assertEquals("$name tab '$label' must stay on one line", 1, pill.lineCount)
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun the_clipboard_tabs_keep_their_labels_inside_their_pills_at_every_system_font_scale() {
        try {
            for (locale in listOf("+en-rUS", "+zh-rCN")) {
                RuntimeEnvironment.setQualifiers(locale)
                val labels = listOf(ctx.getString(R.string.clip_clipboard), ctx.getString(R.string.clip_phrases))
                for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
                    RuntimeEnvironment.setFontScale(scale)
                    assertEquals(
                        "precondition: the system font must be at $scale",
                        scale,
                        ctx.resources.configuration.fontScale,
                        0.001f,
                    )
                    for (phrase in listOf(false, true)) {
                        val clipboard = clipboardView(phrase)
                        layout(clipboard, width = dp(360), height = dp(400))
                        val content = topBarOf(clipboard).getChildAt(0)
                        for (label in labels) {
                            val pill = topBarTextViews(content).single { it.text.toString() == label }
                            val available = (pill.width - pill.paddingLeft - pill.paddingRight).toFloat()
                            val drawn = pill.paint.measureText(label)
                            val authored = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP,
                                ImeType.body,
                                ctx.resources.displayMetrics,
                            )
                            val oneStepLarger = Paint(pill.paint).apply { textSize = pill.textSize + density }
                            val name = "$locale at x$scale tab '$label'"
                            assertEquals("$name must stay on one line", 1, pill.lineCount)
                            assertTrue("$name must fit its pill: needs $drawn in $available", drawn <= available)
                            assertTrue(
                                "$name is drawn at ${pill.textSize}px, larger than the ${authored}px the panel asks for",
                                pill.textSize <= authored + 0.01f,
                            )
                            assertTrue(
                                "$name is drawn at ${pill.textSize}px although a larger size still fits its $available pill",
                                pill.textSize >= authored - 0.01f ||
                                    oneStepLarger.measureText(label) > available,
                            )
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("+en-rUS")
        }
    }

    @Test fun the_clipboard_top_bar_keeps_a_flexible_gap_when_48dp_actions_fit_without_scrolling() {
        val clipboard = clipboardView(phrase = true)
        layout(clipboard, width = dp(411), height = dp(400))
        val bar = topBarOf(clipboard)
        val content = bar.getChildAt(0)

        assertFalse("411dp needs no horizontal scrolling", bar.canScrollHorizontally(1))
        assertEquals("411dp top bar content fills the viewport", bar.width, content.width)
        assertTrue(
            "411dp keeps spacing between the fixed back control and the tab pills while preserving 48dp actions",
            topBarSpacer(content).width >= dp(8),
        )
    }

    @Test fun the_clipboard_back_control_carries_the_back_label() {
        for (phrase in listOf(false, true)) {
            val clipboard = clipboardView(phrase)
            layout(clipboard)
            val name = if (phrase) "phrases" else "clipboard"
            val button = backControls(clipboard).single()
            assertEquals("$name back label", ctx.getString(R.string.clip_back), button.text.toString())
            assertEquals("$name back label stays on one line", 1, button.maxLines)
            assertTrue("$name back label must be clickable", button.hasOnClickListeners())
        }
    }
}
