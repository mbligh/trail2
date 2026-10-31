package net.pocketmap

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*

internal fun assertJsonEquals(expected: Any?, actual: Any?, path: String = "root") {
    when (expected) {
        is JSONObject -> {
            assertTrue("$path must be an object", actual is JSONObject)
            actual as JSONObject
            val keys = expected.keys().asSequence().toSet()
            assertEquals(path, keys, actual.keys().asSequence().toSet())
            for (key in keys) assertJsonEquals(expected.get(key), actual.get(key), "$path.$key")
        }
        is JSONArray -> {
            assertTrue("$path must be an array", actual is JSONArray)
            actual as JSONArray
            assertEquals(path, expected.length(), actual.length())
            for (index in 0 until expected.length()) assertJsonEquals(expected.get(index), actual.get(index), "$path[$index]")
        }
        is Number -> {
            assertTrue("$path must be numeric", actual is Number)
            assertEquals(path, expected.toDouble(), (actual as Number).toDouble(), 0.0)
        }
        else -> assertEquals(path, expected, actual)
    }
}

