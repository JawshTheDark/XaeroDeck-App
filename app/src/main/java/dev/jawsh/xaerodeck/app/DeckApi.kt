package dev.jawsh.xaerodeck.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PlayerState(val x: Double, val y: Double, val z: Double, val yaw: Float)
data class EntityDot(val name: String?, val x: Double, val z: Double, val type: Char)
data class TextSpan(val text: String, val color: Int?, val bold: Boolean,
                    val italic: Boolean, val underline: Boolean, val strike: Boolean)
data class DeckNotification(val id: Long, val time: Long, val text: String,
                            val spans: List<TextSpan> = emptyList())

/** Build Android styled text from Minecraft chat spans. */
fun List<TextSpan>.toSpannable(defaultColor: Int = 0xFFFFFFFF.toInt()): CharSequence {
    if (isEmpty()) return ""
    val sb = android.text.SpannableStringBuilder()
    for (s in this) {
        val start = sb.length
        sb.append(s.text)
        val end = sb.length
        sb.setSpan(android.text.style.ForegroundColorSpan(s.color ?: defaultColor),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (s.bold && s.italic) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD_ITALIC),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        else if (s.bold) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        else if (s.italic) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (s.underline) sb.setSpan(android.text.style.UnderlineSpan(),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (s.strike) sb.setSpan(android.text.style.StrikethroughSpan(),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}
data class DeckStats(val bps: Double, val ping: Int, val tps: Double,
                     val totems: Int, val elytra: Int, val hp: Double)
data class DeckEffect(val name: String, val seconds: Int, val color: Int)
data class DirtyRegion(val dim: String, val x: Int, val z: Int)
data class Status(
    val inGame: Boolean,
    val player: PlayerState?,
    val dimension: String?,
    val worldId: String?,
    val entities: List<EntityDot> = emptyList(),
    val notifications: List<DeckNotification> = emptyList(),
    val stats: DeckStats? = null,
    val chat: List<DeckNotification> = emptyList(),
    val dirty: List<DirtyRegion> = emptyList(),
    val effects: List<DeckEffect> = emptyList(),
    val autopilot: DoubleArray? = null,
    val meteorRev: Int = 0
)

private fun parseStatus(o: JSONObject): Status {
    val player = o.optJSONObject("player")?.let {
        PlayerState(it.getDouble("x"), it.getDouble("y"), it.getDouble("z"), it.getDouble("yaw").toFloat())
    }
    val ents = ArrayList<EntityDot>()
    o.optJSONArray("entities")?.let { arr ->
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            ents.add(EntityDot(e.optString("n", "").ifEmpty { null },
                e.getDouble("x"), e.getDouble("z"),
                e.optString("t", "p").firstOrNull() ?: 'p'))
        }
    }
    fun notifList(key: String): List<DeckNotification> {
        val out = ArrayList<DeckNotification>()
        o.optJSONArray(key)?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val spans = ArrayList<TextSpan>()
                n.optJSONArray("spans")?.let { sa ->
                    for (j in 0 until sa.length()) {
                        val s = sa.getJSONObject(j)
                        val color = s.optString("c", "").takeIf { it.startsWith("#") }?.let {
                            try { 0xFF000000.toInt() or it.substring(1).toInt(16) } catch (e: Exception) { null }
                        }
                        spans.add(TextSpan(s.optString("t"), color,
                            s.has("b"), s.has("i"), s.has("u"), s.has("s")))
                    }
                }
                out.add(DeckNotification(n.optLong("id"), n.optLong("t"), n.optString("text"), spans))
            }
        }
        return out
    }
    val stats = o.optJSONObject("stats")?.let {
        DeckStats(it.optDouble("bps", 0.0), it.optInt("ping"), it.optDouble("tps", 20.0),
            it.optInt("totems"), it.optInt("elytra", -1), it.optDouble("hp"))
    }
    val dirty = ArrayList<DirtyRegion>()
    o.optJSONArray("dirty")?.let { arr ->
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            dirty.add(DirtyRegion(d.optString("d"), d.optInt("x"), d.optInt("z")))
        }
    }
    val effects = ArrayList<DeckEffect>()
    o.optJSONArray("effects")?.let { arr ->
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            effects.add(DeckEffect(e.optString("n"), e.optInt("s"), e.optInt("c")))
        }
    }
    val autopilot = o.optJSONObject("autopilot")?.let {
        doubleArrayOf(it.optDouble("x"), it.optDouble("z"))
    }
    return Status(o.optBoolean("inGame"), player,
        o.optString("dimension", null), o.optString("worldId", null), ents,
        notifList("notifications"), stats, notifList("chat"), dirty, effects, autopilot,
        o.optInt("meteorRev", 0))
}

data class DeckWaypoint(
    val name: String, val initials: String,
    val x: Int, val y: Int, val z: Int, val color: Int, val set: String
)

class DeckApi(private val baseDir: File) {
    @Volatile
    var baseUrl: String = ""

    @Volatile
    var token: String = ""

    private fun get(path: String, etag: String? = null): Pair<Int, ByteArray?> {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 20000
        if (token.isNotEmpty()) conn.setRequestProperty("X-Deck-Token", token)
        if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        return try {
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.use { it.readBytes() } else null
            if (code == 200) {
                conn.getHeaderField("ETag")?.let { lastEtag = it }
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Volatile
    private var lastEtag: String? = null

    suspend fun status(): Status? = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/status")
            if (code != 200 || body == null) return@withContext null
            parseStatus(JSONObject(String(body)))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun waypoints(): List<DeckWaypoint>? = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/waypoints")
            if (code != 200 || body == null) return@withContext null
            val arr = JSONArray(String(body))
            val list = ArrayList<DeckWaypoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    DeckWaypoint(
                        o.optString("name"), o.optString("initials"),
                        o.optInt("x"), o.optInt("y"), o.optInt("z"),
                        o.optInt("color"), o.optString("set")
                    )
                )
            }
            // persist for offline browsing
            worldCacheDir()?.resolve("waypoints.json")?.writeBytes(body)
            list
        } catch (e: Exception) {
            null
        }
    }

    fun cachedWaypoints(): List<DeckWaypoint> {
        val f = worldCacheDir()?.resolve("waypoints.json") ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DeckWaypoint(
                    o.optString("name"), o.optString("initials"),
                    o.optInt("x"), o.optInt("y"), o.optInt("z"),
                    o.optInt("color"), o.optString("set")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addWaypoint(x: Int, z: Int, name: String, y: Int? = null, color: Int? = null): Boolean =
        waypointCall("POST", JSONObject().put("x", x).put("z", z).put("name", name).apply {
            if (y != null) put("y", y)
            if (color != null) put("color", color)
        })

    suspend fun deleteWaypoint(w: DeckWaypoint): Boolean =
        waypointCall("DELETE", JSONObject().put("x", w.x).put("z", w.z).put("name", w.name))

    private suspend fun waypointCall(method: String, body: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$baseUrl/api/waypoints").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.doOutput = true
            conn.connectTimeout = 4000
            if (token.isNotEmpty()) conn.setRequestProperty("X-Deck-Token", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(body.toString().toByteArray())
            }
            val ok = conn.responseCode == 200 &&
                    JSONObject(String(conn.inputStream.use { s -> s.readBytes() })).optBoolean("ok")
            conn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    data class Dimension(val id: String, val key: String, val current: Boolean)

    suspend fun dimensions(): List<Dimension> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/dimensions")
            if (code != 200 || body == null) return@withContext emptyList()
            val arr = JSONArray(String(body))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Dimension(o.optString("id"), o.optString("key"), o.optBoolean("current"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Blocking SSE reader; invokes onStatus for each pushed update until the
     * connection drops. Call from a background coroutine; returns on disconnect.
     */
    fun streamBlocking(onStatus: (Status) -> Unit) {
        val conn = URL("$baseUrl/api/stream").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 15000
        try {
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    onStatus(parseStatus(JSONObject(line.substring(6))))
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    data class MeteorModule(val name: String, val title: String, val category: String,
                            val active: Boolean, val description: String)

    data class MeteorSetting(val group: String, val name: String, val title: String, val type: String,
                             val value: String, val options: List<String>, val description: String,
                             val sliderMin: Double = 0.0, val sliderMax: Double = 0.0, val decimals: Int = 0)

    suspend fun meteorModules(): List<MeteorModule> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/meteor/modules")
            if (code != 200 || body == null) return@withContext emptyList()
            val arr = JSONArray(String(body))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MeteorModule(o.optString("name"), o.optString("title"), o.optString("category"),
                    o.optBoolean("active"), o.optString("description"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun meteorToggle(name: String, active: Boolean): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/meteor/toggle", JSONObject().put("name", name).put("active", active))
            ?.optBoolean("ok") == true
    }

    suspend fun meteorSettings(module: String): List<MeteorSetting> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/meteor/settings?module=" +
                    java.net.URLEncoder.encode(module, "UTF-8"))
            if (code != 200 || body == null) return@withContext emptyList()
            val arr = JSONArray(String(body))
            val out = ArrayList<MeteorSetting>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val settings = g.getJSONArray("settings")
                for (j in 0 until settings.length()) {
                    val s = settings.getJSONObject(j)
                    val opts = ArrayList<String>()
                    s.optJSONArray("options")?.let { oa ->
                        for (k in 0 until oa.length()) opts.add(oa.getString(k))
                    }
                    out.add(MeteorSetting(g.optString("group"), s.optString("name"), s.optString("title"),
                        s.optString("type"), s.optString("value"), opts, s.optString("description"),
                        s.optDouble("sliderMin", 0.0), s.optDouble("sliderMax", 0.0),
                        s.optInt("decimals", 0)))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun meteorSetSetting(module: String, setting: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            postJson("/api/meteor/setting", JSONObject()
                .put("module", module).put("setting", setting).put("value", value))
                ?.optBoolean("ok") == true
        }

    suspend fun sendChat(text: String): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/chat", JSONObject().put("text", text))?.optBoolean("ok") == true
    }

    suspend fun baritoneGoto(x: Int, z: Int): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/baritone", JSONObject().put("action", "goto").put("x", x).put("z", z))
            ?.optBoolean("ok") == true
    }

    suspend fun baritoneCancel(): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/baritone", JSONObject().put("action", "cancel"))?.optBoolean("ok") == true
    }

    suspend fun autopilotFly(x: Int, z: Int): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/baritone", JSONObject().put("action", "flyto").put("x", x).put("z", z))
            ?.optBoolean("ok") == true
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        return try {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            if (token.isNotEmpty()) conn.setRequestProperty("X-Deck-Token", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = if (conn.responseCode == 200)
                JSONObject(String(conn.inputStream.use { it.readBytes() })) else null
            conn.disconnect()
            resp
        } catch (e: Exception) {
            null
        }
    }

    @Volatile
    var currentWorldKey: String = "unknown"

    fun worldCacheDir(): File? {
        val safe = currentWorldKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(baseDir, "worlds/$safe")
        dir.mkdirs()
        return dir
    }

    /** Fetch tile PNG, honoring disk cache + ETag. Returns bitmap or null. */
    suspend fun tile(dim: String, rx: Int, rz: Int, forceNetwork: Boolean,
                     overview: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val dir = worldCacheDir() ?: return@withContext null
        val prefix = if (overview) "ov" else "tile"
        val png = File(dir, "${prefix}_${rx}_$rz.png")
        val etagFile = File(dir, "${prefix}_${rx}_$rz.etag")
        if (!forceNetwork && png.exists()) {
            return@withContext BitmapFactory.decodeFile(png.path)
        }
        try {
            val etag = if (png.exists() && etagFile.exists()) etagFile.readText() else null
            val dimPart = if (dim.isEmpty()) "" else "$dim/"
            val ep = if (overview) "overview" else "tile"
            val (code, body) = get("/api/$ep/$dimPart$rx/$rz.png", etag)
            when {
                code == 200 && body != null -> {
                    png.writeBytes(body)
                    lastEtag?.let { etagFile.writeText(it) }
                    BitmapFactory.decodeByteArray(body, 0, body.size)
                }
                code == 304 -> BitmapFactory.decodeFile(png.path)
                png.exists() -> BitmapFactory.decodeFile(png.path)
                else -> null
            }
        } catch (e: Exception) {
            if (png.exists()) BitmapFactory.decodeFile(png.path) else null
        }
    }

    data class OracleLegendEntry(val label: String, val color: Int)
    data class OracleLegend(val enabled: Boolean, val entries: List<OracleLegendEntry>)

    /** Oracle era-overlay tile, same coords/ETag semantics as tile(). */
    suspend fun oracleTile(dim: String, rx: Int, rz: Int, forceNetwork: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            val dir = worldCacheDir()?.resolve("oracle")?.apply { mkdirs() } ?: return@withContext null
            val png = File(dir, "tile_${rx}_$rz.png")
            val etagFile = File(dir, "tile_${rx}_$rz.etag")
            if (!forceNetwork && png.exists()) {
                return@withContext BitmapFactory.decodeFile(png.path)
            }
            try {
                val etag = if (png.exists() && etagFile.exists()) etagFile.readText() else null
                val dimPart = if (dim.isEmpty()) "" else "$dim/"
                val (code, body) = get("/api/oracle/tile/$dimPart$rx/$rz.png", etag)
                when {
                    code == 200 && body != null -> {
                        png.writeBytes(body)
                        lastEtag?.let { etagFile.writeText(it) }
                        BitmapFactory.decodeByteArray(body, 0, body.size)
                    }
                    code == 304 -> BitmapFactory.decodeFile(png.path)
                    png.exists() -> BitmapFactory.decodeFile(png.path)
                    else -> null
                }
            } catch (e: Exception) {
                if (png.exists()) BitmapFactory.decodeFile(png.path) else null
            }
        }

    suspend fun oracleLegend(): OracleLegend? = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/api/oracle/legend")
            if (code != 200 || body == null) return@withContext null
            val o = JSONObject(String(body))
            val entries = ArrayList<OracleLegendEntry>()
            o.optJSONArray("entries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val color = e.optString("color", "").takeIf { it.startsWith("#") }?.let {
                        try { 0xFF000000.toInt() or it.substring(1).toInt(16) } catch (ex: Exception) { null }
                    } ?: continue
                    entries.add(OracleLegendEntry(e.optString("label"), color))
                }
            }
            OracleLegend(o.optBoolean("enabled"), entries)
        } catch (e: Exception) {
            null
        }
    }
}
