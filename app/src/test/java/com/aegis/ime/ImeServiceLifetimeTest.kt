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

package com.aegis.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ImeServiceLifetimeTest {

    @Test fun invalidationWaitsForAnActiveCallbackAndRejectsEveryLateCallback() {
        val lifetime = ImeServiceLifetime()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackCompleted = AtomicBoolean(false)
        val invalidated = CountDownLatch(1)

        val callback = Thread {
            assertTrue(lifetime.runIfActive {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                callbackCompleted.set(true)
            })
        }
        callback.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val destroy = Thread {
            lifetime.invalidate()
            invalidated.countDown()
        }
        destroy.start()
        assertFalse(invalidated.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        callback.join(5_000)
        destroy.join(5_000)

        assertTrue(callbackCompleted.get())
        assertTrue(invalidated.await(0, TimeUnit.MILLISECONDS))
        assertFalse(lifetime.runIfActive { error("late callback ran") })
        assertFalse(lifetime.isActive())
    }

    @Test fun valueAccessCannotStartAfterDatabaseLifetimeIsInvalidated() {
        val lifetime = ImeServiceLifetime()
        lifetime.invalidate()

        assertNull(lifetime.valueIfActive { error("late database access ran") })
    }
}
