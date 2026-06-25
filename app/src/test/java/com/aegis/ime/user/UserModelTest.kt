package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserModelTest {

    @Test
    fun learnsBoostAndPredicts() {
        val m = UserModel()
        assertEquals(0.0, m.wordBoost("你好"), 0.0)
        m.record(null, "你好", 1000)
        m.record("你好", "世界", 1001)
        m.record("你好", "世界", 1002)
        m.record("你好", "啊", 1003)
        assertTrue("seen word gets positive boost", m.wordBoost("你好") > 0.0)
        assertEquals(listOf("世界", "啊"), m.successors("你好", 8))
        assertTrue(m.dirty)
    }

    @Test
    fun saveLoadRoundtrip() {
        val m = UserModel()
        m.record(null, "测试", 5)
        m.record("测试", "用例", 6)
        val f = File.createTempFile("userdb", ".txt")
        m.save(f)
        assertFalse("save clears dirty", m.dirty)
        val m2 = UserModel().apply { load(f) }
        assertEquals(m.wordBoost("测试"), m2.wordBoost("测试"), 1e-9)
        assertEquals(listOf("用例"), m2.successors("测试", 8))
        f.delete()
    }

    @Test
    fun importMerges() {
        val a = UserModel().apply { record(null, "词", 1) }
        val b = UserModel().apply { record(null, "词", 1); record("词", "条", 1) }
        val f = File.createTempFile("udb", ".txt")
        b.save(f)
        val before = a.wordBoost("词")
        a.importFrom(f, 9)
        assertTrue("merged count raises boost", a.wordBoost("词") > before)
        assertEquals(listOf("条"), a.successors("词", 8))
        f.delete()
    }
}
