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
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.ui.theme.SettingsMotion
import androidx.compose.foundation.layout.size

internal object SettingsRoutes {
    const val INPUT = "input"
    const val KEYBOARD = "keyboard"
    const val DICTS = "dicts"
    const val USER_DICT = "userdict"
    const val BACKUP = "backup"
    const val ABOUT = "about"

    val GROUPS = listOf(INPUT, KEYBOARD, DICTS, USER_DICT, BACKUP, ABOUT)
}

@Composable
internal fun SettingsHomePage(onOpenGroup: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    var showDownloadHint by remember { mutableStateOf(!prefs.flagOr("dl_hint_dismissed", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .settingsScrollInsets(
                scrollState = rememberScrollState(),
                bottomInsets = WindowInsets.safeDrawing,
                topInsets = settingsTopInset(),
            )
            .padding(horizontal = AppSpacing.screenHorizontal)
            .padding(top = AppSpacing.screenHorizontal, bottom = AppSpacing.pageBottom),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.setup_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(
            visible = showDownloadHint,
            enter = SettingsMotion.revealEnter(),
            exit = SettingsMotion.collapseExit(),
        ) {
            AppSection {
                Column(
                    modifier = Modifier.padding(AppSpacing.sectionPadding),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
                ) {
                    Text(stringResource(R.string.setup_first_run_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.setup_first_run_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        modifier = Modifier.align(Alignment.End).testTag("setup_first_run_ack"),
                        onClick = {
                            showDownloadHint = false
                            prefs.edit { putBoolean("dl_hint_dismissed", true) }
                        },
                        shape = MaterialTheme.shapes.extraSmall,
                    ) { Text(stringResource(R.string.setup_first_run_ack)) }
                }
            }
        }

        AppSection {
            SettingsGroupRow(
                titleRes = R.string.settings_group_input_title,
                descRes = R.string.settings_group_input_desc,
                onClick = { onOpenGroup(SettingsRoutes.INPUT) },
            )
            AppSectionDivider()
            SettingsGroupRow(
                titleRes = R.string.settings_group_keyboard_title,
                descRes = R.string.settings_group_keyboard_desc,
                onClick = { onOpenGroup(SettingsRoutes.KEYBOARD) },
            )
            AppSectionDivider()
            SettingsGroupRow(
                titleRes = R.string.settings_group_dicts_title,
                descRes = R.string.settings_group_dicts_desc,
                onClick = { onOpenGroup(SettingsRoutes.DICTS) },
            )
            AppSectionDivider()
            SettingsGroupRow(
                titleRes = R.string.settings_group_userdict_title,
                descRes = R.string.settings_group_userdict_desc,
                onClick = { onOpenGroup(SettingsRoutes.USER_DICT) },
            )
            AppSectionDivider()
            SettingsGroupRow(
                titleRes = R.string.settings_backup_title,
                descRes = R.string.settings_backup_desc,
                onClick = { onOpenGroup(SettingsRoutes.BACKUP) },
            )
            AppSectionDivider()
            SettingsGroupRow(
                titleRes = R.string.settings_group_about_title,
                descRes = R.string.settings_group_about_desc,
                onClick = { onOpenGroup(SettingsRoutes.ABOUT) },
            )
        }
    }
}

@Composable
private fun SettingsGroupRow(titleRes: Int, descRes: Int, onClick: () -> Unit) =
    AppNavigationRow(
        title = stringResource(titleRes),
        description = stringResource(descRes),
        onClick = onClick,
    )

@Composable
internal fun SettingsPageColumn(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSettingsPage(title = title, onBack = onBack, content = content)
}

@Composable
internal fun KeyboardSettingsPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.settings_group_keyboard_title), onBack) {
        LayoutChoiceCard()
        LetterCaseCard()
        KeySoundCard()
        KeyVibrationToggleCard()
        KeyPreviewCard()
        UiLanguageCard()
    }
}

@Composable
internal fun DictSettingsPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.settings_group_dicts_title), onBack) {
        GramDownloadCard()
        DictDownloadCard()
    }
}

@Composable
internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .background(MaterialTheme.colorScheme.background)
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))
    .verticalScroll(scrollState)

@Composable
internal fun settingsTopInset(): WindowInsets {
    val density = LocalDensity.current
    val liveTop = WindowInsets.systemBars.union(WindowInsets.displayCutout).getTop(density)
    val view = LocalView.current
    val rootTop = rememberRootTopInsetPx(view)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val seedTop = remember(context, configuration) { synchronousTopInsetPx(context) }
    return WindowInsets(top = resolveTopInsetPx(liveTop, seedTop, rootTop))
}

internal fun resolveTopInsetPx(liveTop: Int, seedTop: Int, rootTop: Int?): Int = when {
    liveTop > 0 -> liveTop
    rootTop != null -> rootTop
    else -> seedTop
}

@Composable
private fun rememberRootTopInsetPx(view: View): Int? {
    val rootTop = rootTopInsetPx(view)
    var rootInsetsDelivered by remember(view) { mutableStateOf(rootTop != null) }
    DisposableEffect(view, rootInsetsDelivered) {
        if (rootInsetsDelivered) {
            onDispose {}
        } else {
            val observer = view.viewTreeObserver
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (rootTopInsetPx(view) != null) rootInsetsDelivered = true
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
            ViewCompat.requestApplyInsets(view)
            onDispose {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
    }
    return rootTop
}

private fun rootTopInsetPx(view: View): Int? {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return null
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    return insets.getInsets(types).top
}

private fun synchronousTopInsetPx(context: Context): Int {
    val wm = context.getSystemService(WindowManager::class.java) ?: return 0
    val types = AndroidWindowInsets.Type.systemBars() or AndroidWindowInsets.Type.displayCutout()
    val currentMetrics = wm.currentWindowMetrics
    val currentInsets = currentMetrics.windowInsets
    val visibleTop = currentInsets.getInsets(types).top
    val ignoringVisibilityTop = currentInsets.getInsetsIgnoringVisibility(types).top
    val maximumMetrics = wm.maximumWindowMetrics
    val isAttachedToDisplayTop = currentMetrics.bounds.top <= maximumMetrics.bounds.top
    val maximumIgnoringVisibilityTop = if (isAttachedToDisplayTop) {
        maximumMetrics.windowInsets.getInsetsIgnoringVisibility(types).top
    } else {
        0
    }
    return synchronousTopInsetPx(
        visibleTop = visibleTop,
        ignoringVisibilityTop = ignoringVisibilityTop,
        maximumIgnoringVisibilityTop = maximumIgnoringVisibilityTop,
        isAttachedToDisplayTop = isAttachedToDisplayTop,
    )
}

internal fun synchronousTopInsetPx(
    visibleTop: Int,
    ignoringVisibilityTop: Int,
    maximumIgnoringVisibilityTop: Int,
    isAttachedToDisplayTop: Boolean,
): Int = when {
    visibleTop > 0 -> visibleTop
    ignoringVisibilityTop > 0 -> ignoringVisibilityTop
    !isAttachedToDisplayTop -> 0
    maximumIgnoringVisibilityTop > 0 -> maximumIgnoringVisibilityTop
    else -> 0
}

private val IME_SHOW_RETRY_DELAYS_MS = longArrayOf(
    0L, 50L, 100L, 150L, 225L, 300L, 400L, 500L, 650L, 800L, 950L, 1_100L,
)

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
        imm.showSoftInput(target, 0)
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
