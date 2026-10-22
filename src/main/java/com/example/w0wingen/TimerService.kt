package com.example.w0wingen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * W0wingen - MU Immortal boss doğma süresi takibi ve otomasyonu.
 *
 * Kaydet: boss kesilince mezarın sayacı, harita adı + wire ve haritadaki oyuncu noktası okunur.
 * Otomasyon: süresi 20 sn kalan ya da geçmiş bosslara sırayla gider, oto atakla keser, sayacı yeniden
 * kaydeder; boss yokken farm konumuna gider, oto atak ve kalkanı açar.
 */
class TimerService : AccessibilityService() {

    /** Kullanıcı durdurdu. */
    private class StopEx : RuntimeException()

    /** Bir adım başarısız oldu (mesaj zaten gösterildi). */
    private class StepFail : RuntimeException()

    private class MapInfo(val name: String, val wires: Int)

    private class Entry(
        val id: Long,
        val map: String,
        val wire: Int,
        var idx: Int,
        var endMs: Long,
        var px: Float,
        var py: Float
    )

    private class RectView(
        ctx: Context,
        val rects: List<Pair<String, FloatArray>>,
        val dot: PointF?
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            p.strokeWidth = 5f
            p.textSize = 26f
            for ((label, r) in rects) {
                p.style = Paint.Style.STROKE
                p.color = Color.CYAN
                c.drawRect(r[0], r[1], r[2], r[3], p)
                p.style = Paint.Style.FILL
                p.color = Color.YELLOW
                c.drawText(label, r[0] + 4f, r[1] + 28f, p)
            }
            if (dot != null) {
                p.style = Paint.Style.STROKE
                p.color = Color.GREEN
                c.drawCircle(dot.x, dot.y, 26f, p)
            }
        }
    }

    companion object {
        const val DEFAULT_MAPS = "Raklion 1;3\nRaklion 2;3\nRaklion 3;3\nDivine Realm;1\n" +
            "High Heaven 1;1\nPurgatory of Misery 1;1\nEndless Abyss 1;1\nCorridor of Agony 1;1"

        /** Ayar menüsündeki gruplar. "Işınlanma bağlantıları" grubu haritalara göre ayrıca eklenir. */
        val GROUPS = listOf(
            "1 · Okuma bölgeleri (Test ile kontrol et)" to listOf("nameTL", "nameBR", "cntTL", "cntBR", "miniTL", "miniBR"),
            "2 · Harita" to listOf("minimap", "closeMap", "random"),
            "3 · Chat" to listOf("chat", "pmTab", "closeChat"),
            "5 · Wire (sadece Raklion'lar)" to listOf("switchBtn", "wire1", "wire2", "wire3", "switchOk"),
            "6 · Farm" to listOf("autoAtk", "shield"),
            "7 · Envanter, pot, Recycle" to listOf(
                "inventory", "shopTab", "purchaseHP", "purchaseMP", "hpCount", "mpCount",
                "recycleTab", "recycleBtn", "closeRight", "closeLeft"
            )
        )

        val LABELS = mapOf(
            "nameTL" to "Mini harita üstündeki 'Raklion 2 [Wire3]' yazısının SOL ÜSTÜ",
            "nameBR" to "Aynı yazının SAĞ ALTI (Switch düğmesini içine alma)",
            "cntTL" to "Mezar sayacının arandığı alanın SOL ÜSTÜ (karakterin hemen üstü)",
            "cntBR" to "Mezar sayacının arandığı alanın SAĞ ALTI",
            "miniTL" to "Mini haritanın SOL ÜST köşesi (boss ikonu burada aranır)",
            "miniBR" to "Mini haritanın SAĞ ALT köşesi",
            "minimap" to "Sağ üstteki mini harita (haritayı açar)",
            "closeMap" to "Harita ekranının X (kapat) düğmesi (harita açıkken işaretle)",
            "random" to "Haritadaki Random düğmesi (harita açıkken işaretle)",
            "chat" to "Chat'i açan düğme/alan",
            "pmTab" to "Chat'te PM sekmesi",
            "closeChat" to "Chat'i kapatma (isteğe bağlı)",
            "farmLink" to "PM'de FARM konumunun ışınlanma bağlantısı (boss yokken beklenecek yer)",
            "buffLink" to "PM'de BUFF noktasının ışınlanma bağlantısı (buff açıksa)",
            "switchBtn" to "Mini haritadaki Switch düğmesi",
            "wire1" to "Switch listesinde 1. wire",
            "wire2" to "Switch listesinde 2. wire",
            "wire3" to "Switch listesinde 3. wire",
            "switchOk" to "Wire seçince onay düğmesi varsa (isteğe bağlı)",
            "autoAtk" to "Sağdaki Auto (oto saldırı) düğmesi",
            "shield" to "Kalkan düğmesi (farm konumuna varınca basılır)",
            "inventory" to "Sağdaki Inventory düğmesi",
            "shopTab" to "Envanterde alttaki Shop sekmesi (Inventory açıkken)",
            "purchaseHP" to "Shop'ta 99'luk HP pot Purchase düğmesi",
            "purchaseMP" to "Shop'ta 99'luk MP pot Purchase düğmesi",
            "hpCount" to "Alt çubukta HP pot SAYISININ yeri (örn. 452)",
            "mpCount" to "Alt çubukta MP pot SAYISININ yeri (örn. 1425)",
            "recycleTab" to "Envanterde alttaki Recycle sekmesi (Inventory açıkken)",
            "recycleBtn" to "Recycle ekranındaki mavi Recycle düğmesi",
            "closeRight" to "Pencerelerin sağ üst X düğmesi (Inventory/Shop/Recycle)",
            "closeLeft" to "Pencerelerin sol X düğmesi (Gear/Inventory)"
        )

        val DEFAULTS = mapOf(
            "nameTL" to Pair(0.800f, 0.000f),
            "nameBR" to Pair(0.893f, 0.045f),
            "cntTL" to Pair(0.430f, 0.240f),
            "cntBR" to Pair(0.620f, 0.460f),
            "miniTL" to Pair(0.865f, 0.012f),
            "miniBR" to Pair(0.985f, 0.190f),
            "minimap" to Pair(0.922f, 0.046f),
            "closeMap" to Pair(0.894f, 0.157f),
            "random" to Pair(0.868f, 0.814f),
            "switchBtn" to Pair(0.909f, 0.019f),
            "inventory" to Pair(0.971f, 0.376f),
            "shopTab" to Pair(0.743f, 0.884f),
            "purchaseHP" to Pair(0.934f, 0.347f),
            "purchaseMP" to Pair(0.934f, 0.570f),
            "recycleTab" to Pair(0.878f, 0.884f),
            "recycleBtn" to Pair(0.845f, 0.865f),
            "hpCount" to Pair(0.432f, 0.980f),
            "mpCount" to Pair(0.480f, 0.980f),
            "closeRight" to Pair(0.984f, 0.074f),
            "closeLeft" to Pair(0.672f, 0.074f)
        )

        // harita çizim alanı (ekran oranı): yeşil oyuncu imleci burada aranır
        const val MAP_X0 = 0.30f
        const val MAP_X1 = 0.84f
        const val MAP_Y0 = 0.18f
        const val MAP_Y1 = 0.84f

        /** Paneldeki açık/kapalı düğmeleri: anahtar, yazı, varsayılan. */
        val TOGGLES = listOf(
            Triple("potOn", "Pot", true),
            Triple("recycleOn", "Recycle", true),
            Triple("farmOn", "Farm", true),
            Triple("buffOn", "Buff", false),
            Triple("randomOn", "Random", true)
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var handle: TextView? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var saveBtn: Button? = null
    private var autoBtn: Button? = null
    private var listCol: LinearLayout? = null
    private var radiusView: TextView? = null
    private var gapView: TextView? = null
    private var toolsBox: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private val toggleBtns = HashMap<String, Button>()

    private val ACCENT = 0xFF4FC3F7.toInt()
    private val PANEL_BG = 0xF2161920.toInt()
    private val CARD_BG = 0xFF272C3A.toInt()
    private val MISSING_BG = 0xFF4A2429.toInt()
    private val TEXT_DIM = 0xFF9AA3B5.toInt()
    private val OK = 0xFF3DDC84.toInt()
    private val WARN = 0xFFF5B83D.toInt()
    private val BAD = 0xFFE5484D.toInt()

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val timerViews = HashMap<Long, TextView>()
    private val stripViews = HashMap<Long, View>()
    private val blocked = HashMap<Long, Long>()

    @Volatile
    private var busy = false

    @Volatile
    private var cancel = false

    @Volatile
    private var autoRunning = false
    private var worker: Thread? = null

    private var panelOpen = false
    private var toolsOpen = false
    private var hx = 0
    private var hy = 0

    private var gameAuto = false
    private var farming = false
    private var currentId = 0L
    private var kills = 0
    private var fails = 0
    private var lastMaint = 0L
    private var lastPotBuy = 0L
    private var lastRecycle = 0L

    private var sent = 0
    private var cancelled = 0
    private var lastShot = 0L
    private var screenW = 1
    private var screenH = 1
    private var lastOcr = ""

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ yasam dongusu

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
        loadEntries()
        buildUi()
        rebuildList()
        main.postDelayed(ticker, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        cancel = true
        autoRunning = false
        worker?.interrupt()
        main.removeCallbacks(ticker)
        try {
            removeExtra()
            panel?.let { wm.removeView(it) }
            handle?.let { wm.removeView(it) }
            menu?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ yardimcilar

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun say(msg: String) {
        ui { statusView?.text = msg }
    }

    private fun toast(msg: String) {
        ui { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun refreshScreen() {
        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun prefs() = getSharedPreferences("w0wingen", Context.MODE_PRIVATE)

    private fun cfgF(key: String, def: Float): Float =
        prefs().getString("cfg_" + key, null)?.toFloatOrNull() ?: def

    private fun cfgI(key: String, def: Int): Int = cfgF(key, def.toFloat()).toInt()

    private fun cfgB(key: String, def: Boolean): Boolean = prefs().getBoolean("cfg_" + key, def)

    /** Pot ve Recycle tıklamaları arasındaki bekleme (ms). Oyundaki panelden ayarlanır. */
    private fun gap(): Int = (cfgF("clickGap", 1.0f) * 1000).toInt().coerceIn(200, 10000)

    private fun toggleDef(key: String): Boolean {
        for (t in TOGGLES) if (t.first == key) return t.third
        return true
    }

    private fun on(key: String): Boolean = cfgB(key, toggleDef(key))

    private fun hasPt(name: String): Boolean = prefs().contains(name + "_x") || DEFAULTS.containsKey(name)

    private fun pt(name: String): Pair<Float, Float> {
        val d = DEFAULTS[name] ?: Pair(0.5f, 0.5f)
        val p = prefs()
        return Pair(p.getFloat(name + "_x", d.first), p.getFloat(name + "_y", d.second))
    }

    private fun savePt(name: String, x: Float, y: Float) {
        prefs().edit().putFloat(name + "_x", x).putFloat(name + "_y", y).apply()
    }

    private fun pause(ms: Int) {
        var left = ms
        while (left > 0) {
            if (cancel) throw StopEx()
            val s = if (left < 100) left else 100
            Thread.sleep(s.toLong())
            left -= s
        }
        if (cancel) throw StopEx()
    }

    private fun fail(msg: String): Nothing {
        say(msg)
        if (!autoRunning) toast(msg)
        throw StepFail()
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun fmt(totalSec: Long): String {
        val s = Math.abs(totalSec)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------------ harita listesi

    private fun maps(): List<MapInfo> {
        val raw = prefs().getString("cfg_maps", null) ?: DEFAULT_MAPS
        val out = ArrayList<MapInfo>()
        for (line in raw.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split(";")
            val n = parts[0].trim()
            val w = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 1 else 1
            if (n.isNotEmpty()) out.add(MapInfo(n, w.coerceIn(1, 3)))
        }
        return out
    }

    private fun mapInfo(name: String): MapInfo {
        val k = key(name)
        for (m in maps()) if (key(m.name) == k) return m
        return MapInfo(name, 1)
    }

    /** Ayar menüsü grupları: sabit gruplar ve haritalara göre ışınlanma bağlantıları. */
    private fun pointGroups(): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for (g in GROUPS) {
            out.add(g)
            if (g.first.startsWith("3")) {
                val links = ArrayList<String>()
                links.add("farmLink")
                links.add("buffLink")
                for (m in maps()) links.add("link_" + m.name)
                out.add(Pair("4 · Işınlanma bağlantıları (PM sekmesinde)", links))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ kayitlar

    private fun loadEntries() {
        synchronized(lock) {
            entries.clear()
            val s = prefs().getString("entries", "[]") ?: "[]"
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    entries.add(
                        Entry(
                            o.getLong("id"), o.getString("map"), o.getInt("wire"),
                            o.optInt("idx", 0), o.getLong("end"),
                            o.getDouble("px").toFloat(), o.getDouble("py").toFloat()
                        )
                    )
                }
            } catch (e: Exception) {
            }
            // eski kayıtlara numara ver
            for (e in entries) {
                if (e.idx > 0) continue
                var n = 1
                while (entries.any { it !== e && key(it.map) == key(e.map) && it.wire == e.wire && it.idx == n }) n++
                e.idx = n
            }
        }
    }

    private fun saveEntries() {
        val a = JSONArray()
        synchronized(lock) {
            for (e in entries) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("map", e.map)
                o.put("wire", e.wire)
                o.put("idx", e.idx)
                o.put("end", e.endMs)
                o.put("px", e.px.toDouble())
                o.put("py", e.py.toDouble())
                a.put(o)
            }
        }
        prefs().edit().putString("entries", a.toString()).apply()
    }

    private fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    private fun title(e: Entry): String {
        val w = if (mapInfo(e.map).wires > 1) " · Wire " + e.wire else ""
        return e.map + w + " · Boss " + e.idx
    }

    /** Aynı harita+wire'da haritadaki nokta yakınsa aynı boss'tur, değilse yeni numara alır. */
    private fun upsert(map: String, wire: Int, endMs: Long, px: Float, py: Float): Entry {
        refreshScreen()
        val tol = cfgI("sameBossPx", 90).toFloat()
        var result: Entry? = null
        synchronized(lock) {
            var hit: Entry? = null
            var bd = Float.MAX_VALUE
            for (e in entries) {
                if (key(e.map) != key(map) || e.wire != wire) continue
                val dx = (e.px - px) * screenW
                val dy = (e.py - py) * screenH
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d <= tol && d < bd) {
                    bd = d
                    hit = e
                }
            }
            if (hit != null) {
                hit.endMs = endMs
                result = hit
            } else {
                var idx = 1
                while (entries.any { key(it.map) == key(map) && it.wire == wire && it.idx == idx }) idx++
                val ne = Entry(System.currentTimeMillis(), map, wire, idx, endMs, px, py)
                entries.add(ne)
                result = ne
            }
        }
        saveEntries()
        return result!!
    }

    private fun setEnd(e: Entry, endMs: Long) {
        e.endMs = endMs
        saveEntries()
        ui { rebuildList() }
    }

    // ------------------------------------------------------------------ ekran okuma

    private fun grab(): Bitmap? {
        val gap = 1100L - (System.currentTimeMillis() - lastShot)
        if (gap > 0) Thread.sleep(gap)
        lastShot = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        if (hw != null) {
                            result = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        }
                        hb.close()
                    } catch (e: Exception) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    say("Ekran görüntüsü alınamadı (kod " + errorCode + ")")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            say("Ekran görüntüsü hatası: " + e.message)
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /** Panel ve tutamacı gizleyip ekran görüntüsü alır (panel okunacak yerleri kapatmasın). */
    private fun shot(): Bitmap? {
        ui {
            if (panelOpen) panel?.visibility = View.INVISIBLE
            handle?.visibility = View.INVISIBLE
        }
        Thread.sleep(350)
        val b = grab()
        ui {
            panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
            handle?.visibility = View.VISIBLE
        }
        return b
    }

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r < 120 && g > 170 && b < 120 && g - r > 80
    }

    private fun isOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g in 110..210 && b < 90
    }

    private fun orangeAt(b: Bitmap, name: String): Boolean {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val hw = 0.012f * b.width
        val hh = 0.020f * b.height
        val x0 = maxOf(0, (cx - hw).toInt())
        val x1 = minOf(b.width - 1, (cx + hw).toInt())
        val y0 = maxOf(0, (cy - hh).toInt())
        val y1 = minOf(b.height - 1, (cy + hh).toInt())
        var hit = 0
        var tot = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                tot++
                if (isOrange(b.getPixel(x, y))) hit++
            }
        }
        return tot > 0 && hit.toFloat() / tot >= 0.12f
    }

    /** Açık haritada yeşil oyuncu imlecinin merkezi (piksel). Yoksa null. */
    private fun findPlayer(b: Bitmap): PointF? {
        val bw = b.width
        val bh = b.height
        val s = bw / 2560f
        val x0 = (MAP_X0 * bw).toInt()
        val x1 = (MAP_X1 * bw).toInt()
        val y0 = (MAP_Y0 * bh).toInt()
        val y1 = (MAP_Y1 * bh).toInt()
        val w = x1 - x0
        val h = y1 - y0
        var gx = 0L
        var gy = 0L
        var gn = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, x0, y0 + y, w, 1)
            for (x in 0 until w) {
                if (isGreen(row[x])) {
                    gx += x
                    gy += y
                    gn++
                }
            }
        }
        if (gn < 15 * s * s) return null
        return PointF(x0 + gx.toFloat() / gn, y0 + gy.toFloat() / gn)
    }

    /** Mini haritadaki canlı boss ikonunun turuncu-kırmızı alev rengi. Ölü ikon soluk olduğu için buna girmez. */
    private fun isBossRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r >= 165 && b <= 90 && g >= 35 && r - g >= 70 && r - b >= 110
    }

    /** Mini haritanın ortasında (oyuncunun yanında) canlı boss ikonu oranı. */
    private fun miniFrac(b: Bitmap): Float {
        val a = pt("miniTL")
        val c = pt("miniBR")
        val cxm = (a.first + c.first) / 2f
        val cym = (a.second + c.second) / 2f
        val hw = (c.first - a.first) / 2f * 0.6f
        val hh = (c.second - a.second) / 2f * 0.6f
        val x0 = maxOf(0, ((cxm - hw) * b.width).toInt())
        val x1 = minOf(b.width - 1, ((cxm + hw) * b.width).toInt())
        val y0 = maxOf(0, ((cym - hh) * b.height).toInt())
        val y1 = minOf(b.height - 1, ((cym + hh) * b.height).toInt())
        if (x1 <= x0 || y1 <= y0) return 0f
        val w = x1 - x0 + 1
        val row = IntArray(w)
        var hit = 0
        for (y in y0..y1) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (isBossRed(row[x])) hit++
        }
        return hit.toFloat() / (w * (y1 - y0 + 1))
    }

    private fun miniAlive(b: Bitmap): Boolean = miniFrac(b) >= 0.004f

    // ------------------------------------------------------------------ OCR

    private fun crop(b: Bitmap, a: String, c: String): Bitmap? {
        val p1 = pt(a)
        val p2 = pt(c)
        val x0 = (minOf(p1.first, p2.first) * b.width).toInt().coerceIn(0, b.width - 2)
        val y0 = (minOf(p1.second, p2.second) * b.height).toInt().coerceIn(0, b.height - 2)
        val x1 = (maxOf(p1.first, p2.first) * b.width).toInt().coerceIn(x0 + 1, b.width)
        val y1 = (maxOf(p1.second, p2.second) * b.height).toInt().coerceIn(y0 + 1, b.height)
        if (x1 - x0 < 8 || y1 - y0 < 8) return null
        return Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
    }

    private fun ocr(bm: Bitmap): com.google.mlkit.vision.text.Text? {
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bm, 0)), 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            lastOcr = "hata: " + e.message
            null
        }
    }

    private fun ocrRegion(b: Bitmap, a: String, c: String): String? {
        val cr = crop(b, a, c) ?: return null
        val big = Bitmap.createScaledBitmap(cr, cr.width * 3, cr.height * 3, true)
        val res = ocr(big) ?: return null
        lastOcr = res.text.replace("\n", " ")
        return res.text
    }

    /** "Raklion 2 [Wire3]" -> (Raklion 2, 3). Harita listesindeki en yakın adla eşleştirilir. */
    private fun readMapWire(b: Bitmap): Pair<String, Int>? {
        val raw = ocrRegion(b, "nameTL", "nameBR") ?: return null
        val t = raw.replace("\n", " ").trim()
        if (t.isEmpty()) return null
        var cut = t.indexOf('[')
        if (cut < 0) cut = t.indexOf('(')
        if (cut < 0) {
            val m = Regex("(wire|line)", RegexOption.IGNORE_CASE).find(t)
            if (m != null) cut = m.range.first
        }
        val namePart = (if (cut > 0) t.substring(0, cut) else t).trim()
        val tail = if (cut >= 0) t.substring(cut) else ""
        val wire = Regex("\\d").find(tail)?.value?.toIntOrNull() ?: 1
        val k = key(namePart)
        var best: MapInfo? = null
        var bd = Int.MAX_VALUE
        for (m in maps()) {
            val d = lev(k, key(m.name))
            if (d < bd) {
                bd = d
                best = m
            }
        }
        val good = best != null && bd <= maxOf(2, best.name.length / 4)
        val name = if (good && best != null) best.name else namePart
        val w = if (good && best != null) wire.coerceIn(1, best.wires) else wire
        return Pair(name, w)
    }

    /** "34: 23", "3423", "34;23" -> saniye. Harf karışması (O, l) düzeltilir. */
    private fun parseTimer(t: String): Int? {
        val fixed = t.map {
            when (it) {
                'O', 'o', 'D' -> '0'
                'l', 'I', '|' -> '1'
                else -> it
            }
        }.joinToString("")
        val d = fixed.filter { it.isDigit() }
        val mm: Int
        val ss: Int
        when (d.length) {
            3 -> {
                mm = d.substring(0, 1).toInt()
                ss = d.substring(1).toInt()
            }
            4 -> {
                mm = d.substring(0, 2).toInt()
                ss = d.substring(2).toInt()
            }
            else -> return null
        }
        if (ss > 59 || mm > 90) return null
        return mm * 60 + ss
    }

    /**
     * Mezarın üstündeki kırmızı sayacı okur (saniye). Sadece kırmızı pikseller OCR'a verilir,
     * ince ":" için bir de kalınlaştırılmış kopya denenir. İki nokta olmasa da rakamlar yorumlanır.
     */
    private fun readTimer(b: Bitmap): Int? {
        val p1 = pt("cntTL")
        val p2 = pt("cntBR")
        val x0 = (minOf(p1.first, p2.first) * b.width).toInt().coerceIn(0, b.width - 2)
        val y0 = (minOf(p1.second, p2.second) * b.height).toInt().coerceIn(0, b.height - 2)
        val x1 = (maxOf(p1.first, p2.first) * b.width).toInt().coerceIn(x0 + 1, b.width)
        val y1 = (maxOf(p1.second, p2.second) * b.height).toInt().coerceIn(y0 + 1, b.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, x0, y0, w, h)
        val mask = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val bl = Color.blue(c)
            mask[i] = if (r > 140 && r - g > 80 && r - bl > 80) Color.BLACK else Color.WHITE
        }
        val thick = mask.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i] == Color.BLACK) continue
                if (mask[i - 1] == Color.BLACK || mask[i + 1] == Color.BLACK ||
                    mask[i - w] == Color.BLACK || mask[i + w] == Color.BLACK
                ) thick[i] = Color.BLACK
            }
        }
        val scale = 3
        val cx = w * scale / 2f
        val cy = h * scale / 2f
        for (v in listOf(mask, thick)) {
            val bm = Bitmap.createBitmap(v, w, h, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createScaledBitmap(bm, w * scale, h * scale, true)
            val res = ocr(big) ?: continue
            lastOcr = res.text.replace("\n", " ")
            var best: Int? = null
            var bd = Float.MAX_VALUE
            for (blk in res.textBlocks) {
                for (ln in blk.lines) {
                    val secs = parseTimer(ln.text) ?: continue
                    val bb = ln.boundingBox
                    val d = if (bb != null) {
                        val dx = bb.centerX() - cx
                        val dy = bb.centerY() - cy
                        dx * dx + dy * dy
                    } else 0f
                    if (d < bd) {
                        bd = d
                        best = secs
                    }
                }
            }
            if (best != null) return best
        }
        return null
    }

    /** Birbirine 2,5 sn içinde yakın en az [min] okumanın ortancasını (bitiş zamanı) döndürür. */
    private fun consensus(ends: List<Long>, min: Int = 2): Long? {
        var bestGroup: List<Long> = emptyList()
        for (e in ends) {
            val g = ends.filter { Math.abs(it - e) <= 2500L }
            if (g.size > bestGroup.size) bestGroup = g
        }
        if (bestGroup.size < min) return null
        val sorted = bestGroup.sorted()
        return sorted[sorted.size / 2]
    }

    /** Sol üstteki ATK ve DEF değerleri. */
    private fun readStats(b: Bitmap): Pair<Int, Int>? {
        return try {
            val w = (0.27f * b.width).toInt()
            val h = (0.07f * b.height).toInt()
            val cr = Bitmap.createBitmap(b, 0, 0, w, h)
            val big = Bitmap.createScaledBitmap(cr, w * 2, h * 2, true)
            val res = ocr(big) ?: return null
            lastOcr = res.text.replace("\n", " ")
            val s = res.text.replace(Regex("[\\s,.]"), "")
            val nums = Regex("\\d{3,6}").findAll(s).map { it.value.toInt() }.toList()
            if (nums.size >= 2) Pair(nums[0], nums[1]) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Alt çubuktaki pot sayısını okur. Sayı hiç görünmüyorsa (pot bitti) 0, okuma hatasında null. */
    private fun readCount(b: Bitmap, name: String): Int? {
        val p = pt(name)
        val cx = (p.first * b.width).toInt()
        val cy = (p.second * b.height).toInt()
        val hw = (0.030f * b.width).toInt()
        val hh = (0.028f * b.height).toInt()
        val x0 = maxOf(0, cx - hw)
        val y0 = maxOf(0, cy - hh)
        val w = minOf(b.width - x0, hw * 2)
        val h = minOf(b.height - y0, hh * 2)
        if (w < 8 || h < 8) return null
        val cr = Bitmap.createBitmap(b, x0, y0, w, h)
        for (scale in intArrayOf(3, 2)) {
            val big = Bitmap.createScaledBitmap(cr, w * scale, h * scale, true)
            val res = ocr(big) ?: return null
            val tok = Regex("\\d+").findAll(res.text.replace(Regex("[\\s,.]"), ""))
                .map { it.value }.maxByOrNull { it.length }
            if (tok != null) return tok.toIntOrNull()
        }
        return 0
    }

    // ------------------------------------------------------------------ dokunma

    /** Dokunma sırasında panelin ve tutamacın dokunuşu yutmaması için pencereleri geçirgen yapar. */
    private fun setThrough(through: Boolean) {
        val latch = CountDownLatch(1)
        ui {
            try {
                applyThrough(panel, panelLp, through)
                applyThrough(handle, handleLp, through)
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    private fun applyThrough(v: View?, lp: WindowManager.LayoutParams?, through: Boolean) {
        if (v == null || lp == null) return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (through) lp.flags or f else lp.flags and f.inv()
        wm.updateViewLayout(v, lp)
    }

    private fun tapPx(x: Float, y: Float): Boolean {
        setThrough(true)
        try {
            Thread.sleep(40)
            if (shizukuReady() && shell(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString()))) {
                sent++
                return true
            }
            val p = Path()
            p.moveTo(x, y)
            return runGesture(p, 70L)
        } finally {
            setThrough(false)
        }
    }

    private fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun shizukuAsk() {
        try {
            if (!Shizuku.pingBinder()) {
                say("Shizuku çalışmıyor. Shizuku uygulamasını aç ve 'Başlat'a bas.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                say("Shizuku izni tamam, dokunmalar Shizuku ile yapılacak")
                return
            }
            Shizuku.requestPermission(1001)
            say("Shizuku izin penceresinde 'İzin ver'e bas")
        } catch (e: Throwable) {
            say("Shizuku hatası: " + e.message)
        }
    }

    private fun shell(args: Array<String>): Boolean {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, args, null, null) as Process
            proc.waitFor() == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun runGesture(p: Path, dur: Long): Boolean {
        for (i in 0 until 3) {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            val accepted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (accepted) latch.await(2, TimeUnit.SECONDS)
            if (ok) {
                sent++
                return true
            }
            cancelled++
            Thread.sleep(300)
        }
        return false
    }

    private fun tapPoint(name: String) {
        refreshScreen()
        val p = pt(name)
        tapPx(p.first * screenW, p.second * screenH)
    }

    // ------------------------------------------------------------------ is adimlari

    /** Haritayı açar ve harita açıkken alınmış ekran görüntüsünü döndürür. */
    private fun ensureMap(): Bitmap {
        for (i in 0 until 4) {
            val b = shot()
            if (b != null && orangeAt(b, "closeMap")) return b
            tapPoint("minimap")
            pause(1500)
        }
        fail("Harita açılamadı. 'Ayar' menüsünden minimap ve closeMap noktalarını kontrol et.")
    }

    private fun closeMapIfOpen() {
        val b = shot() ?: return
        if (orangeAt(b, "closeMap")) {
            tapPoint("closeMap")
            pause(700)
        }
    }

    private fun job(block: () -> Unit) {
        if (busy) {
            say("Başka bir işlem sürüyor")
            return
        }
        busy = true
        cancel = false
        val wasOpen = panelOpen
        panelOpen = false
        updateUi()
        val t = Thread {
            try {
                block()
            } catch (e: StopEx) {
            } catch (e: StepFail) {
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                say("Hata: " + e.message)
            }
            busy = false
            autoRunning = false
            ui {
                panelOpen = wasOpen
                updateUi()
            }
        }
        worker = t
        t.start()
    }

    private fun stopJob() {
        cancel = true
        autoRunning = false
        worker?.interrupt()
        say("Durduruldu")
    }

    /** Elle basılan düğmeler: otomasyon açıkken çalışmaz, işlem sürüyorsa durdurur. */
    private fun runManual(block: () -> Unit) {
        if (autoRunning) {
            say("Otomasyon açık. Önce otomasyonu durdur.")
            return
        }
        if (busy) {
            stopJob()
            return
        }
        job(block)
    }

    /** Kaydet: harita+wire, mezar sayacı ve haritadaki oyuncu noktasını okuyup satır ekler. */
    private fun doSave() {
        say("Okunuyor...")
        closeMapIfOpen()
        var info: Pair<String, Int>? = null
        val ends = ArrayList<Long>()
        var endMs: Long? = null
        var frames = 0
        for (i in 0 until 8) {
            val b = shot() ?: fail("Ekran görüntüsü alınamadı")
            val stamp = lastShot
            frames++
            if (info == null) info = readMapWire(b)
            val s = readTimer(b)
            if (s != null) {
                ends.add(stamp + s * 1000L)
                endMs = consensus(ends)
            }
            say("Okunuyor... kare " + frames + "  sayaç okuma " + ends.size + "  uyuşan: " + (if (endMs != null) "evet" else "henüz yok"))
            if (endMs != null && info != null) break
        }
        if (info == null) fail("Harita adı okunamadı ('" + lastOcr + "'). Test ile bölgeyi kontrol et.")
        if (endMs == null) {
            fail(
                if (ends.isEmpty()) "Mezar sayacı okunamadı ('" + lastOcr + "'). Karakteri mezarın üstüne getirip tekrar bas."
                else "Sayaç okumaları birbiriyle uyuşmadı (" + ends.size + " okuma). Tekrar bas."
            )
        }
        val endFinal: Long = endMs!!
        val ci = info

        say("Harita noktası alınıyor...")
        val mb = ensureMap()
        val me = findPlayer(mb)
        tapPoint("closeMap")
        pause(600)
        if (me == null) fail("Harita noktası alınamadı (yeşil imleç görünmedi). Tekrar bas.")
        val e = upsert(ci.first, ci.second, endFinal, me.x / mb.width, me.y / mb.height)
        ui { rebuildList() }
        say("Kaydedildi: " + title(e) + " · " + fmt((endFinal - System.currentTimeMillis()) / 1000))
    }

    private fun readMapWireRetry(): Pair<String, Int> {
        for (i in 0 until 3) {
            val b = shot() ?: fail("Ekran görüntüsü alınamadı")
            val r = readMapWire(b)
            if (r != null) return r
            pause(800)
        }
        fail("Harita adı okunamadı ('" + lastOcr + "'). Test ile bölgeyi kontrol et.")
    }

    /** Bossun kayıtlı noktasına gider: gerekirse ışınlanır, wire değiştirir, Random ile yaklaşır, yürür. */
    private fun doGo(e: Entry) {
        say("Gidiliyor: " + title(e))
        ensureLocation(e.map, e.wire)

        var mb = ensureMap()
        refreshScreen()
        val tx = e.px * screenW
        val ty = e.py * screenH
        val target = PointF(tx, ty)

        // Random ile boss noktasına yakın bir yere ışınlan, sonra yürü
        val radius = cfgI("radius", 200).toFloat()
        if (radius > 0f && on("randomOn") && hasPt("random")) {
            val maxTry = cfgI("randomMax", 40)
            var tries = 0
            while (tries < maxTry) {
                val me0 = findPlayer(mb)
                if (me0 != null && dist(me0, target) <= radius) break
                tries++
                say("Random " + tries + "/" + maxTry + " · " + title(e))
                tapPoint("random")
                gameAuto = false
                pause((cfgF("randomWait", 2.5f) * 1000).toInt())
                mb = ensureMap()
            }
        }
        tapPx(tx, ty)
        pause(800)
        val arrive = cfgI("arrive", 45).toFloat()
        val limit = cfgI("walkTimeout", 90) * 1000L
        var t0 = System.currentTimeMillis()
        var retapped = false
        while (true) {
            pause(400)
            val bb = shot() ?: continue
            if (!orangeAt(bb, "closeMap")) {
                tapPoint("minimap")
                pause(1200)
                continue
            }
            val me = findPlayer(bb)
            if (me != null && dist(me, target) <= arrive) break
            if (System.currentTimeMillis() - t0 > limit) {
                if (!retapped) {
                    retapped = true
                    tapPx(tx, ty)
                    t0 = System.currentTimeMillis()
                } else {
                    tapPoint("closeMap")
                    fail("Noktaya varılamadı: " + title(e))
                }
            }
        }
        tapPoint("closeMap")
        pause(500)
        say("Vardı: " + title(e))
    }

    private fun openChatLink(link: String) {
        tapPoint("chat")
        pause(1000)
        tapPoint("pmTab")
        pause(700)
        tapPoint(link)
        pause(600)
        if (prefs().contains("closeChat_x")) {
            tapPoint("closeChat")
            pause(500)
        }
        gameAuto = false
    }

    private fun teleport(map: String) {
        val link = "link_" + map
        val need = ArrayList<String>()
        for (n in listOf("chat", "pmTab", link)) if (!hasPt(n)) need.add(n)
        if (need.isNotEmpty()) fail("Önce 'Ayar' menüsünden işaretle: " + need.joinToString(", "))
        say("Işınlanılıyor: " + map)
        openChatLink(link)
        pause(cfgI("mapTravel", 10) * 1000)
    }

    private fun switchWire(w: Int) {
        val wn = "wire" + w
        if (!hasPt(wn) || !hasPt("switchBtn")) fail("Önce 'Ayar' menüsünden işaretle: switchBtn, " + wn)
        say("Wire " + w + " seçiliyor")
        tapPoint("switchBtn")
        pause(1500)
        tapPoint(wn)
        pause(800)
        if (prefs().contains("switchOk_x")) {
            tapPoint("switchOk")
            pause(600)
        }
        gameAuto = false
        pause(cfgI("switchWait", 6) * 1000)
    }

    /** Oyundaki oto atak düğmesini istenen duruma getirir (düğme aç/kapa olduğu için durumu takip eder). */
    private fun setAuto(want: Boolean) {
        // Her ışınlanma/wire değişiminde oyun oto atağı kapatır; açmak istediğimizde düğme her zaman kapalıdır.
        if (!want && !gameAuto) return
        tapPoint("autoAtk")
        gameAuto = want
        pause(700)
    }

    /** Wire'ı değiştirir ve minimap adından doğrular. */
    private fun switchTo(w: Int): Boolean {
        for (t in 0 until 2) {
            switchWire(w)
            val c = readMapWireRetry()
            if (c.second == w) return true
        }
        return false
    }

    /** Hedef harita ve wire'a gelir: önce (mümkünse) wire, sonra chat bağlantısı, gerekirse wire'ı tekrar düzeltir. */
    private fun ensureLocation(map: String, wire: Int) {
        val target = mapInfo(map)
        closeMapIfOpen()
        var cur = readMapWireRetry()
        if (mapInfo(cur.first).wires > 1 && target.wires > 1 && cur.second != wire) {
            switchTo(wire)
            cur = readMapWireRetry()
        }
        if (key(cur.first) != key(map)) {
            teleport(map)
            cur = readMapWireRetry()
            if (key(cur.first) != key(map)) fail("Işınlanma olmadı, şu an: " + cur.first)
        }
        if (target.wires > 1 && cur.second != wire) {
            if (!switchTo(wire)) fail("Wire değişmedi (istenen Wire " + wire + ")")
        }
    }

    /** Farm: önce wire 3, sonra chat'ten farm bağlantısı, 10 sn sonra oto atak ve kalkan. */
    private fun doFarm() {
        val need = ArrayList<String>()
        for (n in listOf("chat", "pmTab", "farmLink", "autoAtk", "shield")) if (!hasPt(n)) need.add(n)
        if (need.isNotEmpty()) fail("Önce 'Ayar' menüsünden işaretle: " + need.joinToString(", "))
        closeMapIfOpen()
        setAuto(false)
        val fw = cfgI("farmWire", 3)
        val cur = readMapWireRetry()
        if (mapInfo(cur.first).wires > 1 && cur.second != fw) {
            say("Farm için Wire " + fw + " seçiliyor")
            switchTo(fw)
        }
        say("Farm konumuna gidiliyor")
        openChatLink("farmLink")
        say("Farm konumuna varılıyor (" + cfgI("farmWait", 10) + " sn)")
        pause(cfgI("farmWait", 10) * 1000)
        say("Vardı, oto saldırı ve kalkan")
        setAuto(true)
        pause(1000)
        tapPoint("shield")
        pause(500)
        farming = true
        say("Farm modunda")
    }

    // ------------------------------------------------------------------ pot, recycle, buff

    private fun doPots() {
        say("Pot sayısı okunuyor")
        closeMapIfOpen()
        val b = shot() ?: fail("Ekran görüntüsü alınamadı")
        checkPots(b)
    }

    private fun checkPots(b: Bitmap) {
        if (orangeAt(b, "closeMap")) return
        val min = cfgI("potMin", 20)
        val hp = readCount(b, "hpCount")
        val mp = readCount(b, "mpCount")
        say("Pot: HP " + (hp ?: "?") + "  MP " + (mp ?: "?") + "  (eşik " + min + ")")
        val low = (hp != null && hp < min) || (mp != null && mp < min)
        if (!low) return
        buyPots()
    }

    /** Inventory > Shop > iki Purchase düğmesine 3'er kez (toplam 6) > Inventory'yi kapat. Her adım arası 1 sn. */
    private fun buyPots() {
        say("Pot azaldı, alışverişe gidiliyor")
        tapPoint("inventory")
        pause(gap())
        tapPoint("shopTab")
        pause(gap())
        for (i in 0 until 3) {
            tapPoint("purchaseHP")
            pause(gap())
            tapPoint("purchaseMP")
            pause(gap())
        }
        say("Pot alındı (3+3), Inventory kapatılıyor")
        closeInventory()
    }

    /** Inventory > Recycle sekmesi > Recycle düğmesi > Inventory'yi kapat. Her adım arası 1 sn. */
    private fun doRecycle() {
        say("Recycle yapılıyor")
        lastRecycle = System.currentTimeMillis()
        tapPoint("inventory")
        pause(gap())
        tapPoint("recycleTab")
        pause(gap())
        tapPoint("recycleBtn")
        pause(gap())
        say("Recycle basıldı, Inventory kapatılıyor")
        closeInventory()
        say("Recycle tamam")
    }

    /** Envanter açıkken sağ üstteki X'e basar, hâlâ X görünüyorsa tekrar basar. */
    private fun closeInventory() {
        tapPoint("closeRight")
        pause(gap())
        for (i in 0 until 3) {
            val b = shot() ?: continue
            if (orangeAt(b, "closeRight")) {
                tapPoint("closeRight")
            } else if (orangeAt(b, "closeLeft")) {
                tapPoint("closeLeft")
            } else {
                return
            }
            pause(gap())
        }
    }

    /** Buff: önce wire 3, sonra buff konumu; ATK/DEF alt sınırın üstüne çıkınca devam. */
    private fun doBuff(minA: Int, minD: Int) {
        setAuto(false)
        farming = false
        closeMapIfOpen()
        val bw = cfgI("buffWire", 3)
        val cur = readMapWireRetry()
        if (mapInfo(cur.first).wires > 1 && cur.second != bw) {
            say("Buff için Wire " + bw + " seçiliyor")
            switchTo(bw)
        }
        say("ATK/DEF düşük, buff noktasına gidiliyor")
        openChatLink("buffLink")
        pause(cfgI("buffTravel", 12) * 1000)
        val t0 = System.currentTimeMillis()
        val limit = cfgI("buffWait", 90) * 1000L
        while (System.currentTimeMillis() - t0 < limit) {
            val b = shot()
            if (b != null) {
                val st = readStats(b)
                if (st != null && st.first >= minA && st.second >= minD) {
                    say("Buff alındı: ATK " + st.first + " DEF " + st.second)
                    return
                }
            }
            pause(1500)
        }
        say("Buff süresi doldu")
    }

    /** Buff açıksa ATK/DEF'e bakar, ikisi de alt sınırın altındaysa buff'a gider. */
    private fun buffCheck() {
        if (!on("buffOn")) return
        closeMapIfOpen()
        val b = shot() ?: return
        val st = readStats(b)
        val minA = cfgI("minAtk", 0)
        val minD = cfgI("minDef", 0)
        if (st == null) {
            say("ATK/DEF okunamadı: " + lastOcr)
            return
        }
        if (st.first < minA && st.second < minD) doBuff(minA, minD)
        else say("ATK " + st.first + " DEF " + st.second + " tamam")
    }

    /** Otomasyon bakımı (her 20 dk): pot okuma/alma, ardından Recycle. */
    private fun maintenance() {
        lastMaint = System.currentTimeMillis()
        closeMapIfOpen()
        if (on("potOn")) {
            val b = shot()
            if (b != null) checkPots(b)
            pause(gap())
        }
        if (on("recycleOn")) doRecycle()
    }

    // ------------------------------------------------------------------ otomasyon

    private fun missingForAuto(): List<String> {
        val need = ArrayList<String>()
        if (on("farmOn")) for (n in listOf("chat", "pmTab", "farmLink", "autoAtk", "shield")) if (!hasPt(n)) need.add(n)
        for (n in listOf("chat", "pmTab", "autoAtk")) if (!hasPt(n) && !need.contains(n)) need.add(n)
        if (on("buffOn") && !hasPt("buffLink")) need.add("buffLink")
        for (e in snapshot()) {
            val l = "link_" + e.map
            if (!hasPt(l) && !need.contains(l)) need.add(l)
            if (mapInfo(e.map).wires > 1) {
                for (w in 1..mapInfo(e.map).wires) {
                    val n = "wire" + w
                    if (!hasPt(n) && !need.contains(n)) need.add(n)
                }
            }
        }
        return need
    }

    private fun startAuto() {
        if (busy) {
            say("Başka bir işlem sürüyor")
            return
        }
        val need = missingForAuto()
        if (need.isNotEmpty()) {
            val m = "Önce 'Ayar' menüsünden işaretle: " + need.joinToString(", ")
            say(m)
            toast(m)
            return
        }
        autoRunning = true
        job { autoLoop() }
    }

    private fun nextDue(): Entry? {
        val now = System.currentTimeMillis()
        val lead = cfgI("leadSec", 20) * 1000L
        var best: Entry? = null
        for (e in snapshot()) {
            if (e.endMs - now > lead) continue
            if ((blocked[e.id] ?: 0L) > now) continue
            if (best == null || e.endMs < best.endMs) best = e
        }
        return best
    }

    private fun autoLoop() {
        gameAuto = false
        farming = false
        fails = 0
        lastMaint = 0L
        say("Otomasyon açık")
        buffCheck()
        while (autoRunning && !cancel) {
            try {
                val now = System.currentTimeMillis()
                val due = nextDue()
                val every = cfgI("maintMin", 20) * 60000L
                val maintDue = (on("potOn") || on("recycleOn")) && now - lastMaint >= every
                if (maintDue && (due == null || now - lastMaint >= every + 300000L)) {
                    maintenance()
                    continue
                }
                if (due != null) {
                    runBoss(due)
                    continue
                }
                if (on("farmOn") && !farming) {
                    doFarm()
                    fails = 0
                }
                idleTick()
            } catch (e: StepFail) {
                fails++
                val id = currentId
                if (id != 0L) {
                    blocked[id] = System.currentTimeMillis() + 90000L
                    currentId = 0L
                    ui { rebuildList() }
                }
                farming = false
                if (fails >= 5) {
                    say("Üst üste 5 hata, otomasyon durduruldu. Son mesaj yukarıda.")
                    toast("Otomasyon durduruldu: üst üste 5 hata")
                    autoRunning = false
                    return
                }
                pause(3000)
            }
        }
    }

    private fun idleTick() {
        val now = System.currentTimeMillis()
        var next: Entry? = null
        for (e in snapshot()) if (next == null || e.endMs < next.endMs) next = e
        say(
            (if (farming) "Farmda bekleniyor" else "Bekleniyor") +
                (if (next != null) "\nSıradaki: " + title(next) + " · " + fmt((next.endMs - now) / 1000) else "")
        )
        pause(1500)
    }

    private fun runBoss(e: Entry) {
        currentId = e.id
        ui { rebuildList() }
        if (farming) {
            setAuto(false)
            farming = false
        }
        say("Sıradaki: " + title(e))
        val ok = goAndKill(e)
        currentId = 0L
        ui { rebuildList() }
        if (ok) {
            kills++
            fails = 0
            buffCheck()
        } else {
            blocked[e.id] = System.currentTimeMillis() + 90000L
        }
    }

    /** Hem otomasyonda hem elle bir satıra basınca: bossa git, oto atak aç, ölünce sayacı yeniden kaydet. */
    private fun goAndKill(e: Entry): Boolean {
        doGo(e)
        return fightBoss(e)
    }

    /**
     * Bossun noktasına varıldıktan sonra oto atak açılır. Ölüm tespiti muimm'deki gibi sağ üst mini haritadan:
     * boss ikonu görünür (canlı) sonra üst üste 3 kare görünmezse öldü. Ölünce mezardaki yeni sayaç okunup kaydedilir.
     * Boss henüz doğmamışsa sayaçtan doğmasını bekler; başkası kesmişse yeni sayaç görünür ve o kaydedilir.
     */
    private fun fightBoss(e: Entry): Boolean {
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        setAuto(true)
        say(title(e) + "\nOto atak açık, boss bekleniyor")
        var seen = false
        var dead = 0
        val early = ArrayList<Long>()
        while (true) {
            pause(300)
            val b = shot() ?: continue
            val stamp = lastShot
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            if (miniAlive(b)) {
                seen = true
                dead = 0
                early.clear()
                say(title(e) + "\nBoss canlı, savaşılıyor")
            } else if (seen) {
                dead++
                say(title(e) + "\nBoss öldü mü? " + dead + "/3")
                if (dead >= 3) break
            } else {
                val secs = readTimer(b)
                if (secs != null && secs > 90) {
                    early.add(stamp + secs * 1000L)
                    val c = consensus(early, 2)
                    if (c != null) {
                        setEnd(e, c)
                        setAuto(false)
                        say("Boss zaten kesilmiş, süre güncellendi: " + title(e) + " · " + fmt((c - System.currentTimeMillis()) / 1000))
                        return true
                    }
                } else {
                    early.clear()
                    if (secs != null) say(title(e) + "\nDoğmasına " + secs + " sn")
                }
            }
            if (System.currentTimeMillis() - t0 > limit) {
                setAuto(false)
                say("Savaş zaman aşımı: " + title(e))
                return false
            }
        }
        say(title(e) + "\nBoss öldü, sayaç okunuyor")
        val ends = ArrayList<Long>()
        for (i in 0 until 10) {
            val b = shot() ?: continue
            val stamp = lastShot
            val s = readTimer(b)
            if (s != null && s > 90) {
                ends.add(stamp + s * 1000L)
                val c = consensus(ends, 2)
                if (c != null) {
                    setEnd(e, c)
                    setAuto(false)
                    say("Kaydedildi: " + title(e) + " · " + fmt((c - System.currentTimeMillis()) / 1000))
                    return true
                }
            }
            pause(300)
        }
        setAuto(false)
        blocked[e.id] = System.currentTimeMillis() + 600000L
        say("Boss öldü ama sayaç okunamadı: " + title(e) + "\nKarakter mezarın üstündeyken Kaydet'e bas.")
        return true
    }

    /** Test: ekranı okuyup sonuçları ve okuma bölgelerini gösterir. */
    private fun doTest() {
        say("Test: ekran okunuyor")
        val b = shot() ?: fail("Ekran görüntüsü alınamadı")
        val info = readMapWire(b)
        val o1 = lastOcr
        val secs = readTimer(b)
        val o3 = lastOcr
        val st = readStats(b)
        val open = orangeAt(b, "closeMap")
        val me = if (open) findPlayer(b) else null
        val mf = miniFrac(b)
        say(
            "Harita: " + (if (info != null) info.first + " · Wire " + info.second else "okunamadı") + "  ('" + o1 + "')" +
                "\nSayaç: " + (if (secs != null) fmt(secs.toLong()) else "okunamadı") + "  ('" + o3 + "')" +
                "\nMini harita boss: " + String.format("%.4f", mf) + (if (miniAlive(b)) " CANLI" else " yok") + " (eşik 0.0040)" +
                "\nATK/DEF: " + (if (st != null) st.first.toString() + "/" + st.second else "okunamadı") +
                "\nPot HP/MP: " + (readCount(b, "hpCount") ?: "?") + "/" + (readCount(b, "mpCount") ?: "?") +
                "\nHarita açık: " + open + (if (open) "  imleç: " + (me != null) else "")
        )
        ui { showRects(me) }
    }

    // ------------------------------------------------------------------ panel

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun mkBtn(text: String, bg: Int = CARD_BG, fg: Int = Color.WHITE, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setTextColor(fg)
        b.setPadding(dp(8), dp(4), dp(8), dp(4))
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.background = rounded(bg, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.topMargin = dp(6)
        col.addView(btn, lp)
    }

    private fun btnRow(items: List<Pair<String, () -> Unit>>, h: Int): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in items.indices) {
            val action = items[i].second
            val bt = mkBtn(items[i].first) { action() }
            val lp = LinearLayout.LayoutParams(0, h, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(bt, lp)
        }
        return row
    }

    private fun overlayParams(w: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return lp
    }

    private fun updateUi() {
        val sb = saveBtn
        if (sb != null) {
            if (busy && !autoRunning) {
                sb.text = "■  Durdur"
                sb.background = rounded(BAD, 12)
                sb.setTextColor(Color.WHITE)
            } else {
                sb.text = "●  Kaydet"
                sb.background = rounded(if (autoRunning) CARD_BG else ACCENT, 12)
                sb.setTextColor(if (autoRunning) TEXT_DIM else 0xFF04202D.toInt())
            }
        }
        val ab = autoBtn
        if (ab != null) {
            if (autoRunning) {
                ab.text = "■  Otomasyonu durdur"
                ab.background = rounded(BAD, 12)
                ab.setTextColor(Color.WHITE)
            } else {
                ab.text = "▶  Otomasyon"
                ab.background = rounded(OK, 12)
                ab.setTextColor(0xFF04200F.toInt())
            }
        }
        updateToggles()
        applyPanel()
    }

    private fun changeGap(delta: Float) {
        val v = (cfgF("clickGap", 1.0f) + delta).coerceIn(0.25f, 10f)
        prefs().edit().putString("cfg_clickGap", String.format(java.util.Locale.US, "%.2f", v)).apply()
        gapView?.text = String.format("%.2f", v)
    }

    private fun changeRadius(delta: Int) {
        val v = (cfgI("radius", 200) + delta).coerceIn(0, 1000)
        prefs().edit().putString("cfg_radius", v.toString()).apply()
        radiusView?.text = v.toString()
    }

    private fun updateToggles() {
        for (t in TOGGLES) {
            val btn = toggleBtns[t.first] ?: continue
            val isOn = on(t.first)
            btn.text = t.second + (if (isOn) " ✓" else " ✗")
            btn.background = rounded(if (isOn) CARD_BG else MISSING_BG, 10)
        }
    }

    private fun applyPanel() {
        panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
        val mode = if (autoRunning) "▶" else if (busy) "⚙" else "⏱"
        handle?.text = (if (panelOpen) "▸" else "◂") + "\n" + mode
        placeBar()
        if (panelOpen) panel?.post { placeBar() }
    }

    /** Tutamacı ve paneli konumlar: panel tutamacın bulunduğu tarafa göre soluna ya da sağına açılır. */
    private fun placeBar() {
        refreshScreen()
        val h = handle ?: return
        val p = panel ?: return
        val hw = dp(30)
        val hh = dp(56)
        val pw = dp(250)
        hx = hx.coerceIn(0, maxOf(0, screenW - hw))
        hy = hy.coerceIn(0, maxOf(0, screenH - hh))
        val hl = handleLp
        if (hl != null) {
            hl.x = hx
            hl.y = hy
            try {
                wm.updateViewLayout(h, hl)
            } catch (e: Exception) {
            }
        }
        val pl = panelLp
        if (pl != null) {
            val onRight = hx + hw / 2 > screenW / 2
            var px = if (onRight) hx - pw else hx + hw
            px = px.coerceIn(0, maxOf(0, screenW - pw))
            val ph = if (p.visibility == View.GONE) 0 else p.height
            val py = hy.coerceIn(0, maxOf(0, screenH - ph))
            pl.x = px
            pl.y = py
            try {
                wm.updateViewLayout(p, pl)
            } catch (e: Exception) {
            }
        }
    }

    private fun buildUi() {
        refreshScreen()
        hx = prefs().getInt("hx", screenW - dp(30))
        hy = prefs().getInt("hy", (screenH * 0.10f).toInt())

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(12), dp(10), dp(12), dp(12))

        // başlık
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Boss süreleri"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val tools = TextView(this)
        tools.text = "⋯ Araçlar"
        tools.textSize = 12f
        tools.setTextColor(TEXT_DIM)
        tools.setPadding(dp(8), dp(2), 0, dp(2))
        head.addView(tools)
        outer.addView(head)

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.WHITE)
        st.maxLines = 5
        st.setPadding(0, dp(6), 0, dp(2))
        st.text = "Hazır"
        statusView = st
        outer.addView(st)

        // ana düğmeler
        val mainRow = LinearLayout(this)
        mainRow.orientation = LinearLayout.HORIZONTAL
        val sb = mkBtn("●  Kaydet") {
            if (autoRunning) say("Otomasyon açıkken kayıt yapılamaz. Önce durdur.")
            else if (busy) stopJob()
            else job { doSave() }
        }
        sb.textSize = 14f
        sb.typeface = android.graphics.Typeface.DEFAULT_BOLD
        saveBtn = sb
        mainRow.addView(sb, LinearLayout.LayoutParams(0, dp(44), 1f))
        val ab = mkBtn("▶  Otomasyon") {
            if (autoRunning) stopJob() else startAuto()
        }
        ab.textSize = 14f
        ab.typeface = android.graphics.Typeface.DEFAULT_BOLD
        autoBtn = ab
        val alp = LinearLayout.LayoutParams(0, dp(44), 1f)
        alp.leftMargin = dp(6)
        mainRow.addView(ab, alp)
        val mlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = dp(6)
        outer.addView(mainRow, mlp)

        // boss listesi
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        listCol = col
        val sv = ScrollView(this)
        sv.addView(col)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.30f).toInt())
        slp.topMargin = dp(8)
        outer.addView(sv, slp)

        // açık/kapalı düğmeleri (iki satır)
        val trow1 = LinearLayout(this)
        trow1.orientation = LinearLayout.HORIZONTAL
        val trow2 = LinearLayout(this)
        trow2.orientation = LinearLayout.HORIZONTAL
        for (i in TOGGLES.indices) {
            val k = TOGGLES[i].first
            val bt = mkBtn("") {
                prefs().edit().putBoolean("cfg_" + k, !on(k)).apply()
                updateToggles()
            }
            bt.textSize = 12f
            bt.setPadding(dp(2), dp(4), dp(2), dp(4))
            val inFirst = i < 3
            val tl = LinearLayout.LayoutParams(0, dp(34), 1f)
            if (if (inFirst) i > 0 else i > 3) tl.leftMargin = dp(4)
            (if (inFirst) trow1 else trow2).addView(bt, tl)
            toggleBtns[k] = bt
        }
        val t1lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        t1lp.topMargin = dp(8)
        outer.addView(trow1, t1lp)
        val t2lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        t2lp.topMargin = dp(4)
        outer.addView(trow2, t2lp)

        // araçlar (varsayılan gizli)
        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.VERTICAL
        tb.visibility = View.GONE
        val r1 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "Shizuku" to { shizukuAsk() },
                "Test" to { runManual { doTest() } },
                "Ayar" to { toggleMenu() }
            ),
            dp(36)
        )
        val r2 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "Farma git" to { runManual { doFarm() } },
                "Pot kontrol" to { runManual { doPots() } },
                "Recycle" to { runManual { doRecycle() } }
            ),
            dp(36)
        )
        val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(6)
        tb.addView(r1, rlp)
        tb.addView(r2, rlp)
        val rr = LinearLayout(this)
        rr.orientation = LinearLayout.HORIZONTAL
        rr.gravity = Gravity.CENTER_VERTICAL
        val rl = TextView(this)
        rl.text = "Random yarıçap (px)"
        rl.textSize = 11f
        rl.setTextColor(Color.WHITE)
        rr.addView(rl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rv = TextView(this)
        rv.text = cfgI("radius", 200).toString()
        rv.textSize = 13f
        rv.gravity = Gravity.CENTER
        rv.setTextColor(ACCENT)
        radiusView = rv
        rr.addView(mkBtn("−") { changeRadius(-25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        rr.addView(rv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        rr.addView(mkBtn("+") { changeRadius(25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        tb.addView(rr, rlp)
        val gr = LinearLayout(this)
        gr.orientation = LinearLayout.HORIZONTAL
        gr.gravity = Gravity.CENTER_VERTICAL
        val gl = TextView(this)
        gl.text = "Pot/Recycle tık aralığı (sn)"
        gl.textSize = 11f
        gl.setTextColor(Color.WHITE)
        gr.addView(gl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gv = TextView(this)
        gv.text = String.format("%.2f", gap() / 1000f)
        gv.textSize = 13f
        gv.gravity = Gravity.CENTER
        gv.setTextColor(ACCENT)
        gapView = gv
        gr.addView(mkBtn("−") { changeGap(-0.25f) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        gr.addView(gv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        gr.addView(mkBtn("+") { changeGap(0.25f) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        tb.addView(gr, rlp)
        toolsBox = tb
        outer.addView(tb)
        tools.setOnClickListener {
            toolsOpen = !toolsOpen
            tb.visibility = if (toolsOpen) View.VISIBLE else View.GONE
            tools.text = if (toolsOpen) "⋯ Araçlar ▴" else "⋯ Araçlar"
            outer.post { placeBar() }
        }

        outer.visibility = View.GONE
        val pl = overlayParams(dp(250))
        wm.addView(outer, pl)
        panel = outer
        panelLp = pl

        // sürüklenebilir tutamaç
        val h = TextView(this)
        h.textSize = 14f
        h.gravity = Gravity.CENTER
        h.setTextColor(Color.WHITE)
        h.background = rounded(0xE6161920.toInt(), 10)
        h.setPadding(dp(4), dp(10), dp(4), dp(10))
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        h.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = hx
                    startY = hy
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        hx = startX + dx.toInt()
                        hy = startY + dy.toInt()
                        placeBar()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs().edit().putInt("hx", hx).putInt("hy", hy).apply()
                    } else {
                        panelOpen = !panelOpen
                        applyPanel()
                    }
                }
            }
            true
        }
        val hl = overlayParams(dp(30))
        wm.addView(h, hl)
        handle = h
        handleLp = hl
        updateUi()
    }

    private fun rebuildList() {
        val l = listCol ?: return
        l.removeAllViews()
        timerViews.clear()
        stripViews.clear()
        val list = snapshot()
        if (list.isEmpty()) {
            val t = TextView(this)
            t.text = "Henüz kayıt yok.\nBoss kesince, karakter mezarın üstündeyken Kaydet'e bas."
            t.textSize = 11f
            t.setTextColor(TEXT_DIM)
            l.addView(t)
            return
        }
        for (e in list.sortedBy { it.endMs }) l.addView(rowFor(e))
        tick()
    }

    private fun rowFor(e: Entry): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rounded(CARD_BG, 10)

        val strip = View(this)
        stripViews[e.id] = strip
        row.addView(strip, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.setPadding(dp(10), dp(6), dp(4), dp(6))
        val name = TextView(this)
        name.text = (if (e.id == currentId) "▶ " else "") + title(e)
        name.textSize = 11f
        name.setTextColor(if (e.id == currentId) ACCENT else Color.WHITE)
        left.addView(name)
        val tm = TextView(this)
        tm.textSize = 16f
        tm.typeface = android.graphics.Typeface.DEFAULT_BOLD
        left.addView(tm)
        timerViews[e.id] = tm
        left.setOnClickListener { runManual { goAndKill(e) } }
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val del = TextView(this)
        del.text = "✕"
        del.textSize = 16f
        del.setTextColor(TEXT_DIM)
        del.setPadding(dp(12), dp(6), dp(10), dp(6))
        del.setOnClickListener {
            synchronized(lock) { entries.remove(e) }
            saveEntries()
            rebuildList()
        }
        row.addView(del)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        row.layoutParams = lp
        return row
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val lead = cfgI("leadSec", 20)
        for (e in snapshot()) {
            val tv = timerViews[e.id] ?: continue
            val left = (e.endMs - now) / 1000
            tv.text = if (left > 0) fmt(left) else "DOĞDU  +" + fmt(left)
            val c = when {
                left <= lead -> OK
                left <= 120 -> WARN
                else -> Color.WHITE
            }
            tv.setTextColor(c)
            stripViews[e.id]?.setBackgroundColor(c)
        }
    }

    // ------------------------------------------------------------------ ayar menusu

    private fun toggleMenu() {
        val old = menu
        if (old != null) {
            try {
                wm.removeView(old)
            } catch (e: Exception) {
            }
            menu = null
            return
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(PANEL_BG, 16)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))

        val title = TextView(this)
        title.text = "Nokta işaretleme"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(title)
        val hint = TextView(this)
        hint.text = "Oyunda ilgili ekranı aç, aşağıdan bir satıra dokun, sonra oyun ekranında o noktaya dokun. " +
            "Kırmızı satırlar henüz işaretlenmedi. Köşe çiftlerinde önce SOL ÜST, sonra SAĞ ALT satırını işaretle."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(2), 0, dp(4))
        card.addView(hint)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (g in pointGroups()) {
            val gh = TextView(this)
            gh.text = g.first
            gh.textSize = 12f
            gh.setTextColor(ACCENT)
            gh.typeface = android.graphics.Typeface.DEFAULT_BOLD
            gh.setPadding(0, dp(12), 0, dp(2))
            list.addView(gh)
            for (n in g.second) {
                val set = prefs().contains(n + "_x") || DEFAULTS.containsKey(n)
                val label = LABELS[n] ?: ("PM'de " + n.removePrefix("link_") + " haritasının ışınlanma bağlantısı")
                val btn = mkBtn((if (set) "✓  " else "✗  ") + label, if (set) CARD_BG else MISSING_BG) {
                    beginCapture(n, label)
                }
                btn.textSize = 11f
                btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                btn.setPadding(dp(12), dp(8), dp(12), dp(8))
                addBtn(list, btn)
            }
        }
        val sv = ScrollView(this)
        sv.addView(list)
        card.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.62f).toInt()))

        val close = mkBtn("Kapat", ACCENT, 0xFF04202D.toInt()) { toggleMenu() }
        addBtn(card, close, dp(38))

        val lp = overlayParams(dp(340))
        lp.x = dp(8)
        lp.y = dp(8)
        wm.addView(card, lp)
        menu = card
    }

    private fun removeExtra() {
        val v = extra
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            extra = null
        }
    }

    /** Sonraki dokunuşu kaydeden tam ekran saydam katman. */
    private fun beginCapture(name: String, label: String) {
        if (menu != null) toggleMenu()
        removeExtra()
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x33FFFFFF)
        val tv = TextView(this)
        tv.text = "Dokun:  " + label
        tv.textSize = 18f
        tv.setTextColor(Color.BLACK)
        tv.setBackgroundColor(0xDDFFFF00.toInt())
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        fl.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        fl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                refreshScreen()
                savePt(name, ev.rawX / screenW, ev.rawY / screenH)
                removeExtra()
                Toast.makeText(this, "Kaydedildi: " + name, Toast.LENGTH_SHORT).show()
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(fl, lp)
        extra = fl
    }

    /** Okuma bölgelerini (harita adı, sayaç alanı) 6 saniye gösterir. */
    private fun showRects(player: PointF?) {
        removeExtra()
        refreshScreen()
        val rects = ArrayList<Pair<String, FloatArray>>()
        val pairs = listOf(Triple("harita adı", "nameTL", "nameBR"), Triple("sayaç alanı", "cntTL", "cntBR"))
        for ((label, a, c) in pairs) {
            val p1 = pt(a)
            val p2 = pt(c)
            rects.add(
                Pair(
                    label,
                    floatArrayOf(
                        minOf(p1.first, p2.first) * screenW, minOf(p1.second, p2.second) * screenH,
                        maxOf(p1.first, p2.first) * screenW, maxOf(p1.second, p2.second) * screenH
                    )
                )
            )
        }
        val v = RectView(this, rects, player)
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, wlp)
        extra = v
        main.postDelayed({
            if (extra === v) removeExtra()
        }, 6000)
    }
}
