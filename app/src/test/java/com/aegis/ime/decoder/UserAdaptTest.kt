package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The user boost should re-rank a previously-chosen homograph to the top. */
class UserAdaptTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    @Test
    fun userBoostReranks() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val base = PinyinDecoder(dict, lm).decode("shi", 5)
        assumeTrue("need >=2 candidates", base.size >= 2)
        val target = base[1] // a non-default candidate for "shi"

        val um = UserModel()
        repeat(200) { um.record(null, target, it.toLong()) }

        val withUser = PinyinDecoder(dict, lm, userModel = um).decode("shi", 5)
        assertEquals("user-preferred word ranks first", target, withUser.firstOrNull())
    }
}
