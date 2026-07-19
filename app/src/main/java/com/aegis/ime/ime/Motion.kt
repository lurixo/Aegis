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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.aegis.ime.ime.theme.ImeShapes
import java.util.WeakHashMap
import kotlin.math.roundToInt

object Motion {
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val STANDARD_DECEL: Interpolator = PathInterpolator(0f, 0f, 0f, 1f)

    const val SHORT1 = 50L
    const val SHORT2 = 100L

    const val PRESS_IN = SHORT1
    const val PRESS_OUT = SHORT2
    const val STATE_CHANGE = SHORT2

    const val COVER_HOLD = SHORT1

    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun stateLayerColor(argb: Int, level: Float, maxAlpha: Int = 0x22): Int =
        withAlpha(argb, (maxAlpha * level.coerceIn(0f, 1f)).roundToInt())

    fun applyTapFeedback(view: View, color: Int, alpha: Int = 0x24, radiusDp: Float = ImeShapes.keyRadiusDp) {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusDp * view.resources.displayMetrics.density
        }
        view.foreground = RippleDrawable(
            ColorStateList.valueOf(withAlpha(color, alpha)),
            null,
            mask,
        )
    }

    fun showNow(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.invalidate()
    }

    fun hideNow(view: View, endVisibility: Int = View.GONE, endAction: (() -> Unit)? = null) {
        view.animate().cancel()
        view.visibility = endVisibility
        reset(view)
        endAction?.invoke()
    }

    private val coverAnimators = WeakHashMap<View, ValueAnimator>()

    fun snapshot(view: View, backdrop: Int): Bitmap? {
        if (!view.isAttachedToWindow || !enabled()) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(backdrop)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        return bitmap
    }

    fun coverWith(host: View, snapshot: Bitmap?) {
        cancelCover(host)
        if (snapshot == null) return
        if (!host.isAttachedToWindow || !enabled()) {
            snapshot.recycle()
            return
        }
        val drawable = BitmapDrawable(host.resources, snapshot).apply {
            alpha = 255
            setBounds(host.scrollX, host.scrollY, host.scrollX + snapshot.width, host.scrollY + snapshot.height)
        }
        host.overlay.add(drawable)
        coverAnimators[host] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COVER_HOLD
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.overlay.remove(drawable)
                    if (coverAnimators[host] === animation) coverAnimators.remove(host)
                    snapshot.recycle()
                }
            })
            start()
        }
    }

    fun coverSwap(incoming: View, outgoing: View, backdrop: Int) {
        if (incoming.visibility == View.VISIBLE && incoming.alpha >= 1f && outgoing.visibility == View.GONE) return
        incoming.animate().cancel()
        outgoing.animate().cancel()
        val snap = if (outgoing.visibility == View.VISIBLE) snapshot(outgoing, backdrop) else null
        outgoing.visibility = View.GONE
        reset(outgoing)
        showNow(incoming)
        coverWith(incoming, snap)
    }

    fun coverThrough(view: View, backdrop: Int, swap: () -> Unit) {
        view.animate().cancel()
        val snap = if (view.visibility == View.VISIBLE) snapshot(view, backdrop) else null
        swap()
        showNow(view)
        coverWith(view, snap)
    }

    fun cancelCover(view: View) {
        coverAnimators.remove(view)?.cancel()
    }

    internal fun coverActiveForTest(view: View): Boolean = coverAnimators[view] != null

    fun crossfadeColor(view: View, from: Int, to: Int, duration: Long = STATE_CHANGE, apply: (Int) -> Unit): ValueAnimator? {
        if (from == to) { apply(to); return null }
        if (!view.isAttachedToWindow || !enabled()) { apply(to); return null }
        return ValueAnimator.ofArgb(from, to).apply {
            this.duration = duration
            interpolator = STANDARD
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    fun reset(view: View) {
        view.animate().cancel()
        cancelCover(view)
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
    }

    class PressFeedback(private val view: View, private val invalidate: () -> Unit = { view.invalidate() }) {
        var level: Float = 0f
            private set
        private var animator: ValueAnimator? = null

        fun press() = animateTo(1f, PRESS_IN, STANDARD_DECEL)

        fun release() = animateTo(0f, PRESS_OUT, STANDARD)

        fun cancel() = release()

        fun reset() {
            animator?.cancel()
            animator = null
            level = 0f
            invalidate()
        }

        private fun animateTo(target: Float, duration: Long, interpolator: Interpolator) {
            animator?.cancel()
            if (!view.isAttachedToWindow || !enabled()) {
                level = target
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(level, target).apply {
                this.duration = duration
                this.interpolator = interpolator
                addUpdateListener {
                    level = it.animatedValue as Float
                    view.postInvalidateOnAnimation()
                }
                start()
            }
        }
    }
}
