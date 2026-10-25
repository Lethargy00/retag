package app.retag.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    /** MusicBrainz and LRCLIB ask apps to identify themselves. */
    const val USER_AGENT = "Retag/1.0 (open-source Android music tagger)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Returns null on 404. Retries briefly when the server says it's busy. */
    fun getBytes(url: String): ByteArray? {
        var attempt = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = c.responseCode
                if (code == 404) return null
                if ((code == 503 || code == 429) && attempt < 3) {
                    attempt++
                    Thread.sleep(1500L * attempt)
                    continue
                }
                if (code !in 200..299) throw HttpException(code, "${URL(url).host} answered HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
    }

    fun getJson(url: String): JSONObject? = getBytes(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }

    fun getJsonArray(url: String): JSONArray? = getBytes(url)?.let { JSONArray(String(it, Charsets.UTF_8)) }
}

/** Spaces requests out so we stay within each service's rate limit. */
class RateLimiter(private val minIntervalMs: Long) {
    private var last = 0L

    @Synchronized
    fun acquire() {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }
}

/** optString returns "null" for JSON nulls; this returns "" instead. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "").trim()

fun JSONObject.posInt(key: String): String = optInt(key, 0).let { if (it > 0) it.toString() else "" }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
