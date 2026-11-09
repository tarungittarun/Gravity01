package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-written JSON reader that replaces `org.json`/Gson so the app has no serialisation
 * dependency. Two properties matter more than speed: it must never throw on hostile input (a live
 * provider would otherwise crash the screen), and it must refuse truncated input instead of returning
 * a half-object that the UI renders as "0 min late".
 */
class MiniJsonTest {

    @Test
    fun readsNestedStructure() {
        val o = MiniJson.obj("""{"a":1,"b":{"c":[true,null,"x"]},"d":2.5}""")
        assertNotNull(o)
        with(MiniJson) {
            assertEquals(1, o!!.int("a"))
            val nested = o.map("b")!!
            val list = nested.list("c")!!
            assertEquals(3, list.size)
            assertEquals(true, list[0])
            assertNull(list[1])
            assertEquals("x", list[2])
        }
    }

    @Test
    fun stringsUnescapeWhatTheFeedsActuallySend() {
        val o = MiniJson.obj("""{"s":"line\nbreak \"quoted\" back\\slash tab\tA"}""")!!
        val s = o["s"] as String
        assertEquals("line\nbreak \"quoted\" back\\slash tab\tA", s)
        val u = MiniJson.obj("""{"s":"\u0041\u00e9"}""")!!["s"] as String
        assertEquals("A\u00e9", u)
        assertEquals(2, u.length)
    }

    @Test
    fun rejectsTruncatedAndTrailingInput() {
        // No exception, and no partial value either: `obj` returns null and `parse` carries a reason.
        assertTrue(MiniJson.parse("""{"a":1,"b":[2,""") is MiniJson.Result.Error)
        assertTrue(MiniJson.parse("""{"a":1} trailing""") is MiniJson.Result.Error)
        assertTrue(MiniJson.parse("""{"a":}""") is MiniJson.Result.Error)
        assertTrue(MiniJson.parse("") is MiniJson.Result.Error)
        assertNull(MiniJson.obj("""{"a":1,"b":["""))
    }

    @Test
    fun refusesPathologicalDepthInsteadOfStackOverflowing() {
        val deep = "[".repeat(200) + "]".repeat(200)
        val r = MiniJson.parse(deep)
        assertTrue("expected a depth error, got $r", r is MiniJson.Result.Error)
    }

    @Test
    fun numberAccessorsTolerateTheTwoShapesJsonServersUse() = with(MiniJson) {
        val o = MiniJson.obj("""{"i":42,"s":"42","f":1.9,"t":true,"zero":0}""")!!
        assertEquals(42, o.int("i"))
        assertEquals(42, o.int("s"))
        assertEquals(1, o.int("f"))
        assertNull(o.int("t"))
        assertEquals(true, o.bool("t"))
        assertEquals(true, o.bool("i"))
        assertEquals(false, o.bool("zero"))
        // An empty string is "the feed sent nothing", which is different from an absent key only in
        // that it must not be rendered as a station name.
        assertNull(MiniJson.obj("""{"s":""}""")!!.str("s"))
    }

    @Test
    fun arrayRootIsReadable() {
        val a = MiniJson.arr("""[{"code":"NDLS"},{"code":"BCT"}]""")
        assertEquals(2, a?.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("NDLS", (a!![0] as Map<String, Any?>)["code"])
    }

    @Test
    fun whitespaceAndUnicodeOutsideStringsAreIgnored() {
        val o = MiniJson.obj(""" { "a" : [ 1 , 2 ] } """)!!
        assertEquals(2, (o["a"] as List<*>).size)
    }
}
