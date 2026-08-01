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

import java.io.DataInputStream
import java.io.DataOutputStream

internal object PrefsCodec {

    private const val TYPE_BOOL = 'Z'.code
    private const val TYPE_INT = 'I'.code
    private const val TYPE_LONG = 'L'.code
    private const val TYPE_FLOAT = 'F'.code
    private const val TYPE_STRING = 'S'.code
    private const val TYPE_STRING_SET = 'T'.code
    private const val STRING_CHUNK_CHARS = 32 * 1024

    sealed interface Value {
        data class Bool(val v: Boolean) : Value
        data class Integer(val v: Int) : Value
        data class LongVal(val v: Long) : Value
        data class FloatVal(val v: Float) : Value
        data class Str(val v: String) : Value
        data class StrSet(val v: Set<String>) : Value
    }

    fun supported(value: Any?): Boolean =
        value is Boolean || value is Int || value is Long || value is Float || value is String ||
            value is Set<*> && value.all { it is String }

    fun writeEntry(output: DataOutputStream, key: String, value: Any) {
        writeString(output, key)
        when (value) {
            is Boolean -> {
                output.writeByte(TYPE_BOOL)
                output.writeBoolean(value)
            }
            is Int -> {
                output.writeByte(TYPE_INT)
                output.writeInt(value)
            }
            is Long -> {
                output.writeByte(TYPE_LONG)
                output.writeLong(value)
            }
            is Float -> {
                output.writeByte(TYPE_FLOAT)
                output.writeFloat(value)
            }
            is String -> {
                output.writeByte(TYPE_STRING)
                writeString(output, value)
            }
            is Set<*> -> {
                val values = value.filterIsInstance<String>().sorted()
                output.writeByte(TYPE_STRING_SET)
                output.writeInt(values.size)
                for (entry in values) writeString(output, entry)
            }
            else -> throw IllegalArgumentException("unsupported preference value")
        }
    }

    fun readEntry(input: DataInputStream): Pair<String, Value> {
        val key = readString(input)
        val value = when (val type = input.readUnsignedByte()) {
            TYPE_BOOL -> Value.Bool(input.readBoolean())
            TYPE_INT -> Value.Integer(input.readInt())
            TYPE_LONG -> Value.LongVal(input.readLong())
            TYPE_FLOAT -> Value.FloatVal(input.readFloat())
            TYPE_STRING -> Value.Str(readString(input))
            TYPE_STRING_SET -> {
                val count = input.readInt()
                if (count < 0) throw BackupCorruptException("bad string-set count")
                val values = LinkedHashSet<String>()
                repeat(count) {
                    if (!values.add(readString(input))) throw BackupCorruptException("duplicate string-set value")
                }
                Value.StrSet(values)
            }
            else -> throw BackupCorruptException("unknown preference type $type")
        }
        if (input.read() != -1) throw BackupCorruptException("trailing preference data")
        return key to value
    }

    private fun writeString(output: DataOutputStream, value: String) {
        output.writeInt(value.length)
        val bytes = ByteArray(STRING_CHUNK_CHARS * 2)
        var offset = 0
        while (offset < value.length) {
            val count = minOf(STRING_CHUNK_CHARS, value.length - offset)
            for (index in 0 until count) {
                val character = value[offset + index].code
                bytes[index * 2] = (character ushr 8).toByte()
                bytes[index * 2 + 1] = character.toByte()
            }
            output.write(bytes, 0, count * 2)
            offset += count
        }
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0) throw BackupCorruptException("bad string length")
        val value = StringBuilder(minOf(length, STRING_CHUNK_CHARS))
        val bytes = ByteArray(STRING_CHUNK_CHARS * 2)
        var remaining = length
        while (remaining > 0) {
            val count = minOf(STRING_CHUNK_CHARS, remaining)
            input.readFully(bytes, 0, count * 2)
            for (index in 0 until count) {
                val character = ((bytes[index * 2].toInt() and 0xff) shl 8) or
                    (bytes[index * 2 + 1].toInt() and 0xff)
                value.append(character.toChar())
            }
            remaining -= count
        }
        return value.toString()
    }
}
