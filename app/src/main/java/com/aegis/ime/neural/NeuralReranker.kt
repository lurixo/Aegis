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

package com.aegis.ime.neural

import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NeuralReranker(
    val spec: NeuralModelSpec,
    private val openScorer: () -> SentenceScorer,
    private val worker: Executor,
    private val main: Executor,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Phase { LOADING, READY, FAILED, CLOSED }

    class Choice(val best: String, val decoderBest: String)

    class Snapshot(
        val phase: Phase,
        val error: String?,
        val loadMillis: Long,
        val runs: Int,
        val changed: Int,
        val lastMillis: Long,
        val meanMillis: Long,
        val maxMillis: Long,
        val timeouts: Int,
    )

    private class Delivery(val gen: Long, val run: () -> Unit)

    @Volatile private var phase = Phase.LOADING
    @Volatile private var error: String? = null
    @Volatile private var scorer: SentenceScorer? = null
    private val latest = AtomicLong(0L)
    @Volatile private var pendingGen = 0L
    @Volatile private var appliedGen = 0L
    private val claimedGen = AtomicLong(0L)
    private val finishedLock = ReentrantLock()
    private val finishedChanged = finishedLock.newCondition()
    private var finished: Delivery? = null

    private val statsLock = Any()
    private var loadMillis = 0L
    private var runs = 0
    private var changed = 0
    private var lastMillis = 0L
    private var totalMillis = 0L
    private var maxMillis = 0L
    private var timeouts = 0

    val ready: Boolean get() = phase == Phase.READY

    fun start() {
        worker.execute {
            val started = nanoTime()
            try {
                scorer = openScorer()
                synchronized(statsLock) { loadMillis = (nanoTime() - started) / NANOS_PER_MILLI }
                phase = Phase.READY
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                phase = Phase.FAILED
            }
        }
    }

    fun close() {
        latest.incrementAndGet()
        phase = Phase.CLOSED
        scorer?.cancel()
        worker.execute {
            scorer?.close()
            scorer = null
        }
    }

    fun invalidate() {
        latest.incrementAndGet()
        scorer?.cancel()
    }

    fun submit(
        context: String,
        nine: Boolean,
        waitMillis: Long,
        paths: () -> List<Pair<String, Double>>,
        apply: (Choice?) -> Unit,
    ) {
        val gen = latest.incrementAndGet()
        pendingGen = gen
        scorer?.cancel()
        worker.execute { run(gen, context, nine, paths, apply) }
        schedule(waitMillis) { expire(gen, apply) }
    }

    private fun expire(gen: Long, apply: (Choice?) -> Unit) {
        if (gen != latest.get()) return
        deliver(gen) {
            synchronized(statsLock) { timeouts++ }
            scorer?.cancel()
            apply(null)
        }
    }

    private fun run(
        gen: Long,
        context: String,
        nine: Boolean,
        paths: () -> List<Pair<String, Double>>,
        apply: (Choice?) -> Unit,
    ) {
        if (gen != latest.get()) return
        val active = scorer
        if (active == null || phase != Phase.READY) {
            deliver(gen) { apply(null) }
            return
        }
        val started = nanoTime()
        val ranked = runCatching { paths() }.getOrDefault(emptyList()).take(spec.candidates)
        if (gen != latest.get()) return
        if (ranked.size < 2) {
            deliver(gen) { apply(null) }
            return
        }
        val lm = try {
            active.score(context, ranked.map { it.first })
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            deliver(gen) { apply(null) }
            return
        } ?: return
        val pick = choose(DoubleArray(ranked.size) { ranked[it].second }, lm, spec.alpha(nine))
        record((nanoTime() - started) / NANOS_PER_MILLI, pick != 0)
        val choice = Choice(ranked[pick].first, ranked[0].first)
        deliver(gen) { apply(choice) }
    }

    private fun claim(gen: Long): Boolean {
        while (true) {
            val claimed = claimedGen.get()
            if (gen <= claimed) return false
            if (claimedGen.compareAndSet(claimed, gen)) return true
        }
    }

    private fun deliver(gen: Long, action: () -> Unit) {
        if (!claim(gen)) return
        val delivery = Delivery(gen) {
            if (gen == latest.get() && gen > appliedGen) {
                appliedGen = gen
                action()
            }
        }
        finishedLock.withLock {
            finished = delivery
            finishedChanged.signalAll()
        }
        main.execute { delivery.run() }
    }

    fun settle(timeoutMillis: Long): Boolean {
        val gen = latest.get()
        if (pendingGen != gen || gen <= appliedGen) return true
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val delivery = finishedLock.withLock {
            while (finished?.gen != gen && remaining > 0L) remaining = finishedChanged.awaitNanos(remaining)
            finished?.takeIf { it.gen == gen }
        }
        if (delivery == null) {
            synchronized(statsLock) { timeouts++ }
            return false
        }
        delivery.run()
        return true
    }

    private fun record(millis: Long, moved: Boolean) {
        synchronized(statsLock) {
            runs++
            if (moved) changed++
            lastMillis = millis
            totalMillis += millis
            if (millis > maxMillis) maxMillis = millis
        }
    }

    fun snapshot(): Snapshot = synchronized(statsLock) {
        Snapshot(
            phase = phase,
            error = error,
            loadMillis = loadMillis,
            runs = runs,
            changed = changed,
            lastMillis = lastMillis,
            meanMillis = if (runs == 0) 0L else totalMillis / runs,
            maxMillis = maxMillis,
            timeouts = timeouts,
        )
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        fun choose(decoderScores: DoubleArray, lm: DoubleArray, alpha: Double): Int {
            require(decoderScores.size == lm.size) { "score counts differ" }
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in decoderScores.indices) {
                val s = decoderScores[i] + alpha * lm[i]
                if (s > bestScore) {
                    bestScore = s
                    best = i
                }
            }
            return best
        }
    }
}
