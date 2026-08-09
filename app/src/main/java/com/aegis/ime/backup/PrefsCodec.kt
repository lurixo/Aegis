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

package com.aegis.ime.backup

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object PrefsCodec {

    private const val TYPE_BOOL = 'Z'.code
    private const val TYPE_INT = 'I'.code
    private const val TYPE_LONG = 'L'.code
    private const val TYPE_FLOAT = 'F'.code
    private const val TYPE_STRING = 'S'.code
    private const val TYPE_STRING_SET = 'T'.code

    sealed interface Value {
        data class Bool(val v: Boolean) : Value
        data class Integer(val v: Int) : Value
        data class LongVal(val v: Long) : Value
        data class FloatVal(val v: Float) : Value
        data class Str(val v: String) : Value
        data class StrSet(val v: Set<String>) : Value
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Value) {
        when (value) {
            is Value.Bool -> editor.putBoolean(key, value.v)
            is Value.Integer -> editor.putInt(key, value.v)
            is Value.LongVal -> editor.putLong(key, value.v)
            is Value.FloatVal -> editor.putFloat(key, value.v)
            is Value.Str -> editor.putString(key, value.v)
            is Value.StrSet -> editor.putStringSet(key, value.v)
        }
    }

    fun encode(entries: Map<String, Any?>): ByteArray {
        val supported = entries.filterValues {
            it is Boolean || it is Int || it is Long || it is Float || it is String || it is Set<*>
        }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(supported.size)
            for ((key, value) in supported) {
                writeString(out, key)
                when (value) {
                    is Boolean -> { out.writeByte(TYPE_BOOL); out.writeBoolean(value) }
                    is Int -> { out.writeByte(TYPE_INT); out.writeInt(value) }
                    is Long -> { out.writeByte(TYPE_LONG); out.writeLong(value) }
                    is Float -> { out.writeByte(TYPE_FLOAT); out.writeFloat(value) }
                    is String -> { out.writeByte(TYPE_STRING); writeString(out, value) }
                    is Set<*> -> {
                        out.writeByte(TYPE_STRING_SET)
                        val strings = value.filterIsInstance<String>()
                        out.writeInt(strings.size)
                        for (s in strings) writeString(out, s)
                    }
                }
            }
        }
        return bos.toByteArray()
    }

    fun decode(blob: ByteArray): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        DataInputStream(ByteArrayInputStream(blob)).use { input ->
            val count = input.readInt()
            if (count < 0) throw BackupCorruptException("bad prefs count $count")
            repeat(count) {
                val key = readString(input)
                val value = when (val type = input.readByte().toInt() and 0xFF) {
                    TYPE_BOOL -> Value.Bool(input.readBoolean())
                    TYPE_INT -> Value.Integer(input.readInt())
                    TYPE_LONG -> Value.LongVal(input.readLong())
                    TYPE_FLOAT -> Value.FloatVal(input.readFloat())
                    TYPE_STRING -> Value.Str(readString(input))
                    TYPE_STRING_SET -> {
                        val n = input.readInt()
                        if (n < 0) throw BackupCorruptException("bad string-set size $n")
                        val set = LinkedHashSet<String>(n.coerceAtMost(1 shl 16))
                        repeat(n) { set.add(readString(input)) }
                        Value.StrSet(set)
                    }
                    else -> throw BackupCorruptException("unknown pref type $type")
                }
                out[key] = value
            }
        }
        return out
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > input.available()) throw BackupCorruptException("bad string length $len")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
