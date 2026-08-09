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

package com.aegis.ime.user

import java.util.concurrent.Executors

object UserStoreEdits {

    private val lane = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-userstore-edit").apply { isDaemon = true }
    }

    fun submit(work: () -> Unit) {
        runCatching { lane.execute(work) }.getOrElse { work() }
    }
}
