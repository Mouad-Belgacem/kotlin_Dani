package com.tarteelwarsh.tilawa.pipeline

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures generees directement depuis add_harakat() (Dani_app/api/pipeline.py) - jamais retranscrites a la main. */
class AddHarakatTest {
    @Test
    fun `matches the Python add_harakat exactly`() {
        val text = javaClass.getResourceAsStream("/add_harakat_cases.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = JsonParser.parse(text).asJsonArray()
        for (case in cases) {
            val obj = case.asJsonObject()
            val input = obj.getValue("input").asJsonString()
            val expected = obj.getValue("output").asJsonString()
            assertEquals("input=\"$input\"", expected, addHarakat(input))
        }
    }
}
