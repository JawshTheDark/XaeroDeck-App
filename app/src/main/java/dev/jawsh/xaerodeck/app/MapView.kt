package dev.jawsh.xaerodeck.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class MapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // camera in world block coordinates
    var camX = 0.0
    var camZ = 0.0
    var scale = 1.0f
    var follow = true
        set(v) {
            field = v
            onFollowChanged?.invoke(v)
        }

    // These drive drawn state; their setters post an invalidate so the SSE loop
    // no longer needs an unconditional per-tick full invalidate.
    var waypoints: List<DeckWaypoint> = emptyList()
        set(v) { field = v; postInvalidateOnAnimation() }
    var entities: List<EntityDot> = emptyList()
        set(v) { field = v; postInvalidateOnAnimation() }
    var autopilotTarget: DoubleArray? = null
        set(v) { field = v; postInvalidateOnAnimation() }
    var structures: List<DeckApi.SeedFeature> = emptyList()
        set(v) { field = v; postInvalidateOnAnimation() }
    var showStructures = false

    val structureStyle = mapOf(
        "fortress" to Pair("F", 0xFFE05040.toInt()),
        "bastion" to Pair("B", 0xFFE0A030.toInt()),
        "monument" to Pair("M", 0xFF30C0C0.toInt()),
        "mansion" to Pair("W", 0xFFA0683C.toInt()),
        "village" to Pair("V", 0xFFC8B078.toInt()),
        "outpost" to Pair("O", 0xFF909090.toInt()),
        "treasure" to Pair("T", 0xFFE8D040.toInt()),
        "end_city" to Pair("E", 0xFFB060E0.toInt()),
        "desert_temple" to Pair("DT", 0xFFD8C878.toInt()),
        "jungle_temple" to Pair("JT", 0xFF60A040.toInt()),
        "witch_hut" to Pair("WH", 0xFF507050.toInt()),
        "igloo" to Pair("IG", 0xFFD0E8F0.toInt()),
        "shipwreck" to Pair("SW", 0xFF9A7B4F.toInt()),
        "ocean_ruin" to Pair("OR", 0xFF48A0A8.toInt()),
        "ruined_portal" to Pair("RP", 0xFF9060D0.toInt()),
        "ruined_portal_n" to Pair("RP", 0xFF9060D0.toInt()),
        "stronghold" to Pair("SH", 0xFFB8E088.toInt()),
        "gateway" to Pair("EG", 0xFFE060C0.toInt())
    )
    var enabledTypes: MutableSet<String> = structureStyle.keys.toMutableSet().also { it.add("slime") }
    var slimeSeed: Long? = null

    private fun isSlimeChunk(seed: Long, cx: Int, cz: Int): Boolean {
        var s = seed +
                cx.toLong() * cx * 0x4c1906L + cx.toLong() * 0x5ac0dbL +
                cz.toLong() * cz * 0x4307a7L + cz.toLong() * 0x5f24fL
        s = s xor 0x3ad8025fL
        s = (s xor 0x5DEECE66DL) and ((1L shl 48) - 1)
        while (true) {
            s = (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
            val bits = (s ushr 17).toInt()
            val v = bits % 10
            if (bits - v + 9 >= 0) return v == 0
        }
    }
    var showPlayers = true
    var showHostiles = true
    var showGrid = false
    var showTrail = true
    var showOracle = false
    var trail: MutableList<DoubleArray> = ArrayList() // [x, z] pairs

    /** Below this zoom, use 2048-block overview tiles instead of 512-block regions. */
    val overviewThreshold = 0.25f
    fun usingOverview() = scale < overviewThreshold
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0x9066CCFF.toInt()
    }
    // Trail path kept in WORLD coordinates; rebuilt only when the trail changes,
    // then drawn under the world→screen transform (see onDraw).
    private val trailPathWorld = Path()
    private var trailLastCount = -1
    private var trailLastX = Double.NaN
    private var trailLastZ = Double.NaN

    // Smoothed player marker: we lerp the drawn position toward the target.
    var player: PlayerState? = null
        set(v) {
            field = v
            if (v == null) {
                drawnX = null
            } else if (drawnX == null || Math.abs(v.x - drawnX!!) > 256 || Math.abs(v.z - drawnZ) > 256) {
                drawnX = v.x; drawnZ = v.z; drawnYaw = v.yaw
            }
            postInvalidateOnAnimation()
        }
    private var drawnX: Double? = null
    private var drawnZ = 0.0
    private var drawnYaw = 0f

    // fling inertia state (world blocks/sec)
    private var flingVX = 0.0
    private var flingVZ = 0.0
    private var flingLastNanos = 0L

    private fun stopFling() {
        flingVX = 0.0
        flingVZ = 0.0
    }

    var onLongPressBlock: ((x: Int, z: Int) -> Unit)? = null
    var onTapBlock: ((x: Int, z: Int) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null
    var tileRequester: ((rx: Int, rz: Int) -> Unit)? = null
    var oracleRequester: ((rx: Int, rz: Int) -> Unit)? = null

    // Size caches in bytes (by bitmap.byteCount), not entry count, scaled to heap.
    private val maxMem = Runtime.getRuntime().maxMemory()
    private val tileCache = object : LruCache<String, Bitmap>(
        minOf(48L * 1024 * 1024, maxMem / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()
    private val oracleCache = object : LruCache<String, Bitmap>(
        minOf(16L * 1024 * 1024, maxMem / 16).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val oracleMissing = HashSet<String>()

    private val tilePaint = Paint()
    private val oraclePaint = Paint().apply { alpha = 170 }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()

    fun putTile(rx: Int, rz: Int, bmp: Bitmap?, overview: Boolean = false) {
        val key = "${if (overview) "ov" else "t"}_${rx}_$rz"
        if (bmp != null) {
            tileCache.put(key, bmp)
            missing.remove(key)
        } else {
            missing.add(key)
        }
        postInvalidateOnAnimation()
    }

    /**
     * A region changed (push invalidation): clear its "missing" flag so it can
     * be refetched. The old bitmap stays on screen until putTile swaps it —
     * never evict here or the map flickers.
     */
    fun tileChanged(rx: Int, rz: Int) {
        missing.remove("t_${rx}_$rz")
        missing.remove("ov_${Math.floorDiv(rx, 4)}_${Math.floorDiv(rz, 4)}")
        oracleCache.remove("o_${rx}_$rz")
        oracleMissing.remove("o_${rx}_$rz")
    }

    fun putOracleTile(rx: Int, rz: Int, bmp: Bitmap?) {
        val key = "o_${rx}_$rz"
        if (bmp != null) {
            oracleCache.put(key, bmp)
            oracleMissing.remove(key)
        } else {
            oracleMissing.add(key)
        }
        postInvalidateOnAnimation()
    }

    fun clearTiles() {
        tileCache.evictAll()
        missing.clear()
        oracleCache.evictAll()
        oracleMissing.clear()
        invalidate()
    }

    /** Forget 404s so newly explored regions get re-requested. */
    fun retryMissing() {
        if (missing.isNotEmpty() || oracleMissing.isNotEmpty()) {
            missing.clear()
            oracleMissing.clear()
            invalidate()
        }
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = scale
                scale = (scale * d.scaleFactor).coerceIn(0.05f, 16f)
                // zoom around focal point
                val fx = d.focusX - width / 2f
                val fz = d.focusY - height / 2f
                camX += fx / old - fx / scale
                camZ += fz / old - fz / scale
                invalidate()
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            camX += dx / scale
            camZ += dy / scale
            if (follow) follow = false
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (follow) return false
            flingVX = -vx / scale.toDouble()
            flingVZ = -vy / scale.toDouble()
            flingLastNanos = System.nanoTime()
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val wx = camX + (e.x - width / 2f) / scale
            val wz = camZ + (e.y - height / 2f) / scale
            onLongPressBlock?.invoke(Math.round(wx).toInt(), Math.round(wz).toInt())
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scale = (scale * 1.6f).coerceIn(0.05f, 16f)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val wx = camX + (e.x - width / 2f) / scale
            val wz = camZ + (e.y - height / 2f) / scale
            onTapBlock?.invoke(Math.round(wx).toInt(), Math.round(wz).toInt())
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stopFling()
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // fling inertia: glide with exponential decay until slow, cancel on follow
        if ((flingVX != 0.0 || flingVZ != 0.0)) {
            if (follow) stopFling()
            else {
                val now = System.nanoTime()
                val dt = ((now - flingLastNanos).coerceIn(0, 100_000_000L)) / 1e9
                flingLastNanos = now
                camX += flingVX * dt
                camZ += flingVZ * dt
                // friction scales with zoom: zoomed-out flings glide far, zoomed-in stops snap
                val rate = 3.5 * Math.sqrt(scale.coerceIn(0.05f, 4f).toDouble())
                val decay = Math.exp(-rate * dt)
                flingVX *= decay
                flingVZ *= decay
                if (Math.hypot(flingVX, flingVZ) * scale < 12.0) stopFling()
                else postInvalidateOnAnimation()
            }
        }
        canvas.drawColor(0xFF101214.toInt())
        val halfW = width / 2f
        val halfH = height / 2f

        // advance the smoothed player position before computing the viewport
        var animating = false
        player?.let { p ->
            drawnX?.let { dx ->
                val t = 0.18
                drawnX = dx + (p.x - dx) * t
                drawnZ += (p.z - drawnZ) * t
                val dyaw = ((p.yaw - drawnYaw + 540f) % 360f) - 180f
                drawnYaw += (dyaw * t).toFloat()
                if (Math.abs(p.x - drawnX!!) + Math.abs(p.z - drawnZ) > 0.05) animating = true
            }
            if (follow) {
                camX = drawnX ?: p.x
                camZ = if (drawnX != null) drawnZ else p.z
            }
        }

        val left = camX - halfW / scale
        val top = camZ - halfH / scale
        val right = camX + halfW / scale
        val bottom = camZ + halfH / scale

        tilePaint.isFilterBitmap = scale < 1f
        oraclePaint.isFilterBitmap = scale < 1f

        val span = if (usingOverview()) 2048 else 512
        val layer = if (usingOverview()) "ov" else "t"
        val r0x = Math.floorDiv(Math.floor(left).toInt(), span)
        val r0z = Math.floorDiv(Math.floor(top).toInt(), span)
        val r1x = Math.floorDiv(Math.ceil(right).toInt(), span)
        val r1z = Math.floorDiv(Math.ceil(bottom).toInt(), span)

        for (rx in r0x..r1x) for (rz in r0z..r1z) {
            val key = "${layer}_${rx}_$rz"
            val bmp = tileCache.get(key)
            val dst = RectF(
                ((rx * span - left) * scale).toFloat(),
                ((rz * span - top) * scale).toFloat(),
                ((rx * span + span - left) * scale).toFloat(),
                ((rz * span + span - top) * scale).toFloat()
            )
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dst, tilePaint)
            } else if (!missing.contains(key)) {
                tileRequester?.invoke(rx, rz)
            }
        }

        // oracle era overlay — region tiles only, drawn over the base map
        if (showOracle && !usingOverview()) {
            for (rx in r0x..r1x) for (rz in r0z..r1z) {
                val key = "o_${rx}_$rz"
                val bmp = oracleCache.get(key)
                if (bmp != null) {
                    val dst = RectF(
                        ((rx * span - left) * scale).toFloat(),
                        ((rz * span - top) * scale).toFloat(),
                        ((rx * span + span - left) * scale).toFloat(),
                        ((rz * span + span - top) * scale).toFloat()
                    )
                    canvas.drawBitmap(bmp, null, dst, oraclePaint)
                } else if (!oracleMissing.contains(key)) {
                    oracleRequester?.invoke(rx, rz)
                }
            }
        }

        // travel trail — world-space path, rebuilt only when the trail changes
        if (showTrail && trail.size > 1) {
            val last = trail[trail.size - 1]
            if (trail.size != trailLastCount || last[0] != trailLastX || last[1] != trailLastZ) {
                trailPathWorld.reset()
                var first = true
                for (p in trail) {
                    if (first) {
                        trailPathWorld.moveTo(p[0].toFloat(), p[1].toFloat())
                        first = false
                    } else {
                        trailPathWorld.lineTo(p[0].toFloat(), p[1].toFloat())
                    }
                }
                trailLastCount = trail.size
                trailLastX = last[0]; trailLastZ = last[1]
            }
            canvas.save()
            // world→screen: screen = (world - left/top) * scale
            canvas.translate((-left * scale).toFloat(), (-top * scale).toFloat())
            canvas.scale(scale, scale)
            val savedStroke = trailPaint.strokeWidth
            trailPaint.strokeWidth = 3f / scale   // keep the line visually ~3px at any zoom
            canvas.drawPath(trailPathWorld, trailPaint)
            trailPaint.strokeWidth = savedStroke
            canvas.restore()
        }

        // region / chunk grid
        if (showGrid) {
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = 1f
            markerPaint.color = 0x30FFFFFF
            val step = if (scale > 2f) 16 else 512
            var gx = Math.floorDiv(left.toInt(), step) * step
            while (gx < right) {
                val sx = ((gx - left) * scale).toFloat()
                canvas.drawLine(sx, 0f, sx, height.toFloat(), markerPaint)
                gx += step
            }
            var gz = Math.floorDiv(top.toInt(), step) * step
            while (gz < bottom) {
                val sz = ((gz - top) * scale).toFloat()
                canvas.drawLine(0f, sz, width.toFloat(), sz, markerPaint)
                gz += step
            }
            markerPaint.style = Paint.Style.FILL
        }

        // entity radar — colors match Xaero's minimap (red hostile, yellow neutral, green passive)
        for (e in entities) {
            val isPlayer = e.type == 'p' || e.type == 'f'
            if (isPlayer && !showPlayers) continue
            if (!isPlayer && !showHostiles) continue
            val sx = ((e.x - left) * scale).toFloat()
            val sz = ((e.z - top) * scale).toFloat()
            if (sx < -30 || sz < -30 || sx > width + 30 || sz > height + 30) continue
            markerPaint.color = when (e.type) {
                'f' -> 0xFF55FF55.toInt()
                'p' -> 0xFFFFFFFF.toInt()
                'n' -> 0xFFFFFF55.toInt()
                'a' -> 0xFF55CC55.toInt()
                else -> 0xFFFF5555.toInt()
            }
            canvas.drawCircle(sx, sz, if (isPlayer) 8f else 5f, markerPaint)
            if (isPlayer && e.name != null) {
                canvas.drawText(e.name, sx, sz - 14f, textPaint)
            }
        }

        // waypoints
        for (w in waypoints) {
            val sx = ((w.x - left) * scale).toFloat()
            val sz = ((w.z - top) * scale).toFloat()
            if (sx < -40 || sz < -40 || sx > width + 40 || sz > height + 40) continue
            markerPaint.color = 0xFF000000.toInt() or w.color
            canvas.drawCircle(sx, sz, 10f, markerPaint)
            markerPaint.color = Color.WHITE
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = 2.5f
            canvas.drawCircle(sx, sz, 10f, markerPaint)
            markerPaint.style = Paint.Style.FILL
            if (scale > 0.4f) canvas.drawText(w.name, sx, sz - 16f, textPaint)
        }

        // slime chunks — computed locally from the seed, drawn when zoomed in
        if (showStructures && enabledTypes.contains("slime") && scale >= 0.7f) {
            slimeSeed?.let { seed ->
                val c0x = Math.floorDiv(left.toInt(), 16)
                val c1x = Math.floorDiv(right.toInt(), 16)
                val c0z = Math.floorDiv(top.toInt(), 16)
                val c1z = Math.floorDiv(bottom.toInt(), 16)
                if ((c1x - c0x) * (c1z - c0z) <= 40000) {
                    markerPaint.color = 0x4044D044
                    for (cx in c0x..c1x) for (cz in c0z..c1z) {
                        if (!isSlimeChunk(seed, cx, cz)) continue
                        canvas.drawRect(
                            ((cx * 16 - left) * scale).toFloat(),
                            ((cz * 16 - top) * scale).toFloat(),
                            ((cx * 16 + 16 - left) * scale).toFloat(),
                            ((cz * 16 + 16 - top) * scale).toFloat(), markerPaint)
                    }
                }
            }
        }

        // seed-derived structure markers
        if (showStructures) {
            for (f in structures) {
                if (!enabledTypes.contains(f.type)) continue
                val sx = ((f.x - left) * scale).toFloat()
                val sz = ((f.z - top) * scale).toFloat()
                if (sx < -30 || sz < -30 || sx > width + 30 || sz > height + 30) continue
                val style = structureStyle[f.type] ?: continue
                markerPaint.color = 0xD0101418.toInt()
                canvas.drawRect(sx - 13f, sz - 13f, sx + 13f, sz + 13f, markerPaint)
                markerPaint.color = style.second
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = 2.5f
                canvas.drawRect(sx - 13f, sz - 13f, sx + 13f, sz + 13f, markerPaint)
                markerPaint.style = Paint.Style.FILL
                textPaint.color = style.second
                canvas.drawText(style.first, sx, sz + 8f, textPaint)
                textPaint.color = Color.WHITE
            }
        }

        // autopilot target + course line
        autopilotTarget?.let { t ->
            val tx = ((t[0] - left) * scale).toFloat()
            val tz = ((t[1] - top) * scale).toFloat()
            player?.let { p ->
                val px = ((p.x - left) * scale).toFloat()
                val pz = ((p.z - top) * scale).toFloat()
                markerPaint.color = 0x80FF79C6.toInt()
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = 3f
                canvas.drawLine(px, pz, tx, tz, markerPaint)
                markerPaint.style = Paint.Style.FILL
            }
            canvas.save()
            canvas.translate(tx, tz)
            canvas.rotate(45f)
            markerPaint.color = 0xFFFF79C6.toInt()
            canvas.drawRect(-11f, -11f, 11f, 11f, markerPaint)
            markerPaint.color = Color.WHITE
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = 2.5f
            canvas.drawRect(-11f, -11f, 11f, 11f, markerPaint)
            markerPaint.style = Paint.Style.FILL
            canvas.restore()
            canvas.drawText("FLY", tx, tz - 20f, textPaint)
        }

        // player arrow at the smoothed position
        player?.let { p ->
            val px = drawnX ?: p.x
            val pz = if (drawnX != null) drawnZ else p.z
            val pyaw = if (drawnX != null) drawnYaw else p.yaw
            val sx = ((px - left) * scale).toFloat()
            val sz = ((pz - top) * scale).toFloat()
            drawArrow(canvas, sx, sz, pyaw)
        }

        drawCompass(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    private val compassBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x70202430 }
    private val compassText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    /** N/S/E/W edge labels — map is always north-up. */
    private fun drawCompass(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val inset = 34f
        compassText.color = 0xFFFF5555.toInt()
        canvas.drawCircle(cx, inset, 26f, compassBg)
        canvas.drawText("N", cx, inset + 12f, compassText)
        compassText.color = Color.WHITE
        canvas.drawCircle(cx, height - inset, 26f, compassBg)
        canvas.drawText("S", cx, height - inset + 12f, compassText)
        canvas.drawCircle(width - inset, cy, 26f, compassBg)
        canvas.drawText("E", width - inset, cy + 12f, compassText)
        canvas.drawCircle(inset, cy, 26f, compassBg)
        canvas.drawText("W", inset, cy + 12f, compassText)
    }

    private fun drawArrow(canvas: Canvas, sx: Float, sz: Float, yaw: Float) {
        run {
            canvas.save()
            canvas.translate(sx, sz)
            canvas.rotate(yaw + 180f)
            arrowPath.reset()
            arrowPath.moveTo(0f, -18f)
            arrowPath.lineTo(12f, 16f)
            arrowPath.lineTo(0f, 9f)
            arrowPath.lineTo(-12f, 16f)
            arrowPath.close()
            markerPaint.color = 0xFF44AAFF.toInt()
            canvas.drawPath(arrowPath, markerPaint)
            markerPaint.color = Color.WHITE
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = 2f
            canvas.drawPath(arrowPath, markerPaint)
            markerPaint.style = Paint.Style.FILL
            canvas.restore()
        }
    }

    fun centerOn(x: Double, z: Double) {
        camX = x
        camZ = z
        invalidate()
    }

    /** Tiles currently in view (current layer), for periodic revalidation. */
    fun visibleRegions(): List<Pair<Int, Int>> {
        if (width == 0) return emptyList()
        val span = if (usingOverview()) 2048 else 512
        val left = camX - width / 2f / scale
        val top = camZ - height / 2f / scale
        val right = camX + width / 2f / scale
        val bottom = camZ + height / 2f / scale
        val out = ArrayList<Pair<Int, Int>>()
        for (rx in Math.floorDiv(left.toInt(), span)..Math.floorDiv(right.toInt(), span))
            for (rz in Math.floorDiv(top.toInt(), span)..Math.floorDiv(bottom.toInt(), span))
                out.add(rx to rz)
        return out
    }
}
