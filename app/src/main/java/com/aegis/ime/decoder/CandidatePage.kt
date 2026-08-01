// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
// or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.decoder

import java.util.concurrent.atomic.AtomicBoolean

data class CandidatePage<T>(
    val items: List<T>,
    val continuation: CandidateContinuation<T>?,
    val inputEpoch: Long,
)

class CandidateContinuation<T> internal constructor(
    internal val source: CandidatePageSource<T>,
    val inputEpoch: Long,
) {
    internal val consumed = AtomicBoolean()
}

internal data class CandidateSlice<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

internal fun interface CandidatePageSource<T> {
    fun next(pageSize: Int): CandidateSlice<T>
}

internal class ListCandidatePageSource<T>(private val items: List<T>) : CandidatePageSource<T> {
    private var offset = 0

    override fun next(pageSize: Int): CandidateSlice<T> {
        val end = minOf(items.size, offset + pageSize)
        val page = items.subList(offset, end).toList()
        offset = end
        return CandidateSlice(page, offset < items.size)
    }
}

internal class FilteringCandidatePageSource<T>(
    private val source: CandidatePageSource<T>,
    private val keep: (T) -> Boolean,
) : CandidatePageSource<T> {
    private val pending = ArrayDeque<T>()
    private var sourceHasMore = true

    override fun next(pageSize: Int): CandidateSlice<T> {
        while (pending.size < pageSize && sourceHasMore) {
            val slice = source.next(pageSize)
            for (item in slice.items) if (keep(item)) pending.addLast(item)
            sourceHasMore = slice.hasMore
        }
        val count = minOf(pageSize, pending.size)
        val items = ArrayList<T>(count)
        repeat(count) { items.add(pending.removeFirst()) }
        return CandidateSlice(items, pending.isNotEmpty() || sourceHasMore)
    }
}

internal fun <T> firstCandidatePage(
    source: CandidatePageSource<T>,
    inputEpoch: Long,
    pageSize: Int = CANDIDATE_PAGE_SIZE,
): CandidatePage<T> {
    require(pageSize > 0)
    val slice = source.next(pageSize)
    val continuation = if (slice.hasMore) CandidateContinuation(source, inputEpoch) else null
    return CandidatePage(slice.items, continuation, inputEpoch)
}

internal fun <T> continueCandidatePage(
    continuation: CandidateContinuation<T>,
    inputEpoch: Long,
    pageSize: Int = CANDIDATE_PAGE_SIZE,
): CandidatePage<T> {
    require(pageSize > 0)
    if (continuation.inputEpoch != inputEpoch || !continuation.consumed.compareAndSet(false, true)) {
        return CandidatePage(emptyList(), null, inputEpoch)
    }
    return firstCandidatePage(continuation.source, inputEpoch, pageSize)
}

const val CANDIDATE_PAGE_SIZE = 30
