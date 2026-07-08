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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.AnimationUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aegis.ime.ui.theme.AegisTheme

internal val LocalSettingsPressEpoch = compositionLocalOf { 0 }

@Composable
internal fun SettingsActivityChrome(content: @Composable () -> Unit) {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current
    val pageView = LocalView.current
    val page = remember(lifecycleOwner, pageView) { SettingsPressHandoff.Page(lifecycleOwner, pageView) }
    DisposableEffect(page) {
        val release = SettingsPressHandoff.attach(page)
        onDispose { release() }
    }
    AegisTheme(darkTheme = darkTheme) {
        val window = LocalContext.current.findActivity()?.window
        val view = LocalView.current
        val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            window?.syncSettingsBackground(backgroundColor)
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            view.setBackgroundColor(backgroundColor)
            view.rootView.setBackgroundColor(backgroundColor)
        }
        val pressEpoch = page.epoch.intValue
        SideEffect { page.onComposed(pressEpoch) }
        CompositionLocalProvider(LocalSettingsPressEpoch provides pressEpoch) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(bottom = 48.dp),
                    ) {
                        AegisToastOverlay()
                    }
                }
            }
        }
    }
}

internal object SettingsPressHandoff {
    const val COVERED_PRESS_TIMEOUT_MS = 150L
    const val FIRST_FRAME_HOLD_LIMIT_MS = 150L

    private val pages = ArrayList<Page>()

    class Page(val owner: LifecycleOwner, val view: View) : ViewTreeObserver.OnDrawListener {
        val epoch = mutableIntStateOf(0)
        private var dropTarget = -1
        private var dropApplied = false
        private var cleanFrameAt = -1L

        val covered: Boolean
            get() = owner.lifecycle.currentState == Lifecycle.State.STARTED && view.windowVisibility == View.VISIBLE

        fun dropPress() {
            if (dropTarget >= 0) return
            dropTarget = epoch.intValue + 1
            dropApplied = false
            cleanFrameAt = -1L
            epoch.intValue = dropTarget
        }

        fun onComposed(composedEpoch: Int) {
            if (dropTarget < 0 || dropApplied || composedEpoch < dropTarget) return
            dropApplied = true
            view.invalidate()
        }

        override fun onDraw() {
            if (dropApplied && cleanFrameAt < 0) cleanFrameAt = AnimationUtils.currentAnimationTimeMillis()
        }

        fun drewCleanBefore(frameTime: Long): Boolean = cleanFrameAt in 0 until frameTime

        fun onResumed() {
            dropTarget = -1
            dropApplied = false
            cleanFrameAt = -1L
        }
    }

    fun attach(page: Page): () -> Unit {
        val observer = page.view.viewTreeObserver
        val timeout = Runnable { if (page.covered) page.dropPress() }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> page.view.postDelayed(timeout, COVERED_PRESS_TIMEOUT_MS)
                Lifecycle.Event.ON_RESUME -> {
                    page.view.removeCallbacks(timeout)
                    page.onResumed()
                }
                else -> Unit
            }
        }
        val hold = FirstFrameHold(page)
        observer.addOnDrawListener(page)
        observer.addOnPreDrawListener(hold)
        page.owner.lifecycle.addObserver(lifecycleObserver)
        pages.add(page)
        return {
            pages.remove(page)
            page.owner.lifecycle.removeObserver(lifecycleObserver)
            page.view.removeCallbacks(timeout)
            if (observer.isAlive) {
                observer.removeOnDrawListener(page)
                observer.removeOnPreDrawListener(hold)
            }
        }
    }

    private class FirstFrameHold(private val page: Page) : ViewTreeObserver.OnPreDrawListener {
        private var waitingFor: List<Page>? = null
        private var heldSince = 0L

        override fun onPreDraw(): Boolean {
            val frameTime = AnimationUtils.currentAnimationTimeMillis()
            val covered = waitingFor ?: pages.filter { it !== page && it.covered }.also { found ->
                waitingFor = found
                heldSince = SystemClock.uptimeMillis()
                found.forEach(Page::dropPress)
            }
            val released = covered.all { it.drewCleanBefore(frameTime) } ||
                SystemClock.uptimeMillis() - heldSince >= FIRST_FRAME_HOLD_LIMIT_MS
            if (released) page.view.viewTreeObserver.removeOnPreDrawListener(this)
            return released
        }
    }
}

internal fun Window.syncSettingsBackground(backgroundColor: Int) {
    setBackgroundDrawable(ColorDrawable(backgroundColor))
    decorView.setBackgroundColor(backgroundColor)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
