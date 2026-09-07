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

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.aegis.ime.R

enum class KeySound(val value: String, val labelRes: Int, val sampleRes: Int, vararg alternatives: Int) {
    OFF("off", R.string.key_sound_off, 0),
    BLUE("blue", R.string.key_sound_blue, R.raw.key_blue, R.raw.key_blue_2, R.raw.key_blue_3, R.raw.key_blue_4),
    BROWN("brown", R.string.key_sound_brown, R.raw.key_brown, R.raw.key_brown_2, R.raw.key_brown_3, R.raw.key_brown_4),
    RED("red", R.string.key_sound_red, R.raw.key_red, R.raw.key_red_2, R.raw.key_red_3, R.raw.key_red_4),
    BLACK("black", R.string.key_sound_black, R.raw.key_black, R.raw.key_black_2, R.raw.key_black_3, R.raw.key_black_4),
    SILVER("silver", R.string.key_sound_silver, R.raw.key_silver, R.raw.key_silver_2, R.raw.key_silver_3, R.raw.key_silver_4),
    PURPLE("purple", R.string.key_sound_purple, R.raw.key_purple, R.raw.key_purple_2, R.raw.key_purple_3, R.raw.key_purple_4),
    SILENT_RED("silent_red", R.string.key_sound_silent_red, R.raw.key_silent_red, R.raw.key_silent_red_2, R.raw.key_silent_red_3, R.raw.key_silent_red_4),
    SILENT_BLACK("silent_black", R.string.key_sound_silent_black, R.raw.key_silent_black, R.raw.key_silent_black_2, R.raw.key_silent_black_3, R.raw.key_silent_black_4),
    CREAM("cream", R.string.key_sound_cream, R.raw.key_cream, R.raw.key_cream_2, R.raw.key_cream_3, R.raw.key_cream_4);

    val sampleResources: IntArray = if (sampleRes == 0) intArrayOf() else intArrayOf(sampleRes, *alternatives)

    companion object {
        fun of(value: String?): KeySound = entries.firstOrNull { it.value == value } ?: OFF
    }
}

internal class KeySoundPlayer(private val context: Context) {
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<Int, Int>()
    private val loaded = mutableSetOf<Int>()
    private var pendingPress = 0L
    private val sampleOrder = ArrayDeque<Int>()
    private var lastSample = 0
    var sound = KeySound.OFF
        private set

    fun select(value: KeySound) {
        if (sound == value) { prepare(); return }
        pendingPress = 0L
        sampleOrder.clear()
        lastSample = 0
        sound = value
        if (value == KeySound.OFF) release() else prepare()
    }

    fun prepare() {
        if (sound == KeySound.OFF || pool != null) return
        val next = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
        pool = next
        next.setOnLoadCompleteListener { source, sample, status ->
            if (pool !== source || status != 0) return@setOnLoadCompleteListener
            loaded.add(sample)
            if (sound.sampleResources.any { samples[it] == sample } && pendingPress != 0L &&
                SystemClock.uptimeMillis() - pendingPress <= 40L) {
                pendingPress = 0L
                play()
            }
        }
        for (choice in KeySound.entries) {
            for (resource in choice.sampleResources) samples[resource] = next.load(context, resource, 1)
        }
    }

    fun play() {
        if (sound == KeySound.OFF) return
        prepare()
        if (sampleOrder.isEmpty()) {
            val ready = sound.sampleResources.toList().mapNotNull { resource ->
                samples[resource]?.takeIf { it in loaded }
            }.shuffled().toMutableList()
            if (ready.size > 1 && ready.first() == lastSample) {
                val next = ready[1]
                ready[1] = ready[0]
                ready[0] = next
            }
            sampleOrder.addAll(ready)
        }
        val sample = sampleOrder.removeFirstOrNull() ?: 0
        if (sample == 0) {
            pendingPress = SystemClock.uptimeMillis()
            return
        }
        pendingPress = 0L
        lastSample = sample
        pool?.play(sample, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        pendingPress = 0L
        sampleOrder.clear()
        lastSample = 0
        pool?.release()
        pool = null
        samples.clear()
        loaded.clear()
    }
}
