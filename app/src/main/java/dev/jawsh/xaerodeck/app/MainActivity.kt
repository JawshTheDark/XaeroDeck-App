package dev.jawsh.xaerodeck.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class MainActivity : AppCompatActivity() {
    private lateinit var api: DeckApi
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var waypointList: LinearLayout
    private lateinit var followBtn: TextView
    private lateinit var dimChips: LinearLayout
    private lateinit var sidePanel: View
    private var streamJob: Job? = null
    private var slowJob: Job? = null
    private val tileSemaphore = Semaphore(6)
    private val pendingTiles = HashSet<String>()

    /** Dimension shown on the map ("" = whatever the player is in). */
    private var viewedDim = ""
    private var playerDim: String? = null
    private var lastStatus: Status? = null
    private var navMode = false
    private val chatLog = ArrayList<CharSequence>()
    private var chatDialogList: LinearLayout? = null
    private var lastTrailX = Double.NaN
    private var lastTrailZ = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        api = DeckApi(filesDir)
        map = findViewById(R.id.map)
        statusText = findViewById(R.id.statusText)
        waypointList = findViewById(R.id.waypointList)
        followBtn = findViewById(R.id.followBtn)
        dimChips = findViewById(R.id.dimChips)
        sidePanel = findViewById(R.id.sidePanel)
        val addressEdit = findViewById<EditText>(R.id.addressEdit)
        val panelToggle = findViewById<TextView>(R.id.panelToggle)

        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        addressEdit.setText(prefs.getString("addr", "") ?: "")
        applyAddress(addressEdit.text.toString())
        api.currentWorldKey = prefs.getString("worldKey", "unknown") ?: "unknown"

        addressEdit.setOnEditorActionListener { v, _, _ ->
            prefs.edit().putString("addr", v.text.toString().trim()).apply()
            applyAddress(v.text.toString().trim())
            map.clearTiles()
            restartStream()
            false
        }

        panelToggle.setOnClickListener {
            sidePanel.visibility = if (sidePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        followBtn.setOnClickListener {
            map.follow = !map.follow
            if (map.follow && viewedDim.isNotEmpty()) switchDim("") // follow implies player's dim
            map.player?.let { if (map.follow) map.centerOn(it.x, it.z) }
        }
        map.onFollowChanged = { updateFollowBtn() }
        updateFollowBtn()

        map.tileRequester = { rx, rz -> requestTile(rx, rz, false) }
        map.onLongPressBlock = { x, z -> promptWaypoint(x, z) }

        // zoom about the screen center — which is the player while following
        findViewById<TextView>(R.id.zoomInBtn).setOnClickListener {
            map.scale = (map.scale * 1.5f).coerceIn(0.05f, 16f)
            map.invalidate()
        }
        findViewById<TextView>(R.id.zoomOutBtn).setOnClickListener {
            map.scale = (map.scale / 1.5f).coerceIn(0.05f, 16f)
            map.invalidate()
        }

        findViewById<TextView>(R.id.chatBtn).setOnClickListener { showChat() }
        val navBtn = findViewById<TextView>(R.id.navBtn)
        navBtn.setOnClickListener {
            navMode = !navMode
            navBtn.setBackgroundColor(if (navMode) 0xD04488CC.toInt() else 0xB0202430.toInt())
            statusText.append(if (navMode) "\nNav mode: tap map to Baritone #goto" else "\nNav mode off")
        }
        navBtn.setOnLongClickListener {
            lifecycleScope.launch {
                api.baritoneCancel()
                statusText.append("\n#cancel sent")
            }
            true
        }
        map.onTapBlock = { x, z ->
            if (navMode) {
                // snap to a waypoint marker if the tap landed on/near one
                val snapRadius = 28f / map.scale
                val target = map.waypoints.minByOrNull {
                    val dx = it.x - x.toDouble()
                    val dz = it.z - z.toDouble()
                    dx * dx + dz * dz
                }?.takeIf {
                    Math.abs(it.x - x) <= snapRadius && Math.abs(it.z - z) <= snapRadius
                }
                val gx = target?.x ?: x
                val gz = target?.z ?: z
                lifecycleScope.launch {
                    val ok = api.baritoneGoto(gx, gz)
                    statusText.append(when {
                        !ok -> "\nBaritone goto failed (remote-control on? token?)"
                        target != null -> "\n#goto → ${target.name} (${gx}, ${gz})"
                        else -> "\n#goto $gx $gz"
                    })
                }
            }
        }

        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.meteorBtn).setOnClickListener { showMeteorPanel() }
        applyViewPrefs()

        map.waypoints = api.cachedWaypoints()
        restartStream()
        startSlowLoop()
        startDiscovery(addressEdit)
    }

    /** Listen for the mod's UDP beacon; auto-connect when no address is typed. */
    private fun startDiscovery(addressEdit: EditText) {
        lifecycleScope.launch(Dispatchers.IO) {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("xaerodeck").apply { setReferenceCounted(false) }
            while (true) {
                val manualAddr = getSharedPreferences("deck", MODE_PRIVATE)
                    .getString("addr", "")?.trim() ?: ""
                if (manualAddr.isEmpty()) {
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
                                val url = "http://${packet.address.hostAddress}:$port"
                                if (api.baseUrl != url) {
                                    api.baseUrl = url
                                    runOnUiThread {
                                        addressEdit.hint = "auto: ${packet.address.hostAddress}"
                                        statusText.append("\nFound PC at ${packet.address.hostAddress}")
                                    }
                                    restartStream()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // no beacon heard this cycle — keep listening
                    } finally {
                        try { lock.release() } catch (e: Exception) {}
                    }
                }
                delay(3000)
            }
        }
    }

    private fun applyAddress(addr: String) {
        api.baseUrl = when {
            addr.isEmpty() -> api.baseUrl // keep auto-discovered address
            addr.startsWith("http") -> addr.trimEnd('/')
            else -> "http://$addr:8399"
        }
    }

    private fun updateFollowBtn() {
        followBtn.text = if (map.follow) "◉ Following" else "○ Free look"
    }

    private fun requestTile(rx: Int, rz: Int, force: Boolean, overview: Boolean = map.usingOverview()) {
        val key = "${viewedDim}_${if (overview) "ov" else "t"}_${rx}_$rz"
        val dim = viewedDim
        synchronized(pendingTiles) {
            if (!pendingTiles.add(key)) return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            tileSemaphore.withPermit {
                val bmp = api.tile(dim, rx, rz, force, overview)
                if (dim == viewedDim) {
                    launch(Dispatchers.Main) { map.putTile(rx, rz, bmp, overview) }
                }
            }
            synchronized(pendingTiles) { pendingTiles.remove(key) }
        }
    }

    private fun switchDim(dim: String) {
        if (dim == viewedDim) return
        viewedDim = dim
        updateWorldKey()
        map.clearTiles()
        synchronized(pendingTiles) { pendingTiles.clear() }
        renderDimChips(lastDims)
        // waypoints only make sense in the dimension they belong to
        map.waypoints = if (effectiveViewedDim() == playerDim) api.cachedWaypoints() else emptyList()
    }

    private fun effectiveViewedDim(): String? =
        if (viewedDim.isEmpty()) playerDim else "minecraft:$viewedDim".takeIf { !viewedDim.contains(":") } ?: viewedDim

    private var lastDims: List<DeckApi.Dimension> = emptyList()

    private fun renderDimChips(dims: List<DeckApi.Dimension>) {
        lastDims = dims
        dimChips.removeAllViews()
        if (dims.isEmpty()) return
        val options = listOf(DeckApi.Dimension("", "live", false)) + dims
        for (d in options) {
            val chip = TextView(this)
            val selected = d.key == "live" && viewedDim.isEmpty() || d.key == viewedDim
            chip.text = when (d.key) {
                "live" -> "LIVE"
                "overworld" -> "Overworld"
                "the_nether" -> "Nether"
                "the_end" -> "End"
                else -> d.key
            }
            chip.setPadding(26, 14, 26, 14)
            chip.textSize = 14f
            chip.setTextColor(if (selected) 0xFF102030.toInt() else 0xFFCCDDEE.toInt())
            chip.setBackgroundColor(if (selected) 0xFF7FB8E8.toInt() else 0xB0202430.toInt())
            chip.setOnClickListener {
                if (d.key != "live") map.follow = false
                switchDim(if (d.key == "live") "" else d.key)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 12
            dimChips.addView(chip, lp)
        }
    }

    private fun updateWorldKey() {
        val st = lastStatus ?: return
        val dimForCache = if (viewedDim.isEmpty()) st.dimension else viewedDim
        val key = "${st.worldId}_$dimForCache"
        if (key != api.currentWorldKey) {
            api.currentWorldKey = key
            getSharedPreferences("deck", MODE_PRIVATE).edit().putString("worldKey", key).apply()
        }
    }

    private fun onStatus(st: Status?) {
        runOnUiThread {
            if (st != null && st.inGame && st.player != null) {
                val dimChanged = playerDim != null && playerDim != st.dimension
                playerDim = st.dimension
                lastStatus = st
                if (dimChanged && viewedDim.isEmpty()) {
                    map.clearTiles()
                    synchronized(pendingTiles) { pendingTiles.clear() }
                }
                updateWorldKey()
                for (n in st.notifications) showNotification(n)
                for (c in st.chat) appendChat(
                    if (c.spans.isNotEmpty()) c.spans.toSpannable() else c.text)
                // push invalidation: refresh exactly the regions that changed
                for (d in st.dirty) {
                    val dimMatch = if (viewedDim.isEmpty())
                        st.dimension?.endsWith(d.dim) == true else viewedDim == d.dim
                    if (dimMatch) {
                        map.tileChanged(d.x, d.z)
                        if (map.usingOverview()) {
                            requestTile(Math.floorDiv(d.x, 4), Math.floorDiv(d.z, 4),
                                force = true, overview = true)
                        } else {
                            requestTile(d.x, d.z, force = true, overview = false)
                        }
                    }
                }
                // trail recording (only in the player's dimension view)
                val p = st.player
                if (lastTrailX.isNaN() || Math.abs(p.x - lastTrailX) + Math.abs(p.z - lastTrailZ) >= 8) {
                    lastTrailX = p.x
                    lastTrailZ = p.z
                    map.trail.add(doubleArrayOf(p.x, p.z))
                    if (map.trail.size > 5000) map.trail.removeAt(0)
                }
                // only show the live arrow when looking at the player's dimension
                val viewingPlayerDim = viewedDim.isEmpty() || effectiveViewedDim() == st.dimension
                map.player = if (viewingPlayerDim) st.player else null
                map.entities = if (viewingPlayerDim) st.entities else emptyList()
                val sb = StringBuilder()
                sb.append(st.worldId ?: "?").append('\n')
                sb.append(st.dimension ?: "?").append('\n')
                sb.append("%.0f, %.0f, %.0f".format(p.x, p.y, p.z))
                if (st.dimension == "minecraft:the_nether") {
                    sb.append("\nOW: %.0f, %.0f".format(p.x * 8, p.z * 8))
                } else if (st.dimension == "minecraft:overworld") {
                    sb.append("\nNether: %.0f, %.0f".format(p.x / 8, p.z / 8))
                }
                st.stats?.let { s ->
                    sb.append("\n\n%.0f bps   %d ms   %.1f tps".format(s.bps, s.ping, s.tps))
                    sb.append("\n❤ %.0f   ⛨ %d totem%s".format(s.hp, s.totems, if (s.totems == 1) "" else "s"))
                    if (s.elytra >= 0) sb.append("   ⇗ ${s.elytra}%")
                }
                if (st.effects.isNotEmpty()) {
                    val styled = android.text.SpannableStringBuilder(sb)
                    styled.append("\n")
                    for (e in st.effects) {
                        val start = styled.length
                        val time = when {
                            e.seconds < 0 -> "∞"
                            e.seconds >= 3600 -> "%d:%02d:%02d".format(e.seconds / 3600, e.seconds / 60 % 60, e.seconds % 60)
                            else -> "%d:%02d".format(e.seconds / 60, e.seconds % 60)
                        }
                        styled.append("\n${e.name}  $time")
                        styled.setSpan(
                            android.text.style.ForegroundColorSpan(0xFF000000.toInt() or e.color),
                            start, styled.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    statusText.text = styled
                } else {
                    statusText.text = sb
                }
            } else if (st != null) {
                map.player = null
                statusText.text = "Connected — not in a world"
            } else {
                map.player = null
                statusText.text = if (api.baseUrl.isEmpty())
                    "Enter your PC's IP above" else "Offline — showing cached map"
            }
        }
    }

    /** SSE stream with automatic reconnect; falls back to nothing gracefully. */
    private fun restartStream() {
        streamJob?.cancel()
        if (api.baseUrl.isEmpty()) return
        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    api.streamBlocking { st -> onStatus(st) }
                } catch (e: Exception) {
                    // stream unavailable (old mod or hiccup) — poll for a bit instead
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

    /** Slow housekeeping: waypoints, dimension list, visible-tile revalidation. */
    private fun startSlowLoop() {
        slowJob?.cancel()
        slowJob = lifecycleScope.launch {
            var tick = 0
            while (true) {
                if (api.baseUrl.isNotEmpty() && lastStatus?.inGame == true) {
                    if (tick % 10 == 0) refreshWaypoints()
                    if (tick % 15 == 0) renderDimChips(api.dimensions())
                    // newly explored regions: forget 404s so they get re-requested
                    if (tick % 3 == 0) map.retryMissing()
                    // keep the terrain around the player extra fresh
                    if (tick % 2 == 0 && viewedDim.isEmpty()) {
                        map.player?.let { p ->
                            val prx = Math.floorDiv(p.x.toInt(), 512)
                            val prz = Math.floorDiv(p.z.toInt(), 512)
                            for (dx in -1..1) for (dz in -1..1) {
                                requestTile(prx + dx, prz + dz, force = true)
                            }
                        }
                    }
                    // full visible revalidation, cheap thanks to ETags (304s)
                    if (tick % 5 == 1) {
                        for ((rx, rz) in map.visibleRegions()) requestTile(rx, rz, force = true)
                    }
                }
                tick++
                delay(1000)
            }
        }
    }

    // Xaero's 16 waypoint colors (Minecraft chat colors), by index
    private val xaeroColorsDisplay = intArrayOf(
        0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(),
        0xFFAA0000.toInt(), 0xFFAA00AA.toInt(), 0xFFFFAA00.toInt(), 0xFFAAAAAA.toInt(),
        0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(), 0xFF55FFFF.toInt(),
        0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt()
    )

    private fun promptWaypoint(x: Int, z: Int) {
        if (effectiveViewedDim() != playerDim && viewedDim.isNotEmpty()) {
            statusText.append("\nWaypoints can only be added in your current dimension")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }
        val nameInput = EditText(this).apply {
            hint = "Waypoint name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val yInput = EditText(this).apply {
            hint = "Y (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        container.addView(nameInput)
        container.addView(yInput)
        var chosenColor = 12 // red
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<View>()
        for (i in 0 until 16) {
            val v = View(this)
            val lp = LinearLayout.LayoutParams(0, 56, 1f)
            lp.setMargins(3, 16, 3, 0)
            v.layoutParams = lp
            v.setBackgroundColor(xaeroColorsDisplay[i])
            v.alpha = if (i == chosenColor) 1f else 0.45f
            v.setOnClickListener {
                chosenColor = i
                swatches.forEachIndexed { j, s -> s.alpha = if (j == i) 1f else 0.45f }
            }
            swatches.add(v)
            colorRow.addView(v)
        }
        container.addView(colorRow)
        AlertDialog.Builder(this)
            .setTitle("Add waypoint at $x, $z")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().ifBlank { "Deck" }
                val y = yInput.text.toString().toIntOrNull()
                lifecycleScope.launch {
                    val ok = api.addWaypoint(x, z, name, y, chosenColor)
                    statusText.append(if (ok) "\nWaypoint \"$name\" added" else "\nWaypoint add failed")
                    refreshWaypoints()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }

        fun toggle(label: String, key: String, def: Boolean, apply: (Boolean) -> Unit): android.widget.CheckBox {
            val cb = android.widget.CheckBox(this)
            cb.text = label
            cb.isChecked = prefs.getBoolean(key, def)
            cb.setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean(key, v).apply()
                apply(v)
                map.invalidate()
            }
            container.addView(cb)
            return cb
        }

        toggle("Show players (radar)", "showPlayers", true) { map.showPlayers = it }
        toggle("Show mobs (radar)", "showHostiles", true) { map.showHostiles = it }
        toggle("Grid overlay (regions / chunks)", "showGrid", false) { map.showGrid = it }
        toggle("Show Meteor notifications", "showNotifs", true) { }
        toggle("Show travel trail", "showTrail", true) { map.showTrail = it }

        val tokenEdit = EditText(this)
        tokenEdit.hint = "Pairing token (from remote-control module / config)"
        tokenEdit.setText(prefs.getString("token", "") ?: "")
        tokenEdit.setSingleLine()
        tokenEdit.setOnEditorActionListener { v, _, _ ->
            prefs.edit().putString("token", v.text.toString().trim()).apply()
            api.token = v.text.toString().trim()
            false
        }
        container.addView(tokenEdit)

        val trailClear = android.widget.Button(this)
        trailClear.text = "Clear travel trail"
        trailClear.setOnClickListener {
            map.trail.clear()
            trailFile()?.delete()
            map.invalidate()
        }
        container.addView(trailClear)

        val worldsBtn = android.widget.Button(this)
        worldsBtn.text = "Browse cached worlds"
        worldsBtn.setOnClickListener { showWorldBrowser() }
        container.addView(worldsBtn)
        toggle("Keep screen awake", "keepAwake", true) {
            if (it) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // active-module color for the Meteor panel
        container.addView(TextView(this).apply {
            text = "Enabled module color (Meteor panel)"
            setPadding(0, 20, 0, 4)
        })
        val activeColors = intArrayOf(
            0xFF55FF88.toInt(), 0xFFAA66FF.toInt(), 0xFF55DDFF.toInt(), 0xFFFFAA33.toInt(),
            0xFFFF66AA.toInt(), 0xFFFF5555.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt()
        )
        val current = prefs.getInt("activeColor", 0xFF55FF88.toInt())
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<View>()
        for (c in activeColors) {
            val v = View(this)
            val clp = LinearLayout.LayoutParams(0, 56, 1f)
            clp.setMargins(3, 4, 3, 0)
            v.layoutParams = clp
            v.setBackgroundColor(c)
            v.alpha = if (c == current) 1f else 0.4f
            v.setOnClickListener {
                prefs.edit().putInt("activeColor", c).apply()
                swatches.forEach { s -> s.alpha = 0.4f }
                v.alpha = 1f
            }
            swatches.add(v)
            colorRow.addView(v)
        }
        container.addView(colorRow)

        val cacheDir = api.worldCacheDir()
        val sizeMb = (cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L) / 1048576.0
        val clearBtn = android.widget.Button(this)
        clearBtn.text = "Clear map cache for this world (%.1f MB)".format(sizeMb)
        clearBtn.setOnClickListener {
            cacheDir?.deleteRecursively()
            cacheDir?.mkdirs()
            map.clearTiles()
            statusText.append("\nTile cache cleared")
        }
        container.addView(clearBtn)

        AlertDialog.Builder(this)
            .setTitle("XaeroDeck settings")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    private val seenNotifs = HashSet<Long>()

    private fun appendChat(text: CharSequence) {
        chatLog.add(text)
        if (chatLog.size > 200) chatLog.removeAt(0)
        chatDialogList?.let { list ->
            val tv = TextView(this)
            tv.text = text
            tv.setTextColor(0xFFDDE6EE.toInt())
            tv.textSize = 14f
            list.addView(tv)
            (list.parent as? android.widget.ScrollView)?.post {
                (list.parent as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun showChat() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 8)
        }
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (line in chatLog) {
            val tv = TextView(this)
            tv.text = line
            tv.setTextColor(0xFFDDE6EE.toInt())
            tv.textSize = 14f
            list.addView(tv)
        }
        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 700))
        chatDialogList = list
        val input = EditText(this).apply {
            hint = "Send chat (needs chat-relay module ON)…"
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        input.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    val ok = api.sendChat(text)
                    if (ok) (v as EditText).setText("")
                    else appendChat("⚠ send failed (chat-relay on? token set?)")
                }
            }
            true
        }
        container.addView(input)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this)
            .setTitle("Chat")
            .setView(container)
            .setPositiveButton("Close") { _, _ -> chatDialogList = null }
            .setOnDismissListener { chatDialogList = null }
            .show()
    }

    private fun showNotification(n: DeckNotification) {
        if (!seenNotifs.add(n.id)) return
        if (seenNotifs.size > 400) seenNotifs.clear().also { seenNotifs.add(n.id) }
        if (n.text.startsWith("💀")) {
            val vib = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
        if (!getSharedPreferences("deck", MODE_PRIVATE).getBoolean("showNotifs", true)) return
        val overlay = findViewById<LinearLayout>(R.id.notifOverlay)
        val tv = TextView(this)
        tv.text = if (n.spans.isNotEmpty()) n.spans.toSpannable() else n.text
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        tv.setBackgroundColor(0xD0202430.toInt())
        tv.setPadding(28, 14, 28, 14)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 6
        overlay.addView(tv, lp)
        while (overlay.childCount > 4) overlay.removeViewAt(0)
        tv.alpha = 0f
        tv.animate().alpha(1f).setDuration(150).start()
        tv.postDelayed({
            tv.animate().alpha(0f).setDuration(400)
                .withEndAction { overlay.removeView(tv) }.start()
        }, 5000)
    }

    private fun showMeteorPanel() {
        val intent = android.content.Intent(this, MeteorActivity::class.java)
        intent.putExtra("baseUrl", api.baseUrl)
        startActivity(intent)
    }

    private fun applyViewPrefs() {
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        map.showPlayers = prefs.getBoolean("showPlayers", true)
        map.showHostiles = prefs.getBoolean("showHostiles", true)
        map.showGrid = prefs.getBoolean("showGrid", false)
        map.showTrail = prefs.getBoolean("showTrail", true)
        api.token = prefs.getString("token", "") ?: ""
        if (!prefs.getBoolean("keepAwake", true)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        loadTrail()
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
            trailFile()?.writeText(map.trail.takeLast(5000)
                .joinToString("\n") { "${it[0]},${it[1]}" })
        } catch (e: Exception) {
        }
    }

    override fun onPause() {
        super.onPause()
        saveTrail()
    }

    private fun showWorldBrowser() {
        val worldsDir = java.io.File(filesDir, "worlds")
        val dirs = worldsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (dirs.isEmpty()) return
        val names = dirs.map {
            val mb = it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } / 1048576.0
            "${it.name}  (%.1f MB)".format(mb)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Cached worlds — tap LIVE chip to return")
            .setItems(names) { _, which ->
                saveTrail()
                api.currentWorldKey = dirs[which].name
                map.clearTiles()
                synchronized(pendingTiles) { pendingTiles.clear() }
                map.waypoints = api.cachedWaypoints()
                loadTrail()
                map.player = null
                statusText.text = "Browsing cached: ${dirs[which].name}"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun refreshWaypoints() {
        val wps = api.waypoints() ?: api.cachedWaypoints()
        if (viewedDim.isNotEmpty() && effectiveViewedDim() != playerDim) return
        map.waypoints = wps
        waypointList.removeAllViews()
        var widestRow = 0f
        for (w in wps.sortedBy { it.name.lowercase() }) {
            val row = TextView(this)
            row.text = "● ${w.name}  (${w.x}, ${w.z})"
            row.setTextColor(0xFF000000.toInt() or w.color)
            row.textSize = 15f
            row.setSingleLine()
            row.setPadding(8, 10, 8, 10)
            widestRow = maxOf(widestRow, row.paint.measureText(row.text.toString()) + 16)
            row.setOnClickListener {
                if (navMode) {
                    lifecycleScope.launch {
                        val ok = api.baritoneGoto(w.x, w.z)
                        statusText.append(if (ok) "\n#goto → ${w.name} (${w.x}, ${w.z})"
                        else "\nBaritone goto failed")
                    }
                } else {
                    map.follow = false
                    map.centerOn(w.x.toDouble(), w.z.toDouble())
                }
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete waypoint \"${w.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            val ok = api.deleteWaypoint(w)
                            statusText.append(if (ok) "\nDeleted \"${w.name}\"" else "\nDelete failed")
                            refreshWaypoints()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            waypointList.addView(row)
        }
        // widen the side panel to fit the longest waypoint line (within reason)
        val screenW = resources.displayMetrics.widthPixels
        val minW = (screenW * 0.22f).toInt()
        val maxW = (screenW * 0.45f).toInt()
        val desired = (widestRow + 40).toInt().coerceIn(minW, maxW)
        val lp = sidePanel.layoutParams as LinearLayout.LayoutParams
        if (lp.width != desired) {
            lp.width = desired
            lp.weight = 0f
            sidePanel.layoutParams = lp
        }
        map.invalidate()
    }
}
