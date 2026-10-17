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

import android.os.Process
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
    private val cpuMillis: () -> Long = Process::getElapsedCpuTime,
    private val majorFaults: () -> Long = ProcessStat::majorFaults,
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
        val runsWithinFirst: Int,
        val runsWithinSecond: Int,
        val runsWithinThird: Int,
        val runsLonger: Int,
        val lastQueueMillis: Long,
        val meanQueueMillis: Long,
        val maxQueueMillis: Long,
        val timeouts: Int,
        val cancelled: Int,
        val firstKeys: Int,
        val lastFirstKeyMillis: Long,
        val meanFirstKeyMillis: Long,
        val maxFirstKeyMillis: Long,
        val warms: Int,
        val lastWarmMillis: Long,
        val meanWarmMillis: Long,
        val maxWarmMillis: Long,
        val slowInferences: Int,
        val slowInferenceMillis: Long,
        val slowInferenceCpuMillis: Long,
        val slowInferenceMajorFaults: Long,
    )

    private class Delivery(val gen: Long, val run: () -> Unit)

    private class Warmup(val context: String) {
        @Volatile var done = false
    }

    @Volatile private var warmup: Warmup? = null
    @Volatile private var phase = Phase.LOADING
    @Volatile private var error: String? = null
    @Volatile private var scorer: SentenceScorer? = null
    private val latest = AtomicLong(0L)
    @Volatile private var pendingGen = 0L
    @Volatile private var pendingSince = 0L
    @Volatile private var pendingWait = 0L
    @Volatile private var appliedGen = 0L
    private val claimedGen = AtomicLong(0L)
    private val finishedLock = ReentrantLock()
    private val finishedChanged = finishedLock.newCondition()
    private var finished: Delivery? = null

    private val abortLock = Any()

    private val statsLock = Any()
    private var loadMillis = 0L
    private var runs = 0
    private var changed = 0
    private var lastMillis = 0L
    private var totalMillis = 0L
    private var maxMillis = 0L
    private var runsWithinFirst = 0
    private var runsWithinSecond = 0
    private var runsWithinThird = 0
    private var runsLonger = 0
    private var lastQueueMillis = 0L
    private var totalQueueMillis = 0L
    private var maxQueueMillis = 0L
    private var timeouts = 0
    private var cancelled = 0
    private var firstKeyAt: Long? = null
    private var firstKeys = 0
    private var lastFirstKeyMillis = 0L
    private var totalFirstKeyMillis = 0L
    private var maxFirstKeyMillis = 0L
    private var warms = 0
    private var lastWarmMillis = 0L
    private var totalWarmMillis = 0L
    private var maxWarmMillis = 0L
    private var slowInferences = 0
    private var slowInferenceMillis = 0L
    private var slowInferenceCpuMillis = 0L
    private var slowInferenceMajorFaults = 0L

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
        cancel()
        worker.execute {
            scorer?.close()
            scorer = null
        }
    }

    fun invalidate() {
        latest.incrementAndGet()
        cancelUnlessWarming(null)
    }

    fun warm(context: String) {
        if (phase != Phase.READY || warmup?.context == context) return
        val next = Warmup(context)
        warmup = next
        cancel()
        worker.execute { load(next) }
    }

    private fun load(next: Warmup) {
        val active = scorer
        if (active != null && resumed(active) { warmup === next && phase == Phase.READY }) {
            val started = nanoTime()
            runCatching { measured { active.warm(next.context) } }.onSuccess { recordWarm((nanoTime() - started) / NANOS_PER_MILLI) }
        }
        next.done = true
    }

    private inline fun <T> measured(call: () -> T): T {
        val started = nanoTime()
        val cpu = cpuMillis()
        val faults = majorFaults()
        try {
            return call()
        } finally {
            val millis = (nanoTime() - started) / NANOS_PER_MILLI
            if (millis >= SLOW_INFERENCE_MILLIS) recordSlowInference(millis, cpuMillis() - cpu, majorFaults() - faults)
        }
    }

    private fun cancel() {
        synchronized(abortLock) { scorer?.cancel() }
    }

    private inline fun resumed(active: SentenceScorer, current: () -> Boolean): Boolean = synchronized(abortLock) {
        if (!current()) return false
        active.resume()
        true
    }

    private fun cancelUnlessWarming(context: String?) {
        val active = warmup
        if (active != null && !active.done && (context == null || active.context == context)) return
        cancel()
    }

    fun submit(
        context: String,
        nine: Boolean,
        waitMillis: Long,
        paths: List<Pair<String, Double>>,
        apply: (Choice?) -> Unit,
    ) {
        val gen = latest.incrementAndGet()
        pendingGen = gen
        if (warmup?.context != context) warmup = null
        cancelUnlessWarming(context)
        val queued = nanoTime()
        pendingSince = queued
        pendingWait = TimeUnit.MILLISECONDS.toNanos(waitMillis)
        worker.execute { run(gen, queued, context, nine, paths, apply) }
        schedule(waitMillis) { expire(gen, apply) }
    }

    private fun expire(gen: Long, apply: (Choice?) -> Unit) {
        if (gen != latest.get()) return
        deliver(gen) {
            synchronized(statsLock) { timeouts++ }
            cancelUnlessWarming(null)
            apply(null)
        }
    }

    private fun run(
        gen: Long,
        queued: Long,
        context: String,
        nine: Boolean,
        paths: List<Pair<String, Double>>,
        apply: (Choice?) -> Unit,
    ) {
        if (gen != latest.get()) return dropped(gen)
        val active = scorer
        if (active == null || phase != Phase.READY) {
            deliver(gen) { apply(null) }
            return
        }
        val started = nanoTime()
        val ranked = paths.take(spec.candidates)
        if (ranked.size < 2) {
            deliver(gen) { apply(null) }
            return
        }
        if (!resumed(active) { gen == latest.get() }) return dropped(gen)
        val lm = try {
            measured { active.score(context, ranked.map { it.first }) }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            deliver(gen) { apply(null) }
            return
        } ?: return dropped(gen)
        val pick = choose(DoubleArray(ranked.size) { ranked[it].second }, lm, spec.alpha(nine))
        record((started - queued) / NANOS_PER_MILLI, (nanoTime() - started) / NANOS_PER_MILLI, pick != 0)
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

    fun settle(waitMillis: Long): Boolean {
        val gen = latest.get()
        if (pendingGen != gen || gen <= appliedGen) return true
        var remaining = pendingSince + minOf(TimeUnit.MILLISECONDS.toNanos(waitMillis), pendingWait) - nanoTime()
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

    fun firstKeyDown() {
        val now = nanoTime()
        synchronized(statsLock) { firstKeyAt = now }
    }

    fun firstKeyDropped() {
        synchronized(statsLock) { firstKeyAt = null }
    }

    fun firstKeyShown() {
        synchronized(statsLock) {
            val since = firstKeyAt ?: return
            firstKeyAt = null
            val millis = (nanoTime() - since) / NANOS_PER_MILLI
            firstKeys++
            lastFirstKeyMillis = millis
            totalFirstKeyMillis += millis
            if (millis > maxFirstKeyMillis) maxFirstKeyMillis = millis
        }
    }

    private fun recordWarm(millis: Long) {
        synchronized(statsLock) {
            warms++
            lastWarmMillis = millis
            totalWarmMillis += millis
            if (millis > maxWarmMillis) maxWarmMillis = millis
        }
    }

    private fun recordSlowInference(millis: Long, cpu: Long, faults: Long) {
        synchronized(statsLock) {
            slowInferences++
            slowInferenceMillis += millis
            slowInferenceCpuMillis += cpu
            slowInferenceMajorFaults += faults
        }
    }

    private fun dropped(gen: Long) {
        if (gen != latest.get()) synchronized(statsLock) { cancelled++ }
    }

    private fun record(queueMillis: Long, millis: Long, moved: Boolean) {
        synchronized(statsLock) {
            runs++
            if (moved) changed++
            lastMillis = millis
            totalMillis += millis
            if (millis > maxMillis) maxMillis = millis
            when {
                millis <= SPREAD_FIRST_MILLIS -> runsWithinFirst++
                millis <= SPREAD_SECOND_MILLIS -> runsWithinSecond++
                millis <= SPREAD_THIRD_MILLIS -> runsWithinThird++
                else -> runsLonger++
            }
            lastQueueMillis = queueMillis
            totalQueueMillis += queueMillis
            if (queueMillis > maxQueueMillis) maxQueueMillis = queueMillis
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
            runsWithinFirst = runsWithinFirst,
            runsWithinSecond = runsWithinSecond,
            runsWithinThird = runsWithinThird,
            runsLonger = runsLonger,
            lastQueueMillis = lastQueueMillis,
            meanQueueMillis = if (runs == 0) 0L else totalQueueMillis / runs,
            maxQueueMillis = maxQueueMillis,
            timeouts = timeouts,
            cancelled = cancelled,
            firstKeys = firstKeys,
            lastFirstKeyMillis = lastFirstKeyMillis,
            meanFirstKeyMillis = if (firstKeys == 0) 0L else totalFirstKeyMillis / firstKeys,
            maxFirstKeyMillis = maxFirstKeyMillis,
            warms = warms,
            lastWarmMillis = lastWarmMillis,
            meanWarmMillis = if (warms == 0) 0L else totalWarmMillis / warms,
            maxWarmMillis = maxWarmMillis,
            slowInferences = slowInferences,
            slowInferenceMillis = slowInferenceMillis,
            slowInferenceCpuMillis = slowInferenceCpuMillis,
            slowInferenceMajorFaults = slowInferenceMajorFaults,
        )
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val SLOW_INFERENCE_MILLIS = 100L
        private const val SPREAD_FIRST_MILLIS = 16L
        private const val SPREAD_SECOND_MILLIS = 32L
        private const val SPREAD_THIRD_MILLIS = 50L
        const val MAX_CONTEXT_CHARS = 32
        const val MIN_CONTEXT_CHARS = 16
        private const val CONTEXT_BOUNDARIES = "\n。！？；，、：!?;,."

        fun contextWindow(text: String, fromStart: Boolean): String {
            if (fromStart && text.length <= MAX_CONTEXT_CHARS) return text
            val end = text.length
            for (i in maxOf(1, end - MAX_CONTEXT_CHARS)..end - MIN_CONTEXT_CHARS) {
                if (text[i - 1] in CONTEXT_BOUNDARIES) return text.substring(i)
            }
            val found = text.indexOfLast { it in CONTEXT_BOUNDARIES } + 1
            val anchor = if (found > 0 || fromStart) found else return text.takeLast(MAX_CONTEXT_CHARS)
            val start = anchor + Math.floorDiv(end - anchor - MIN_CONTEXT_CHARS, MIN_CONTEXT_CHARS) * MIN_CONTEXT_CHARS
            return text.substring(maxOf(0, start))
        }

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
