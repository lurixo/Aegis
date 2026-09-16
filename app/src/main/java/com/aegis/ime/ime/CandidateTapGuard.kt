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

internal class CandidateTapGuard {
    private var shown: List<String> = emptyList()
    private var staleShown: List<String> = emptyList()
    private var pendingSince = NEVER
    private var settledAt = NEVER
    private var staleMillis = 0L
    private var pressed: List<String>? = null
    private var pressedAt = NEVER
    private var pressedRisky = false

    fun show(candidates: List<String>, pending: Boolean, now: Long) {
        if (pending) {
            if (pendingSince == NEVER) pendingSince = now
        } else if (pendingSince != NEVER) {
            staleMillis = now - pendingSince
            settledAt = now
            staleShown = shown
            pendingSince = NEVER
        }
        shown = candidates
    }

    fun press(downTime: Long) {
        pressed = shown
        pressedAt = downTime
        pressedRisky = pendingSince == NEVER && settledAt != NEVER &&
            downTime >= settledAt && downTime - settledAt < SETTLE_MILLIS &&
            staleMillis >= STALE_VISIBLE_MILLIS
    }

    fun accepts(index: Int, now: Long): Boolean {
        val atPress = pressed ?: return true
        pressed = null
        if (now - pressedAt > PRESS_MILLIS) return true
        val word = shown.getOrNull(index) ?: return false
        if (atPress.getOrNull(index) != word) return false
        return !pressedRisky || staleShown.getOrNull(index) == word
    }

    internal fun pendingForTest(): Boolean = pendingSince != NEVER

    internal companion object {
        const val SETTLE_MILLIS = 200L
        const val STALE_VISIBLE_MILLIS = 120L
        const val PRESS_MILLIS = 3_000L
        private const val NEVER = Long.MIN_VALUE
    }
}
