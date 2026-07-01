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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aegis.ime.ui.theme.AegisTheme
import kotlinx.coroutines.delay

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
    val tryFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current

    LaunchedEffect(tryFieldFocused, resumeSignal, tryFieldImeRequest) {
        if (!tryFieldFocused && tryFieldImeRequest == 0) return@LaunchedEffect
        delay(50)
        hostView.requestImeWhenReady(context, focusTarget = {
            tryFieldFocusRequester.requestFocus()
            keyboardController?.show()
        })
    }

    var showDownloadHint by remember { mutableStateOf(!prefs.getBoolean("dl_hint_dismissed", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        GramDownloadCard()
        DictDownloadCard()
        FuzzySettingsCard()
        AssociationToggleCard()
        LayoutChoiceCard()

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
                    }
                }
                .onFocusChanged { tryFieldFocused = it.isFocused },
        )

        UserDictCard()
    }
}

internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    insets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(insets)
    .verticalScroll(scrollState)

private val IME_SHOW_RETRY_DELAYS_MS = longArrayOf(0L, 75L, 150L, 300L, 600L, 900L)

internal fun View.requestImeWhenReady(
    context: Context = this.context,
    focusTarget: () -> Unit,
    showSoftInput: (View) -> Boolean = { target ->
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    },
    isReady: View.() -> Boolean = { isAttachedToWindow && hasWindowFocus() },
    retryDelaysMs: LongArray = IME_SHOW_RETRY_DELAYS_MS,
) {
    if (isReady()) {
        post {
            focusTarget()
            showImeForFocusedViewWhenReady(showSoftInput, isReady, retryDelaysMs)
        }
        return
    }
    if (!isAttachedToWindow) {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.requestImeWhenReady(context, focusTarget, showSoftInput, isReady, retryDelaysMs)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        return
    }
    if (!hasWindowFocus()) {
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                val observer = viewTreeObserver
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                requestImeWhenReady(context, focusTarget, showSoftInput, isReady, retryDelaysMs)
            }
        }
        viewTreeObserver.addOnWindowFocusChangeListener(listener)
    }
}

private fun View.showImeForFocusedViewWhenReady(
    showSoftInput: (View) -> Boolean,
    isReady: View.() -> Boolean,
    retryDelaysMs: LongArray,
) {
    var done = false
    var listener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    var retry: Runnable? = null

    fun focusedImeTarget(): View? =
        rootView.findFocus()?.takeIf { isReady() && it.isAttachedToWindow && it.isFocused }

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
        done = true
        removeListener()
        cancelRetry()
    }

    lateinit var scheduleAttempt: (Int) -> Unit

    fun attempt(index: Int) {
        if (done) return
        val target = focusedImeTarget()
        if (target == null) {
            scheduleAttempt(index + 1)
            return
        }
        target.post {
            if (done) return@post
            val current = focusedImeTarget()
            if (current == null) {
                scheduleAttempt(index + 1)
                return@post
            }
            if (showSoftInput(current)) finish() else scheduleAttempt(index + 1)
        }
    }

    scheduleAttempt = attemptScheduler@{ index ->
        if (done || retry != null) return@attemptScheduler
        if (index >= retryDelaysMs.size) {
            removeListener()
            return@attemptScheduler
        }
        val r = Runnable {
            retry = null
            attempt(index)
        }
        retry = r
        val delayMs = retryDelaysMs[index]
        if (delayMs <= 0L) post(r) else postDelayed(r, delayMs)
    }

    val observer = viewTreeObserver
    if (!observer.isAlive) return

    listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> scheduleAttempt(0) }
    observer.addOnGlobalFocusChangeListener(listener)
    scheduleAttempt(0)
}
