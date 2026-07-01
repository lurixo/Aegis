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

package com.aegis.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.ui.theme.AegisTheme
import kotlinx.coroutines.delay

/** Landing screen: enable the IME, switch to it, and a field to try typing. */
class SetupActivity : ComponentActivity() {
    private var resumeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SetupScreen(resumeSignal = resumeSignal)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal += 1
    }
}

@Composable
private fun SetupScreen(resumeSignal: Int = 0) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var typed by remember { mutableStateOf("") }
    var tryFieldFocused by remember { mutableStateOf(false) }
    var tryFieldImeRequest by remember { mutableIntStateOf(0) }
    var activeTryFieldImeRequest by remember { mutableIntStateOf(0) }
    val tryFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    val latestTryFieldFocused by rememberUpdatedState(tryFieldFocused)
    val latestTryFieldImeRequest by rememberUpdatedState(tryFieldImeRequest)
    val latestActiveTryFieldImeRequest by rememberUpdatedState(activeTryFieldImeRequest)
    val activeImeRequest = remember { ImeRequestHolder() }

    fun isTryFieldImeRequestActive(requestToken: Int): Boolean {
        val isCurrentTap = requestToken != 0 && latestActiveTryFieldImeRequest == requestToken
        return latestTryFieldImeRequest == requestToken && (latestTryFieldFocused || isCurrentTap)
    }

    DisposableEffect(
        hostView,
        tryFieldFocused,
        resumeSignal,
        tryFieldImeRequest,
        activeTryFieldImeRequest,
    ) {
        onDispose { activeImeRequest.cancel() }
    }

    LaunchedEffect(
        hostView,
        tryFieldFocused,
        resumeSignal,
        tryFieldImeRequest,
        activeTryFieldImeRequest,
    ) {
        val requestToken = tryFieldImeRequest
        if (!isTryFieldImeRequestActive(requestToken)) return@LaunchedEffect
        delay(50)
        if (!isTryFieldImeRequestActive(requestToken)) return@LaunchedEffect
        activeImeRequest.replace(
            hostView.requestImeWhenReady(
                context,
                focusTarget = {
                    tryFieldFocusRequester.requestFocus()
                    keyboardController?.show()
                },
                shouldContinue = {
                    isTryFieldImeRequestActive(requestToken)
                },
            ),
        )
    }

    // B3: a one-time, non-blocking first-run hint that the optional downloads exist (the seed dict + base
    // grammar already work offline, so this never blocks typing). Dismissed for good once acknowledged.
    var showDownloadHint by remember { mutableStateOf(!prefs.getBoolean("dl_hint_dismissed", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // debug.16: pad the scroll VIEWPORT with the full safe-drawing insets (system bars + cutout + IME)
            // OUTSIDE the scroll, so the viewport shrinks to the keyboard top — keeping content below the status
            // bar and letting the focused "试打" field be brought above the keyboard (see settingsScrollInsets).
            .settingsScrollInsets(
                scrollState = rememberScrollState(),
                insets = WindowInsets.safeDrawing,
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Aegis 输入法", style = MaterialTheme.typography.headlineMedium)
        Text(
            "离线中文 / 英文输入法。自建拼音引擎（全拼 + 九宫格 T9），模糊拼音、简拼、中英混输、" +
                "英文补全纠错、离线自学习；可选下载万象大模型增强。全程离线，输入不联网。",
            style = MaterialTheme.typography.bodyMedium,
        )

        // B3 (debug.13): one-time, non-blocking hint that the optional downloads exist — never a dialog.
        if (showDownloadHint) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("首次使用", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "下方可选下载【增强模型】与【全量词库】(都不是必须的)。内置种子词库与基础语法已能离线打字," +
                            "想要更准/更全时再下载即可。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            showDownloadHint = false
                            prefs.edit { putBoolean("dl_hint_dismissed", true) }
                        },
                    ) { Text("知道了") }
                }
            }
        }

        // debug.13 下载模块顺序: 增强模型(B1, 上) → 全量词库(B2, 下) → 模糊拼音 → 联想(D1) → 9键/26键。
        // The cards are each in their own file so the B-order work and the model/dict/fuzzy work don't collide.
        GramDownloadCard()   // B1 模型(.gram) — reused as-is, on top
        DictDownloadCard()   // B2 全量词库包 — below the model,独立 download / 更新检测 (B5)
        FuzzySettingsCard()
        AssociationToggleCard() // D1 联想开关 (UI + pref; KeyboardController D2 reads it)
        LayoutChoiceCard()
        // debug.16 Option A: the separate 常用语管理 Activity is gone — categories/phrases are now managed
        // fully inline in the clipboard panel's 常用语 tab (＋ / ✎ / 长按 chip / 长按卡拖动 / 展开卡 编辑·移动·删除).

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("启用步骤", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("1 · 在系统设置中启用 Aegis") }
                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("2 · 切换到 Aegis 输入法") }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("3 · 在此试打") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(tryFieldFocusRequester)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        tryFieldImeRequest += 1
                        activeTryFieldImeRequest = tryFieldImeRequest
                    }
                }
                .onFocusChanged {
                    tryFieldFocused = it.isFocused
                    if (!it.isFocused) activeTryFieldImeRequest = 0
                },
        )

        UserDictCard()
    }
}

/**
 * debug.16: inset modifier for the edge-to-edge settings scroller. [insets] (WindowInsets.safeDrawing =
 * system bars + display cutout + IME) pad the scroll container OUTSIDE the verticalScroll, so the scroll
 * VIEWPORT shrinks to the keyboard top. This keeps the top content below the status bar and the nav bar
 * unoccluded, and — crucially — because the viewport excludes the keyboard, the scroll's bring-into-view lifts
 * the focused 试打 field ABOVE the keyboard when it gains focus (with windowSoftInputMode=adjustResize stopping
 * the window from panning under the status bar). There is no leftover blank: the IME inset collapses when the
 * keyboard hides.
 *
 * The IME inset MUST stay OUTSIDE the scroll. Placing it inside (as trailing content padding) only grows the
 * scroll RANGE without shrinking the viewport, so the focused field is never auto-lifted and can sit hidden
 * behind the keyboard. [insets] is a parameter so a Robolectric test can drive it deterministically.
 */
internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    insets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(insets)
    .verticalScroll(scrollState)

private val IME_SHOW_RETRY_DELAYS_MS = longArrayOf(0L, 75L, 150L, 300L, 600L, 900L, 1200L, 1800L)

private class ImeRequestHolder {
    private var current: ImeRequestHandle? = null

    fun replace(next: ImeRequestHandle) {
        current?.cancel()
        current = next
    }

    fun cancel() {
        current?.cancel()
        current = null
    }
}

internal fun interface ImeRequestHandle {
    fun cancel()
}

private class ImeRequestState(
    private val shouldContinue: () -> Boolean,
) : ImeRequestHandle {
    private val cleanups = ArrayList<() -> Unit>()
    private var finished = false

    fun isActive(): Boolean = !finished && shouldContinue()

    fun addCleanup(cleanup: () -> Unit) {
        if (finished) {
            cleanup()
        } else {
            cleanups.add(cleanup)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        val pendingCleanups = cleanups.toList().asReversed()
        cleanups.clear()
        pendingCleanups.forEach { it() }
    }

    override fun cancel() {
        finish()
    }
}

internal fun View.requestImeWhenReady(
    context: Context = this.context,
    focusTarget: () -> Unit,
    showSoftInput: (View) -> Boolean = { target ->
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    },
    restartInput: (View) -> Unit = { target ->
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(target)
    },
    isReady: View.() -> Boolean = { isAttachedToWindow && hasWindowFocus() },
    isImeVisible: View.() -> Boolean = {
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    },
    shouldContinue: () -> Boolean = { true },
    retryDelaysMs: LongArray = IME_SHOW_RETRY_DELAYS_MS,
): ImeRequestHandle {
    val state = ImeRequestState(shouldContinue)
    requestImeWhenReady(
        context,
        focusTarget,
        showSoftInput,
        restartInput,
        isReady,
        isImeVisible,
        retryDelaysMs,
        state,
    )
    return state
}

private fun View.requestImeWhenReady(
    context: Context,
    focusTarget: () -> Unit,
    showSoftInput: (View) -> Boolean,
    restartInput: (View) -> Unit,
    isReady: View.() -> Boolean,
    isImeVisible: View.() -> Boolean,
    retryDelaysMs: LongArray,
    state: ImeRequestState,
) {
    if (!state.isActive()) {
        state.finish()
        return
    }
    if (isReady()) {
        val requestFocus = Runnable {
            if (!state.isActive() || !isAttachedToWindow) {
                state.finish()
                return@Runnable
            }
            focusTarget()
            if (!state.isActive() || !isAttachedToWindow) {
                state.finish()
                return@Runnable
            }
            showImeForFocusedViewWhenReady(
                showSoftInput,
                restartInput,
                isReady,
                isImeVisible,
                retryDelaysMs,
                state,
            )
        }
        state.addCleanup { removeCallbacks(requestFocus) }
        post(requestFocus)
        return
    }
    if (!isAttachedToWindow) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (!state.isActive()) {
                    state.finish()
                    return
                }
                v.requestImeWhenReady(
                    context,
                    focusTarget,
                    showSoftInput,
                    restartInput,
                    isReady,
                    isImeVisible,
                    retryDelaysMs,
                    state,
                )
            }
            override fun onViewDetachedFromWindow(v: View) {
                state.finish()
            }
        }
        addOnAttachStateChangeListener(listener)
        state.addCleanup { removeOnAttachStateChangeListener(listener) }
        return
    }
    if (!hasWindowFocus()) {
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!state.isActive()) {
                    state.finish()
                    return
                }
                if (!hasFocus) return
                val observer = viewTreeObserver
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                requestImeWhenReady(
                    context,
                    focusTarget,
                    showSoftInput,
                    restartInput,
                    isReady,
                    isImeVisible,
                    retryDelaysMs,
                    state,
                )
            }
        }
        viewTreeObserver.addOnWindowFocusChangeListener(listener)
        state.addCleanup {
            val observer = viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}

private fun View.showImeForFocusedViewWhenReady(
    showSoftInput: (View) -> Boolean,
    restartInput: (View) -> Unit,
    isReady: View.() -> Boolean,
    isImeVisible: View.() -> Boolean,
    retryDelaysMs: LongArray,
    state: ImeRequestState,
) {
    var listener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    var retry: Runnable? = null

    fun focusedImeTarget(): View? =
        rootView.findFocus()?.takeIf {
            state.isActive() &&
                isAttachedToWindow &&
                isReady() &&
                it.isAttachedToWindow &&
                it.windowToken != null &&
                it.isShown &&
                it.isFocused
        }

    fun removeListener() {
        val current = viewTreeObserver
        val active = listener ?: return
        if (current.isAlive) current.removeOnGlobalFocusChangeListener(active)
        listener = null
    }

    fun cancelRetry() {
        retry?.let { removeCallbacks(it) }
        retry = null
    }

    fun finish() {
        removeListener()
        cancelRetry()
        state.finish()
    }

    if (!state.isActive() || !isAttachedToWindow) {
        finish()
        return
    }

    val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            finish()
        }
    }
    addOnAttachStateChangeListener(detachListener)
    state.addCleanup { removeOnAttachStateChangeListener(detachListener) }

    lateinit var scheduleAttempt: (Int) -> Unit

    fun attempt(index: Int) {
        if (!state.isActive() || !isAttachedToWindow) {
            finish()
            return
        }
        val target = focusedImeTarget()
        if (target == null) {
            scheduleAttempt(index + 1)
            return
        }
        val showAttempt = Runnable {
            if (!state.isActive() || !isAttachedToWindow) {
                finish()
                return@Runnable
            }
            val current = focusedImeTarget()
            if (current == null) {
                scheduleAttempt(index + 1)
                return@Runnable
            }
            if (current.isImeVisible()) {
                finish()
                return@Runnable
            }
            restartInput(current)
            showSoftInput(current)
            if (current.isImeVisible()) finish() else scheduleAttempt(index + 1)
        }
        state.addCleanup { target.removeCallbacks(showAttempt) }
        target.post(showAttempt)
    }

    scheduleAttempt = attemptScheduler@{ index ->
        if (!state.isActive() || !isAttachedToWindow) {
            finish()
            return@attemptScheduler
        }
        if (retry != null) return@attemptScheduler
        if (index >= retryDelaysMs.size) {
            finish()
            return@attemptScheduler
        }
        val r = Runnable {
            retry = null
            attempt(index)
        }
        retry = r
        state.addCleanup { removeCallbacks(r) }
        val delayMs = retryDelaysMs[index]
        if (delayMs <= 0L) post(r) else postDelayed(r, delayMs)
    }

    val observer = viewTreeObserver
    if (!observer.isAlive) {
        finish()
        return
    }

    val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        scheduleAttempt(0)
    }
    listener = focusListener
    observer.addOnGlobalFocusChangeListener(focusListener)
    state.addCleanup {
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(focusListener)
    }
    scheduleAttempt(0)
}
