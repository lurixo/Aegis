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

package com.aegis.ime.ime.theme

/**
 * F2: corner-radius tokens (in dp) for the self-drawn IME surface. MD3 hierarchy = small keys / larger
 * cards; keys are rounded rectangles, never pills ("圆角不宜过大", ≤ 16dp).
 *
 * Seeded with the CURRENT values for a zero-visual-change baseline; the F2 shape
 * milestone tunes [keyRadiusDp] toward 12 and retires the oval enter / figure-8 left column.
 */
object ImeShapes {
    /** Ordinary key corner — MD3 medium (≤16dp, never pill). */
    const val keyRadiusDp = 12f

    /** Candidate / panel card corner (MD3 large). */
    const val cardRadiusDp = 16f

    /** debug.17: inline text-input field (常用语 / 新建分类) — a MEDIUM rounded RECTANGLE, never a stadium pill. */
    const val inputRadiusDp = 12f

    /** True pills: toggle chips / tags only — never keys. */
    const val chipRadiusDp = 999f
}
