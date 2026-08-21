package dev.jawsh.xaerodeck.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainActivity : ComponentActivity() {
    private lateinit var api: DeckApi
    private lateinit var map: MapView
    private var streamJob: Job? = null
    private val tileSemaphore = Semaphore(6)
    private val pendingTiles = HashSet<String>()
    private val lastOverviewReq = HashMap<String, Long>()

    private var viewedDim = ""
    private var playerDim: String? = null
    @Volatile private var ignoredServer: String? = null // last spoof-ignored beacon ip
    private var lastStatus: Status? = null
    private var lastTrailX = Double.NaN
    private var lastTrailZ = 0.0
    private val seenNotifs = HashSet<Long>()

    // Compose-observed state
    private val statusS = mutableStateOf<Status?>(null)
    private val connectedS = mutableStateOf(false)
    private val waypointsS = mutableStateOf<List<DeckWaypoint>>(emptyList())
    private val dimsS = mutableStateOf<List<DeckApi.Dimension>>(emptyList())
    private val followS = mutableStateOf(true)
    private val navModeS = mutableStateOf(0) // 0 off, 1 walk (#goto), 2 fly (autopilot)
    private val etaS = mutableStateOf<String?>(null)
    private val stashArmS = mutableStateOf(false)
    private var smoothBps = 0.0
    private var walkTarget: DoubleArray? = null

    data class Sighting(val name: String, val x: Int, val z: Int, val dim: String,
                        val time: Long, val friend: Boolean)
    private val sightingsS = mutableStateOf<List<Sighting>>(emptyList())
    private var sightingsWorld: String? = null
    private var sightingsDirty = false

    // connection-lost watchdog: armed once we've seen the player in a world
    private var connSeenGame = false
    private var connLostAt = 0L
    private var connAlerted = false
    private val panelS = mutableStateOf(true)
    private val logS = mutableStateOf("")
    private val autoAddrS = mutableStateOf("")
    private val browsingS = mutableStateOf<String?>(null)
    private val toasts = mutableStateListOf<Pair<Long, AnnotatedString>>()
    private val chatLog = mutableStateListOf<AnnotatedString>()
    private var toastId = 0L
    private val overlayS = mutableStateOf(false)
    private val overlayModulesS = mutableStateOf<List<DeckApi.MeteorModule>>(emptyList())
    private var lastMeteorRev = 0
    private val showOracleS = mutableStateOf(false)
    private val oracleLegendS = mutableStateOf<List<DeckApi.OracleLegendEntry>>(emptyList())
    private val showLocS = mutableStateOf(false)
    private val showLocFilterS = mutableStateOf(false)
    private val routeEditS = mutableStateOf(false)
    private val routeLenS = mutableStateOf(0)

    // watchdog
    private val alertS = mutableStateOf<Pair<String, Boolean>?>(null) // text to sticky
    private var alertId = 0L
    private var alertRingtone: android.media.Ringtone? = null
    private var wdPlayerArmed = true
    private var wdPlayerLastSeen = 0L
    private var wdPrevTotems: Int? = null
    private var wdHpArmed = true
    private var wdElytraArmed = true
    private var wdWorldId: String? = null
    private var wdGraceUntil = 0L

    private val soundPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION")
        val uri: android.net.Uri? = res.data?.getParcelableExtra(
            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        getSharedPreferences("deck", MODE_PRIVATE).edit()
            .putString("alertSound", uri?.toString() ?: "").apply()
        log(if (uri != null) "ALERT SOUND SET" else "ALERT SOUND: SILENT")
    }

    private fun pickAlertSound() {
        val cur = getSharedPreferences("deck", MODE_PRIVATE).getString("alertSound", null)
        val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_ALL)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "XaeroDeck alert sound")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                cur?.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse))
        }
        soundPicker.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = DeckApi(filesDir)
        map = MapView(this)
        // the button rail lives on the left edge now — keep the W compass clear of it
        map.compassWestInsetPx = resources.displayMetrics.density * 150
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        applyAddress(prefs.getString("addr", "") ?: "")
        api.currentWorldKey = prefs.getString("worldKey", "unknown") ?: "unknown"
        api.token = prefs.getString("token", "") ?: ""
        map.showPlayers = prefs.getBoolean("showPlayers", true)
        map.showHostiles = prefs.getBoolean("showHostiles", true)
        map.showGrid = prefs.getBoolean("showGrid", false)
        map.showTrail = prefs.getBoolean("showTrail", true)
        map.showOracle = prefs.getBoolean("showOracle", false)
        showOracleS.value = map.showOracle
        overlayS.value = prefs.getBoolean("meteorOverlay", false)
        if (prefs.getBoolean("keepAwake", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        map.onFollowChanged = { followS.value = it }
        map.tileRequester = { rx, rz -> requestTile(rx, rz, false) }
        map.oracleRequester = { rx, rz -> requestOracleTile(rx, rz, false) }
        map.waypoints = api.cachedWaypoints()
        waypointsS.value = map.waypoints
        loadTrail()
        loadLocTypes()

        setContent { HudScreen() }
        restartStream()
        startSlowLoop()
        startDiscovery()
    }

    // ---------- networking / logic (unchanged behavior) ----------

    private fun applyAddress(addr: String) {
        api.baseUrl = when {
            addr.isEmpty() -> api.baseUrl
            addr.startsWith("http") -> addr.trimEnd('/')
            else -> "http://$addr:8399"
        }
    }

    private fun log(msg: String) {
        logS.value = msg
    }

    private fun requestTile(rx: Int, rz: Int, force: Boolean, level: Int = map.tileLevel()) {
        val key = "${viewedDim}_${map.levelPrefix(level)}_${rx}_$rz"
        val dim = viewedDim
        synchronized(pendingTiles) { if (!pendingTiles.add(key)) return }
        lifecycleScope.launch(Dispatchers.IO) {
            tileSemaphore.withPermit {
                // Unchanged (304) leaves the existing cached bitmap untouched — no putTile.
                when (val res = api.tile(dim, rx, rz, force, level)) {
                    is TileResult.Loaded ->
                        if (dim == viewedDim) launch(Dispatchers.Main) { map.putTile(rx, rz, res.bitmap, level) }
                    is TileResult.Missing ->
                        if (dim == viewedDim) launch(Dispatchers.Main) { map.putTile(rx, rz, null, level) }
                    is TileResult.Unchanged -> {}
                }
            }
            synchronized(pendingTiles) { pendingTiles.remove(key) }
        }
    }

    private fun requestOracleTile(rx: Int, rz: Int, force: Boolean) {
        val key = "${viewedDim}_o_${rx}_$rz"
        val dim = viewedDim
        synchronized(pendingTiles) { if (!pendingTiles.add(key)) return }
        lifecycleScope.launch(Dispatchers.IO) {
            tileSemaphore.withPermit {
                when (val res = api.oracleTile(dim, rx, rz, force)) {
                    is TileResult.Loaded ->
                        if (dim == viewedDim) launch(Dispatchers.Main) { map.putOracleTile(rx, rz, res.bitmap) }
                    is TileResult.Missing ->
                        if (dim == viewedDim) launch(Dispatchers.Main) { map.putOracleTile(rx, rz, null) }
                    is TileResult.Unchanged -> {}
                }
            }
            synchronized(pendingTiles) { pendingTiles.remove(key) }
        }
    }

    private fun switchDim(dim: String) {
        if (dim == viewedDim) return
        viewedDim = dim
        browsingS.value = null
        updateWorldKey()
        map.clearTiles()
        synchronized(pendingTiles) { pendingTiles.clear() }
        map.waypoints = if (effectiveViewedDim() == playerDim) api.cachedWaypoints() else emptyList()
        waypointsS.value = map.waypoints
        loadTrail()
    }

    private fun effectiveViewedDim(): String? =
        if (viewedDim.isEmpty()) playerDim
        else "minecraft:$viewedDim".takeIf { !viewedDim.contains(":") } ?: viewedDim

    private fun updateWorldKey() {
        val st = lastStatus ?: return
        if (browsingS.value != null) return
        val dimForCache = if (viewedDim.isEmpty()) st.dimension else viewedDim
        val key = "${st.worldId}_$dimForCache"
        if (key != api.currentWorldKey) {
            api.currentWorldKey = key
            getSharedPreferences("deck", MODE_PRIVATE).edit().putString("worldKey", key).apply()
        }
    }

    private fun onStatus(st: Status?) {
        runOnUiThread {
            statusS.value = st
            connectedS.value = st != null
            if (st != null && st.inGame && st.player != null) {
                val dimChanged = playerDim != null && playerDim != st.dimension
                playerDim = st.dimension
                lastStatus = st
                if (dimChanged && viewedDim.isEmpty()) {
                    map.clearTiles()
                    synchronized(pendingTiles) { pendingTiles.clear() }
                    saveTrail(); loadTrail()
                }
                updateWorldKey()
                for (n in st.notifications) showNotification(n)
                for (c in st.chat) {
                    chatLog.add(if (c.spans.isNotEmpty()) c.spans.toAnnotated() else AnnotatedString(c.text))
                    if (chatLog.size > 200) chatLog.removeAt(0)
                }
                for (d in st.dirty) {
                    val dimMatch = if (viewedDim.isEmpty())
                        st.dimension?.endsWith(d.dim) == true else viewedDim == d.dim
                    if (dimMatch) {
                        map.tileChanged(d.x, d.z)
                        val lvl = map.tileLevel()
                        if (lvl > 0) {
                            val div = if (lvl == 1) 4 else 8
                            val ox = Math.floorDiv(d.x, div); val oz = Math.floorDiv(d.z, div)
                            val k = "${lvl}_${ox}_$oz"
                            val now = System.currentTimeMillis()
                            if (now - (lastOverviewReq[k] ?: 0) > 3000) {
                                lastOverviewReq[k] = now
                                requestTile(ox, oz, force = true, level = lvl)
                            }
                        } else {
                            requestTile(d.x, d.z, force = true, level = 0)
                        }
                    }
                }
                val p = st.player
                if (lastTrailX.isNaN() || Math.abs(p.x - lastTrailX) + Math.abs(p.z - lastTrailZ) >= 8) {
                    lastTrailX = p.x; lastTrailZ = p.z
                    map.trail.add(doubleArrayOf(p.x, p.z))
                    if (map.trail.size > 5000) map.trail.removeAt(0)
                }
                val viewingPlayerDim = viewedDim.isEmpty() || effectiveViewedDim() == st.dimension
                map.player = if (viewingPlayerDim) st.player else null
                map.entities = if (viewingPlayerDim) st.entities else emptyList()
                map.autopilotTarget = if (viewingPlayerDim) st.autopilot else null
                map.activeRoute = if (viewingPlayerDim) st.route else null
                if (st.meteorRev != lastMeteorRev) {
                    lastMeteorRev = st.meteorRev
                    if (overlayS.value) lifecycleScope.launch {
                        overlayModulesS.value = api.meteorModules()
                    }
                }
                val bpsNow = st.stats?.bps ?: 0.0
                smoothBps = if (smoothBps <= 0.01) bpsNow else smoothBps * 0.8 + bpsNow * 0.2
                walkTarget?.let { t ->
                    if (Math.hypot(p.x - t[0], p.z - t[1]) < 6.0) walkTarget = null
                }
                etaS.value = computeEta(st)
                recordSightings(st)
                connSeenGame = true
                connLostAt = 0L
                connAlerted = false
                watchdog(st)
            } else {
                map.player = null
                etaS.value = null
                if (st == null && connLostAt == 0L) connLostAt = System.currentTimeMillis()
            }
            // No unconditional invalidate here: the player/entities/waypoints/
            // autopilotTarget setters each post an invalidate when their drawn
            // state actually changes, so redraws still track live updates.
        }
    }

    /**
     * Intel journal: every player the radar streams past gets logged per world.
     * A sighting within 5 min and 500 blocks of that player's last entry counts
     * as the same encounter and just refreshes it (rate-limited to avoid list
     * churn while trailing someone); anything else is a new entry.
     */
    private fun recordSightings(st: Status) {
        val wid = st.worldId ?: return
        if (wid != sightingsWorld) {
            sightingsWorld = wid
            sightingsS.value = loadSightings(wid)
        }
        val now = System.currentTimeMillis()
        var list = sightingsS.value
        var changed = false
        val dim = st.dimension?.substringAfter(':') ?: "overworld"
        for (e in st.entities) {
            if (e.type != 'p' && e.type != 'f') continue
            val name = e.name ?: continue
            val last = list.firstOrNull { it.name == name }
            if (last != null && now - last.time < 300000 &&
                Math.hypot(e.x - last.x, e.z - last.z) < 500.0) {
                if (now - last.time >= 30000 || Math.hypot(e.x - last.x, e.z - last.z) >= 32.0) {
                    list = listOf(last.copy(x = e.x.toInt(), z = e.z.toInt(), dim = dim, time = now)) +
                            list.filter { it !== last }
                    changed = true
                }
            } else {
                list = listOf(Sighting(name, e.x.toInt(), e.z.toInt(), dim, now, e.type == 'f')) + list
                changed = true
            }
        }
        if (changed) {
            if (list.size > 200) list = list.take(200)
            sightingsS.value = list
            sightingsDirty = true
        }
    }

    private fun sightingsFile(worldId: String): java.io.File {
        val dir = java.io.File(filesDir, "sightings").apply { mkdirs() }
        return java.io.File(dir, worldId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    }

    private fun loadSightings(worldId: String): List<Sighting> = try {
        val arr = org.json.JSONArray(sightingsFile(worldId).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Sighting(o.getString("n"), o.getInt("x"), o.getInt("z"),
                o.optString("d", "overworld"), o.getLong("t"), o.optBoolean("f"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveSightings() {
        val wid = sightingsWorld ?: return
        if (!sightingsDirty) return
        sightingsDirty = false
        try {
            val arr = org.json.JSONArray()
            for (s in sightingsS.value) {
                arr.put(org.json.JSONObject().put("n", s.name).put("x", s.x).put("z", s.z)
                    .put("d", s.dim).put("t", s.time).put("f", s.friend))
            }
            sightingsFile(wid).writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "NOW"
            s < 3600 -> "${s / 60}M"
            s < 86400 -> "${s / 3600}H"
            else -> "${s / 86400}D"
        }
    }

    /**
     * Time-to-arrive from remaining path length / smoothed speed. Routes count
     * every remaining leg; loops show the full-lap time instead (they never
     * arrive). Recomputed on every status frame, so it tracks speed changes.
     */
    private fun computeEta(st: Status): String? {
        val p = st.player ?: return null
        var label = "ETA"
        val dist: Double
        val route = st.route
        if (route != null && route.first.isNotEmpty()) {
            val (pts, idx, loop) = route
            if (loop) {
                var lap = Math.hypot(pts.last()[0] - pts[0][0], pts.last()[1] - pts[0][1])
                for (j in 0 until pts.size - 1)
                    lap += Math.hypot(pts[j][0] - pts[j + 1][0], pts[j][1] - pts[j + 1][1])
                label = "LAP"; dist = lap
            } else {
                val i0 = idx.coerceIn(0, pts.size - 1)
                var d = Math.hypot(p.x - pts[i0][0], p.z - pts[i0][1])
                for (j in i0 until pts.size - 1)
                    d += Math.hypot(pts[j][0] - pts[j + 1][0], pts[j][1] - pts[j + 1][1])
                dist = d
            }
        } else if (st.autopilot != null) {
            dist = Math.hypot(p.x - st.autopilot[0], p.z - st.autopilot[1])
        } else {
            val t = walkTarget ?: return null
            dist = Math.hypot(p.x - t[0], p.z - t[1])
        }
        val distTxt = if (dist < 1000) "${dist.toInt()}M" else "%.1fKM".format(dist / 1000.0)
        if (smoothBps < 1.5) return "$label --:-- · $distTxt"
        fun fmt(sec: Long) = when {
            sec >= 36000 -> ">10H"
            sec >= 3600 -> "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
            else -> "%d:%02d".format(sec / 60, sec % 60)
        }
        val time = fmt((dist / smoothBps).toLong())
        // long overworld hauls: what the same trip costs through the nether
        val nether = if (label == "ETA" && dist >= 3000 &&
            st.dimension == "minecraft:overworld")
            " · NETHER ${fmt((dist / 8 / smoothBps).toLong())}" else ""
        return "$label $time · $distTxt$nether"
    }

    private fun showNotification(n: DeckNotification) {
        if (!seenNotifs.add(n.id)) return
        if (seenNotifs.size > 400) { seenNotifs.clear(); seenNotifs.add(n.id) }
        if (n.text.startsWith("💀")) {
            val vib = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
        if (!getSharedPreferences("deck", MODE_PRIVATE).getBoolean("showNotifs", true)) return
        val text = if (n.spans.isNotEmpty()) n.spans.toAnnotated() else AnnotatedString(n.text)
        val id = toastId++
        toasts.add(id to text)
        while (toasts.size > 4) toasts.removeAt(0)
        lifecycleScope.launch {
            delay(5000)
            toasts.removeAll { it.first == id }
        }
    }

    private fun watchdog(st: Status) {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        if (!prefs.getBoolean("wdEnabled", true)) return
        val now = System.currentTimeMillis()
        // server join / world hop: reset baselines and hold alerts while stats settle
        if (st.worldId != wdWorldId) {
            wdWorldId = st.worldId
            wdPrevTotems = null
            wdPlayerArmed = true
            wdHpArmed = true
            wdElytraArmed = true
            wdGraceUntil = now + 10000
        }
        if (now < wdGraceUntil) {
            st.stats?.let { wdPrevTotems = it.totems }
            return
        }
        val players = st.entities.filter { it.type == 'p' }
        if (players.isNotEmpty()) {
            if (wdPlayerArmed && prefs.getBoolean("wdPlayer", true)) {
                wdPlayerArmed = false
                triggerAlert("PLAYER: ${players.first().name ?: "?"}", sticky = true)
            }
            wdPlayerLastSeen = now
        } else if (!wdPlayerArmed && now - wdPlayerLastSeen >= 10000) {
            wdPlayerArmed = true
        }
        st.stats?.let { s ->
            wdPrevTotems?.let { prev ->
                if (s.totems < prev && prefs.getBoolean("wdTotem", true))
                    triggerAlert("TOTEM POP ×${s.totems}", sticky = false)
            }
            wdPrevTotems = s.totems
            val hpThresh = prefs.getInt("wdHpThresh", 8)
            if (wdHpArmed && s.hp <= hpThresh && prefs.getBoolean("wdHp", true)) {
                wdHpArmed = false
                triggerAlert("HP LOW: %.0f".format(s.hp), sticky = false)
            } else if (!wdHpArmed && s.hp > hpThresh + 4) {
                wdHpArmed = true
            }
            val elyThresh = prefs.getInt("wdElytraThresh", 15)
            if (wdElytraArmed && s.elytra in 1..elyThresh && prefs.getBoolean("wdElytra", true)) {
                wdElytraArmed = false
                triggerAlert("ELYTRA LOW: ${s.elytra}%", sticky = false)
            } else if (!wdElytraArmed && s.elytra > elyThresh) {
                wdElytraArmed = true
            }
        }
    }

    private fun triggerAlert(text: String, sticky: Boolean) {
        val vib = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
        try {
            alertRingtone?.stop()
            val pref = getSharedPreferences("deck", MODE_PRIVATE).getString("alertSound", null)
            val uri = when {
                pref == "" -> null // user chose silent
                pref != null -> android.net.Uri.parse(pref)
                else -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            }
            alertRingtone = uri?.let { android.media.RingtoneManager.getRingtone(this, it) }?.also { it.play() }
            lifecycleScope.launch { delay(3000); alertRingtone?.stop() }
        } catch (e: Exception) {
        }
        val id = ++alertId
        alertS.value = text to sticky
        if (!sticky) lifecycleScope.launch {
            delay(10000)
            if (alertId == id) alertS.value = null
        }
    }

    private fun dismissAlert() {
        alertS.value = null
        try { alertRingtone?.stop() } catch (e: Exception) {}
    }

    private fun restartStream() {
        streamJob?.cancel()
        if (api.baseUrl.isEmpty()) return
        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    api.streamBlocking { st -> onStatus(st) }
                } catch (e: Exception) {
                    var polled = false
                    repeat(5) {
                        val st = api.status()
                        if (st != null) polled = true
                        onStatus(st)
                        delay(1000)
                    }
                    if (!polled) onStatus(null)
                }
                delay(1000)
            }
        }
    }

    private fun startSlowLoop() {
        lifecycleScope.launch {
            var tick = 0
            while (true) {
                if (connSeenGame && !connAlerted && connLostAt > 0 &&
                    System.currentTimeMillis() - connLostAt > 12000) {
                    connAlerted = true
                    val prefs = getSharedPreferences("deck", MODE_PRIVATE)
                    if (prefs.getBoolean("wdEnabled", true) && prefs.getBoolean("wdConn", true))
                        triggerAlert("⚠ CONNECTION LOST", sticky = true)
                }
                if (tick % 30 == 7) saveSightings()
                if (api.baseUrl.isNotEmpty() && lastStatus?.inGame == true && browsingS.value == null) {
                    if (tick % 10 == 0) refreshWaypoints()
                    if (tick % 15 == 0) dimsS.value = api.dimensions()
                    if (tick % 3 == 2 && showLocS.value) {
                        val halfW = map.width / 2f / map.scale
                        val halfH = map.height / 2f / map.scale
                        val cx = map.camX; val cz = map.camZ
                        val limit = 32000.0
                        val x0 = (cx - minOf(halfW.toDouble(), limit)).toInt()
                        val x1 = (cx + minOf(halfW.toDouble(), limit)).toInt()
                        val z0 = (cz - minOf(halfH.toDouble(), limit)).toInt()
                        val z1 = (cz + minOf(halfH.toDouble(), limit)).toInt()
                        val dimPath = viewedDim.ifEmpty {
                            playerDim?.substringAfter(':') ?: "overworld"
                        }
                        api.seedFeatures(dimPath, x0, z0, x1, z1)?.let { feats ->
                            map.structures = feats
                        }
                    }
                    if (tick % (if (map.usingOverview()) 10 else 3) == 0) map.retryMissing()
                    if (tick % 2 == 0 && viewedDim.isEmpty()) {
                        map.player?.let { p ->
                            val prx = Math.floorDiv(p.x.toInt(), 512)
                            val prz = Math.floorDiv(p.z.toInt(), 512)
                            for (dx in -1..1) for (dz in -1..1) requestTile(prx + dx, prz + dz, true, 0)
                        }
                    }
                    if (tick % 5 == 1) for ((rx, rz) in map.visibleRegions()) requestTile(rx, rz, true)
                }
                tick++
                delay(1000)
            }
        }
    }

    private fun startDiscovery() {
        lifecycleScope.launch(Dispatchers.IO) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("xaerodeck").apply { setReferenceCounted(false) }
            while (true) {
                val manual = getSharedPreferences("deck", MODE_PRIVATE).getString("addr", "")?.trim() ?: ""
                if (manual.isEmpty()) {
                    try {
                        lock.acquire()
                        java.net.DatagramSocket(null).use { socket ->
                            socket.reuseAddress = true
                            socket.bind(java.net.InetSocketAddress(8398))
                            socket.soTimeout = 6000
                            val buf = ByteArray(256)
                            val packet = java.net.DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length)
                            if (msg.startsWith("XAERODECK ")) {
                                val port = msg.split(" ")[1].toIntOrNull() ?: 8399
                                val ip = packet.address.hostAddress ?: ""
                                val url = "http://$ip:$port"
                                val current = api.baseUrl
                                when {
                                    current == url -> {} // already on this server — no-op
                                    // adopt only when we have no server yet, or the current
                                    // one is dead; never let a beacon redirect a live connection
                                    current.isEmpty() || !connectedS.value -> {
                                        api.baseUrl = url
                                        autoAddrS.value = ip
                                        ignoredServer = null
                                        restartStream()
                                    }
                                    // different server while connected: possible spoof — ignore
                                    else -> if (ignoredServer != ip) {
                                        ignoredServer = ip
                                        runOnUiThread {
                                            android.widget.Toast.makeText(this@MainActivity,
                                                "ignoring different server $ip",
                                                android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { lock.release() } catch (e: Exception) {}
                    }
                }
                delay(3000)
            }
        }
    }

    private suspend fun refreshWaypoints() {
        val wps = api.waypoints() ?: api.cachedWaypoints()
        if (viewedDim.isNotEmpty() && effectiveViewedDim() != playerDim) return
        map.waypoints = wps
        waypointsS.value = wps
        map.invalidate()
    }

    private fun exitRouteEdit() {
        routeEditS.value = false
        map.routeDraft.clear()
        map.clearShape()
        routeLenS.value = 0
        stashArmS.value = false
        map.invalidate()
    }

    /**
     * Stash-hider decoy: a randomized route that makes the chunk trail lie.
     * The stash sits mid-segment on a long through-line (never an endpoint,
     * never a shape center), a few spurs end in tight loiter clusters that
     * read as fake stash sites, and the route's real end is km away. Every
     * generation rolls fresh bearings/distances so there's no signature shape.
     */
    private fun stashDecoyRoute(px: Double, pz: Double, sx: Double, sz: Double): List<DoubleArray> {
        val r = java.util.Random()
        val pts = ArrayList<DoubleArray>()
        var heading = Math.atan2(sz - pz, sx - px)
        if (Math.hypot(sx - px, sz - pz) < 64) heading = r.nextDouble() * 2 * Math.PI
        var x = sx; var z = sz
        pts.add(doubleArrayOf(x, z))
        fun leg(dist: Double) {
            x += Math.cos(heading) * dist; z += Math.sin(heading) * dist
            pts.add(doubleArrayOf(x, z))
        }
        // overshoot straight past the stash so it reads as a mid-trail point
        leg(1500.0 + r.nextDouble() * 1500)
        repeat(2 + r.nextInt(2)) {
            heading += (if (r.nextBoolean()) 1 else -1) * (Math.PI / 2) + (r.nextDouble() - 0.5) * 0.6
            leg(1200.0 + r.nextDouble() * 1800)
            // fake terminus: tight cluster = "someone stopped here" bait
            for (k in 0 until 4) {
                val a = heading + Math.PI / 2 * k + r.nextDouble() * 0.5
                x += Math.cos(a) * (120 + r.nextDouble() * 120)
                z += Math.sin(a) * (120 + r.nextDouble() * 120)
                pts.add(doubleArrayOf(x, z))
            }
        }
        heading += (r.nextDouble() - 0.5) * 2.0
        leg(2500.0 + r.nextDouble() * 2500)
        return pts
    }

    private fun sendRoute(loop: Boolean) {
        val pts = map.routeDraft.toList()
        if (pts.isEmpty()) {
            log("NO POINTS :: TAP THE MAP FIRST")
            return
        }
        lifecycleScope.launch {
            val ok = api.autopilotRoute(pts, loop)
            log(when {
                !ok -> "ROUTE FAILED :: DECK-AUTOPILOT ON? TOKEN?"
                loop -> "✈ ROUTE ∞ :: ${pts.size} POINTS, LOOPING"
                else -> "✈ ROUTE :: ${pts.size} POINTS"
            })
            if (ok) exitRouteEdit()
        }
    }

    private fun refreshSlimeSeed() {
        lifecycleScope.launch {
            map.slimeSeed = api.oracleGetSeed()?.trim()?.toLongOrNull()
        }
    }

    private fun loadLocTypes() {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        prefs.getStringSet("locTypes", null)?.let {
            map.enabledTypes = it.toMutableSet()
        }
    }

    @Composable
    private fun LocFilterDialog(mono: FontFamily, onClose: () -> Unit) {
        val order = listOf(
            "stronghold" to "STRONGHOLD", "monument" to "MONUMENT", "mansion" to "MANSION",
            "village" to "VILLAGE", "outpost" to "OUTPOST", "treasure" to "BURIED TREASURE",
            "desert_temple" to "DESERT TEMPLE", "jungle_temple" to "JUNGLE TEMPLE",
            "witch_hut" to "WITCH HUT", "igloo" to "IGLOO", "shipwreck" to "SHIPWRECK",
            "ocean_ruin" to "OCEAN RUIN", "ruined_portal" to "RUINED PORTAL (OW)",
            "slime" to "SLIME CHUNKS",
            "fortress" to "FORTRESS", "bastion" to "BASTION", "ruined_portal_n" to "RUINED PORTAL (NETHER)",
            "end_city" to "END CITY", "gateway" to "END GATEWAY")
        HudDialog(mono, "LOC FILTERS", onClose, wide = true, fillHeight = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                for ((key, label) in order) {
                    var on by remember(key) { mutableStateOf(map.enabledTypes.contains(key)) }
                    val glyphColor = map.structureStyle[key]?.second ?: 0xFF44D044.toInt()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontFamily = mono, fontSize = 13.sp, color = Color(glyphColor))
                        Switch(checked = on, onCheckedChange = { v ->
                            on = v
                            if (v) map.enabledTypes.add(key) else map.enabledTypes.remove(key)
                            getSharedPreferences("deck", MODE_PRIVATE).edit()
                                .putStringSet("locTypes", map.enabledTypes.toSet()).apply()
                            map.invalidate()
                        }, colors = SwitchDefaults.colors(
                            checkedThumbColor = Hud.accent, checkedTrackColor = Hud.surfaceHi,
                            uncheckedThumbColor = Hud.sub, uncheckedTrackColor = Hud.surface))
                    }
                }
            }
        }
    }

    private fun trailFile(): java.io.File? = api.worldCacheDir()?.resolve("trail.csv")

    private fun loadTrail() {
        map.trail.clear()
        try {
            trailFile()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val p = line.split(",")
                if (p.size == 2) map.trail.add(doubleArrayOf(p[0].toDouble(), p[1].toDouble()))
            }
        } catch (e: Exception) {
        }
        lastTrailX = Double.NaN
    }

    private fun saveTrail() {
        try {
            trailFile()?.writeText(map.trail.takeLast(5000).joinToString("\n") { "${it[0]},${it[1]}" })
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        saveTrail()
        saveSightings()
    }

    // ---------- UI ----------

    @Composable
    private fun HudScreen() {
        val mono = FontFamily.Monospace
        var showChat by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var addWpAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var deleteWp by remember { mutableStateOf<DeckWaypoint?>(null) }

        LaunchedEffect(Unit) {
            if (showOracleS.value && oracleLegendS.value.isEmpty()) {
                api.oracleLegend()?.takeIf { it.enabled }?.let { oracleLegendS.value = it.entries }
            }
            map.onLongPressBlock = { x, z ->
                if (effectiveViewedDim() == playerDim || viewedDim.isEmpty()) {
                    runOnUiThread { addWpAt = x to z }
                } else log("WAYPOINTS ONLY IN CURRENT DIM")
            }
            map.onTapBlock = { x, z ->
                if (routeEditS.value) {
                    if (map.shapeMode == null) {
                        if (stashArmS.value) {
                            val p = map.player
                            map.routeDraft.clear()
                            map.routeDraft.addAll(stashDecoyRoute(
                                p?.x ?: x.toDouble(), p?.z ?: z.toDouble(),
                                x.toDouble(), z.toDouble()))
                            routeLenS.value = map.routeDraft.size
                            log("DECOY LAID :: TAP AGAIN REROLLS · GO FLIES IT")
                        } else {
                            map.routeDraft.add(doubleArrayOf(x.toDouble(), z.toDouble()))
                            routeLenS.value = map.routeDraft.size
                        }
                        map.invalidate()
                    }
                } else if (navModeS.value > 0) {
                    val snap = 28f / map.scale
                    val t = map.waypoints.minByOrNull {
                        val dx = it.x - x.toDouble(); val dz = it.z - z.toDouble(); dx * dx + dz * dz
                    }?.takeIf { Math.abs(it.x - x) <= snap && Math.abs(it.z - z) <= snap }
                    val gx = t?.x ?: x; val gz = t?.z ?: z
                    val fly = navModeS.value == 2
                    lifecycleScope.launch {
                        val ok = if (fly) api.autopilotFly(gx, gz) else api.baritoneGoto(gx, gz)
                        if (ok && !fly) walkTarget = doubleArrayOf(gx.toDouble(), gz.toDouble())
                        val verb = if (fly) "✈ FLY" else "#GOTO"
                        log(when {
                            !ok && fly -> "FLY FAILED :: DECK-AUTOPILOT MODULE ON?"
                            !ok -> "GOTO FAILED :: REMOTE-CONTROL? TOKEN?"
                            t != null -> "$verb ${t.name.uppercase()} $gx $gz"
                            else -> "$verb $gx $gz"
                        })
                    }
                }
            }
        }

        Row(Modifier.fillMaxSize().background(Hud.bg)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())

                Row(Modifier.align(Alignment.TopStart).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DimChip("LIVE", viewedDim.isEmpty()) { map.follow = true; switchDim("") }
                    for (d in dimsS.value) {
                        val label = when (d.key) {
                            "overworld" -> "OVERWORLD"; "the_nether" -> "NETHER"; "the_end" -> "END"
                            else -> d.key.uppercase()
                        }
                        DimChip(label, viewedDim == d.key) { map.follow = false; switchDim(d.key) }
                    }
                }

                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                    HudButton(if (panelS.value) "▶" else "◀") { panelS.value = !panelS.value }
                }

                if (!routeEditS.value) Column(
                    Modifier.align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 64.dp, bottom = 64.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HudButton(when (navModeS.value) {
                        1 -> "WALK"; 2 -> "FLY"; else -> "NAVIGATE"
                    }, active = navModeS.value > 0) {
                        navModeS.value = (navModeS.value + 1) % 3
                        log(when (navModeS.value) {
                            1 -> "WALK :: TAP MAP/WAYPOINT TO #GOTO"
                            2 -> "FLY :: TAP MAP/WAYPOINT TO AUTOPILOT (ELYTRAFLY + DECK-AUTOPILOT ON)"
                            else -> "NAV OFF"
                        })
                    }
                    HudButton("CHAT") { showChat = true }
                    HudButton("MODULES", active = overlayS.value) {
                        overlayS.value = !overlayS.value
                        getSharedPreferences("deck", MODE_PRIVATE).edit()
                            .putBoolean("meteorOverlay", overlayS.value).apply()
                    }
                    HudButton("ERA", active = showOracleS.value) {
                        if (showOracleS.value) {
                            showOracleS.value = false
                            map.showOracle = false
                            getSharedPreferences("deck", MODE_PRIVATE).edit()
                                .putBoolean("showOracle", false).apply()
                            map.invalidate()
                        } else lifecycleScope.launch {
                            val legend = api.oracleLegend()
                            if (legend == null || !legend.enabled) {
                                android.widget.Toast.makeText(this@MainActivity,
                                    "oracle not configured on PC", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                oracleLegendS.value = legend.entries
                                showOracleS.value = true
                                map.showOracle = true
                                getSharedPreferences("deck", MODE_PRIVATE).edit()
                                    .putBoolean("showOracle", true).apply()
                                map.invalidate()
                            }
                        }
                    }
                    Text("MARKERS", fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                        color = if (showLocS.value) Hud.onAccent else Hud.accent,
                        modifier = Modifier
                            .background(if (showLocS.value) Hud.accent else Hud.surface)
                            .border(1.dp, if (showLocS.value) Hud.accent else Hud.border)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        showLocS.value = !showLocS.value
                                        map.showStructures = showLocS.value
                                        if (!showLocS.value) map.structures = emptyList()
                                        else {
                                            log("MARKERS ON :: HOLD FOR FILTERS")
                                            refreshSlimeSeed()
                                        }
                                        map.invalidate()
                                    },
                                    onLongPress = { showLocFilterS.value = true })
                            }
                            .padding(horizontal = 18.dp, vertical = 14.dp))
                    HudButton("ROUTE", active = routeEditS.value) {
                        routeEditS.value = !routeEditS.value
                        if (routeEditS.value) {
                            map.follow = false
                            log("ROUTE :: TAP POINTS · GO=FLY · GO∞=LOOP · SPIRAL/AREA=PATTERNS")
                        } else exitRouteEdit()
                        map.invalidate()
                    }
                    HudButton("METEOR") {
                        val i = Intent(this@MainActivity, MeteorActivity::class.java)
                        i.putExtra("baseUrl", api.baseUrl)
                        startActivity(i)
                    }
                    HudButton("CONFIG") { showSettings = true }
                }

                Column(Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End) {
                    if (showOracleS.value && oracleLegendS.value.isNotEmpty()) {
                        Column(Modifier.background(Color(0xD0100D17)).border(1.dp, Hud.border)
                            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (e in oracleLegendS.value) OracleLegendRow(mono, Color(e.color), e.label)
                            OracleLegendRow(mono, Hud.red, "MODIFIED")
                        }
                    }
                    HudButton("+", big = true) { map.scale = (map.scale * 1.5f).coerceIn(0.03f, 16f); map.invalidate() }
                    HudButton("−", big = true) { map.scale = (map.scale / 1.5f).coerceIn(0.03f, 16f); map.invalidate() }
                }

                if (overlayS.value) MeteorOverlay(mono)

                Row(Modifier.align(Alignment.BottomStart).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HudButton(if (followS.value) "◉ FOLLOW" else "○ FREE", active = followS.value) {
                        map.follow = !map.follow
                        if (map.follow) { switchDim(""); map.player?.let { map.centerOn(it.x, it.z) } }
                    }
                    if (navModeS.value > 0 || map.autopilotTarget != null) HudButton("✕ CANCEL") {
                        walkTarget = null; etaS.value = null
                        lifecycleScope.launch { api.baritoneCancel(); log("CANCELLED") }
                    }
                    etaS.value?.let {
                        Text(it, fontFamily = mono, fontSize = 16.sp, color = Hud.cyan,
                            modifier = Modifier.background(Color(0xE0100D17)).border(1.dp, Hud.border)
                                .padding(horizontal = 12.dp, vertical = 13.dp))
                    }
                }

                if (routeEditS.value) {
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ROUTE ${routeLenS.value}", fontFamily = mono, fontSize = 12.sp,
                            color = Hud.cyan,
                            modifier = Modifier.background(Color(0xE0100D17)).padding(6.dp))
                        HudButton("GO") {
                            when (map.shapeMode) {
                                "ellipse" -> lifecycleScope.launch {
                                    val ok = api.autopilotRoute(map.ellipsePoints(), loop = true)
                                    log(if (ok) "✈ ORBITING ${(map.shRx * 2).toInt()}×${(map.shRz * 2).toInt()}"
                                    else "ROUTE FAILED :: DECK-AUTOPILOT ON?")
                                    if (ok) exitRouteEdit()
                                }
                                "spiral" -> lifecycleScope.launch {
                                    val ok = api.autopilotRoute(map.spiralPoints(), loop = false)
                                    log(if (ok) "✈ SPIRAL r=${map.spOuter.toInt()}"
                                    else "ROUTE FAILED :: DECK-AUTOPILOT ON?")
                                    if (ok) exitRouteEdit()
                                }
                                else -> sendRoute(loop = false)
                            }
                        }
                        if (map.shapeMode == null) HudButton("LOOP") { sendRoute(loop = true) }
                        HudButton("ELLIPSE", active = map.shapeMode == "ellipse") {
                            if (map.shapeMode == "ellipse") map.clearShape() else {
                                map.routeDraft.clear(); routeLenS.value = 0
                                map.startEllipse()
                                log("ELLIPSE :: DRAG CENTER · PULL HANDLES · PINCH · GO ORBITS IT")
                            }
                        }
                        HudButton("SPIRAL", active = map.shapeMode == "spiral") {
                            if (map.shapeMode == "spiral") map.clearShape() else {
                                map.routeDraft.clear(); routeLenS.value = 0
                                map.startSpiral()
                                log("SPIRAL :: DRAG TO MOVE · PINCH TO RESIZE · GO FLIES IT")
                            }
                        }
                        HudButton("AUTOMAP") {
                            val hw = map.width / 2f / map.scale
                            val hh = map.height / 2f / map.scale
                            lifecycleScope.launch {
                                val ok = api.autopilotArea(
                                    (map.camX - hw).toInt(), (map.camZ - hh).toInt(),
                                    (map.camX + hw).toInt(), (map.camZ + hh).toInt(), 160)
                                log(if (ok) "✈ AUTOMAP VISIBLE AREA" else "AREA FAILED :: DECK-AUTOPILOT ON?")
                                exitRouteEdit()
                            }
                        }
                        HudButton("STASH", active = stashArmS.value) {
                            stashArmS.value = !stashArmS.value
                            if (stashArmS.value) {
                                map.clearShape()
                                map.routeDraft.clear(); routeLenS.value = 0
                                log("STASH HIDER :: TAP YOUR STASH — DECOY TRAIL ROUTES THROUGH IT")
                            }
                            map.invalidate()
                        }
                        HudButton("CLEAR") {
                            map.routeDraft.clear()
                            routeLenS.value = 0
                            map.clearShape()
                            stashArmS.value = false
                        }
                        HudButton("✕ EXIT") { exitRouteEdit() }
                    }
                }

                Column(Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    for ((_, t) in toasts) {
                        Text(t, fontFamily = mono, fontSize = 22.sp,
                            modifier = Modifier.background(Color(0xF0100D17))
                                .border(1.dp, Hud.accent)
                                .padding(horizontal = 26.dp, vertical = 16.dp))
                    }
                    if (logS.value.isNotEmpty()) {
                        Text(logS.value, fontFamily = mono, fontSize = 15.sp, color = Hud.accent,
                            modifier = Modifier.background(Color(0xE0100D17))
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }


            }

            if (panelS.value) SidePanel(mono)
        }

        if (showLocFilterS.value) LocFilterDialog(mono) { showLocFilterS.value = false }
        if (showChat) ChatDialog(mono) { showChat = false }
        if (showSettings) SettingsDialog(mono) { showSettings = false }
        addWpAt?.let { (x, z) -> AddWaypointDialog(mono, x, z) { addWpAt = null } }
        deleteWp?.let { w ->
            ConfirmDialog(mono, "DELETE ${w.name.uppercase()}?", onConfirm = {
                lifecycleScope.launch {
                    log(if (api.deleteWaypoint(w)) "DELETED ${w.name.uppercase()}" else "DELETE FAILED")
                    refreshWaypoints()
                }
                deleteWp = null
            }) { deleteWp = null }
        }

        AlertOverlay(mono)

        // waypoint delete hook for the panel list
        panelDeleteRequest = { deleteWp = it }
    }

    private var panelDeleteRequest: (DeckWaypoint) -> Unit = {}

    @Composable
    private fun BoxScope.MeteorOverlay(mono: FontFamily) {
        val conf = androidx.compose.ui.platform.LocalConfiguration.current
        var category by remember { mutableStateOf("") }
        val activeColor = remember {
            Color(getSharedPreferences("deck", MODE_PRIVATE).getInt("activeColor", 0xFF55FF88.toInt()))
        }
        LaunchedEffect(Unit) { overlayModulesS.value = api.meteorModules() }
        Column(Modifier
            .align(Alignment.TopStart)
            .padding(start = 10.dp, top = 76.dp)
            .width(380.dp)
            .heightIn(max = (conf.screenHeightDp * 0.75f).dp)
            .background(Color(0xE6100D17))
            .border(1.dp, Hud.accent)
            .padding(8.dp)) {
            val modules = overlayModulesS.value
            MeteorCategoryTabs(mono, modules, category) { category = it }
            Spacer(Modifier.height(6.dp))
            if (modules.isEmpty()) {
                Text("▚ METEOR NOT REACHABLE\nREMOTE-CONTROL MODULE ON? TOKEN SET?",
                    fontFamily = mono, fontSize = 12.sp, color = Hud.orange)
            }
            val visible = modules
                .filter { category.isEmpty() || it.category == category }
                .sortedBy { it.title.lowercase() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.name }) { m -> MeteorModuleCard(mono, m, activeColor, api) }
            }
        }
    }

    @Composable
    private fun OracleLegendRow(mono: FontFamily, color: Color, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).background(color).border(1.dp, Hud.border))
            Text(label.uppercase(), fontFamily = mono, fontSize = 11.sp, color = Hud.text)
        }
    }

    @Composable
    private fun AlertOverlay(mono: FontFamily) {
        val alert = alertS.value ?: return
        Dialog(onDismissRequest = { dismissAlert() },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false)) {
            val pulse by rememberInfiniteTransition(label = "alert").animateFloat(
                initialValue = 0.2f, targetValue = 0.6f,
                animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "alert")
            Box(Modifier
                .fillMaxSize()
                .background(Color(1f, 0f, 0f, pulse * 0.45f))
                .border(8.dp, Color(1f, 0.15f, 0.15f, (pulse + 0.4f).coerceAtMost(1f)))
                .clickable { dismissAlert() },
                contentAlignment = Alignment.Center) {
                Text(alert.first, fontFamily = mono, fontSize = 46.sp, color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(24.dp))
            }
        }
    }

    @Composable
    private fun DimChip(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Hud.onAccent else Hud.sub,
            modifier = Modifier
                .background(if (selected) Hud.accent else Hud.surface)
                .border(1.dp, if (selected) Hud.accent else Hud.border)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp))
    }

    @Composable
    private fun HudButton(label: String, active: Boolean = false, big: Boolean = false, onClick: () -> Unit) {
        Text(label, fontFamily = FontFamily.Monospace,
            fontSize = if (big) 26.sp else 17.sp,
            color = if (active) Hud.onAccent else Hud.accent,
            modifier = Modifier
                .background(if (active) Hud.accent else Hud.surface)
                .border(1.dp, if (active) Hud.accent else Hud.border)
                .clickable(onClick = onClick)
                .padding(horizontal = if (big) 24.dp else 18.dp, vertical = 14.dp))
    }

    @Composable
    private fun SidePanel(mono: FontFamily) {
        val st = statusS.value
        Column(Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 250.dp, max = 400.dp)
            .fillMaxHeight()
            .background(Hud.panel)
            .border(1.dp, Hud.accent)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())) {

            browsingS.value?.let {
                Text("▚ CACHED :: ${it.uppercase()}", fontFamily = mono, fontSize = 11.sp, color = Hud.yellow)
                Text("TAP LIVE TO RETURN", fontFamily = mono, fontSize = 10.sp, color = Hud.sub)
                Spacer(Modifier.height(8.dp))
            }

            if (st != null && st.inGame && st.player != null) {
                val p = st.player
                Text("▚ ${st.worldId?.uppercase() ?: "?"} :: ${st.dimension?.substringAfter(':')?.uppercase() ?: "?"}",
                    fontFamily = mono, fontSize = 11.sp, color = Hud.accent)
                Text("%.0f %.0f %.0f".format(p.x, p.y, p.z),
                    fontFamily = mono, fontSize = 21.sp, color = Hud.text, fontWeight = FontWeight.Bold)
                if (st.dimension == "minecraft:the_nether")
                    Text("OW %.0f %.0f".format(p.x * 8, p.z * 8), fontFamily = mono, fontSize = 12.sp, color = Hud.green)
                else if (st.dimension == "minecraft:overworld")
                    Text("NETHER %.0f %.0f".format(p.x / 8, p.z / 8), fontFamily = mono, fontSize = 12.sp, color = Hud.green)
                Spacer(Modifier.height(10.dp))
                st.stats?.let { s ->
                    StatLine(mono, "SPD", "%.0fbps".format(s.bps), "PING", "${s.ping}ms")
                    StatLine(mono, "TPS", "%.1f".format(s.tps), "HP", "%.0f".format(s.hp),
                        v1Color = if (s.tps >= 19) Hud.green else Hud.orange,
                        v2Color = if (s.hp <= 8) Hud.red else Hud.text)
                    StatLine(mono, "TTM", "×${s.totems}", "ELY",
                        if (s.elytra >= 0) "${s.elytra}%" else "—",
                        v2Color = if (s.elytra in 0..20) Hud.red else Hud.text)
                }
                if (st.effects.isNotEmpty()) {
                    HudDivider(mono, "EFFECTS")
                    for (e in st.effects) {
                        val time = when {
                            e.seconds < 0 -> "∞"
                            e.seconds >= 3600 -> "%d:%02d:%02d".format(e.seconds / 3600, e.seconds / 60 % 60, e.seconds % 60)
                            else -> "%d:%02d".format(e.seconds / 60, e.seconds % 60)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.name.uppercase().replace(' ', '_'), fontFamily = mono, fontSize = 12.sp,
                                color = Color(0xFF000000.toInt() or e.color).takeIf { e.color != 0 } ?: Hud.cyan)
                            Text(time, fontFamily = mono, fontSize = 12.sp, color = Hud.text)
                        }
                    }
                }
            } else {
                Text(if (connectedS.value) "▚ CONNECTED :: NOT IN WORLD"
                else if (api.baseUrl.isEmpty()) "▚ AWAITING DISCOVERY…"
                else "▚ OFFLINE :: CACHED MAP", fontFamily = mono, fontSize = 11.sp, color = Hud.sub)
                if (autoAddrS.value.isNotEmpty())
                    Text("AUTO ${autoAddrS.value}", fontFamily = mono, fontSize = 10.sp, color = Hud.green)
            }

            HudDivider(mono, "WAYPOINTS")
            Text("TAP JUMP · NAV+TAP GOTO · HOLD DELETE", fontFamily = mono, fontSize = 9.sp, color = Hud.sub)
            Spacer(Modifier.height(4.dp))
            val sortedWaypoints = remember(waypointsS.value) {
                waypointsS.value.sortedBy { it.name.lowercase() }
            }
            for (w in sortedWaypoints) {
                Row(Modifier.fillMaxWidth()
                    .background(Hud.surface)
                    .border(1.dp, Hud.border)
                    .clickable {
                        when (navModeS.value) {
                            1 -> lifecycleScope.launch {
                                val ok = api.baritoneGoto(w.x, w.z)
                                if (ok) walkTarget = doubleArrayOf(w.x.toDouble(), w.z.toDouble())
                                log(if (ok) "#GOTO ${w.name.uppercase()}" else "GOTO FAILED")
                            }
                            2 -> lifecycleScope.launch {
                                log(if (api.autopilotFly(w.x, w.z)) "✈ FLY ${w.name.uppercase()}" else "FLY FAILED :: DECK-AUTOPILOT ON?")
                            }
                            else -> { map.follow = false; map.centerOn(w.x.toDouble(), w.z.toDouble()) }
                        }
                    }
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("◆ ${w.name}  ${w.x} ${w.z}", fontFamily = mono, fontSize = 15.sp,
                        color = Color(0xFF000000.toInt() or w.color),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    when (st?.dimension) {
                        "minecraft:overworld" -> "N ${w.x / 8} ${w.z / 8}"
                        "minecraft:the_nether" -> "OW ${w.x * 8} ${w.z * 8}"
                        else -> null
                    }?.let {
                        Text(it, fontFamily = mono, fontSize = 10.sp, color = Hud.green)
                    }
                    Text("✕", fontFamily = mono, fontSize = 18.sp, color = Hud.sub,
                        modifier = Modifier.clickable { panelDeleteRequest(w) }
                            .padding(horizontal = 16.dp, vertical = 12.dp))
                }
                Spacer(Modifier.height(4.dp))
            }

            if (sightingsS.value.isNotEmpty()) {
                HudDivider(mono, "SIGHTINGS")
                Text("PLAYERS SEEN ON RADAR · TAP TO VIEW", fontFamily = mono, fontSize = 9.sp, color = Hud.sub)
                Spacer(Modifier.height(4.dp))
                for (s in sightingsS.value.take(30)) {
                    Row(Modifier.fillMaxWidth()
                        .clickable {
                            map.follow = false
                            if (s.dim != effectiveViewedDim()?.substringAfter(':')) switchDim(s.dim)
                            map.centerOn(s.x.toDouble(), s.z.toDouble())
                        }
                        .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text((if (s.friend) "✦ " else "☠ ") + s.name,
                            fontFamily = mono, fontSize = 13.sp,
                            color = if (s.friend) Hud.green else Hud.red,
                            modifier = Modifier.weight(1f))
                        Text("${s.x} ${s.z}", fontFamily = mono, fontSize = 12.sp, color = Hud.text)
                        Text("  ${ago(s.time)}", fontFamily = mono, fontSize = 11.sp, color = Hud.sub)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("✕ CLEAR LOG", fontFamily = mono, fontSize = 10.sp, color = Hud.sub,
                    modifier = Modifier.clickable {
                        sightingsS.value = emptyList()
                        sightingsDirty = true
                        saveSightings()
                    }.padding(vertical = 6.dp))
            }
        }
    }

    @Composable
    private fun StatLine(mono: FontFamily, k1: String, v1: String, k2: String, v2: String,
                         v1Color: Color = Hud.text, v2Color: Color = Hud.text) {
        Row(Modifier.fillMaxWidth()) {
            Text(k1, fontFamily = mono, fontSize = 12.sp, color = Hud.sub, modifier = Modifier.width(42.dp))
            Text(v1, fontFamily = mono, fontSize = 12.sp, color = v1Color, modifier = Modifier.weight(1f))
            Text(k2, fontFamily = mono, fontSize = 12.sp, color = Hud.sub, modifier = Modifier.width(46.dp))
            Text(v2, fontFamily = mono, fontSize = 12.sp, color = v2Color)
        }
    }

    @Composable
    private fun HudDivider(mono: FontFamily, label: String) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.border))
        Spacer(Modifier.height(6.dp))
        Text(label, fontFamily = mono, fontSize = 11.sp, color = Hud.accent)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun HudDialog(mono: FontFamily, title: String, onClose: () -> Unit,
                          wide: Boolean = false, fillHeight: Boolean = false,
                          content: @Composable ColumnScope.() -> Unit) {
        Dialog(onDismissRequest = onClose,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = !wide)) {
            Column(Modifier
                .let { if (wide) it.fillMaxWidth(0.8f) else it.widthIn(min = 340.dp, max = 560.dp) }
                .let { if (fillHeight) it.fillMaxHeight(0.85f) else it }
                .background(Hud.panel)
                .border(1.dp, Hud.accent)
                .padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("▚ $title", fontFamily = mono, fontSize = 13.sp, color = Hud.accent)
                    Text("✕", fontFamily = mono, fontSize = 13.sp, color = Hud.sub,
                        modifier = Modifier.clickable(onClick = onClose))
                }
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }

    @Composable
    private fun ChatDialog(mono: FontFamily, onClose: () -> Unit) {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        var input by remember { mutableStateOf("") }
        var fontSize by remember { mutableStateOf(prefs.getFloat("chatFontSize", 14f)) }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // pinch to shrink / spread to enlarge chat text — handled on the initial
        // pointer pass so the scrolling list can't steal two-finger gestures
        val pinchModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var lastDist = -1f
                do {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val dist = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                        if (lastDist > 0f && dist > 0f) {
                            fontSize = (fontSize * (dist / lastDist)).coerceIn(9f, 30f)
                            prefs.edit().putFloat("chatFontSize", fontSize).apply()
                        }
                        lastDist = dist
                        event.changes.forEach { it.consume() }
                    } else {
                        lastDist = -1f
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        // pin to the newest line on open and whenever chat grows
        LaunchedEffect(chatLog.size) {
            if (chatLog.isNotEmpty()) listState.scrollToItem(chatLog.size - 1)
        }
        HudDialog(mono, "CHAT", onClose, wide = true, fillHeight = true) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()
                .then(pinchModifier), state = listState) {
                items(chatLog.toList()) { line ->
                    Text(line, fontFamily = mono, fontSize = fontSize.sp, color = Hud.text,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            HudTextField(mono, input, { input = it }, "SEND (CHAT-RELAY MUST BE ON)", ImeAction.Send) {
                val text = input.trim()
                if (text.isNotEmpty()) lifecycleScope.launch {
                    if (api.sendChat(text)) input = "" else log("SEND FAILED :: RELAY? TOKEN?")
                }
            }
        }
    }

    @Composable
    private fun AddWaypointDialog(mono: FontFamily, x: Int, z: Int, onClose: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var y by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(12) }
        val colors = intArrayOf(
            0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(),
            0xFFAA0000.toInt(), 0xFFAA00AA.toInt(), 0xFFFFAA00.toInt(), 0xFFAAAAAA.toInt(),
            0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(), 0xFF55FFFF.toInt(),
            0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt())
        HudDialog(mono, "WAYPOINT @ $x $z", onClose) {
            HudTextField(mono, name, { name = it }, "NAME", ImeAction.Next) {}
            Spacer(Modifier.height(6.dp))
            HudTextField(mono, y, { y = it }, "Y (OPTIONAL)", ImeAction.Done) {}
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                colors.forEachIndexed { i, c ->
                    Box(Modifier.weight(1f).height(26.dp).background(Color(c))
                        .border(if (i == color) 2.dp else 0.dp, if (i == color) Color.White else Color.Transparent)
                        .clickable { color = i })
                }
            }
            Spacer(Modifier.height(12.dp))
            HudButton("ADD WAYPOINT", active = true) {
                val n = name.ifBlank { "Deck" }
                lifecycleScope.launch {
                    val ok = api.addWaypoint(x, z, n, y.toIntOrNull(), color)
                    log(if (ok) "ADDED ${n.uppercase()}" else "ADD FAILED")
                    refreshWaypoints()
                }
                onClose()
            }
        }
    }

    @Composable
    private fun ConfirmDialog(mono: FontFamily, title: String, onConfirm: () -> Unit, onClose: () -> Unit) {
        HudDialog(mono, title, onClose) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("CONFIRM", active = true, onClick = onConfirm)
                HudButton("ABORT", onClick = onClose)
            }
        }
    }

    @Composable
    private fun SettingsDialog(mono: FontFamily, onClose: () -> Unit) {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        var addr by remember { mutableStateOf(prefs.getString("addr", "") ?: "") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
        var oracleSeed by remember { mutableStateOf("") }
        var structVer by remember { mutableStateOf("") }
        var structVersAvail by remember { mutableStateOf(listOf<String>()) }
        var showWorlds by remember { mutableStateOf(false) }
        var showVerPicker by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            api.oracleConfig()?.let {
                oracleSeed = it.seed
                structVer = it.structureVersion
                structVersAvail = it.available
            }
        }
        HudDialog(mono, "CONFIG", onClose, wide = true, fillHeight = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                HudTextField(mono, addr, { addr = it }, "PC IP (EMPTY = AUTO-DISCOVER)", ImeAction.Done) {
                    prefs.edit().putString("addr", addr.trim()).apply()
                    applyAddress(addr.trim()); map.clearTiles(); restartStream()
                }
                Spacer(Modifier.height(6.dp))
                HudTextField(mono, token, { token = it }, "PAIRING TOKEN", ImeAction.Done) {
                    prefs.edit().putString("token", token.trim()).apply()
                    api.token = token.trim()
                }
                Spacer(Modifier.height(6.dp))
                HudTextField(mono, oracleSeed, { oracleSeed = it },
                    "ORACLE SEED (WORLD SEED, EMPTY = OFF)", ImeAction.Done) {
                    lifecycleScope.launch {
                        val ok = api.oracleSetSeed(oracleSeed.trim())
                        log(if (ok) "ORACLE SEED SET" else "SEED SET FAILED :: TOKEN?")
                        if (ok) oracleLegendS.value = emptyList()
                    }
                }
                Spacer(Modifier.height(6.dp))
                WideButton(mono, "STRUCTURE VERSION: " +
                        (structVer.ifBlank { "AUTO" })) { showVerPicker = true }
                Spacer(Modifier.height(8.dp))
                SettingSwitch(mono, "PLAYER RADAR", "showPlayers") { map.showPlayers = it }
                SettingSwitch(mono, "MOB RADAR", "showHostiles") { map.showHostiles = it }
                SettingSwitch(mono, "GRID OVERLAY", "showGrid") { map.showGrid = it }
                SettingSwitch(mono, "TRAVEL TRAIL", "showTrail") { map.showTrail = it }
                SettingSwitch(mono, "NOTIFICATIONS", "showNotifs") { }
                SettingSwitch(mono, "KEEP AWAKE", "keepAwake") {
                    if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                HudDivider(mono, "WATCHDOG")
                SettingSwitch(mono, "WATCHDOG", "wdEnabled") { }
                SettingSwitch(mono, "CONNECTION LOST", "wdConn") { }
                SettingSwitch(mono, "PLAYER ALERT", "wdPlayer") { }
                SettingSwitch(mono, "TOTEM POP", "wdTotem") { }
                SettingSwitch(mono, "HP LOW", "wdHp") { }
                SettingSwitch(mono, "ELYTRA LOW", "wdElytra") { }
                Spacer(Modifier.height(6.dp))
                var hpThresh by remember { mutableStateOf(prefs.getInt("wdHpThresh", 8).toString()) }
                Text("HP THRESHOLD", fontFamily = mono, fontSize = 10.sp, color = Hud.sub)
                HudTextField(mono, hpThresh, { hpThresh = it }, "8", ImeAction.Done) {
                    prefs.edit().putInt("wdHpThresh", hpThresh.toIntOrNull() ?: 8).apply()
                }
                Spacer(Modifier.height(6.dp))
                var elyThresh by remember { mutableStateOf(prefs.getInt("wdElytraThresh", 15).toString()) }
                Text("ELYTRA THRESHOLD %", fontFamily = mono, fontSize = 10.sp, color = Hud.sub)
                HudTextField(mono, elyThresh, { elyThresh = it }, "15", ImeAction.Done) {
                    prefs.edit().putInt("wdElytraThresh", elyThresh.toIntOrNull() ?: 15).apply()
                }
                Spacer(Modifier.height(12.dp))
                WideButton(mono, "CLEAR TRAIL") { map.trail.clear(); trailFile()?.delete(); map.invalidate() }
                Spacer(Modifier.height(6.dp))
                WideButton(mono, "CLEAR TILE CACHE") {
                    api.worldCacheDir()?.deleteRecursively(); api.worldCacheDir()?.mkdirs()
                    map.clearTiles(); log("TILE CACHE CLEARED")
                }
                Spacer(Modifier.height(6.dp))
                WideButton(mono, "BROWSE CACHED WORLDS") { showWorlds = true }
                Spacer(Modifier.height(6.dp))
                WideButton(mono, "ALERT SOUND…") { pickAlertSound() }
                Spacer(Modifier.height(6.dp))
                WideButton(mono, "TEST ALERT") { triggerAlert("TEST ALERT", sticky = false) }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (showWorlds) {
            val dirs = java.io.File(filesDir, "worlds").listFiles()?.filter { it.isDirectory }
                ?.sortedBy { it.name } ?: emptyList()
            HudDialog(mono, "CACHED WORLDS", { showWorlds = false }) {
                for (d in dirs) {
                    val mb = d.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576.0
                    Text("◆ ${d.name}  %.1fMB".format(mb), fontFamily = mono, fontSize = 12.sp,
                        color = Hud.text,
                        modifier = Modifier.fillMaxWidth().clickable {
                            saveTrail()
                            api.currentWorldKey = d.name
                            browsingS.value = d.name
                            map.clearTiles()
                            synchronized(pendingTiles) { pendingTiles.clear() }
                            map.waypoints = api.cachedWaypoints()
                            waypointsS.value = map.waypoints
                            loadTrail()
                            map.player = null
                            showWorlds = false
                            onClose()
                        }.padding(vertical = 6.dp))
                }
            }
        }
        if (showVerPicker) {
            HudDialog(mono, "STRUCTURE VERSION", { showVerPicker = false }) {
                Text("VERSION THE MAP WAS GENERATED ON (CHUNKBASE-STYLE)",
                    fontFamily = mono, fontSize = 10.sp, color = Hud.sub)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                    val opts = listOf("") + structVersAvail.reversed()
                    for (v in opts) {
                        val label = if (v.isEmpty()) "AUTO (NEWEST ERA)" else v
                        val sel = v == structVer
                        Text((if (sel) "◆ " else "  ") + label, fontFamily = mono, fontSize = 13.sp,
                            color = if (sel) Hud.accent else Hud.text,
                            modifier = Modifier.fillMaxWidth().clickable {
                                lifecycleScope.launch {
                                    val ok = api.oracleSetStructureVersion(v)
                                    if (ok) {
                                        structVer = v
                                        map.structures = emptyList()
                                        log("STRUCTURE VERSION: $label")
                                    } else log("VERSION SET FAILED :: TOKEN?")
                                }
                                showVerPicker = false
                            }.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun WideButton(mono: FontFamily, label: String, onClick: () -> Unit) {
        Text(label, fontFamily = mono, fontSize = 13.sp, color = Hud.accent,
            modifier = Modifier.fillMaxWidth()
                .background(Hud.surface).border(1.dp, Hud.border)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp))
    }

    @Composable
    private fun SettingSwitch(mono: FontFamily, label: String, key: String, apply: (Boolean) -> Unit) {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        var checked by remember { mutableStateOf(prefs.getBoolean(key, key != "showGrid")) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = mono, fontSize = 12.sp, color = Hud.text)
            Switch(checked = checked, onCheckedChange = {
                checked = it
                prefs.edit().putBoolean(key, it).apply()
                apply(it)
                map.invalidate()
            }, colors = SwitchDefaults.colors(
                checkedThumbColor = Hud.accent, checkedTrackColor = Hud.surfaceHi,
                uncheckedThumbColor = Hud.sub, uncheckedTrackColor = Hud.surface))
        }
    }

    @Composable
    private fun HudTextField(mono: FontFamily, value: String, onChange: (String) -> Unit,
                             placeholder: String, ime: ImeAction, onDone: () -> Unit) {
        TextField(value = value, onValueChange = onChange,
            placeholder = { Text(placeholder, fontFamily = mono, fontSize = 11.sp, color = Hud.sub) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = mono, fontSize = 13.sp, color = Hud.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ime),
            keyboardActions = KeyboardActions(onDone = { onDone() }, onSend = { onDone() }, onNext = {}),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Hud.surface, unfocusedContainerColor = Hud.surface,
                focusedIndicatorColor = Hud.accent, unfocusedIndicatorColor = Hud.border,
                cursorColor = Hud.accent),
            modifier = Modifier.fillMaxWidth())
    }
}
