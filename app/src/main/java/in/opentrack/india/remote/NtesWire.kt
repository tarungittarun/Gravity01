package `in`.opentrack.india.remote

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The railway's own mobile enquiry protocol — the source every "live running status" app is built on.
 *
 * Why this file exists at all: everything else keyless we found (timetable dumps, the station board,
 * third-party mirrors) publishes a *schedule*, so a tracker built on those shows planned times and reads
 * like a timetable however good the UI is. This endpoint answers with per-stop **actual** arrival and
 * departure minutes, platforms, delays and a last-updated stamp — which is the whole product.
 *
 * The framing is an AES-CBC envelope, and the decisive fact about it is that the key, the IV and the
 * signature secret are constants inside the official app, shared by every client on earth — not a
 * credential. No registration, no quota, no key to buy, nothing per-user to leak, which is what makes
 * this acceptable under "no keys, no proxies, no ads" where a commercial API key would not be. Upstream
 * can still rotate them, so this is one link in [ProviderChain] rather than the whole chain: when the
 * framing changes, [NtesProvider] returns null, the chain falls through to the timetable source, and the
 * screen says which one it is showing instead of pretending.
 *
 * Wire format, cross-checked against the reference client's vectors in `NtesWireTest`:
 *   request  `{"jsonIn": "<MD5(plain+secret)>#<HEX(BASE64(AES-CBC(pkcs7(plain))))>"}`
 *   response the same envelope in a `jsonIn` field, or bare JSON.
 */
object NtesWire {

    const val BASE_URL = "https://enquiry.indianrail.gov.in/crisns/AppServAnd"

    /** The endpoint checks the client signature; this is what the official Android app sends. */
    const val USER_AGENT = "Dalvik/2.1.0 (Linux; Android 11)"

    private const val KEY = "8EA4DB2CC1EB3DC5"
    private const val IV = "7DC5EB3BB4DB6EA8"
    private const val SECRET = "645fbc1e56e23365f2f3c204ae0899f6"

    const val SUB_LIVE = "ShowFullRunJson"
    const val SUB_SCHEDULE = "GetTrainSchedule"

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val HEX = "0123456789ABCDEF"

    /**
     * `11-Oct-2026`, in Indian Standard Time whatever the device clock's zone says: the API keys a run by
     * this exact string, and a traveller abroad must still be asking about the Indian train day.
     */
    fun dateForWire(epochDay: Long): String {
        val fmt = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(Date(epochDay * 86_400_000L))
    }

    fun plainFor(subService: String, trainNo: String, epochDay: Long): String =
        "service=TrainRunningMob&subService=$subService&trainNo=$trainNo&startDate=${dateForWire(epochDay)}"

    /** The whole POST body. The envelope is hex and base64 only, so no JSON escaping is needed. */
    fun envelope(plain: String): String = "{\"jsonIn\":\"" + wrap(plain) + "\"}"

    /**
     * `MD5(plain+secret)#HEX(BASE64(AES(plain)))` — the framing both ways. The two encodings stack in that
     * order and the outer one is hex *of the base64 text*, not of the ciphertext: reading the reference
     * client closely matters here, because `HEX(ciphertext)` compiles just as happily and every request then
     * gets rejected by the server as a malformed envelope, which on a phone looks like "no live data".
     */
    fun wrap(plain: String): String {
        val cipherText = aes(Cipher.ENCRYPT_MODE, plain.toByteArray(Charsets.UTF_8))
        return md5Upper(plain + SECRET) + "#" + hexEncode(base64Encode(cipherText).toByteArray(Charsets.UTF_8))
    }

    /**
     * Recover the payload text from a response. Accepts a bare JSON body as well as the enveloped one,
     * because some sub-services answer unencrypted and a client that insists on one form fails the other.
     */
    fun unwrap(body: String): String {
        val text = body.trim()
        var payload = text
        if (text.startsWith("{")) {
            val doc = runCatching { JSONObject(text) }.getOrNull()
            val nested = doc?.opt("jsonIn") as? String
            if (nested.isNullOrBlank()) return text // plain mobile JSON is not an AES envelope
            payload = nested
        }
        val hex = payload.substringAfter('#', payload).trim()
        // Unwrap in the same order the sender stacked them: hex -> base64 text -> ciphertext.
        val cipherText = base64Decode(String(hexDecode(hex), Charsets.UTF_8))
        val plain = String(aes(Cipher.DECRYPT_MODE, cipherText), Charsets.UTF_8)
        // PKCS#7 padding bytes are 0x01..0x10; strip them rather than trusting the tail of a string.
        return plain.trimEnd { it.code in 1..16 }
    }

    /** Decrypt-and-parse convenience for callers that only want the JSON object. */
    /**
     * A structural description of a decoded reply: key names, nesting, array lengths - and no values.
     *
     * This exists because the parser has never been fed a real payload, so "is it `STN` or `StationCode`" has
     * been answered by guessing twice. The phone already has the answer; the cheap way to get it out of the
     * phone is a signature rather than the body. Values are deliberately dropped (digits carried train
     * numbers, minutes and coordinates nobody wants in a shared log), which still distinguishes
     * `{"S":{"STN":[x120]}}` from `{"result":{"Status":[{"TrainNo":scalar,...}]}}` - the exact thing we need.
     */
    internal fun shapeOf(root: Any?, depth: Int = 2): String = when (root) {
        is org.json.JSONObject -> {
            val names = root.keys().asSequence().take(10).toList()
            if (depth <= 0 || names.isEmpty()) "{}"
            else names.joinToString(",", "{", "}") { n ->
                val v = root.opt(n)
                if (depth > 1 && (v is org.json.JSONObject || v is org.json.JSONArray))
                    "$n=" + shapeOf(v, depth - 1)
                else n
            }
        }
        is org.json.JSONArray -> if (root.length() == 0) "[]"
            else "[x" + root.length() + shapeOf(root.opt(0), depth - 1) + "]"
        else -> "scalar"
    }

    fun unwrapJson(body: String): JSONObject? {
        val plain = runCatching { unwrap(body) }.getOrNull() ?: return null
        return runCatching { JSONObject(plain.trim()) }.getOrNull()
    }

    private fun aes(mode: Int, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            mode,
            SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return cipher.doFinal(data)
    }

    private fun md5Upper(text: String): String =
        hexEncode(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

    private fun hexEncode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private fun hexDecode(hex: String): ByteArray {
        val clean = StringBuilder(hex.length)
        for (c in hex) if (Character.digit(c, 16) >= 0) clean.append(c)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[2 * i], 16) shl 4) + Character.digit(clean[2 * i + 1], 16)).toByte()
        }
        return out
    }

    // Standard base64 with padding. Hand-rolled rather than java.util.Base64 because that class needs API
    // 26 and this app still installs on API 24, and rather than android.util.Base64 because the crypto has
    // to be testable on a JVM, where that class is a stub that throws.
    private fun base64Encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(B64[b0 ushr 2])
            out.append(if (b1 >= 0) B64[((b0 and 0x03) shl 4) or (b1 ushr 4)] else B64[(b0 and 0x03) shl 4])
            out.append(
                when {
                    b1 < 0 -> '='
                    b2 >= 0 -> B64[((b1 and 0x0F) shl 2) or (b2 ushr 6)]
                    else -> B64[(b1 and 0x0F) shl 2]
                },
            )
            out.append(if (b2 >= 0) B64[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    private fun base64Decode(text: String): ByteArray {
        val rev = IntArray(128) { -1 }
        for (i in B64.indices) rev[B64[i].code] = i
        val acc = ArrayList<Byte>(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = if (c.code < 128) rev[c.code] else -1
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                acc.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return acc.toByteArray()
    }

    /** Base64 helpers, visible for the test that pins them against the JDK's implementation. */
    internal fun b64EncodeForTest(bytes: ByteArray): String = base64Encode(bytes)
    internal fun b64DecodeForTest(text: String): ByteArray = base64Decode(text)
    internal fun encryptForTest(plain: String): ByteArray = aes(Cipher.ENCRYPT_MODE, plain.toByteArray(Charsets.UTF_8))

    // ---- response parsing ---------------------------------------------------------------------------

    /** One halt on the run, as the feed reported it. Null means "the feed said nothing", never "zero". */
    data class Row(
        val code: String?,
        val name: String?,
        val schedArr: Int?,
        val schedDep: Int?,
        val actArr: Int?,
        val actDep: Int?,
        val delayArr: Int?,
        val delayDep: Int?,
        val platform: String?,
        val km: Int?,
        val cancelled: Boolean,
        val departed: Boolean?,
    )

    data class Parsed(
        val trainName: String?,
        val currentCode: String?,
        val currentName: String?,
        val nextCode: String?,
        val message: String?,
        val feedStampMillis: Long?,
        val rows: List<Row>,
        /** Top-level key names, kept so a schema change upstream is visible in the diagnostics log. */
        val topKeys: List<String>,
    ) {
        /** A delay field alone is not an observed arrival/departure. */
        val hasReportedTimes: Boolean get() = rows.any { it.actArr != null || it.actDep != null }
        /** True when the answer carries at least one actual time or delay, i.e. it is running data. */
        val hasLiveColumns: Boolean
            get() = rows.any { it.actArr != null || it.actDep != null || it.delayArr != null || it.delayDep != null }
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /**
     * Tolerant parse. The mobile API renames its containers across sub-services and versions, and the shape
     * can only be observed from outside, so each field is looked up by a short list of plausible names and
     * the row array is found by scanning rather than by path. That buys the property that matters: an
     * unrecognised shape yields null — the chain falls back and the UI says the feed sent no running times
     * — instead of throwing or, worse, half-parsing a cell into a confident wrong minute.
     */
    fun parse(text: String): Parsed? {
        val doc = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val array = findRowArray(doc) ?: return null
        val rows = ArrayList<Row>(array.length())
        val mobile = doc.has("STNS")
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val schArr = o.minutes("ARR", "STA", "schArr", "scheduledArrival")
            val schDep = o.minutes("DEP", "STD", "schDep", "scheduledDeparture")
            // The mobile STNS feed calls ETA/ETD estimates even at future stops, and echoes
            // STA/STD into them. A changed minute is only a candidate: the provider also checks
            // the run and clock before calling it an actual. Schedule-identical cells stay blank.
            val actArr = if (mobile) o.minutes("ETA")?.takeIf { it != schArr }
                else o.minutes("ATA", "actArr", "actualArrival", "ARV_TIME")
            val actDep = if (mobile) o.minutes("ETD")?.takeIf { it != schDep }
                else o.minutes("ATD", "actDep", "actualDeparture", "DEP_TIME")
            rows.add(
                Row(
                    code = o.str("STN", "SC", "stationCode", "stnCode", "code")?.uppercase(Locale.ROOT)?.trim(),
                    name = o.str("STN_NAME", "SN", "stationName", "S_NAME", "name"),
                    schedArr = schArr,
                    schedDep = schDep,
                    actArr = actArr,
                    actDep = actDep,
                    delayArr = o.delay("ARR_DELAY", "DARR", "DELAY", "delayArr", "ARRDELAY"),
                    delayDep = o.delay("DEP_DELAY", "DDEP", "DEPDELAY", "delayDep"),
                    platform = o.str("ARRPLT", "DEPPLT", "PF", "platform", "PLAT", "PB"),
                    km = o.int("DIST", "distance", "km"),
                    cancelled = o.flag("CANC", "IS_CANCELED", "cancelled"),
                    departed = when {
                        o.flag("DEPARTED", "departed") -> true
                        o.hasAny("DEPARTED", "departed") -> false
                        else -> null
                    },
                ),
            )
        }
        return Parsed(
            trainName = doc.str("TMNAME", "trainName", "TRAIN_NAME"),
            currentCode = doc.str("CTT", "currentStation", "currentStnCode")?.uppercase(Locale.ROOT)?.trim(),
            currentName = doc.str("CTT_NAME", "currentStationName"),
            nextCode = doc.str("NEXT", "nextStation", "NS")?.uppercase(Locale.ROOT)?.trim(),
            message = doc.str("MSG", "REMARK", "message", "lastUpdate"),
            feedStampMillis = doc.stampMillis("LASTUPD", "LAST_UPDATE", "lastUpdate", "feedTime", "ATADATA"),
            rows = rows,
            topKeys = doc.keys().asSequence().sorted().toList(),
        )
    }

    private fun findRowArray(doc: JSONObject): JSONArray? {
        for (k in listOf("STNS", "S", "stops", "route", "trainSchedule", "DATA", "data", "Result", "rows")) {
            val v = doc.opt(k)
            if (v is JSONArray && v.length() > 0 && v.optJSONObject(0) != null) return v
        }
        // One level deeper: some sub-services wrap the payload as {"Response": {"...": [...]}}.
        for (key in doc.keys()) {
            val v = doc.opt(key)
            if (v is JSONObject) {
                val got = findRowArray(v)
                if (got != null) return got
            }
        }
        return null
    }

    private fun JSONObject.hasAny(vararg keys: String): Boolean = keys.any { has(it) }

    private fun JSONObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = opt(k) ?: continue
            val s = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> null
            }?.trim()
            if (!s.isNullOrEmpty() && s != "--" && !s.equals("null", true) && s != "NA") return s
        }
        return null
    }

    private fun JSONObject.int(vararg keys: String): Int? {
        for (k in keys) {
            val v = opt(k) ?: continue
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.filter { it.isDigit() || it == '-' }.toIntOrNull()
                else -> null
            }
            if (n != null) return n
        }
        return null
    }

    private fun JSONObject.flag(vararg keys: String): Boolean {
        for (k in keys) {
            val v = opt(k) ?: continue
            if (v is Boolean) return v
            if (v is Number) return v.toInt() != 0
            val s = v.toString().trim().lowercase(Locale.ROOT)
            if (s in setOf("1", "true", "yes", "y", "cancelled", "cancel", "canceled")) return true
            if (s in setOf("0", "false", "no", "n")) return false
            if (s.contains("cancel")) return true
        }
        return false
    }

    /** A clock cell as minutes past the IST midnight of the run day; `+1`/`+d` day markers folded in. */
    private fun JSONObject.minutes(vararg keys: String): Int? {
        for (k in keys) {
            val v = opt(k) ?: continue
            val s = v.toString().trim()
            if (s.isEmpty() || s == "--" || s.equals("na", true)) continue
            if (s.contains("UA", true) || s == "0") continue
            // Mobile NTES writes "Source/20:55 13-Oct" and "Destination/08:20 14-Oct".
            // Strip only these known wrappers, never digits from an arbitrary status string.
            val clock = s.substringAfter('/', s).trim()
            val parts = clock.split('.', ':', ' ').filter { it.isNotBlank() }
            if (parts.size < 2) continue
            val h = parts[0].filter { it.isDigit() }.toIntOrNull() ?: continue
            val digits = parts[1].filter { it.isDigit() }
            if (digits.isEmpty()) continue
            val m = digits.substring(0, minOf(2, digits.length)).padEnd(2, '0').toIntOrNull() ?: continue
            if (h > 23 || m > 59) continue
            val tail = clock.substringAfter('+', "")
            val extraDays = tail.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            return h * 60 + m + extraDays * 1440
        }
        return null
    }

    /** Delay cells arrive as "12", "12 min", "+12", "On Time" or "-3". */
    private fun JSONObject.delay(vararg keys: String): Int? {
        for (k in keys) {
            val v = opt(k) ?: continue
            val s = v.toString()
            if (s.isBlank()) continue
            if (s.contains("on time", true)) return 0
            val clockDelay = Regex("^([+-]?)(\\d{1,2}):(\\d{2})$").matchEntire(s.trim())
            if (clockDelay != null) {
                val hours = clockDelay.groupValues[2].toInt()
                val minutes = clockDelay.groupValues[3].toInt()
                if (minutes < 60) return (hours * 60 + minutes) *
                    (if (clockDelay.groupValues[1] == "-") -1 else 1)
                continue
            }
            val n = Regex("(-?\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) return n
        }
        return null
    }

    /**
     * The instant the feed says *it* generated this data, if it says so at all. Formats seen upstream are an
     * epoch in seconds and several `dd-MM-yyyy HH:mm` spellings; anything else stays null, because guessing
     * would put a wrong "updated at" on the screen and that is the one label a traveller actually trusts.
     */
    private fun JSONObject.stampMillis(vararg keys: String): Long? {
        for (k in keys) {
            val v = opt(k) ?: continue
            if (v is Number && v.toLong() > 1_500_000_000L) return v.toLong() * 1000L
            val s = v.toString().trim()
            if (s.length < 10) continue
            for (pattern in listOf("dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd-MM-yy HH:mm", "yyyy-MM-dd HH:mm:ss")) {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH)
                fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                val d = runCatching { fmt.parse(s) }.getOrNull()
                if (d != null) return d.time
            }
            val digits = s.filter { it.isDigit() }
            if (digits.length in 10..13) {
                val n = digits.toLongOrNull() ?: continue
                return if (digits.length == 10) n * 1000L else n
            }
        }
        return null
    }
}
