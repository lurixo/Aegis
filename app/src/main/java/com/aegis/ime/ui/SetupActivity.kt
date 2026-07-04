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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AegisTheme

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
                    SettingsNavGraph(resumeSignal = resumeSignal)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeSignal += 1
    }
}

internal object SettingsRoutes {
    const val HOME = "home"
    const val INPUT = "input"
    const val DICTS = "dicts"
    const val USER_DICT = "userdict"
    const val ABOUT = "about"

    val GROUPS = listOf(INPUT, DICTS, USER_DICT, ABOUT)
}

@Composable
internal fun SettingsNavGraph(
    resumeSignal: Int = 0,
    navController: NavHostController = rememberNavController(),
) {
    val openGroup: (String) -> Unit = { route ->
        if (navController.currentDestination?.route == SettingsRoutes.HOME) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    val back: () -> Unit = {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }
    NavHost(navController = navController, startDestination = SettingsRoutes.HOME) {
        composable(SettingsRoutes.HOME) {
            SettingsHomePage(onOpenGroup = openGroup)
        }
        composable(SettingsRoutes.INPUT) {
            InputSettingsPage(onBack = back)
        }
        composable(SettingsRoutes.DICTS) {
            DictSettingsPage(onBack = back)
        }
        composable(SettingsRoutes.USER_DICT) {
            UserDictPage(onBack = back)
        }
        composable(SettingsRoutes.ABOUT) {
            AboutPage(resumeSignal = resumeSignal, onBack = back)
        }
    }
}

@Composable
private fun SettingsHomePage(onOpenGroup: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

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
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.setup_summary),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showDownloadHint) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.setup_first_run_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.setup_first_run_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            showDownloadHint = false
                            prefs.edit { putBoolean("dl_hint_dismissed", true) }
                        },
                    ) { Text(stringResource(R.string.setup_first_run_ack)) }
                }
            }
        }

        SettingsGroupCard(
            titleRes = R.string.settings_group_input_title,
            descRes = R.string.settings_group_input_desc,
            onClick = { onOpenGroup(SettingsRoutes.INPUT) },
        )
        SettingsGroupCard(
            titleRes = R.string.settings_group_dicts_title,
            descRes = R.string.settings_group_dicts_desc,
            onClick = { onOpenGroup(SettingsRoutes.DICTS) },
        )
        SettingsGroupCard(
            titleRes = R.string.settings_group_userdict_title,
            descRes = R.string.settings_group_userdict_desc,
            onClick = { onOpenGroup(SettingsRoutes.USER_DICT) },
        )
        SettingsGroupCard(
            titleRes = R.string.settings_group_about_title,
            descRes = R.string.settings_group_about_desc,
            onClick = { onOpenGroup(SettingsRoutes.ABOUT) },
        )
    }
}

@Composable
private fun SettingsGroupCard(titleRes: Int, descRes: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.settings_back)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backLabel },
        ) {
            Text("‹", style = MaterialTheme.typography.headlineMedium)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun SettingsPageColumn(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .settingsScrollInsets(
                scrollState = rememberScrollState(),
                insets = WindowInsets.safeDrawing,
            )
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsPageHeader(title, onBack)
        content()
    }
}

@Composable
private fun InputSettingsPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.settings_group_input_title), onBack) {
        LayoutChoiceCard()
        FuzzySettingsCard()
        AssociationToggleCard()
    }
}

@Composable
private fun DictSettingsPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.settings_group_dicts_title), onBack) {
        DictDownloadCard()
        GramDownloadCard()
    }
}

@Composable
private fun AboutPage(resumeSignal: Int, onBack: () -> Unit) {
    val context = LocalContext.current
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

    SettingsPageColumn(stringResource(R.string.settings_group_about_title), onBack) {
        AppVersionCard()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.setup_steps_title), style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_enable_button)) }
                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_switch_button)) }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.setup_try_field_label)) },
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
    }
}

internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    insets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(insets)
    .verticalScroll(scrollState)

private val IME_SHOW_RETRY_DELAYS_MS = longArrayOf(
    0L, 50L, 100L, 150L, 225L, 300L, 400L, 500L, 650L, 800L, 950L, 1_100L,
)

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
