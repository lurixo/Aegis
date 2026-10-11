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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralCpuTest {

    private fun cpuinfo(vararg features: String) = features.withIndex().joinToString("\n\n") { (i, f) ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: $f\nCPU implementer\t: 0x41"
    }

    @Test fun aCoreWithDotProductAndHalfPrecisionIsSupported() {
        val v82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
        assertTrue(NeuralCpu.supports("aarch64", cpuinfo(v82, v82)))
    }

    @Test fun anArmv8CoreWithoutDotProductIsNotSupported() {
        assertFalse(NeuralCpu.supports("aarch64", cpuinfo("fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid")))
    }

    @Test fun everyCoreMustSupportTheInstructions() {
        val big = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
        val little = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop"
        assertFalse(NeuralCpu.supports("aarch64", cpuinfo(big, little)))
    }

    @Test fun anUnreadableCpuListIsNotSupported() {
        assertFalse(NeuralCpu.supports("aarch64", ""))
    }

    private fun x86Cpuinfo(vararg flags: String) = flags.withIndex().joinToString("\n\n") { (i, f) ->
        "processor\t: $i\nvendor_id\t: AuthenticAMD\nflags\t\t: $f\nbogomips\t: 7585.40"
    }

    @Test fun anX86CoreWithAvx2IsSupported() {
        val v3 = "fpu sse sse2 ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand bmi1 avx2 bmi2"
        assertTrue(NeuralCpu.supports("x86_64", x86Cpuinfo(v3, v3)))
    }

    @Test fun anX86CoreWithoutAvx2IsNotSupported() {
        val avx = "fpu sse sse2 ssse3 cx16 sse4_1 sse4_2 popcnt aes xsave avx"
        assertFalse(NeuralCpu.supports("x86_64", x86Cpuinfo(avx)))
    }

    @Test fun anX86CoreWithoutFmaIsNotSupported() {
        val noFma = "fpu sse sse2 ssse3 cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand bmi1 avx2 bmi2"
        assertFalse(NeuralCpu.supports("x86_64", x86Cpuinfo(noFma)))
    }

    @Test fun anUnreadableX86CpuListIsNotSupported() {
        assertFalse(NeuralCpu.supports("x86_64", ""))
    }

    @Test fun otherArchitecturesAreNotChecked() {
        assertTrue(NeuralCpu.supports("riscv64", ""))
    }

    @Test fun theAndroidRuntimeChecksTheArmInstructions() {
        val v82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
        assertTrue(NeuralCpu.supports("Dalvik", "aarch64", cpuinfo(v82)))
        assertFalse(NeuralCpu.supports("Dalvik", "aarch64", cpuinfo("fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid")))
        assertFalse(NeuralCpu.supports("Dalvik", "aarch64", ""))
    }

    @Test fun theAndroidRuntimeChecksTheX86Instructions() {
        val v3 = "fpu sse sse2 ssse3 fma cx16 sse4_1 sse4_2 movbe popcnt aes xsave avx f16c rdrand bmi1 avx2 bmi2"
        assertTrue(NeuralCpu.supports("Dalvik", "x86_64", x86Cpuinfo(v3)))
        assertFalse(NeuralCpu.supports("Dalvik", "x86_64", x86Cpuinfo("fpu sse sse2 ssse3 cx16 sse4_1 sse4_2 popcnt aes xsave avx")))
        assertFalse(NeuralCpu.supports("Dalvik", "x86_64", ""))
    }

    @Test fun aHostJvmIsNotChecked() {
        assertTrue(NeuralCpu.supports("OpenJDK 64-Bit Server VM", "aarch64", ""))
        assertTrue(NeuralCpu.supports("OpenJDK 64-Bit Server VM", "aarch64", cpuinfo("fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid")))
        assertTrue(NeuralCpu.supports("OpenJDK 64-Bit Server VM", "x86_64", ""))
        assertTrue(NeuralCpu.supports("OpenJDK 64-Bit Server VM", "amd64", ""))
    }
}
