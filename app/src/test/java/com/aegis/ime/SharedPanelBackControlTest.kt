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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.PanelBackButton
import com.aegis.ime.ime.theme.ImePalette
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
import org.robolectric.annotation.GraphicsMode

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

    private fun backControls(root: View): List<PanelBackButton> {
        val out = ArrayList<PanelBackButton>()
        fun walk(v: View) {
            if (v is PanelBackButton) out.add(v)
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
            assertTrue("$field uses the shared back control", button is PanelBackButton)
            assertTrue("$field back hit width", button.width >= dp(48))
            assertTrue("$field back hit height", button.height >= dp(48))
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

    private fun topBarOf(clipboard: ClipboardView): HorizontalScrollView =
        clipboard.fixedChromeViewsForTest().first() as HorizontalScrollView

    private fun topBarSpacer(content: View): View =
        (0 until (content as ViewGroup).childCount)
            .map { content.getChildAt(it) }
            .single { (it.layoutParams as LinearLayout.LayoutParams).weight > 0f }

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

    private fun clipboardView(phrase: Boolean): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { listOf("clip") }
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { listOf("phrase") }
        if (phrase) showPhraseTab("默认") else refresh()
    }

    private fun assertTopBarTargetsStayReachable(widthDp: Int, phrase: Boolean) {
        val clipboard = clipboardView(phrase)
        layout(clipboard, width = dp(widthDp), height = dp(400))
        val name = "${widthDp}dp ${if (phrase) "phrases" else "clipboard"}"
        val bar = topBarOf(clipboard)
        val content = bar.getChildAt(0)
        val targets = topBarTargets(content)
        assertTrue("$name exposes top bar targets", targets.size >= 4)

        for (target in targets) {
            val inContent = Rect(0, 0, target.width, target.height).also { rect ->
                var current: View = target
                while (current !== content) {
                    rect.offset(current.left, current.top)
                    current = current.parent as View
                }
            }
            assertTrue("$name target is laid out", target.width > 0 && target.height > 0)
            assertTrue(
                "$name target must stay inside the top bar content: $inContent width=${content.width}",
                inContent.left >= 0 && inContent.right <= content.width,
            )
        }

        if (content.width <= bar.width) {
            assertEquals("$name top bar content must fill the viewport", bar.width, content.width)
            assertTrue("$name flexible gap must expand with the viewport", topBarSpacer(content).width > 0)
        } else {
            assertEquals("$name flexible gap collapses before anything is dropped", 0, topBarSpacer(content).width)
        }

        val last = targets.last()
        bar.scrollTo(content.width, 0)
        val visible = boundsIn(clipboard, last)
        val viewport = boundsIn(clipboard, bar)
        assertTrue(
            "$name last target must be fully visible after scrolling to the end: $visible in $viewport",
            visible.left >= viewport.left && visible.right <= viewport.right,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun the_clipboard_top_bar_fits_without_scrolling_at_three_hundred_sixty_dp() {
        assertTopBarTargetsStayReachable(360, phrase = false)
        assertTopBarTargetsStayReachable(360, phrase = true)
        val clipboard = clipboardView(phrase = true)
        layout(clipboard, width = dp(360), height = dp(400))
        val bar = topBarOf(clipboard)
        assertFalse("360dp still fits the whole top bar", bar.canScrollHorizontally(1))
        assertEquals("360dp top bar content fills the viewport", bar.width, bar.getChildAt(0).width)
    }

    @Test fun the_clipboard_top_bar_keeps_its_flexible_gap_when_the_window_is_wide() {
        val clipboard = clipboardView(phrase = true)
        layout(clipboard, width = dp(411), height = dp(400))
        val bar = topBarOf(clipboard)
        val content = bar.getChildAt(0)

        assertFalse("411dp needs no horizontal scrolling", bar.canScrollHorizontally(1))
        assertEquals("411dp top bar content fills the viewport", bar.width, content.width)
        assertTrue(
            "411dp keeps a real flexible gap between back and the tab pills",
            topBarSpacer(content).width >= dp(40),
        )
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun the_clipboard_top_bar_keeps_every_target_reachable_at_three_hundred_twenty_dp() {
        assertTopBarTargetsStayReachable(320, phrase = false)
        assertTopBarTargetsStayReachable(320, phrase = true)
    }

    @Test fun clipboard_and_phrase_pages_use_the_same_back_control_geometry() {
        val clipboard = clipboardView(phrase = false)
        val custom = CustomSymbolPanel(ctx).apply { refresh() }
        layout(custom)

        for (phrase in listOf(false, true)) {
            if (phrase) clipboard.showPhraseTab("默认") else clipboard.refresh()
            layout(clipboard)
            val name = if (phrase) "phrases" else "clipboard"
            val button = backControls(clipboard).single()
            assertEquals("$name back hit width", dp(48), button.width)
            assertEquals("$name back hit height", dp(48), button.height)
            assertEquals(
                "$name back matches the custom pages",
                custom.backButtonForTest().width to custom.backButtonForTest().height,
                button.width to button.height,
            )
            assertEquals(
                "$name back keeps the shared left inset",
                dp(8),
                (button.parent as View).paddingLeft,
            )
        }
    }

    private fun inkBounds(view: View): Rect {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y).ushr(24) == 0) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return Rect(left, top, right + 1, bottom + 1)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun the_shared_back_control_draws_a_twenty_four_dp_icon_box_inside_its_hit_target() {
        val button = PanelBackButton(ctx).apply { tint = ImePalette.STATIC_LIGHT.keyLabel }
        button.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        button.layout(0, 0, button.measuredWidth, button.measuredHeight)

        assertEquals("shared back control hit width", dp(48), button.width)
        assertEquals("shared back control hit height", dp(48), button.height)

        val ink = inkBounds(button)
        assertTrue("shared back control must draw its glyph", ink.width() > 0 && ink.height() > 0)
        val inkWidthDp = ink.width() / density
        val inkHeightDp = ink.height() / density
        assertEquals("back glyph ink width in dp", 14.67f, inkWidthDp, 0.75f)
        assertEquals("back glyph ink height in dp", 18.67f, inkHeightDp, 0.75f)
        assertTrue(
            "back glyph must stay inside a 24dp icon box: ${inkWidthDp}dp by ${inkHeightDp}dp",
            inkWidthDp <= 24f && inkHeightDp <= 24f,
        )
        assertEquals("back glyph ink center x", button.width / 2f, ink.exactCenterX(), 1f)
        assertEquals("back glyph ink center y", button.height / 2f, ink.exactCenterY(), 1f)
        assertEquals("shared back control icon box in dp", 24, PanelBackButton.ICON_DP)
    }
}
