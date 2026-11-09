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
import android.os.SystemClock
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
import android.widget.HorizontalScrollView
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
    /** Ölünce Free Revival basıldı; otomasyon döngüsü kaldığı işe geri döner. */
    private class Revived : RuntimeException()
    /** Oyundan atıldık (giriş ekranı göründü); otomasyon durur. */
    private class LoggedOut : RuntimeException()

    /** Bir adım başarısız oldu (mesaj zaten gösterildi). */
    private class StepFail : RuntimeException()

    /** Başka bir iş sürerken saklı bir boss'un zamanı geldi: uzun yaklaşma/Random döngülerinden çıkmak için. */
    private class Interrupted : RuntimeException()

    private class MapInfo(val name: String, val wires: Int)

    /** İşaretlenmiş altın (golden) canavar noktası (ekran oranı). Golden haritası tek: goldMap ayarında tutulur. */
    private class Gold(val id: Long, var idx: Int, val px: Float, val py: Float)

    /** İşaretlenmiş red boss noktası (ekran oranı). Red boss haritası tek: redMap ayarında; süre kaydedilmez. */
    private class Red(val id: Long, var idx: Int, val px: Float, val py: Float)

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
            "High Heaven 1;1\nPurgatory of Misery 1;1\nEndless Abyss 1;1\nCorridor of Agony 1;1\nSwamp of Peace;3\nVIP Domain 2;1\nVIP Domain 3;1\nVIP Domain 4;1\nVIP Domain 5;1\nAida 1;3\nAida 2;3\nIcarus 1;3\nIcarus 3;3\nKanturu Ruins 1;3\nKanturu Ruins 2;3\nKanturu Relics 1;3\nKanturu Relics 2;3\nSuperb Realm 2;1\nSuperb Realm 3;1"

        /** Ayar menüsündeki gruplar. "Işınlanma bağlantıları" grubu haritalara göre ayrıca eklenir. */
        val GROUPS = listOf(
            "1 · Okuma bölgeleri (Test ile kontrol et)" to listOf("nameTL", "nameBR", "cntTL", "cntBR", "miniTL", "miniBR", "ownTL", "ownBR"),
            "2 · Harita" to listOf("minimap", "closeMap", "random"),
            "3 · Chat" to listOf("chat", "pmTab", "closeChat"),
            "5 · Wire (sadece Raklion'lar)" to listOf("switchBtn", "wire1", "wire2", "wire3", "switchOk"),
            "6 · Farm ve savaş düğmeleri" to listOf("autoAtk", "shield", "imp", "angel", "revive", "goldLink", "redLink"),
            "7 · Envanter, pot, Recycle" to listOf(
                "inventory", "shopTab", "purchaseHP", "purchaseMP", "hpCount", "mpCount", "rndCount",
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
            "ownTL" to "Bossa saldırırken üstte çıkan satırın ('Lv250 ... yxesky') SOL ÜSTÜ (sahip kontrolü için)",
            "ownBR" to "Aynı satırın SAĞ ALTI (oyuncu adı dahil)",
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
            "goldLink" to "PM'de GOLDEN haritasının ışınlanma bağlantısı (golden açıksa; tek koordinat)",
            "redLink" to "PM'de RED BOSS haritasının (VIP Domain) ışınlanma bağlantısı (red boss açıksa; tek koordinat)",
            "imp" to "Imp düğmesi (alt çubukta pot yanında, farm konumuna varınca basılır)",
            "angel" to "Angel düğmesi (alt çubukta pot yanında, bossu keserken basılır)",
            "revive" to "Free Revival düğmesi (isteğe bağlı: yazı okunamazsa buraya basılır; ölüm ekranı açıkken işaretle)",
            "inventory" to "Sağdaki Inventory düğmesi",
            "shopTab" to "Envanterde alttaki Shop sekmesi (Inventory açıkken)",
            "purchaseHP" to "Shop'ta 99'luk HP pot Purchase düğmesi",
            "purchaseMP" to "Shop'ta 99'luk MP pot Purchase düğmesi",
            "hpCount" to "Alt çubukta HP pot SAYISININ yeri (örn. 452)",
            "mpCount" to "Alt çubukta MP pot SAYISININ yeri (örn. 1425)",
            "rndCount" to "Alt çubukta Random (göz simgeli) eşyanın SAYISININ yeri (örn. 286)",
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
            "closeLeft" to Pair(0.672f, 0.074f),
            "revive" to Pair(0.431f, 0.586f)
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
            Triple("impOn", "Imp", true),
            Triple("angelOn", "Angel", true),
            Triple("reviveOn", "Diril", true),
            Triple("buffOn", "Buff", false),
            Triple("randomOn", "Random", true),
            Triple("goldOn", "Golden", false),
            Triple("redOn", "Red boss", false),
            Triple("routeOn", "Rota", false),
            Triple("idleFarmOn", "Boşta farm", false),
            Triple("macroTpOn", "Kayıtlı ışın", false),
            Triple("protoOn", "Golden protokolü", true),
            Triple("ownerOn", "Sahip kontrolü", false),
            Triple("popupOn", "Pencere kapatıcı", false)
        )

        /** Kayıtlı tema numarası; Gotik temaya bir kez otomatik geçilir. */
        fun themeIdx(p: android.content.SharedPreferences): Int {
            if (!p.getBoolean("themeGothic", false)) p.edit().putInt("theme", 0).putBoolean("themeGothic", true).apply()
            return p.getInt("theme", 0).coerceIn(0, THEMES.size - 1)
        }

        /** Temalar: vurgu, panel, kart, eksik, ayar ekranı zemini, ayar ekranı girişi. */
        val THEME_NAMES = listOf("Gotik", "Mavi", "Zümrüt", "Mor", "Turuncu", "Gül")
        val THEMES = listOf(
            intArrayOf(0xFFB48AE0.toInt(), 0xF2100D14.toInt(), 0xFF1B1721.toInt(), 0xFF3A1824.toInt(), 0xFF0B090E.toInt(), 0xFF1B1721.toInt()),
            intArrayOf(0xFF4FC3F7.toInt(), 0xF2161920.toInt(), 0xFF272C3A.toInt(), 0xFF4A2429.toInt(), 0xFF0F1115.toInt(), 0xFF262B3A.toInt()),
            intArrayOf(0xFF2DD4BF.toInt(), 0xF2101A18.toInt(), 0xFF1F302C.toInt(), 0xFF4A2429.toInt(), 0xFF0B1412.toInt(), 0xFF1F2E2B.toInt()),
            intArrayOf(0xFFB388FF.toInt(), 0xF2181426.toInt(), 0xFF2B2540.toInt(), 0xFF4A2429.toInt(), 0xFF100C1A.toInt(), 0xFF2A2440.toInt()),
            intArrayOf(0xFFFFB74D.toInt(), 0xF21C1712.toInt(), 0xFF332B20.toInt(), 0xFF4A2429.toInt(), 0xFF14100B.toInt(), 0xFF302A20.toInt()),
            intArrayOf(0xFFFF6B81.toInt(), 0xF21A1215.toInt(), 0xFF33202A.toInt(), 0xFF4A2429.toInt(), 0xFF140B0F.toInt(), 0xFF301F27.toInt())
        )

        // ---- ayar profilleri: cfg_* değerleri (bildirim konusu ve harita listesi hariç) adla saklanır
        val PROFILE_EXTRA = listOf("routeMaps", "goldMap", "redMap", "golds", "reds", "hidden", "ignorepts", "buffStep")

        private val PROFILE_SKIP = setOf(
            "tabs5", "tabs6", "ntfyCmds", "cfg_ntfy", "cfg_maps", "profile_names", "profile_active", "theme", "themeGothic", "tab", "uiSize", "uiAlpha",
            "bfx", "bfy", "bfSize", "bfOpen", "hx", "hy", "swx", "swy", "scx", "scy", "scOpen", "entries", "stamps", "shortcuts", "superbAdded", "kanturuAdded", "aidaIcarusAdded"
        )

        /** Plan = tüm ayarlar: cfg_*, işaret noktaları, kayıtlı işlemler, pencere kayıtları, rota listeleri. Pencere konumu, tema, süre kayıtları ve istatistikler hariç. */
        private fun profKey(k: String) = !k.startsWith("profile_") && !k.startsWith("stat_") && !k.startsWith("sect_") && !k.startsWith("mg_") && !k.startsWith("card_open_") && !PROFILE_SKIP.contains(k)

        fun profileNames(p: android.content.SharedPreferences): List<String> =
            p.getString("profile_names", "")!!.split("\n").filter { it.isNotBlank() }

        fun saveProfile(p: android.content.SharedPreferences, rawName: String): String {
            val name = rawName.replace("\n", " ").trim().take(20)
            if (name.isEmpty()) return ""
            val o = org.json.JSONObject()
            val tm = org.json.JSONObject()
            for ((k, v) in p.all) {
                if (!profKey(k)) continue
                when (v) {
                    is Boolean -> { o.put(k, v); tm.put(k, "b") }
                    is String -> o.put(k, v)
                    is Int -> { o.put(k, v); tm.put(k, "i") }
                    is Long -> { o.put(k, v); tm.put(k, "l") }
                    is Float -> { o.put(k, v.toDouble()); tm.put(k, "f") }
                }
            }
            o.put("__t", tm)
            for (t in TOGGLES) if (!o.has("cfg_" + t.first)) o.put("cfg_" + t.first, t.third)
            val names = profileNames(p).toMutableList()
            if (!names.contains(name)) names.add(name)
            p.edit().putString("profile_" + name, o.toString()).putString("profile_names", names.joinToString("\n"))
                .putString("profile_active", name).apply()
            return name
        }

        fun applyProfile(p: android.content.SharedPreferences, name: String): Boolean {
            val js = p.getString("profile_" + name, null) ?: return false
            val o = org.json.JSONObject(js)
            val e = p.edit()
            for (k in p.all.keys) if (k.startsWith("cfg_") && profKey(k) && !o.has(k)) e.remove(k)
            val tm = o.optJSONObject("__t") ?: org.json.JSONObject()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k == "__t" || !profKey(k)) continue
                val v = o.get(k)
                when (tm.optString(k, "")) {
                    "b" -> e.putBoolean(k, o.getBoolean(k))
                    "i" -> e.putInt(k, o.getInt(k))
                    "l" -> e.putLong(k, o.getLong(k))
                    "f" -> e.putFloat(k, o.getDouble(k).toFloat())
                    else -> if (v is Boolean) e.putBoolean(k, v) else e.putString(k, v.toString())
                }
            }
            e.putString("profile_active", name).apply()
            return true
        }

        /** Aktif profilin rota/harita/golden/red bölümünü şu anki değerlerle günceller. */
        fun syncActiveExtras(p: android.content.SharedPreferences) {
            val a = p.getString("profile_active", "") ?: ""
            if (a.isEmpty()) return
            val js = p.getString("profile_" + a, null) ?: return
            try {
                val o = org.json.JSONObject(js)
                for (k in PROFILE_EXTRA) { val v = p.getString(k, null); if (v != null) o.put(k, v) else o.remove(k) }
                p.edit().putString("profile_" + a, o.toString()).apply()
            } catch (e: Exception) {
            }
        }

        fun deleteProfile(p: android.content.SharedPreferences, name: String) {
            val names = profileNames(p).filter { it != name }
            val e = p.edit().remove("profile_" + name).putString("profile_names", names.joinToString("\n"))
            if (p.getString("profile_active", "") == name) e.remove("profile_active")
            e.apply()
        }
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
    private var stopWin: TextView? = null
    private var stopLp: WindowManager.LayoutParams? = null
    private var dockSc: TextView? = null
    private var dockScLp: WindowManager.LayoutParams? = null
    private var dockSv: TextView? = null
    private var dockSvLp: WindowManager.LayoutParams? = null
    private var toolsBox: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private val toggleBtns = HashMap<String, Button>()

    private var ACCENT = 0xFF4FC3F7.toInt()
    private var PANEL_BG = 0xF2161920.toInt()
    private var CARD_BG = 0xFF272C3A.toInt()
    private var MISSING_BG = 0xFF4A2429.toInt()
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
    private var deaths = 0
    @Volatile private var lastLoginChk = 0L
    private val golds = ArrayList<Gold>()
    private val reds = ArrayList<Red>()
    private var redKills = 0
    private var redWinOpen = false
    private var redRadiusView: TextView? = null
    private var routeRadiusView: TextView? = null
    private var goldKills = 0
    private var goldWinOpen = false
    private var stampWinOpen = false
    private var goldRadiusView: TextView? = null
    @Volatile private var lastRatio = 0f
    private val deathCnt = HashMap<Long, Int>()
    private var startedAt = 0L
    @Volatile private var lastSay = ""
    private val evLog = java.util.ArrayDeque<String>()
    @Volatile private var pollRun = false
    private var pollThread: Thread? = null
    @Volatile private var lastCmdTime = 0L
    @Volatile private var shotReq = false
    @Volatile private var shotOut: Bitmap? = null
    private var bossWin: LinearLayout? = null
    private val winTimers = HashMap<Long, TextView>()
    private var fails = 0
    private var lastMaint = 0L
    private var lastPotBuy = 0L
    private var lastRecycle = 0L
    private var lastPot = 0L

    private var sent = 0
    private var cancelled = 0
    private var lastShot = 0L
    private var screenW = 1
    private var screenH = 1
    private var lastOcr = ""

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun applyTheme() {
        val t = THEMES[themeIdx(prefs())]
        ACCENT = t[0]
        PANEL_BG = t[1]
        CARD_BG = t[2]
        MISSING_BG = t[3]
    }

    private fun rebuildUi() {
        ui {
            try {
                panel?.let { wm.removeView(it) }
                handle?.let { wm.removeView(it) }
                stopWin?.let { wm.removeView(it) }
                dockSc?.let { wm.removeView(it) }
                dockSv?.let { wm.removeView(it) }
            } catch (e: Exception) {
            }
            panel = null
            handle = null
            stopWin = null
            dockSc = null
            dockSv = null
            sizeBtns.clear()
            applyTheme()
            buildUi()
            rebuildList()
            applyPanel()
        }
    }

    // ---- sayaç (kesim istatistikleri)
    private var statsView: TextView? = null
    private var statsSaved = ""

    private fun loadStats() {
        kills = prefs().getInt("stat_kills", 0)
        goldKills = prefs().getInt("stat_gold", 0)
        redKills = prefs().getInt("stat_red", 0)
        routeKills = prefs().getInt("stat_route", 0)
        deaths = prefs().getInt("stat_deaths", 0)
    }

    private fun statsText(): String {
        val run = if (autoRunning && startedAt > 0L) (System.currentTimeMillis() - startedAt) / 1000 else 0L
        return "Boss " + kills + " · Golden " + goldKills + " · Red " + redKills + " · Rota " + routeKills + " · Ölüm " + deaths +
            (if (run > 0) "\n⏱ " + fmt(run) else "")
    }

    private fun updateStats() {
        statsView?.text = statsText()
        val key = kills.toString() + "," + goldKills + "," + redKills + "," + routeKills + "," + deaths
        if (key != statsSaved) {
            statsSaved = key
            prefs().edit().putInt("stat_kills", kills).putInt("stat_gold", goldKills).putInt("stat_red", redKills)
                .putInt("stat_route", routeKills).putInt("stat_deaths", deaths).apply()
        }
    }

    private fun resetStats() {
        kills = 0
        goldKills = 0
        redKills = 0
        routeKills = 0
        deaths = 0
        updateStats()
        toast("Sayaç sıfırlandı")
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ yasam dongusu

    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Profil değişince (uygulamadan ya da panelden) rota/golden/red listeleri bellekte yenilenir; bu listelerdeki değişiklikler aktif profile yazılır. */
    private fun registerProfileSync() {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == "profile_active") {
                loadGolds()
                loadReds()
                loadRoute()
                resetRoute()
                goldHere = false
                redHere = false
            } else if (key != null && PROFILE_EXTRA.contains(key)) {
                syncActiveExtras(sp)
            }
        }
        prefListener = l
        prefs().registerOnSharedPreferenceChangeListener(l)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
        applyTheme()
        loadStats()
        loadEntries()
        loadGolds()
        loadReds()
        loadRoute()
        registerProfileSync()
        buildUi()
        rebuildList()
        main.postDelayed(ticker, 1000)
        startPoller()
        startWatchdog()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        pollRun = false
        dogRun = false
        stopRecording(false)
        pollThread?.interrupt()
        closeBossWin()
        closeStopwatch()
        closeShortcuts()
        closeBossFloat()
        closePick()
        closeProto()
        prefListener?.let { prefs().unregisterOnSharedPreferenceChangeListener(it) }
        cancel = true
        autoRunning = false
        worker?.interrupt()
        main.removeCallbacks(ticker)
        try {
            removeExtra()
            panel?.let { wm.removeView(it) }
            handle?.let { wm.removeView(it) }
            stopWin?.let { wm.removeView(it) }
            dockSc?.let { wm.removeView(it) }
            dockSv?.let { wm.removeView(it) }
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
        lastSay = msg
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
        if (key == "goldRadius" || key == "redRadius" || key == "routeRadius") (prefs().getString("cfg_radius", null)?.toFloatOrNull() ?: 200f)
        else prefs().getString("cfg_" + key, null)?.toFloatOrNull() ?: def

    private fun cfgI(key: String, def: Int): Int = cfgF(key, def.toFloat()).toInt()

    private fun cfgB(key: String, def: Boolean): Boolean = prefs().getBoolean("cfg_" + key, def)

    /** Pot ve Recycle tıklamaları arasındaki bekleme (ms). Oyundaki panelden ayarlanır. */
    private fun gap(): Int = (cfgF("clickGap", 1.0f) * 1000).toInt().coerceIn(200, 10000)

    private fun toggleDef(key: String): Boolean {
        for (t in TOGGLES) if (t.first == key) return t.third
        return true
    }

    private fun on(key: String): Boolean =
        if (key == "goldRandomOn" || key == "redRandomOn" || key == "routeRandomOn") cfgB("randomOn", true) else cfgB(key, toggleDef(key))

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
        ev("Hata: " + msg.replace("\n", " "))
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
        var raw = prefs().getString("cfg_maps", null) ?: DEFAULT_MAPS
        if (raw.contains("Swamp of Peace;1")) {
            raw = raw.replace("Swamp of Peace;1", "Swamp of Peace;3")
            prefs().edit().putString("cfg_maps", raw).apply()
        }
        if (!raw.lowercase().contains("vip domain 5")) {
            raw = raw.trimEnd() + "\nVIP Domain 5;1"
            prefs().edit().putString("cfg_maps", raw).apply()
        }
        if (!prefs().getBoolean("vip234Added", false)) {
            for (n in listOf("2", "3", "4")) {
                if (!raw.lowercase().contains("vip domain " + n)) raw = raw.trimEnd() + "\nVIP Domain " + n + ";1"
            }
            prefs().edit().putString("cfg_maps", raw).putBoolean("vip234Added", true).apply()
        }
        if (!prefs().getBoolean("aidaIcarusAdded", false)) {
            for (n in listOf("Aida 1", "Aida 2", "Icarus 1", "Icarus 3")) {
                if (!raw.lowercase().contains(n.lowercase())) raw = raw.trimEnd() + "\n" + n + ";3"
            }
            prefs().edit().putString("cfg_maps", raw).putBoolean("aidaIcarusAdded", true).apply()
        }
        if (!prefs().getBoolean("aidaIcarus3", false)) {
            for (n in listOf("Aida 1", "Aida 2", "Icarus 1", "Icarus 3")) raw = raw.replace(n + ";1", n + ";3")
            prefs().edit().putString("cfg_maps", raw).putBoolean("aidaIcarus3", true).apply()
        }
        if (!prefs().getBoolean("kanturuAdded", false)) {
            for (n in listOf("Kanturu Ruins 1", "Kanturu Ruins 2", "Kanturu Relics 1", "Kanturu Relics 2")) {
                if (!raw.lowercase().contains(n.lowercase())) raw = raw.trimEnd() + "\n" + n + ";3"
            }
            prefs().edit().putString("cfg_maps", raw).putBoolean("kanturuAdded", true).apply()
        }
        if (!prefs().getBoolean("superbAdded", false)) {
            for (n in listOf("Superb Realm 2", "Superb Realm 3")) {
                if (!raw.lowercase().contains(n.lowercase())) raw = raw.trimEnd() + "\n" + n + ";1"
            }
            prefs().edit().putString("cfg_maps", raw).putBoolean("superbAdded", true).apply()
        }
        if (!raw.lowercase().contains("swamp of peace")) {
            // eski kayıtlı listeye yeni haritayı bir kez ekle
            raw = raw.trimEnd() + "\nSwamp of Peace;3"
            prefs().edit().putString("cfg_maps", raw).apply()
        }
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
                // Süresi hâlâ çok uzakta olan bir kayıt, az önce kesilen boss olamaz: yakın başka boss'tur.
                val overdue = e.endMs - System.currentTimeMillis() <= cfgI("leadSec", 20) * 1000L + 120000L
                if (d <= tol && (d <= tol * 0.4f || overdue) && d < bd) {
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

    @Volatile private var shotGap = 900L

    private fun grab(): Bitmap? {
        val gap = shotGap - (System.currentTimeMillis() - lastShot)
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
                    if (errorCode == 3) shotGap = minOf(1300L, shotGap + 100L)
                    else say("Ekran görüntüsü alınamadı (kod " + errorCode + ")")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            say("Ekran görüntüsü hatası: " + e.message)
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        val rb = result
        if (shotReq && rb != null) {
            shotOut = Bitmap.createScaledBitmap(rb, rb.width / 2, rb.height / 2, true)
            shotReq = false
        }
        return result
    }

    /** Panel ve tutamacı gizleyip ekran görüntüsü alır (panel okunacak yerleri kapatmasın). */
    private fun shot(): Bitmap? {
        // Otomasyon sürerken tutamaç gizlenip gösterilmez (yanıp sönme olmasın); elle yapılan okumalarda gizlenir
        val hidePanel = panelOpen
        val hideHandle = !autoRunning
        val hide = hidePanel || hideHandle
        if (hide) {
            ui {
                if (hidePanel) panel?.visibility = View.INVISIBLE
                if (hideHandle) handle?.visibility = View.INVISIBLE
            }
            Thread.sleep(350)
        }
        val b = grab()
        if (hide) {
            ui {
                panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
                handle?.visibility = View.VISIBLE
            }
        }
        if (b != null && autoRunning && on("reviveOn") && deathScreen(b) && deathConfirmed(b)) revive()
        if (b != null && autoRunning && loginScreen(b)) loggedOut()
        if (b != null && autoRunning && on("popupOn")) popupScan(b, false)
        return b
    }

    // ---- Açılır pencere kapatıcı ----
    private class Popup(val x: Float, val y: Float, val sig: IntArray)
    private val popupStreak = HashMap<Int, Int>()
    @Volatile private var popupLastScan = 0L
    @Volatile private var popupCool = 0L

    private fun popupList(): List<Popup> {
        val out = ArrayList<Popup>()
        val raw = prefs().getString("popups", "") ?: ""
        for (it in raw.split(";")) {
            val f = it.split(",")
            if (f.size < 3 + 48) continue
            try {
                out.add(Popup(f[0].toFloat(), f[1].toFloat(), IntArray(48) { i -> f[2 + i].toInt() }))
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** Noktanın çevresini 4x4 hücreye böler, her hücrenin ortalama R,G,B değerini verir (48 sayı). */
    private fun popupSig(b: Bitmap, x: Float, y: Float): IntArray {
        val cx = x * b.width
        val cy = y * b.height
        val hw = 0.022f * b.width
        val hh = 0.038f * b.height
        val out = IntArray(48)
        for (gy in 0 until 4) for (gx in 0 until 4) {
            val xa = (cx - hw + 2 * hw * gx / 4f).toInt().coerceIn(0, b.width - 1)
            val xb = (cx - hw + 2 * hw * (gx + 1) / 4f).toInt().coerceIn(xa, b.width - 1)
            val ya = (cy - hh + 2 * hh * gy / 4f).toInt().coerceIn(0, b.height - 1)
            val yb = (cy - hh + 2 * hh * (gy + 1) / 4f).toInt().coerceIn(ya, b.height - 1)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            var yy = ya
            while (yy <= yb) {
                var xx = xa
                while (xx <= xb) {
                    val c = b.getPixel(xx, yy)
                    r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
                    xx++
                }
                yy++
            }
            val i = (gy * 4 + gx) * 3
            if (n > 0) { out[i] = (r / n).toInt(); out[i + 1] = (g / n).toInt(); out[i + 2] = (bl / n).toInt() }
        }
        return out
    }

    private fun popupDiff(a: IntArray, c: IntArray): Int {
        var d = 0
        for (i in 0 until 48) d += Math.abs(a[i] - c[i])
        return d / 48
    }

    /** Kayıtlı pencerelerden biri ekrandaysa (üst üste 2 taramada) kapatma noktasına dokunur. force: test, bekleme yok, hemen dokunur. */
    private fun popupScan(b: Bitmap, force: Boolean): Int {
        val now = System.currentTimeMillis()
        if (!force && (now - popupLastScan < cfgI("popupSec", 5) * 1000L || now < popupCool)) return 0
        popupLastScan = now
        val list = popupList()
        var closed = 0
        for ((i, pp) in list.withIndex()) {
            val d = popupDiff(pp.sig, popupSig(b, pp.x, pp.y))
            if (d <= 22) {
                val n = (popupStreak[i] ?: 0) + 1
                popupStreak[i] = n
                if (force || n >= 2) {
                    popupStreak[i] = 0
                    popupCool = System.currentTimeMillis() + 3000L
                    tapPx(pp.x * b.width, pp.y * b.height)
                    ev("Pencere kapatıldı (#" + (i + 1) + ")", true, 3)
                    closed++
                    Thread.sleep(600)
                    break
                }
            } else popupStreak[i] = 0
        }
        return closed
    }

    private fun popupTest() {
        val list = popupList()
        if (list.isEmpty()) {
            say("Kayıtlı pencere yok. Önce 'Pencere ekle'.")
            return
        }
        val b = shot() ?: return
        val sb = StringBuilder()
        for ((i, pp) in list.withIndex()) sb.append("#" + (i + 1) + " fark " + popupDiff(pp.sig, popupSig(b, pp.x, pp.y)) + " (eşik 22)\n")
        val n = popupScan(b, true)
        say(sb.toString() + (if (n > 0) "Pencere bulundu ve kapatıldı" else "Eşleşen pencere yok"))
    }

    private var popupOverlay: View? = null

    private fun popupMark() {
        if (autoRunning || busy) {
            say("Otomasyon açıkken eklenemez. Önce durdur.")
            return
        }
        if (popupOverlay != null) return
        refreshScreen()
        panelOpen = false
        updateUi()
        val v = View(this)
        v.setBackgroundColor(0x14FFAA00)
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 14f
        info.text = "Açık pencerenin kapatma (X) noktasına dokun"
        info.setPadding(dp(14), dp(8), dp(14), dp(8))
        info.background = rounded(0xEE161920.toInt(), 12)
        val ilp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        ilp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ilp.y = dp(8)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val x = ev.rawX
                val y = ev.rawY
                try {
                    wm.removeView(v)
                    wm.removeView(info)
                } catch (e: Exception) {
                }
                popupOverlay = null
                Thread {
                    Thread.sleep(400)
                    val b = grab()
                    if (b == null) {
                        toast("Ekran alınamadı")
                    } else {
                        val fx = x / screenW
                        val fy = y / screenH
                        val sig = popupSig(b, fx, fy)
                        val e = StringBuilder()
                        e.append(fx).append(",").append(fy)
                        for (n in sig) e.append(",").append(n)
                        val old = prefs().getString("popups", "") ?: ""
                        prefs().edit().putString("popups", if (old.isEmpty()) e.toString() else old + ";" + e).apply()
                        toast("Pencere kaydedildi (" + popupList().size + ")")
                        tapPx(x, y)
                    }
                    ui {
                        panelOpen = true
                        updateUi()
                    }
                }.start()
            }
            true
        }
        popupOverlay = v
        ui {
            wm.addView(v, lp)
            wm.addView(info, ilp)
        }
    }

    private fun brownFrac(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        val xa = (x0 * b.width).toInt()
        val xb = (x1 * b.width).toInt()
        val ya = (y0 * b.height).toInt()
        val yb = (y1 * b.height).toInt()
        var hit = 0
        var tot = 0
        var y = ya
        while (y < yb) {
            var x = xa
            while (x < xb) {
                tot++
                if (isButtonBrown(b.getPixel(x, y))) hit++
                x += 3
            }
            y += 3
        }
        return if (tot == 0) 0f else hit.toFloat() / tot
    }

    /** Giriş ekranı: alt ortadaki "Start Game" / "Select Server" yazısı. OCR olduğu için en fazla 8 sn'de bir bakılır. */
    private fun loginScreen(b: Bitmap): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLoginChk < 8000L) return false
        lastLoginChk = now
        val x0 = (0.30f * b.width).toInt()
        val y0 = (0.60f * b.height).toInt()
        val cr = Bitmap.createBitmap(b, x0, y0, (0.40f * b.width).toInt(), (0.20f * b.height).toInt())
        val res = ocr(cr) ?: return false
        val t = res.text.lowercase()
        return t.contains("start") || t.contains("select server")
    }

    private fun loggedOut(): Nothing {
        ev("Oyundan atıldın, otomasyon durdu", true, 5)
        say("Oyundan atıldın (giriş ekranı). Otomasyon durdu.")
        autoRunning = false
        throw LoggedOut()
    }

    /** Ölüm penceresindeki iki turuncu düğme (Free Revival, Respawn Now) görünüyor mu? Ucuz renk testi. */
    /** Ölüm penceresindeki düğmelerin kahve-turuncu tonu (≈155,107,68). */
    private fun isButtonBrown(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r in 130..225 && g in 80..160 && b <= 110 && r - g >= 25 && g - b >= 20
    }

    @Volatile private var deathDenied = 0L

    /** Renk testi yanılabilir (kahverengi arayüz/harita): ölüm penceresinde 'Revival/Respawn' yazısı OCR ile doğrulanır. */
    private fun deathConfirmed(b: Bitmap): Boolean {
        val now = System.currentTimeMillis()
        if (now - deathDenied < 2500L) return false
        val x0 = (0.30f * b.width).toInt()
        val y0 = (0.34f * b.height).toInt()
        val cr = Bitmap.createBitmap(b, x0, y0, (0.40f * b.width).toInt(), (0.34f * b.height).toInt())
        val t = (ocr(cr)?.text ?: "").lowercase()
        val ok = t.contains("revival") || t.contains("respawn") || t.contains("revive")
        if (!ok) deathDenied = now
        return ok
    }

    private fun deathScreen(b: Bitmap): Boolean {
        return brownFrac(b, 0.385f, 0.475f, 0.57f, 0.60f) > 0.3f && brownFrac(b, 0.525f, 0.615f, 0.57f, 0.60f) > 0.3f
    }

    /** Ölüm penceresinde 'Free Revival' yazısını OCR ile bulup dokunur; bulamazsa işaretli noktaya basar. */
    private fun revive() {
        deaths++
        say("Öldün, Free Revival'a basılıyor")
        var tapped = false
        for (i in 0 until 3) {
            if (i > 0) pause(1500)
            val b = grab() ?: continue
            val x0 = (0.30f * b.width).toInt()
            val y0 = (0.34f * b.height).toInt()
            val cr = Bitmap.createBitmap(b, x0, y0, (0.40f * b.width).toInt(), (0.34f * b.height).toInt())
            val res = ocr(cr) ?: continue
            for (blk in res.textBlocks) for (ln in blk.lines) {
                if (ln.text.lowercase().contains("revival")) {
                    val r = ln.boundingBox ?: continue
                    tapPx((x0 + r.centerX()).toFloat(), (y0 + r.centerY()).toFloat())
                    tapped = true
                }
            }
            if (tapped) break
        }
        if (!tapped && hasPt("revive")) tapPoint("revive")
        pause(4000)
        throw Revived()
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
        return orangeRatio(b, name, 1f) >= 0.10f
    }

    /** Haritanın kapat (X) düğmesi: işaretli noktada ya da çevresindeki geniş alanda turuncu var mı. */
    private fun mapOpen(b: Bitmap): Boolean {
        return orangeRatio(b, "closeMap", 1f) >= 0.06f || orangeRatio(b, "closeMap", 3f) >= 0.03f
    }

    private fun orangeRatio(b: Bitmap, name: String, scale: Float): Float {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val hw = 0.012f * scale * b.width
        val hh = 0.020f * scale * b.height
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
        return if (tot > 0) hit.toFloat() / tot else 0f
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
        val mask = BooleanArray(w * h)
        val blue = IntArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, x0, y0 + y, w, 1)
            for (x in 0 until w) {
                if (isGreen(row[x])) {
                    mask[y * w + x] = true
                    blue[y * w + x] = Color.blue(row[x])
                }
            }
        }
        // Yeşil lekeleri ayır: oyuncu oku tek parça ve büyük, NPC yazı harfleri ve kişi ikonları küçük parçalardır.
        val stack = IntArray(w * h)
        var bestN = 0
        var bestX = 0f
        var bestY = 0f
        var bestBlue = 0f
        for (start in mask.indices) {
            if (!mask[start]) continue
            var sp = 0
            stack[sp++] = start
            mask[start] = false
            var n = 0
            var sx = 0L
            var sy = 0L
            var sb = 0L
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % w
                val cy = cur / w
                n++
                sx += cx
                sy += cy
                sb += blue[cur]
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni]) {
                        mask[ni] = false
                        stack[sp++] = ni
                    }
                }
            }
            if (n > bestN) {
                bestN = n
                bestX = sx.toFloat() / n
                bestY = sy.toFloat() / n
                bestBlue = sb.toFloat() / n
            }
        }
        if (bestN < 60 * s * s) return null
        return PointF(x0 + bestX, y0 + bestY)
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
    private val throughOn = ArrayList<Pair<View, WindowManager.LayoutParams>>()

    /** Dokunuş noktasını örten pencereleri (panel, tutamaç, kronometre, Durdur) geçici olarak dokunuşa kapatır; diğerlerine dokunmaz. */
    private fun setThrough(through: Boolean, x: Float = -1f, y: Float = -1f) {
        val latch = CountDownLatch(1)
        ui {
            try {
                if (through) {
                    val m = dp(24)
                    val cands = listOf(Pair(panel, panelLp), Pair(handle, handleLp), Pair(swWin, swLp), Pair(scWin, scLp), Pair(stopWin, stopLp), Pair(dockSc, dockScLp), Pair(dockSv, dockSvLp), Pair(bfWin, bfLp))
                    for ((v, lp) in cands) {
                        if (v == null || lp == null || v.visibility != View.VISIBLE) continue
                        val w = if (v.width > 0) v.width else lp.width
                        val h = if (v.height > 0) v.height else dp(60)
                        if (x < lp.x - m || x > lp.x + w + m || y < lp.y - m || y > lp.y + h + m) continue
                        applyThrough(v, lp, true)
                        throughOn.add(Pair(v, lp))
                    }
                } else {
                    for ((v, lp) in throughOn) applyThrough(v, lp, false)
                    throughOn.clear()
                }
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
        lastBeat = System.currentTimeMillis()
        setThrough(true, x, y)
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
        if (intrOn && dueNow()) throw Interrupted()
        for (i in 0 until 4) {
            val b = shot()
            if (b != null && mapOpen(b)) return b
            lastRatio = if (b != null) orangeRatio(b, "closeMap", 1f) else -1f
            tapPoint("minimap")
            pause(1300)
        }
        fail("Harita açılamadı (X noktasında turuncu oranı " + String.format("%.3f", lastRatio) + ", gereken 0.06). 'Ayar' menüsünde closeMap noktasını harita açıkken X'in ortasına işaretle.")
    }

    private fun closeMapIfOpen() {
        val b = shot() ?: return
        if (mapOpen(b)) {
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
        stopReported = false
        stopWhy = "otomasyon kendiliğinden durdu"
        val wasAuto = autoRunning
        val wasOpen = panelOpen
        panelOpen = false
        updateUi()
        val t = Thread {
            try {
                block()
            } catch (e: StopEx) {
            } catch (e: StepFail) {
                stopWhy = "adım hatası"
            } catch (e: Revived) {
                stopWhy = "karakter öldü"
            } catch (e: LoggedOut) {
                stopWhy = "oyundan atıldı"
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                say("Hata: " + e.message)
                stopWhy = "beklenmeyen hata: " + (e.message ?: e.javaClass.simpleName)
                if (autoRunning) errorStop(stopWhy)
            }
            val selfStopped = wasAuto && !cancel && !stopReported
            busy = false
            autoRunning = false
            if (selfStopped) errorStop(stopWhy)
            ui {
                panelOpen = wasOpen
                updateUi()
            }
        }
        worker = t
        t.start()
    }

    private fun stopJob() {
        ev("Elle durduruldu")
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
        approach(ensureMap(), e.px, e.py, title(e), cfgI("radius", 200).toFloat(), on("randomOn"))
    }

    /** Harita açıkken: (isteğe bağlı Random ile yaklaşıp) işaretli noktaya yürür, varınca haritayı kapatır. */
    private fun approach(map0: Bitmap, px: Float, py: Float, label: String, radius: Float, useRandom: Boolean) {
        var mb = map0
        refreshScreen()
        val tx = px * screenW
        val ty = py * screenH
        val target = PointF(tx, ty)

        // Random ile hedefe yakın bir yere ışınlan, sonra yürü
        if (radius > 0f && useRandom && hasPt("random")) {
            val maxTry = cfgI("randomMax", 40)
            var tries = 0
            while (tries < maxTry) {
                val me0 = findPlayer(mb)
                if (me0 != null && dist(me0, target) <= radius) break
                tries++
                say("Random " + tries + "/" + maxTry + " · " + label)
                tapPoint("random")
                gameAuto = false
                pause((cfgF("randomWait", 2.5f) * 1000).toInt())
                mb = ensureMap()
            }
        }
        tapPx(tx, ty)
        gameAuto = false
        pause(400)
        val arrive = cfgI("arrive", 45).toFloat()
        val limit = cfgI("walkTimeout", 90) * 1000L
        var t0 = System.currentTimeMillis()
        var retapped = false
        while (true) {
            pause(100)
            if (intrOn && dueNow()) throw Interrupted()
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
                    fail("Noktaya varılamadı: " + label)
                }
            }
        }
        tapPoint("closeMap")
        pause(300)
        say("Vardı: " + label)
    }

    @Volatile private var lastLink = ""

    private fun openChatLink(link: String) {
        tapPoint("chat")
        pause(700)
        tapPoint("pmTab")
        pause(450)
        tapPoint(link)
        pause(400)
        if (prefs().contains("closeChat_x")) {
            tapPoint("closeChat")
            pause(300)
        }
        lastLink = link
        goldHere = false
        redHere = false
        routeAt = false
        gameAuto = false
    }

    /** Haritaya ışınlanma kaydı varsa ve "Kayıtlı ışın" açıksa true. */
    private fun tpMacroOn(map: String): Boolean =
        (cfgB("macroTpOn", false) || (protoGold && key(map) == key(goldMapName()))) && macroHas("tp_" + map)

    /** Golden protokolü çalışırken golden haritasına, "Kayıtlı ışın" kapalı olsa da kayıtlı ışınlanmayla gidilir. */
    @Volatile private var protoGold = false
    @Volatile private var protoStarting = false

    private fun teleport(map: String) {
        if (tpMacroOn(map)) {
            say("Işınlanılıyor (kayıtlı): " + map)
            playMacro("tp_" + map)
            lastLink = "link_" + map
            goldHere = false
            redHere = false
            routeAt = false
            gameAuto = false
            pause(cfgI("mapTravel", 10) * 1000)
            return
        }
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
    @Volatile private var lastFightEnd = 0L

    /** Az önceki kesimden hemen sonra yeni hedef zaten üstümüzdeyse oto atak sürüyordur; düğmeye basmak onu kapatırdı. */
    private fun stillOnTarget(bd: Float): Boolean =
        gameAuto && bd < Float.MAX_VALUE / 2 && bd <= cfgI("arrive", 45) * 1.5f && System.currentTimeMillis() - lastFightEnd < 30000L

    private fun setAuto(want: Boolean) {
        // Her ışınlanma/wire değişiminde oyun oto atağı kapatır; açmak istediğimizde düğme her zaman kapalıdır.
        if (!want && !gameAuto) return
        tapPoint("autoAtk")
        ev("Oto atak düğmesine basıldı (istenen: " + (if (want) "AÇIK" else "KAPALI") + ")")
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
        goldHere = false
        redHere = false
        routeAt = false
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
        if (on("impOn") && hasPt("imp")) {
            tapPoint("imp")
            pause(500)
        }
        farming = true
        ev("Farm konumunda")
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
        val lowHp = hp != null && hp < min
        val lowMp = mp != null && mp < min
        val rmin = cfgI("rndMin", 0)
        var lowRnd = false
        if (rmin > 0 && hasPt("rndCount")) {
            val rc = readCount(b, "rndCount")
            ev("Random sayısı: " + (rc ?: "?") + " (eşik " + rmin + ")")
            lowRnd = rc != null && rc < rmin
        }
        if (!lowHp && !lowMp && !lowRnd) return
        if (lowHp || lowMp) buyPots(lowHp, lowMp)
        if (lowRnd) {
            if (macroHas("rndbuy")) {
                say("Random azaldı, kayıtlı işlem oynatılıyor")
                playMacro("rndbuy")
                ev("Random alındı (kayıtlı işlem)", true, 2)
            } else {
                ev("Random azaldı ama 'Random al' kaydı yok (Bakım > Kayıtlı işlemler)", true, 3)
            }
        }
    }

    /** Inventory > Shop > iki Purchase düğmesine 3'er kez (toplam 6) > Inventory'yi kapat. Her adım arası 1 sn. */
    private fun buyPots(buyHp0: Boolean, buyMp0: Boolean) {
        var buyHp = buyHp0
        var buyMp = buyMp0
        if (buyHp && macroHas("pothp")) {
            say("HP pot: kayıtlı işlem oynatılıyor")
            playMacro("pothp")
            buyHp = false
        }
        if (buyMp && macroHas("potmp")) {
            say("MP pot: kayıtlı işlem oynatılıyor")
            playMacro("potmp")
            buyMp = false
        }
        if (!buyHp && !buyMp) {
            ev("Pot alındı (kayıtlı işlem)", true, 2)
            return
        }
        val clicks = cfgI("potClicks", 3)
        val what = (if (buyHp) "HP" else "") + (if (buyHp && buyMp) " + " else "") + (if (buyMp) "MP" else "")
        say("Pot azaldı (" + what + "), alışverişe gidiliyor")
        tapPoint("inventory")
        pause(gap())
        tapPoint("shopTab")
        pause(gap())
        if (buyHp) {
            say("HP pot alınıyor (" + clicks + " kez)")
            for (i in 0 until clicks) {
                tapPoint("purchaseHP")
                pause(gap())
            }
        }
        if (buyMp) {
            say("MP pot alınıyor (" + clicks + " kez)")
            for (i in 0 until clicks) {
                tapPoint("purchaseMP")
                pause(gap())
            }
        }
        say("Pot alındı (" + what + "), Inventory kapatılıyor")
        ev("Pot alındı: " + what, true, 2)
        closeInventory()
    }

    private fun doRecycle() {
        say("Recycle yapılıyor")
        lastRecycle = System.currentTimeMillis()
        fightsSinceRec = 0
        if (macroHas("recycle")) {
            playMacro("recycle")
            say("Recycle tamam (kayıtlı işlem)")
            return
        }
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
        ev("Buff'a gidiliyor", true, 3)
        openChatLink("buffLink")
        val tt0 = System.currentTimeMillis()
        val travel = cfgI("buffTravel", 12) * 1000L
        while (System.currentTimeMillis() - tt0 < travel) {
            if (dueNow()) {
                ev("Boss zamanı geldi, buff yolculuğu yarıda bırakıldı")
                return
            }
            pause(500)
        }
        val t0 = System.currentTimeMillis()
        val limit = cfgI("buffWait", 90) * 1000L
        while (System.currentTimeMillis() - t0 < limit) {
            if (dueNow()) {
                ev("Boss zamanı geldi, buff beklemesi bırakıldı")
                return
            }
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
        lastBuffChk = System.currentTimeMillis()
        if (!on("buffOn")) return
        closeMapIfOpen()
        val b = shot() ?: return
        val st = readStats(b)
        val minA = cfgI("minAtk", 0)
        val minD = cfgI("minDef", 0)
        if (st == null) {
            buffReadFails++
            say("ATK/DEF okunamadı: " + lastOcr)
            ev("Buff: ATK/DEF okunamadı (" + buffReadFails + ")", buffReadFails == 3, 3)
            return
        }
        buffReadFails = 0
        ev("Buff kontrol: ATK " + st.first + " DEF " + st.second + " · eşik " + minA + "/" + minD)
        if (st.first < minA && st.second < minD) doBuff(minA, minD)
        else say("ATK " + st.first + " DEF " + st.second + " tamam")
    }

    /** Otomasyon bakımı (her 20 dk): pot okuma/alma, ardından Recycle. */
    /** Recycle: otomasyon çalışırken her recycleEvery dakikada bir. */
    private fun maintenance(recDue: Boolean) {
        ev("Bakım: Recycle")
        closeMapIfOpen()
        if (recDue) {
            lastRecycle = System.currentTimeMillis()
            doRecycle()
        }
    }

    /**
     * Bir boss/golden/red kesimi başlarken (varış + oto atak sonrası): pot dakikada bir (potWatchSec) okunur, eşiğin altındaysa kayıtlı işlemle alınır,
     * Recycle her N kesimde bir (recycleKillEvery) ya da süre aralığı dolunca yapılır. Savaş döngüsü bundan sonra başladığı için
     * envanter mini haritayı kapatsa da ölü sanılmaz.
     */
    private fun fightMaint() {
        if (!autoRunning) return
        fightsSinceRec++
        val now = System.currentTimeMillis()
        if (on("potOn") && now - lastPot >= cfgI("potWatchSec", 60) * 1000L) potCheckNow()
        angelCheck()
        if (on("recycleOn")) {
            val n = cfgI("recycleKillEvery", 0)
            val byKills = n > 0 && fightsSinceRec >= n
            val byTime = System.currentTimeMillis() - lastRecycle >= cfgI("recycleEvery", 20) * 60000L
            if (byKills || byTime) {
                say("Savaş başında Recycle" + (if (byKills) " (" + fightsSinceRec + " kesim)" else ""))
                doRecycle()
            }
        }
    }

    @Volatile private var fightsSinceRec = 0
    @Volatile private var lastAngelChk = 0L

    /** Alt çubukta Angel yuvasındaki parlak (simge) piksel oranı. Boş yuva koyu ve düzdür. */
    private fun angelFrac(b: Bitmap): Float {
        val p = pt("angel")
        val cx = (p.first * b.width).toInt()
        val cy = (p.second * b.height).toInt()
        val hw = maxOf(4, (0.012f * b.width).toInt())
        val hh = maxOf(4, (0.022f * b.height).toInt())
        var hit = 0
        var tot = 0
        var y = maxOf(0, cy - hh)
        while (y < minOf(b.height, cy + hh)) {
            var x = maxOf(0, cx - hw)
            while (x < minOf(b.width, cx + hw)) {
                val c = b.getPixel(x, y)
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum > 150) hit++
                tot++
                x += 2
            }
            y += 2
        }
        return if (tot == 0) 0f else hit.toFloat() / tot
    }

    private fun angelPresent(b: Bitmap): Boolean = angelFrac(b) >= cfgI("angelThr", 30) / 1000f

    /** Angel açıkken ve "Angel al" kaydı varsa: Angel simgesi yuvada yoksa (iki ölçümde) kayıtlı işlemle alınır. */
    private fun angelCheck() {
        if (!on("angelOn") || !hasPt("angel") || !macroHas("angelbuy")) return
        val now = System.currentTimeMillis()
        if (now - lastAngelChk < 30000L) return
        lastAngelChk = now
        closeMapIfOpen()
        val b1 = shot() ?: return
        if (angelPresent(b1)) return
        pause(800)
        val b2 = shot() ?: return
        if (angelPresent(b2)) return
        say("Angel simgesi yok, kayıtlı işlem oynatılıyor")
        playMacro("angelbuy")
        ev("Angel alındı (kayıtlı işlem)", true, 2)
        lastAngelChk = System.currentTimeMillis()
    }

    /** Pot sayısını OCR ile okur; eşiğin altındaki pot varsa alır. Süreye değil okumaya bağlı. */
    private fun potCheckNow() {
        if (!on("potOn")) return
        lastPot = System.currentTimeMillis()
        closeMapIfOpen()
        val b = shot() ?: return
        checkPots(b)
    }

    // ------------------------------------------------------------------ otomasyon

    private fun missingForAuto(): List<String> {
        val need = ArrayList<String>()
        if (on("farmOn") || on("idleFarmOn")) for (n in listOf("chat", "pmTab", "farmLink", "autoAtk", "shield")) if (!hasPt(n)) need.add(n)
        if (on("farmOn") && on("impOn") && !hasPt("imp")) need.add("imp")
        if (on("angelOn") && !hasPt("angel")) need.add("angel")
        for (n in listOf("chat", "pmTab", "autoAtk")) if (!hasPt(n) && !need.contains(n)) need.add(n)
        if (on("buffOn") && !hasPt("buffLink")) need.add("buffLink")
        if (on("goldOn")) {
            if (!hasPt("goldLink") && !tpMacroOn(goldMapName())) need.add("goldLink")
            val gm = goldMapName()
            if (gm.isNotEmpty() && mapInfo(gm).wires > 1) {
                val n = "wire" + cfgI("goldWire", 3)
                if (!hasPt(n)) need.add(n)
                if (!hasPt("switchBtn")) need.add("switchBtn")
            }
        }
        if (on("redOn") && !hasPt("redLink") && !tpMacroOn(redMapName())) need.add("redLink")
        if (on("routeOn")) {
            for (rm in routeSnapshot()) {
                val l = "link_" + rm
                if (!hasPt(l) && !tpMacroOn(rm) && !need.contains(l)) need.add(l)
                if (mapInfo(rm).wires > 1) {
                    for (w in 1..mapInfo(rm).wires) {
                        val n = "wire" + w
                        if (!hasPt(n) && !need.contains(n)) need.add(n)
                    }
                    if (!hasPt("switchBtn") && !need.contains("switchBtn")) need.add("switchBtn")
                }
            }
        }
        val maps = ArrayList<String>()
        for (e in snapshot()) maps.add(e.map)
        for (m in maps) {
            val l = "link_" + m
            if (!hasPt(l) && !tpMacroOn(m) && !need.contains(l)) need.add(l)
            if (mapInfo(m).wires > 1) {
                for (w in 1..mapInfo(m).wires) {
                    val n = "wire" + w
                    if (!hasPt(n) && !need.contains(n)) need.add(n)
                }
            }
        }
        return need
    }

    private fun startAuto() {
        if (protoStarting) protoStarting = false else protoGold = false
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
        lastBeat = System.currentTimeMillis()
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

    @Volatile private var intrOn = false
    @Volatile private var buffNeeded = false
    @Volatile private var buffReadFails = 0
    private val recoverLog = ArrayList<Long>()

    private fun dueNow(): Boolean = autoRunning && nextDue() != null

    /** Savaş sırasında (kendi ekran görüntüsünden) buff süresine bakar; bittiyse savaşı bırakıp buff'a gitmek üzere işaretler. */
    private fun fightBuff(b: Bitmap): Boolean {
        if (!autoRunning || !on("buffOn")) return false
        val now = System.currentTimeMillis()
        if (now - lastBuffChk < cfgI("buffWatchSec", 45) * 1000L) return false
        lastBuffChk = now
        val st = readStats(b)
        if (st == null) {
            buffReadFails++
            ev("Buff: ATK/DEF okunamadı (savaşta)")
            return false
        }
        buffReadFails = 0
        val minA = cfgI("minAtk", 0)
        val minD = cfgI("minDef", 0)
        ev("Buff kontrol (savaşta): ATK " + st.first + " DEF " + st.second + " · eşik " + minA + "/" + minD)
        if (st.first < minA && st.second < minD) {
            buffNeeded = true
            ev("Buff bitti, savaş bırakılıp buff'a gidiliyor", true, 3)
            setAuto(false)
            say("Buff bitti, buff'a gidiliyor")
            return true
        }
        return false
    }

    @Volatile private var lastKillAt = 0L
    @Volatile private var protoKillAt = 0L
    @Volatile private var protoKillTotal = 0
    @Volatile private var protoIdleMode = false
    @Volatile private var lastKillTotal = 0
    @Volatile private var idleFarmUntil = 0L

    private fun autoLoop() {
        gameAuto = false
        farming = false
        fails = 0
        lastPot = System.currentTimeMillis()
        lastRecycle = 0L
        say("Otomasyon açık")
        startedAt = System.currentTimeMillis()
        deathCnt.clear()
        lastKillAt = System.currentTimeMillis()
        lastKillTotal = kills + goldKills + redKills + routeKills
        idleFarmUntil = 0L
        ev("Otomasyon başladı", true, 3)
        buffCheck()
        potCheckNow()
        protoKillAt = System.currentTimeMillis()
        protoKillTotal = kills + goldKills + redKills + routeKills
        while (autoRunning && !cancel) {
            try {
                val now = System.currentTimeMillis()
                // Golden protokolü bekçisi: belirli süre hiçbir şey kesilmezse uyarı verir, reddedilmezse protokol uygulanır
                run {
                    val tot = kills + goldKills + redKills + routeKills
                    if (tot != protoKillTotal) {
                        protoKillTotal = tot
                        protoKillAt = now
                    }
                    val lim = cfgI("protoIdleMin", 20)
                    if (lim > 0 && on("protoOn") && protoWin == null && now - protoKillAt >= lim * 60000L) {
                        protoKillAt = now
                        if (now - lastProtoAt >= cfgI("protoCooldownMin", 15) * 60000L) {
                            ev(lim.toString() + " dk hiçbir şey kesilmedi, Golden protokolü uyarısı", true, 5)
                            protoIdleMode = true
                            ui { showProtoWarning(lim.toString() + " dk hiçbir şey kesilmedi", false) }
                        }
                    }
                }
                val due = nextDue()
                val recEvery = cfgI("recycleEvery", 20) * 60000L
                val recDue = on("recycleOn") && now - lastRecycle >= recEvery
                // Recycle ve pot savaş sırasında (fightMaint) yapılır; savaş yapılmıyorsa ya da çok gecikmişse boşta yapılır
                val killMode = on("goldOn") || on("redOn") || on("routeOn")
                val lateBy = if (killMode) 600000L else 0L
                if (recDue && due == null && now - lastRecycle - recEvery >= lateBy) {
                    maintenance(recDue)
                    continue
                }
                if (due != null) {
                    runBoss(due)
                    continue
                }
                if (buffNeeded) {
                    buffNeeded = false
                    lastBuffChk = now
                    doBuff(cfgI("minAtk", 0), cfgI("minDef", 0))
                    continue
                }
                if (on("buffOn") && now - lastBuffChk >= cfgI("buffWatchSec", 45) * 1000L) {
                    buffCheck()
                    continue
                }
                if (on("idleFarmOn")) {
                    val total = kills + goldKills + redKills + routeKills
                    if (total != lastKillTotal) {
                        lastKillTotal = total
                        lastKillAt = now
                    }
                    val minutes = cfgI("idleFarmMin", 10)
                    if (now < idleFarmUntil) {
                        if (!farming) doFarm()
                        idleTick()
                        continue
                    }
                    if (minutes > 0 && now - lastKillAt >= minutes * 60000L) {
                        ev(minutes.toString() + " dk hiçbir şey kesilmedi, farma gidiliyor", true, 3)
                        say(minutes.toString() + " dk kesim yok, farma gidiliyor")
                        doFarm()
                        idleFarmUntil = System.currentTimeMillis() + cfgI("idleFarmStay", 10) * 60000L
                        lastKillAt = System.currentTimeMillis()
                        continue
                    }
                }
                if (on("routeOn") && routeSnapshot().isNotEmpty()) {
                    runRoute()
                    continue
                }
                if (on("redOn") && System.currentTimeMillis() >= redIdleUntil) {
                    runRed()
                    continue
                }
                if (on("goldOn") && System.currentTimeMillis() >= goldIdleUntil) {
                    runGold()
                    continue
                }
                if (on("farmOn") && !farming) {
                    doFarm()
                    fails = 0
                }
                idleTick()
            } catch (e: Revived) {
                goldHere = false
                redHere = false
                routeAt = false
                val dead = currentId
                currentId = 0L
                var skipped = ""
                if (dead != 0L) {
                    val c = (deathCnt[dead] ?: 0) + 1
                    deathCnt[dead] = c
                    val lim = cfgI("deathLimit", 3)
                    if (c >= lim) {
                        val mins = cfgI("deathBlockMin", 15)
                        blocked[dead] = System.currentTimeMillis() + mins * 60000L
                        deathCnt[dead] = 0
                        val en = snapshot().firstOrNull { it.id == dead }
                        skipped = " · " + (if (en != null) shortTitle(en) else "boss") + " " + lim + " kez öldürdü, " + mins + " dk atlanıyor, farma dönülüyor"
                    }
                }
                ev("Öldün, dirildin" + skipped, true, 4)
                gameAuto = false
                farming = false
                ui { rebuildList() }
                say("Canlandın, kaldığın işe dönülüyor")
            } catch (e: Interrupted) {
                intrOn = false
                ev("Boss zamanı geldi, yapılan iş bırakıldı")
                try {
                    if (gameAuto) setAuto(false)
                    closeMapIfOpen()
                } catch (x: Exception) {
                }
                goldHere = false
                redHere = false
                routeAt = false
            } catch (e: StepFail) {
                fails++
                goldHere = false
                redHere = false
                routeAt = false
                val id = currentId
                if (id != 0L) {
                    blocked[id] = System.currentTimeMillis() + 90000L
                    currentId = 0L
                    ui { rebuildList() }
                }
                farming = false
                if (fails >= 5) {
                    val nowT = System.currentTimeMillis()
                    recoverLog.removeAll { nowT - it > 3600000L }
                    recoverLog.add(nowT)
                    if (recoverLog.size >= 6) {
                        ev("Bir saatte 6 kez üst üste 5 hata, otomasyon durdu", true, 5)
                        say("Sürekli hata, otomasyon durduruldu. Son mesaj yukarıda.")
                        toast("Otomasyon durduruldu: sürekli hata")
                        autoRunning = false
                        errorStop("bir saatte 6 kez üst üste 5 hata")
                        return
                    }
                    ev("Üst üste 5 hata, 30 sn bekleyip toparlanılıyor (" + recoverLog.size + "/6)", true, 4)
                    say("Üst üste 5 hata, 30 sn sonra yeniden denenecek")
                    try {
                        closeMapIfOpen()
                    } catch (x: Exception) {
                    }
                    gameAuto = false
                    lastLink = ""
                    pause(30000)
                    fails = 0
                }
                pause(3000)
            } catch (e: StopEx) {
                throw e
            } catch (e: LoggedOut) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                fails++
                goldHere = false
                redHere = false
                routeAt = false
                farming = false
                ev("Beklenmeyen hata: " + e.javaClass.simpleName + " " + (e.message ?: ""), true, 4)
                say("Beklenmeyen hata, devam ediliyor: " + (e.message ?: e.javaClass.simpleName))
                if (fails >= 8) {
                    autoRunning = false
                    errorStop("üst üste 8 beklenmeyen hata: " + (e.message ?: e.javaClass.simpleName))
                    return
                }
                pause(3000)
            }
        }
    }

    @Volatile private var lastWatch = 0L

    // ------------------------------------------------------------------ takılma bekçisi

    /** Son hareket: bir dokunuş ya da bekleme/farm durumuna oturmuş döngü. */
    @Volatile private var lastBeat = 0L
    @Volatile private var dogRun = false
    private val rescues = ArrayList<Long>()

    private fun startWatchdog() {
        dogRun = true
        Thread {
            while (dogRun) {
                try {
                    Thread.sleep(30000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                try {
                    val lim = cfgI("stuckMin", 10) * 60000L
                    if (lim <= 0L || !autoRunning || !busy) continue
                    val idle = System.currentTimeMillis() - lastBeat
                    if (idle >= lim) rescue(idle / 60000L)
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    private fun rescue(mins: Long) {
        val now = System.currentTimeMillis()
        rescues.removeAll { now - it > 3600000L }
        if (rescues.size >= 3) {
            ev("Takıldı ve 1 saatte 3 kez kurtarılamadı, otomasyon durdu", true, 5)
            say("Takıldı, kurtarılamadı. Otomasyon durduruldu.")
            cancel = true
            autoRunning = false
            worker?.interrupt()
            return
        }
        rescues.add(now)
        ev("Takıldı: " + mins + " dk hiçbir şey yapmadı, kurtarma deneniyor (" + lastSay.replace("\n", " · ") + ")", true, 5)
        cancel = true
        worker?.interrupt()
        var w = 0
        while (busy && w < 150) {
            Thread.sleep(100)
            w++
        }
        if (busy) {
            ev("Kurtarılamadı: işlem yanıt vermiyor. Uygulamayı kontrol et.", true, 5)
            autoRunning = false
            return
        }
        cancel = false
        lastBeat = System.currentTimeMillis()
        autoRunning = true
        try {
            closeMapIfOpen()
        } catch (e: Exception) {
        }
        if (!autoRunning) return
        autoRunning = false
        startAuto()
        ev("Kurtarma: otomasyon yeniden başlatıldı", true, 4)
    }

    private fun idleTick() {
        val now = System.currentTimeMillis()
        lastBeat = now
        // Farmda/bekleyişte ölüm ve oyundan atılma ancak ekran görüntüsüyle anlaşılır: birkaç saniyede bir bak
        if (now - lastWatch >= 4000L) {
            lastWatch = now
            shot()
        }
        if (farming && on("potOn") && now - lastPot >= cfgI("potWatchSec", 60) * 1000L) {
            potCheckNow()
            return
        }
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
        goldHere = false
        redHere = false
        routeAt = false
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
            deathCnt.remove(e.id)
            ev("Kesildi: " + shortTitle(e) + " · yeni süre " + fmt((e.endMs - System.currentTimeMillis()) / 1000), true, 2)
            buffCheck()
        } else {
            ev("Boss başarısız: " + shortTitle(e))
            blocked[e.id] = System.currentTimeMillis() + (if (ownerSkipped) 300000L else 90000L)
            ownerSkipped = false
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
        var t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        setAuto(true)
        if (on("angelOn") && hasPt("angel")) {
            tapPoint("angel")
            pause(300)
        }
        say(title(e) + "\nOto atak açık, boss bekleniyor")
        fightMaint()
        t0 = System.currentTimeMillis()
        var seen = false
        var dead = 0
        val early = ArrayList<Long>()
        while (true) {
            pause(100)
            val b = shot() ?: continue
            val stamp = lastShot
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val oth = ownerTakenCheck(b)
            if (oth != null) {
                setAuto(false)
                ev("Boss başka oyuncuda (" + oth + "), sıradakine geçiliyor: " + shortTitle(e), true, 3)
                say("Boss başka oyuncuda: " + oth)
                ownerSkipped = true
                return false
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
                (if (hasPt("rndCount")) "\nRandom sayısı: " + (readCount(b, "rndCount") ?: "?") else "") +
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

    /** İnce çerçeveli kart. */
    private fun card(color: Int, r: Int, stroke: Int = 0xFF332D40.toInt()): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        g.setStroke(dp(1), stroke)
        return g
    }

    /** Gotik çift çerçeve: gümüş-gri dış hat, mor iç hat, köşe süsleri ve üstte mor taş. */
    private fun framed(color: Int, r: Int): android.graphics.drawable.Drawable =
        GothicFrame(color, dp(r).toFloat(), resources.displayMetrics.density)

    /** Açık düğme dolgusu: vurgu renginin canlı koyu tonu. */
    private fun onFill(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(ACCENT, hsv)
        hsv[1] = minOf(0.78f, maxOf(hsv[1], 0.5f))
        hsv[2] = 0.62f
        return Color.HSVToColor(hsv)
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
        b.background = card(bg, 10)
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

    private val sizeBtns = ArrayList<Button>()
    private var alphaView: TextView? = null

    private fun panelW(): Int = dp(intArrayOf(200, 250, 310)[prefs().getInt("uiSize", 1).coerceIn(0, 2)])

    private fun uiAlpha(): Int = prefs().getInt("uiAlpha", 95).coerceIn(30, 100)

    private fun updateSizeBtns() {
        val cur = prefs().getInt("uiSize", 1).coerceIn(0, 2)
        for (i in sizeBtns.indices) {
            sizeBtns[i].background = rounded(if (i == cur) ACCENT else CARD_BG, 10)
            sizeBtns[i].setTextColor(if (i == cur) 0xFF04202D.toInt() else Color.WHITE)
        }
    }

    private fun setUiSize(i: Int) {
        prefs().edit().putInt("uiSize", i).apply()
        updateSizeBtns()
        val pl = panelLp ?: return
        val p = panel ?: return
        pl.width = panelW()
        try {
            wm.updateViewLayout(p, pl)
        } catch (e: Exception) {
        }
        placeBar()
    }

    private fun changeAlpha(delta: Int) {
        val v = (uiAlpha() + delta).coerceIn(30, 100)
        prefs().edit().putInt("uiAlpha", v).apply()
        alphaView?.text = v.toString()
        panel?.alpha = v / 100f
    }

    private fun changeRouteRadius(delta: Int) {
        val v = (cfgI("routeRadius", 150) + delta).coerceIn(0, 1000)
        prefs().edit().putString("cfg_routeRadius", v.toString()).apply()
        routeRadiusView?.text = v.toString()
    }

    private fun changeRedRadius(delta: Int) {
        val v = (cfgI("redRadius", 150) + delta).coerceIn(0, 1000)
        prefs().edit().putString("cfg_redRadius", v.toString()).apply()
        redRadiusView?.text = v.toString()
    }

    private fun changeGoldRadius(delta: Int) {
        val v = (cfgI("goldRadius", 150) + delta).coerceIn(0, 1000)
        prefs().edit().putString("cfg_goldRadius", v.toString()).apply()
        goldRadiusView?.text = v.toString()
    }

    private fun changeRadius(delta: Int) {
        val v = (cfgI("radius", 200) + delta).coerceIn(0, 1000)
        prefs().edit().putString("cfg_radius", v.toString()).apply()
        radiusView?.text = v.toString()
    }

    private var profileBtn: Button? = null
    private val stepViews = HashMap<String, Pair<TextView, Int>>()

    private fun updateProfileBtn() {
        plansBox?.let { pb -> ui { buildPlans(pb) } }
        val names = profileNames(prefs())
        val a = prefs().getString("profile_active", "") ?: ""
        profileBtn?.text = if (names.isEmpty()) "Profil yok (ayar ekranından kaydet)" else "Profil: " + (if (a.isEmpty()) "-" else a) + "  ▸  (uzun bas: güncelle)"
    }

    /** Sıradaki profile geçer; sayısal alanlar ve düğmeler yeni değerleri gösterir. */
    private fun cycleProfile() {
        if (autoRunning || busy) {
            say("Otomasyon açıkken profil değişmez. Önce durdur.")
            return
        }
        val names = profileNames(prefs())
        if (names.isEmpty()) {
            toast("Henüz profil yok. Ayar ekranından kaydet.")
            return
        }
        val a = prefs().getString("profile_active", "") ?: ""
        loadProfile(names[(names.indexOf(a) + 1) % names.size])
    }

    /** Tek tuşla: planı (profili) yükler ve otomasyonu başlatır. */
    private fun startPlan(name: String) {
        if (autoRunning || busy) {
            say("Otomasyon açık. Önce durdur.")
            toast("Önce otomasyonu durdur")
            return
        }
        loadProfile(name)
        val need = missingForAuto()
        if (need.isNotEmpty()) {
            say("Başlatılamadı, eksik nokta: " + need.joinToString(", "))
            return
        }
        startAuto()
    }

    private fun loadProfile(next: String) {
        if (autoRunning || busy) {
            say("Otomasyon açıkken profil değişmez. Önce durdur.")
            return
        }
        applyProfile(prefs(), next)
        loadGolds()
        loadReds()
        loadRoute()
        resetRoute()
        goldHere = false
        redHere = false
        updateToggles()
        updateProfileBtn()
        radiusView?.text = cfgI("radius", 200).toString()
        goldRadiusView?.text = cfgI("goldRadius", 150).toString()
        redRadiusView?.text = cfgI("redRadius", 150).toString()
        routeRadiusView?.text = cfgI("routeRadius", 150).toString()
        gapView?.text = String.format("%.2f", gap() / 1000f)
        for ((k, pv) in stepViews) pv.first.text = cfgI(k, pv.second).toString()
        say("Profil yüklendi: " + next)
        toast("Profil: " + next)
    }

    // ------------------------------------------------------------------ Ana sekme: seçili profilde AÇIK olan seçenekler tek ekranda

    private var homeBox: LinearLayout? = null
    private var homeOpen = ""

    private var plansBox: LinearLayout? = null

    private fun refreshHome() {
        val hb = homeBox ?: return
        ui {
            buildHome(hb)
            plansBox?.let { buildPlans(it) }
        }
    }

    /** PLANLAR sekmesi: kayıtlı planlar; her biri için tek tuşla başlat, yükle, güncelle. */
    private fun buildPlans(box: LinearLayout) {
        box.removeAllViews()
        val full = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val names = profileNames(prefs())
        val active = prefs().getString("profile_active", "") ?: ""
        val head = TextView(this)
        head.text = "PLANLAR"
        head.textSize = 11f
        head.setTextColor(ACCENT)
        head.typeface = android.graphics.Typeface.DEFAULT_BOLD
        box.addView(head)
        val hint = TextView(this)
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.text = if (names.isEmpty()) "Plan yok. Uygulamadaki ayar ekranından bir adla kaydet." else "Plan tüm ayarları içerir. ntfy: plan <ad>. ● aktif plan."
        box.addView(hint)
        for (n in names) {
            val sel = n == active
            val cd = LinearLayout(this)
            cd.orientation = LinearLayout.VERTICAL
            cd.background = card(CARD_BG, 14, if (sel) ACCENT else 0xFF332D40.toInt())
            cd.setPadding(dp(12), dp(8), dp(12), dp(10))
            val t = TextView(this)
            t.text = (if (sel) "● " else "○ ") + n
            t.textSize = 15f
            t.tag = "serif"
            t.typeface = android.graphics.Typeface.SERIF
            t.setTextColor(if (sel) ACCENT else Color.WHITE)
            cd.addView(t)
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            val go = mkBtn("▶ Başlat", OK, 0xFF04200F.toInt()) { startPlan(n) }
            r.addView(go, LinearLayout.LayoutParams(0, dp(38), 1.3f))
            val ld = mkBtn("Yükle") {
                if (autoRunning || busy) say("Otomasyon açık. Önce durdur.") else loadProfile(n)
            }
            r.addView(ld, LinearLayout.LayoutParams(0, dp(38), 1f).also { it.leftMargin = dp(6) })
            val up = mkBtn("Güncelle") {
                if (n == active) {
                    saveProfile(prefs(), n)
                    toast("Plan güncellendi: " + n)
                } else toast("Önce planı yükle, sonra güncelle")
            }
            r.addView(up, LinearLayout.LayoutParams(0, dp(38), 1f).also { it.leftMargin = dp(6) })
            cd.addView(r, LinearLayout.LayoutParams(full, wrap).also { it.topMargin = dp(8) })
            box.addView(cd, LinearLayout.LayoutParams(full, wrap).also { it.topMargin = dp(8) })
        }
    }

    private fun buildHome(box: LinearLayout) {
        box.removeAllViews()
        var cur = box
        val full = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        fun lpTop(h: Int = wrap): LinearLayout.LayoutParams {
            val l = LinearLayout.LayoutParams(full, h)
            l.topMargin = dp(6)
            return l
        }
        fun head(t: String) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = rounded(0x22FFFFFF, 12)
            card.setPadding(dp(10), dp(6), dp(10), dp(10))
            val lab = TextView(this)
            lab.text = t
            lab.textSize = 11f
            lab.setTextColor(ACCENT)
            lab.typeface = android.graphics.Typeface.DEFAULT_BOLD
            card.addView(lab)
            val cl = LinearLayout.LayoutParams(full, wrap)
            cl.topMargin = dp(8)
            box.addView(card, cl)
            cur = card
        }
        fun step(label: String, key: String, def: Int, lo: Int, hi: Int, st: () -> Int) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val l = TextView(this)
            l.text = label
            l.textSize = 11f
            l.setTextColor(Color.WHITE)
            row.addView(l, LinearLayout.LayoutParams(0, wrap, 1f))
            val v = TextView(this)
            v.text = cfgI(key, def).toString()
            v.textSize = 13f
            v.gravity = Gravity.CENTER
            v.setTextColor(ACCENT)
            fun bump(sign: Int) {
                val nv = (cfgI(key, def) + sign * st()).coerceIn(lo, hi)
                prefs().edit().putString("cfg_" + key, nv.toString()).apply()
                v.text = nv.toString()
            }
            row.addView(mkBtn("−") { bump(-1) }, LinearLayout.LayoutParams(dp(38), dp(34)))
            row.addView(v, LinearLayout.LayoutParams(dp(64), wrap))
            row.addView(mkBtn("+") { bump(1) }, LinearLayout.LayoutParams(dp(38), dp(34)))
            cur.addView(row, lpTop())
        }
        fun buttons(items: List<Pair<String, () -> Unit>>) = cur.addView(btnRow(items, dp(36)), lpTop())

        val names = profileNames(prefs())
        val active = prefs().getString("profile_active", "") ?: ""
        // KONTROL MERKEZİ: satır satır. Sağdaki anahtar aç/kapatır, soldaki isme basınca o seçeneğin ayarları açılır/kapanır.
        val icons = mapOf(
            "potOn" to "pot", "recycleOn" to "recycle", "farmOn" to "skull", "impOn" to "imp", "angelOn" to "angel", "reviveOn" to "heart",
            "buffOn" to "buff", "randomOn" to "dice", "goldOn" to "star", "redOn" to "gem", "routeOn" to "route", "idleFarmOn" to "moon", "macroTpOn" to "pin", "protoOn" to "shield", "ownerOn" to "target", "popupOn" to "popup"
        )
        val cc = TextView(this)
        cc.text = "KONTROL MERKEZİ"
        cc.textSize = 11f
        cc.setTextColor(ACCENT)
        cc.typeface = android.graphics.Typeface.DEFAULT_BOLD
        cc.setPadding(0, dp(10), 0, 0)
        box.addView(cc)

        fun hint(t: String) {
            val h = TextView(this)
            h.text = t
            h.textSize = 11f
            h.setTextColor(TEXT_DIM)
            cur.addView(h, lpTop())
        }

        /** Kayıtlı işlem satırı: durum + kaydet / dene / sil. */
        fun macroLine(k: String, label: String) {
            hint(label + " kaydı: " + (if (macroHas(k)) "var" else "yok"))
            buttons(listOf<Pair<String, () -> Unit>>(
                "● Kaydet" to { startRecording(k) },
                "▶ Dene" to { if (macroHas(k)) runManual { playMacro(k) } else say("Önce kaydet: " + label) },
                "✕ Sil" to { prefs().edit().remove("macro_" + k).apply(); refreshHome() }
            ))
        }

        fun settingsFor(key: String) {
            when (key) {
                "potOn" -> {
                    step("Pot eşiği", "potMin", 20, 0, 999) { 5 }
                    step("Pot okuma (sn)", "potWatchSec", 60, 10, 600) { 10 }
                    macroLine("pothp", "HP pot al")
                    macroLine("potmp", "MP pot al")
                }
                "recycleOn" -> {
                    step("Her kaç kesimde Recycle (0=kapalı)", "recycleKillEvery", 0, 0, 50) { 1 }
                    step("Recycle aralığı (dk)", "recycleEvery", 20, 1, 240) { 5 }
                    macroLine("recycle", "Recycle")
                }
                "farmOn" -> {
                    step("Farm wire'ı", "farmWire", 3, 1, 3) { 1 }
                    step("Varış bekleme (sn)", "farmWait", 10, 0, 60) { 5 }
                }
                "reviveOn" -> {
                    step("Aynı boss'ta ölüm sınırı", "deathLimit", 3, 1, 20) { 1 }
                    step("Sınırda atlama süresi (dk)", "deathBlockMin", 15, 1, 120) { 5 }
                }
                "buffOn" -> {
                    val bs = mkBtn("Adım: " + prefs().getInt("buffStep", 1000) + "  ▸") { }
                    bs.setOnClickListener {
                        val c = prefs().getInt("buffStep", 1000)
                        prefs().edit().putInt("buffStep", when (c) { 100 -> 1000; 1000 -> 10000; else -> 100 }).apply()
                        refreshHome()
                    }
                    cur.addView(bs, lpTop(dp(34)))
                    step("Minimum ATK", "minAtk", 0, 0, 999999) { prefs().getInt("buffStep", 1000) }
                    step("Minimum DEF", "minDef", 0, 0, 999999) { prefs().getInt("buffStep", 1000) }
                    step("Buff wire'ı", "buffWire", 3, 1, 3) { 1 }
                    step("Buff kontrol aralığı (sn)", "buffWatchSec", 45, 10, 600) { 5 }
                }
                "randomOn" -> {
                    step("Random yarıçap (px) · tüm modlar", "radius", 200, 0, 1000) { 25 }
                    step("En fazla Random", "randomMax", 40, 1, 200) { 5 }
                    step("Random sayısı eşiği (0=kapalı)", "rndMin", 0, 0, 999) { 5 }
                    hint("Alt çubuktaki Random sayısı eşiğin altına düşünce kayıtlı 'Random al' işlemi oynatılır. Sayının yeri: Ayar > Envanter > rndCount.")
                    macroLine("rndbuy", "Random al")
                }
                "goldOn" -> {
                    buttons(listOf<Pair<String, () -> Unit>>("Harita: " + (goldMapName().ifEmpty { "seç" }) + "  ▸" to { pickGoldMap() }))
                    buttons(listOf<Pair<String, () -> Unit>>("Golden işaretle" to { runManual { goldMark() } }, "Golden listesi" to { toggleGoldWin() }))
                    step("Golden wire'ı", "goldWire", 3, 1, 3) { 1 }
                    step("Golden yeniden bakma (sn)", "goldRetry", 15, 1, 600) { 5 }
                }
                "redOn" -> {
                    buttons(listOf<Pair<String, () -> Unit>>("Harita: " + (redMapName().ifEmpty { "seç" }) + "  ▸" to { pickRedMap() }))
                    buttons(listOf<Pair<String, () -> Unit>>("Red işaretle" to { runManual { redMark() } }, "Red listesi" to { toggleRedWin() }))
                    step("Red boss wire'ı", "redWire", 1, 1, 3) { 1 }
                    step("Red yeniden bakma (sn)", "redRetry", 15, 1, 600) { 5 }
                }
                "routeOn" -> {
                    buttons(listOf<Pair<String, () -> Unit>>("Rota listesi" to { toggleRouteWin() }, "Canlı boss kaydet" to { runManual { doSaveAlive() } }))
                    buttons(listOf<Pair<String, () -> Unit>>("Gizli nokta" to { runManual { hiddenMark() } }, "Yoksay" to { runManual { ignoreMark() } }))
                }
                "idleFarmOn" -> {
                    step("Kesim yoksa (dk)", "idleFarmMin", 10, 0, 120) { 1 }
                    step("Farmda kal (dk)", "idleFarmStay", 10, 1, 120) { 1 }
                }
                "protoOn" -> {
                    hint("Otomasyon hatayla durursa uyarı çıkar; reddetmezsen Golden, Pot, Recycle ve Diril açılır, Farm/Rota/Red/Boşta farm kapanır ve otomasyon yeniden başlar. Golden haritasına kayıtlı ışınlanmayla gidilir.")
                    val gm = goldMapName()
                    val rec = gm.isNotEmpty() && macroHas("tp_" + gm)
                    hint("Harita: " + (gm.ifEmpty { "seçilmedi" }) + " · ışınlanma kaydı: " + (if (rec) "var" else "yok") + " · işaretli golden: " + goldSnapshot().size)
                    buttons(listOf<Pair<String, () -> Unit>>("Harita: " + (gm.ifEmpty { "seç" }) + "  ▸" to { pickGoldMap() }))
                    buttons(listOf<Pair<String, () -> Unit>>("Golden işaretle" to { runManual { goldMark() } }, "Golden listesi" to { toggleGoldWin() }))
                    step("Uyarı süresi (sn)", "protoSec", 10, 3, 60) { 1 }
                    step("Tekrar bekleme (dk)", "protoCooldownMin", 15, 1, 120) { 5 }
                    step("Kesim yoksa devreye gir (dk, 0=kapalı)", "protoIdleMin", 20, 0, 240) { 5 }
                    buttons(listOf<Pair<String, () -> Unit>>("Uyarıyla test" to { protoTest(true) }, "Hemen başlat" to { protoTest(false) }))
                    hint("Test gerçekten çalışır: ayarları değiştirir ve otomasyonu başlatır (otomasyon kapalıyken).")
                }
                "ownerOn" -> {
                    hint("Bossa saldırırken üstteki satırda senin adından başka bir oyuncu adı görünürse boss bırakılır, sıradakine geçilir. Adın: " + (prefs().getString("cfg_myName", "yxesky") ?: "yxesky") + " (uygulamadaki ayar ekranından değişir). Okuma yerini Ayar > Okuma bölgeleri'nde ownTL/ownBR ile işaretle.")
                    buttons(listOf<Pair<String, () -> Unit>>("Ayar (işaretle)" to { toggleMenu() }, "Oku (test)" to { runManual { ownerTest() } }))
                }
                "popupOn" -> {
                    hint("Etkinlik/reklam gibi pencereler ekranı kapatırsa bot takılır. Pencere açıkken 'Pencere ekle'ye bas, sonra kapatma (X) noktasına dokun: bot o noktanın rengini ve çevresini öğrenir, aynısını görünce kapatır. Her pencere için bir kez yap.")
                    hint("Kayıtlı pencere: " + popupList().size)
                    buttons(listOf<Pair<String, () -> Unit>>("Pencere ekle" to { popupMark() }, "Hepsini sil" to { prefs().edit().remove("popups").apply(); say("Pencere kayıtları silindi") }))
                    step("Tarama aralığı (sn)", "popupSec", 5, 2, 60) { 1 }
                    buttons(listOf<Pair<String, () -> Unit>>("Şimdi tara (test)" to { runManual { popupTest() } }))
                }
                "macroTpOn" -> hint("Açıkken bot kayıtlı haritalara kayıtla gider; kayıt yoksa chat'ten. Kayıtlar Bakım sekmesinde.")
                "angelOn" -> {
                    hint("Angel simgesi alt çubukta yoksa Bakım > Kayıtlı işlemler > Angel al kaydı oynatılır. Angel noktasını yuvanın tam ortasına işaretle.")
                    step("Simge eşiği (‰ parlak piksel)", "angelThr", 30, 1, 500) { 5 }
                    buttons(listOf<Pair<String, () -> Unit>>("Kontrol (test)" to { runManual { angelTest() } }))
                    macroLine("angelbuy", "Angel al")
                }
                else -> hint("Bu seçeneğin ek ayarı yok.")
            }
        }

        for (t in TOGGLES) {
            val key = t.first
            val isOn = on(key)
            val isOpen = homeOpen == key
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = card(CARD_BG, 14, if (isOpen) ACCENT else 0xFF332D40.toInt())
            // sol: simge + isim (ayarları aç/kapat)
            val left = LinearLayout(this)
            left.orientation = LinearLayout.HORIZONTAL
            left.gravity = Gravity.CENTER_VERTICAL
            left.setPadding(dp(12), 0, dp(6), 0)
            left.addView(IconView(this, icons[key] ?: "dice", if (isOn) Color.WHITE else 0xFF8D8798.toInt()), LinearLayout.LayoutParams(dp(28), dp(28)))
            val name = TextView(this)
            name.text = t.second
            name.textSize = 15f
            name.setTextColor(if (isOn) Color.WHITE else TEXT_DIM)
            left.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = dp(12) })
            val chev = TextView(this)
            chev.text = if (isOpen) "⌄" else "›"
            chev.textSize = 18f
            chev.setTextColor(TEXT_DIM)
            left.addView(chev)
            left.setOnClickListener {
                homeOpen = if (homeOpen == key) "" else key
                refreshHome()
            }
            row.addView(left, LinearLayout.LayoutParams(0, dp(52), 1f))
            // sağ: aç/kapat anahtarı
            val sw = FrameLayout(this)
            sw.background = rounded(if (isOn) 0xFF6A2FB0.toInt() else 0xFF3A3544.toInt(), 14)
            val knob = View(this)
            knob.background = rounded(Color.WHITE, 12)
            sw.addView(knob, FrameLayout.LayoutParams(dp(22), dp(22)).also {
                it.gravity = (if (isOn) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                it.leftMargin = dp(3)
                it.rightMargin = dp(3)
            })
            val swWrap = FrameLayout(this)
            swWrap.setPadding(dp(8), 0, dp(12), 0)
            swWrap.addView(sw, FrameLayout.LayoutParams(dp(48), dp(28), Gravity.CENTER))
            swWrap.setOnClickListener {
                prefs().edit().putBoolean("cfg_" + key, !on(key)).apply()
                updateToggles()
            }
            row.addView(swWrap, LinearLayout.LayoutParams(dp(68), dp(52)))
            box.addView(row, lpTop())
            if (isOpen) {
                val panelBox = LinearLayout(this)
                panelBox.orientation = LinearLayout.VERTICAL
                panelBox.background = card(0x22FFFFFF, 12, 0xFF2A2533.toInt())
                panelBox.setPadding(dp(12), dp(4), dp(12), dp(10))
                val lp2 = LinearLayout.LayoutParams(full, wrap)
                lp2.topMargin = dp(3)
                box.addView(panelBox, lp2)
                cur = panelBox
                settingsFor(key)
                cur = box
            }
        }

        head("BOSS ZAMANI")
        step("Bossa kaç sn kala git", "leadSec", 20, 0, 300) { 5 }
        applyFont(box)
    }

    private fun updateToggles() {
        refreshHome()
        if (scWin != null) scRender()
        for (t in TOGGLES) {
            val btn = toggleBtns[t.first] ?: continue
            val isOn = on(t.first)
            btn.text = t.second + (if (isOn) " ✓" else " ✗")
            btn.background = rounded(if (isOn) CARD_BG else MISSING_BG, 10)
        }
    }

    private fun applyPanel() {
        panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
        stopWin?.let {
            it.visibility = View.VISIBLE
            val act = autoRunning || busy
            it.background = card(if (act) BAD else CARD_BG, 12, if (act) 0xFFFF8A80.toInt() else 0xFF332D40.toInt())
            it.alpha = if (act) 1f else 0.55f
        }
        dockSc?.visibility = View.VISIBLE
        dockSv?.visibility = View.VISIBLE
        val mode = if (autoRunning) "▶" else if (busy) "⚙" else "⏱"
        handle?.text = (if (panelOpen) "▸" else "◂") + "\n" + mode
        placeBar()
        if (panelOpen) panel?.post { placeBar() }
    }

    /** Tutamacı ve paneli konumlar: panel tutamacın bulunduğu tarafa göre soluna ya da sağına açılır. */
    /** Büyük çizgi simgeli kutu: durum verilirse köşede mor anahtar gösterir. */
    private fun mkTile(icon: String, label: String, state: Boolean?, h: Int, act: () -> Unit): View {
        val fl = FrameLayout(this)
        val on = state == true
        fl.background = if (on) card(0xFF241536.toInt(), 14, 0xFF8E4DD6.toInt()) else card(CARD_BG, 14, 0xFF332D40.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val iv = IconView(this, icon, if (state == false) 0xFF8D8798.toInt() else Color.WHITE)
        col.addView(iv, LinearLayout.LayoutParams(dp(30), dp(30)))
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 11f
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.setTextColor(if (state == false) TEXT_DIM else Color.WHITE)
        col.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(3) })
        fl.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (state != null) {
            val pill = FrameLayout(this)
            pill.background = rounded(if (on) 0xFF7B33C8.toInt() else 0xFF3A3544.toInt(), 8)
            val knob = View(this)
            knob.background = rounded(Color.WHITE, 8)
            pill.addView(knob, FrameLayout.LayoutParams(dp(9), dp(9)).also {
                it.gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                it.leftMargin = dp(2)
                it.rightMargin = dp(2)
            })
            fl.addView(pill, FrameLayout.LayoutParams(dp(24), dp(14)).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = dp(5)
                it.rightMargin = dp(5)
            })
        }
        fl.setOnClickListener { act() }
        return fl
    }

    /** Tüm panelde tek yazı tipi: kısık, ince çizgili sans. */
    private fun applyFont(v: View) {
        if (v is TextView && v.tag != "serif") {
            val bold = v.typeface?.isBold == true
            v.typeface = android.graphics.Typeface.create("sans-serif-medium", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
    }

    private fun placeBar() {
        refreshScreen()
        val h = handle ?: return
        val p = panel ?: return
        val hw = dp(30)
        val hh = dp(56)
        val pw = panelW()
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
        val sl = stopLp
        val sv2 = stopWin
        if (sl != null && sv2 != null) {
            val sw2 = dp(82)
            val sh2 = dp(34)
            sl.x = (hx + hw - sw2).coerceIn(0, maxOf(0, screenW - sw2))
            sl.y = if (hy + hh + dp(4) + sh2 <= screenH) hy + hh + dp(4) else maxOf(0, hy - sh2 - dp(4))
            try {
                wm.updateViewLayout(sv2, sl)
            } catch (e: Exception) {
            }
            val dl = dockScLp
            val dv = dockSc
            if (dl != null && dv != null) {
                val below = sl.y > hy
                dl.x = sl.x
                dl.y = if (below) (if (sl.y + sh2 + dp(4) + sh2 <= screenH) sl.y + sh2 + dp(4) else maxOf(0, hy - sh2 - dp(4))) else maxOf(0, sl.y - sh2 - dp(4))
                try {
                    wm.updateViewLayout(dv, dl)
                } catch (e: Exception) {
                }
                val vl = dockSvLp
                val vv = dockSv
                if (vl != null && vv != null) {
                    vl.x = dl.x
                    vl.y = if (below) minOf(dl.y + sh2 + dp(4), maxOf(0, screenH - sh2)) else maxOf(0, dl.y - sh2 - dp(4))
                    try {
                        wm.updateViewLayout(vv, vl)
                    } catch (e: Exception) {
                    }
                }
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
        outer.background = framed(PANEL_BG, 18)
        outer.setPadding(dp(16), dp(20), dp(16), dp(18))

        // başlık
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "RICHINGEN"
        title.textSize = 19f
        title.letterSpacing = 0.12f
        title.gravity = Gravity.CENTER
        title.setTextColor(0xFFC9ADEB.toInt())
        title.tag = "serif"
        title.typeface = android.graphics.Typeface.SERIF
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(head)

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.WHITE)
        st.maxLines = 3
        st.setPadding(0, dp(6), 0, dp(2))
        st.text = "Hazır"
        statusView = st
        outer.addView(st)
        val stats = TextView(this)
        stats.textSize = 11f
        stats.setTextColor(ACCENT)
        stats.typeface = android.graphics.Typeface.DEFAULT_BOLD
        stats.setPadding(0, dp(2), 0, dp(2))
        statsView = stats
        stats.text = statsText()
        outer.addView(stats)

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
        val mainRowLp = mlp
        val tabBoss = LinearLayout(this)
        tabBoss.orientation = LinearLayout.VERTICAL
        val tabAuto = LinearLayout(this)
        tabAuto.orientation = LinearLayout.VERTICAL
        val tabTools = LinearLayout(this)
        tabTools.orientation = LinearLayout.VERTICAL
        val tabMark = LinearLayout(this)
        tabMark.orientation = LinearLayout.VERTICAL
        val tabMaint = LinearLayout(this)
        tabMaint.orientation = LinearLayout.VERTICAL
        fun sect(parent: LinearLayout, title: String): LinearLayout {
            val outerBox = LinearLayout(this)
            outerBox.orientation = LinearLayout.VERTICAL
            val lab = TextView(this)
            lab.textSize = 11f
            lab.setTextColor(TEXT_DIM)
            lab.typeface = android.graphics.Typeface.DEFAULT_BOLD
            lab.setPadding(0, dp(10), 0, dp(6))
            val body = LinearLayout(this)
            body.orientation = LinearLayout.VERTICAL
            val sk = "sect_" + title.take(24)
            var open = prefs().getBoolean(sk, !title.startsWith("HARİTA IŞINLANMA"))
            fun ap() {
                lab.text = (if (open) "▾  " else "▸  ") + title
                body.visibility = if (open) View.VISIBLE else View.GONE
            }
            ap()
            lab.setOnClickListener {
                open = !open
                prefs().edit().putBoolean(sk, open).apply()
                ap()
                panel?.post { placeBar() }
            }
            outerBox.addView(lab)
            outerBox.addView(body)
            parent.addView(outerBox)
            return body
        }
        val bw = mkBtn("📋  Bosslar penceresi") { toggleBossWin() }
        bw.textSize = 13f
        val bwlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36))
        bwlp.topMargin = dp(6)
        tabBoss.addView(bw, bwlp)
        val bf = mkBtn("◈  Boss ekranı (yüzen, taşınır)") { toggleBossFloat() }
        bf.textSize = 13f
        tabBoss.addView(bf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).also { it.topMargin = dp(6) })

        // boss listesi
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        listCol = col
        val sv = ScrollView(this)
        sv.addView(col)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.28f).toInt())
        slp.topMargin = dp(8)
        tabBoss.addView(sv, slp)

        // açık/kapalı düğmeleri, gruplanmış
        fun togGroup(parent: LinearLayout, title: String, keys: List<String>) {
            val lab = TextView(this)
            lab.text = title
            lab.textSize = 11f
            lab.setTextColor(TEXT_DIM)
            lab.setPadding(0, dp(8), 0, dp(2))
            parent.addView(lab)
            for (chunk in keys.chunked(3)) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for ((j, k) in chunk.withIndex()) {
                    val bt = mkBtn("") {
                        prefs().edit().putBoolean("cfg_" + k, !on(k)).apply()
                        updateToggles()
                    }
                    bt.textSize = 12f
                    val tl = LinearLayout.LayoutParams(0, dp(38), 1f)
                    if (j > 0) tl.leftMargin = dp(4)
                    row.addView(bt, tl)
                    toggleBtns[k] = bt
                }
                for (j in chunk.size until 3) row.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 1f))
                val rl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rl.topMargin = dp(4)
                parent.addView(row, rl)
            }
        }
        val pb = mkBtn("") { cycleProfile() }
        pb.textSize = 12f
        pb.setOnLongClickListener {
            val a = prefs().getString("profile_active", "") ?: ""
            if (a.isNotEmpty()) {
                saveProfile(prefs(), a)
                toast("Profil güncellendi: " + a)
            } else {
                toast("Önce uygulamadaki ayar ekranından bir profil kaydet")
            }
            true
        }
        profileBtn = pb
        val pbl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
        pbl.topMargin = dp(6)
        tabAuto.addView(pb, pbl)
        updateProfileBtn()
        togGroup(tabAuto, "MOD", listOf("farmOn", "goldOn", "redOn", "routeOn", "idleFarmOn", "randomOn"))
        togGroup(tabMaint, "BAKIM", listOf("potOn", "recycleOn", "buffOn"))
        togGroup(tabAuto, "SAVAŞ", listOf("impOn", "angelOn", "reviveOn"))

        val boxMaintBtns = sect(tabMaint, "ELLE")
        val boxGold = sect(tabMark, "GOLDEN")
        val boxRed = sect(tabMark, "RED BOSS")
        val boxRoute = sect(tabMark, "ROTA VE CANLI BOSS")
        val boxAutoBtns = sect(tabAuto, "ELLE")

        // araçlar (varsayılan gizli)
        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.VERTICAL
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
                "Pot kontrol" to { runManual { doPots() } },
                "Recycle" to { runManual { doRecycle() } }
            ),
            dp(36)
        )
        val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(6)
        tb.addView(r1, rlp)
        val themeBtn = mkBtn("") { }
        fun themeLabel() {
            themeBtn.text = "Tema: " + THEME_NAMES[themeIdx(prefs())] + "  ▸"
        }
        themeLabel()
        themeBtn.setOnClickListener {
            val n = (prefs().getInt("theme", 0) + 1) % THEMES.size
            prefs().edit().putInt("theme", n).apply()
            rebuildUi()
        }
        tb.addView(themeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).also { it.topMargin = dp(6) })
        tb.addView(mkBtn("Sayacı sıfırla") { resetStats() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).also { it.topMargin = dp(6) })
        boxMaintBtns.addView(r2, rlp)
        val boxMacro = sect(tabMaint, "KAYITLI İŞLEMLER (kaydedersen bot bunları oynatır)")
        fun macroRow(parent: LinearLayout, k: String, v: String) {
            macroBase[k] = v
            val mr = LinearLayout(this)
            mr.orientation = LinearLayout.HORIZONTAL
            mr.gravity = Gravity.CENTER_VERTICAL
            val ml = TextView(this)
            ml.textSize = 11f
            ml.setTextColor(Color.WHITE)
            macroLabels[k] = ml
            mr.addView(ml, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val b1 = mkBtn("●") { startRecording(k) }
            val b2 = mkBtn("▶") {
                if (macroHas(k)) runManual { playMacro(k) } else say("Önce kaydet: " + v)
            }
            val b3 = mkBtn("✕") {
                prefs().edit().remove("macro_" + k).apply()
                refreshMacroLabels()
            }
            for (b in listOf(b1, b2, b3)) {
                b.textSize = 12f
                val bl = LinearLayout.LayoutParams(dp(40), dp(34))
                bl.leftMargin = dp(4)
                mr.addView(b, bl)
            }
            parent.addView(mr, rlp)
        }
        for ((k, v) in macroNames) macroRow(boxMacro, k, v)
        val boxTp = sect(tabMaint, "HARİTA IŞINLANMA KAYITLARI (Merkez'deki \"Kayıtlı ışın\" açıkken bot haritaya bu kayıtla gider; kayıt yoksa ya da kapalıysa chat'ten gider)")
        for (m in maps()) macroRow(boxTp, "tp_" + m.name, m.name)
        refreshMacroLabels()

        // buff eşikleri panelden ayarlanır
        val boxBuff = sect(tabMaint, "BUFF EŞİĞİ (ATK ve DEF ikisi de altındaysa buff'a gidilir)")
        fun stepRow(label: String, key: String, def: Int, lo: Int, hi: Int, into: LinearLayout = boxBuff, stepOf: () -> Int) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val l = TextView(this)
            l.text = label
            l.textSize = 11f
            l.setTextColor(Color.WHITE)
            row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(this)
            v.text = cfgI(key, def).toString()
            v.textSize = 13f
            v.gravity = Gravity.CENTER
            v.setTextColor(ACCENT)
            stepViews[key] = Pair(v, def)
            fun bump(sign: Int) {
                val nv = (cfgI(key, def) + sign * stepOf()).coerceIn(lo, hi)
                prefs().edit().putString("cfg_" + key, nv.toString()).apply()
                v.text = nv.toString()
            }
            row.addView(mkBtn("−") { bump(-1) }, LinearLayout.LayoutParams(dp(38), dp(34)))
            row.addView(v, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(mkBtn("+") { bump(1) }, LinearLayout.LayoutParams(dp(38), dp(34)))
            into.addView(row, rlp)
        }
        val stepBtn = mkBtn("") { }
        fun stepLabel() {
            stepBtn.text = "Adım: " + prefs().getInt("buffStep", 1000) + "  ▸"
        }
        stepLabel()
        stepBtn.setOnClickListener {
            val cur = prefs().getInt("buffStep", 1000)
            val nx = when (cur) {
                100 -> 1000
                1000 -> 10000
                else -> 100
            }
            prefs().edit().putInt("buffStep", nx).apply()
            stepLabel()
        }
        boxBuff.addView(stepBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)).also { it.topMargin = dp(6) })
        stepRow("Minimum ATK", "minAtk", 0, 0, 999999) { prefs().getInt("buffStep", 1000) }
        stepRow("Minimum DEF", "minDef", 0, 0, 999999) { prefs().getInt("buffStep", 1000) }
        stepRow("Buff wire'ı", "buffWire", 3, 1, 3) { 1 }
        stepRow("Buff bekleme sınırı (sn)", "buffWait", 90, 15, 600) { 15 }
        val boxPot = sect(tabMaint, "POT VE RECYCLE")
        stepRow("Pot eşiği", "potMin", 20, 0, 999, boxPot) { 5 }
        stepRow("Pot okuma (sn)", "potWatchSec", 60, 10, 600, boxPot) { 10 }
        stepRow("Random eşiği (0=kapalı)", "rndMin", 0, 0, 999, boxPot) { 5 }
        stepRow("Her kaç kesimde Recycle (0=kapalı)", "recycleKillEvery", 0, 0, 50, boxPot) { 1 }
        stepRow("Recycle aralığı (dk)", "recycleEvery", 20, 1, 240, boxPot) { 5 }
        val boxIdle = sect(tabMaint, "BOŞTA FARM")
        stepRow("Kesim yoksa (dk)", "idleFarmMin", 10, 0, 120, boxIdle) { 1 }
        stepRow("Farmda kal (dk)", "idleFarmStay", 10, 1, 120, boxIdle) { 1 }
        boxAutoBtns.addView(
            btnRow(listOf<Pair<String, () -> Unit>>("Farma git" to { runManual { doFarm() } }), dp(36)),
            rlp
        )
        val r3 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "Golden işaretle" to { runManual { goldMark() } },
                "Golden listesi" to { toggleGoldWin() }
            ),
            dp(36)
        )
        boxGold.addView(r3, rlp)
        val r4 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "⏱ Zaman kaydet" to { addStamp() },
                "Kayıt listesi" to { toggleStampWin() },
                "Kronometre" to { toggleStopwatch() },
                "Kısayollar" to { toggleShortcuts() }
            ),
            dp(36)
        )
        tb.addView(r4, rlp)
        val r5 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "Red işaretle" to { runManual { redMark() } },
                "Red listesi" to { toggleRedWin() }
            ),
            dp(36)
        )
        boxRed.addView(r5, rlp)
        val r6 = btnRow(
            listOf<Pair<String, () -> Unit>>(
                "Canlı boss kaydet" to { runManual { doSaveAlive() } },
                "Rota listesi" to { toggleRouteWin() }
            ),
            dp(36)
        )
        boxRoute.addView(r6, rlp)
        boxRoute.addView(
            btnRow(
                listOf<Pair<String, () -> Unit>>(
                    "Gizli nokta" to { runManual { hiddenMark() } },
                    "Yoksay işaretle" to { runManual { ignoreMark() } }
                ),
                dp(36)
            ),
            rlp
        )
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
        boxAutoBtns.addView(rr, rlp)
        val gq = LinearLayout(this)
        gq.orientation = LinearLayout.HORIZONTAL
        gq.gravity = Gravity.CENTER_VERTICAL
        val gql = TextView(this)
        gql.text = "Golden Random yarıçap (px)"
        gql.textSize = 11f
        gql.setTextColor(Color.WHITE)
        gq.addView(gql, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gqv = TextView(this)
        gqv.text = cfgI("goldRadius", 150).toString()
        gqv.textSize = 13f
        gqv.gravity = Gravity.CENTER
        gqv.setTextColor(ACCENT)
        goldRadiusView = gqv
        gq.addView(mkBtn("−") { changeGoldRadius(-25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        gq.addView(gqv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        gq.addView(mkBtn("+") { changeGoldRadius(25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        val rq = LinearLayout(this)
        rq.orientation = LinearLayout.HORIZONTAL
        rq.gravity = Gravity.CENTER_VERTICAL
        val rql = TextView(this)
        rql.text = "Red boss Random yarıçap (px)"
        rql.textSize = 11f
        rql.setTextColor(Color.WHITE)
        rq.addView(rql, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rqv = TextView(this)
        rqv.text = cfgI("redRadius", 150).toString()
        rqv.textSize = 13f
        rqv.gravity = Gravity.CENTER
        rqv.setTextColor(ACCENT)
        redRadiusView = rqv
        rq.addView(mkBtn("−") { changeRedRadius(-25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        rq.addView(rqv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        rq.addView(mkBtn("+") { changeRedRadius(25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        val tq = LinearLayout(this)
        tq.orientation = LinearLayout.HORIZONTAL
        tq.gravity = Gravity.CENTER_VERTICAL
        val tql = TextView(this)
        tql.text = "Rota Random yarıçap (px)"
        tql.textSize = 11f
        tql.setTextColor(Color.WHITE)
        tq.addView(tql, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val tqv = TextView(this)
        tqv.text = cfgI("routeRadius", 150).toString()
        tqv.textSize = 13f
        tqv.gravity = Gravity.CENTER
        tqv.setTextColor(ACCENT)
        routeRadiusView = tqv
        tq.addView(mkBtn("−") { changeRouteRadius(-25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        tq.addView(tqv, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        tq.addView(mkBtn("+") { changeRouteRadius(25) }, LinearLayout.LayoutParams(dp(38), dp(34)))
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
        boxMaintBtns.addView(gr, rlp)
        toolsBox = tb
        // panel boyutu ve saydamlık
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        val sl = TextView(this)
        sl.text = "Panel boyutu"
        sl.textSize = 11f
        sl.setTextColor(Color.WHITE)
        sr.addView(sl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sizeNames = listOf("Küçük", "Orta", "Büyük")
        for (i in sizeNames.indices) {
            val sb2 = mkBtn(sizeNames[i]) { setUiSize(i) }
            sb2.textSize = 11f
            sizeBtns.add(sb2)
            val sbl = LinearLayout.LayoutParams(dp(54), dp(34))
            sbl.leftMargin = dp(3)
            sr.addView(sb2, sbl)
        }
        tb.addView(sr, rlp)
        val ar = LinearLayout(this)
        ar.orientation = LinearLayout.HORIZONTAL
        ar.gravity = Gravity.CENTER_VERTICAL
        val al = TextView(this)
        al.text = "Saydamlık (%)"
        al.textSize = 11f
        al.setTextColor(Color.WHITE)
        ar.addView(al, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val av = TextView(this)
        av.text = uiAlpha().toString()
        av.textSize = 13f
        av.gravity = Gravity.CENTER
        av.setTextColor(ACCENT)
        alphaView = av
        ar.addView(mkBtn("−") { changeAlpha(-10) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        ar.addView(av, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        ar.addView(mkBtn("+") { changeAlpha(10) }, LinearLayout.LayoutParams(dp(38), dp(34)))
        tb.addView(ar, rlp)
        tabTools.addView(tb)

        // sekmeler
        val tabHome = LinearLayout(this)
        tabHome.orientation = LinearLayout.VERTICAL
        homeBox = tabHome
        val homeScroll = ScrollView(this)
        homeScroll.addView(tabHome)
        val tabPlans = LinearLayout(this)
        tabPlans.orientation = LinearLayout.VERTICAL
        plansBox = tabPlans
        val plansScroll = ScrollView(this)
        plansScroll.addView(tabPlans)
        val tabNames = listOf("Merkez", "Planlar", "Boss", "İşaret", "Bakım", "Araç")
        fun scrolled(v: LinearLayout): ScrollView {
            val sv = ScrollView(this)
            sv.addView(v)
            return sv
        }
        val markScroll = scrolled(tabMark)
        val maintScroll = scrolled(tabMaint)
        val toolsScroll = scrolled(tabTools)
        val tabViews = listOf<View>(homeScroll, plansScroll, tabBoss, markScroll, maintScroll, toolsScroll)
        val tabBtns = ArrayList<TextView>()
        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        fun showTab(i: Int) {
            for (k in tabViews.indices) {
                tabViews[k].visibility = if (k == i) View.VISIBLE else View.GONE
                tabBtns[k].setTextColor(if (k == i) ACCENT else TEXT_DIM)
                tabBtns[k].background = if (k == i) card(CARD_BG, 8, 0xFF6A3FA8.toInt()) else rounded(0x00000000, 8)
            }
            prefs().edit().putInt("tab", i).apply()
            outer.post { placeBar() }
        }
        for ((k, n) in tabNames.withIndex()) {
            val t = TextView(this)
            t.text = n
            t.textSize = 12f
            t.gravity = Gravity.CENTER
            t.typeface = android.graphics.Typeface.DEFAULT_BOLD
            t.setOnClickListener { showTab(k) }
            tabBtns.add(t)
            tabBar.addView(t, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        val tbl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tbl.topMargin = dp(6)
        outer.addView(tabBar, tbl)
        outer.addView(homeScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.46f).toInt()))
        outer.addView(plansScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.46f).toInt()))
        outer.addView(tabBoss)
        val tabH = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.46f).toInt())
        outer.addView(markScroll, tabH)
        outer.addView(maintScroll, LinearLayout.LayoutParams(tabH))
        outer.addView(toolsScroll, LinearLayout.LayoutParams(tabH))
        buildHome(tabHome)
        buildPlans(tabPlans)
        if (!prefs().getBoolean("tabs6", false)) prefs().edit().putInt("tab", 0).putBoolean("tabs6", true).apply()
        showTab(prefs().getInt("tab", 0).coerceIn(0, 5))
        outer.addView(mainRow, mainRowLp)
        outer.alpha = uiAlpha() / 100f
        updateSizeBtns()

        applyFont(outer)
        outer.visibility = View.GONE
        val pl = overlayParams(panelW())
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

        // Otomasyon sürerken tutamacın yanında hep dokunulabilir Durdur düğmesi
        val sw = TextView(this)
        sw.text = "■ Durdur"
        sw.textSize = 12f
        sw.typeface = android.graphics.Typeface.DEFAULT_BOLD
        sw.gravity = Gravity.CENTER
        sw.setTextColor(Color.WHITE)
        sw.background = rounded(BAD, 12)
        sw.setPadding(dp(8), dp(6), dp(8), dp(6))
        sw.setOnClickListener { stopJob() }
        val slp2 = overlayParams(dp(82))
        wm.addView(sw, slp2)
        stopWin = sw
        stopLp = slp2
        applyFont(sw)

        // Kısayollar için tutamacın dışında duran düğme (Durdur gibi)
        val kb = TextView(this)
        kb.text = "☰ Kısayol"
        kb.textSize = 12f
        kb.typeface = android.graphics.Typeface.DEFAULT_BOLD
        kb.gravity = Gravity.CENTER
        kb.setTextColor(Color.WHITE)
        kb.background = card(onFill(), 12, ACCENT)
        kb.setPadding(dp(8), dp(6), dp(8), dp(6))
        kb.setOnClickListener { toggleShortcuts() }
        val klp = overlayParams(dp(82))
        wm.addView(kb, klp)
        dockSc = kb
        dockScLp = klp
        applyFont(kb)

        // Kaydet: panel kapalıyken de tek dokunuşla boss kaydı
        val kv = TextView(this)
        kv.text = "● Kaydet"
        kv.textSize = 12f
        kv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        kv.gravity = Gravity.CENTER
        kv.setTextColor(0xFF04200F.toInt())
        kv.background = rounded(OK, 12)
        kv.setPadding(dp(8), dp(6), dp(8), dp(6))
        kv.setOnClickListener { runManual { doSave() } }
        val vlp = overlayParams(dp(82))
        wm.addView(kv, vlp)
        dockSv = kv
        dockSvLp = vlp
        applyFont(kv)
        updateUi()
    }

    private fun rebuildList() {
        bfRender()
        if (bossWin != null) {
            val gw = goldWinOpen
            val sw = stampWinOpen
            val rw = redWinOpen
            val tw = routeWinOpen
            closeBossWin()
            if (sw) toggleStampWin() else if (tw) toggleRouteWin() else if (rw) toggleRedWin() else if (gw) toggleGoldWin() else toggleBossWin()
        }
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

    private val GROUP_COLORS = intArrayOf(
        0xFFB48AE0.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFF81C784.toInt(),
        0xFFFF6B81.toInt(), 0xFFFFF176.toInt(), 0xFF4DB6AC.toInt(), 0xFFF06292.toInt()
    )

    /** Aynı harita + wire'daki bossların rengi aynıdır (haritaya ve wire'a göre sıralı gruplara palet sırayla verilir). */
    private fun groupColor(e: Entry): Int {
        val keys = snapshot().map { key(it.map) + "|" + it.wire }.distinct().sorted()
        val i = keys.indexOf(key(e.map) + "|" + e.wire)
        return GROUP_COLORS[(if (i < 0) 0 else i) % GROUP_COLORS.size]
    }

    private fun rowFor(e: Entry): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rounded(CARD_BG, 10)

        val strip = View(this)
        strip.setBackgroundColor(groupColor(e))
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
        updateStats()
        bfTick()
        val now = System.currentTimeMillis()
        val lead = cfgI("leadSec", 20)
        for (e in snapshot()) {
            val tv = winTimers[e.id] ?: continue
            val left = (e.endMs - now) / 1000
            tv.text = if (left > 0) fmt(left) else "DOĞDU +" + fmt(left)
            tv.setTextColor(if (left <= lead) OK else if (left <= 120) WARN else Color.WHITE)
        }
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
        }
    }


    // ------------------------------------------------------------------ kısa adlar ve boss penceresi

    /** "Raklion 2" -> "Rak2", "Divine Realm" -> "DR", "Purgatory of Misery 1" -> "PM1". */
    private fun shortMap(m: String): String {
        val parts = m.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val num = parts.lastOrNull { p -> p.all { it.isDigit() } } ?: ""
        val words = parts.filter { p -> !p.all { it.isDigit() } && p.lowercase() != "of" }
        val base = if (words.size >= 2) words.joinToString("") { it.take(1).uppercase() } else (words.firstOrNull()?.take(3) ?: m)
        return base + num
    }

    private fun shortTitle(e: Entry): String {
        val w = if (mapInfo(e.map).wires > 1) " · W" + e.wire else ""
        return shortMap(e.map) + w + " · #" + e.idx
    }

    private fun closeBossWin() {
        val w = bossWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        bossWin = null
        goldWinOpen = false
        redWinOpen = false
        routeWinOpen = false
        stampWinOpen = false
        winTimers.clear()
    }

    /** Tüm kayıtlı bosslar için ayrı büyük pencere; satıra dokununca bossa gidip keser. */
    private fun toggleBossWin() {
        if (bossWin != null) {
            closeBossWin()
            return
        }
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = framed(PANEL_BG, 18)
        outer.setPadding(dp(18), dp(14), dp(18), dp(16))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = "Bosslar (" + snapshot().size + ")"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(mkBtn("Kapat ✕") { closeBossWin() }, LinearLayout.LayoutParams(dp(90), dp(36)))
        outer.addView(head)
        outer.addView(mkBtn("✎  Canlı boss kaydet") { runManual { doSaveAlive() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).also { it.topMargin = dp(6) })
        val hint = TextView(this)
        hint.text = "Satıra dokun: bossa git ve kes. Silmek için ✕'e uzun bas."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(4), 0, dp(4))
        outer.addView(hint)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = snapshot().sortedBy { it.endMs }
        if (list.isEmpty()) {
            val e0 = TextView(this)
            e0.text = "Henüz kayıt yok."
            e0.setTextColor(TEXT_DIM)
            col.addView(e0)
        }
        for (e in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 10)
            row.setPadding(0, 0, dp(8), 0)
            val gstrip = View(this)
            gstrip.setBackgroundColor(groupColor(e))
            row.addView(gstrip, LinearLayout.LayoutParams(dp(6), LinearLayout.LayoutParams.MATCH_PARENT))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.setPadding(dp(10), dp(8), 0, dp(8))
            val n1 = TextView(this)
            n1.text = (if (e.id == currentId) "▶ " else "") + shortTitle(e)
            n1.textSize = 15f
            n1.typeface = android.graphics.Typeface.DEFAULT_BOLD
            n1.setTextColor(if (e.id == currentId) ACCENT else Color.WHITE)
            left.addView(n1)
            val n2 = TextView(this)
            n2.text = title(e)
            n2.textSize = 10f
            n2.setTextColor(TEXT_DIM)
            left.addView(n2)
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val tm = TextView(this)
            tm.textSize = 18f
            tm.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tm.setTextColor(Color.WHITE)
            winTimers[e.id] = tm
            row.addView(tm)
            val del = TextView(this)
            del.text = "✕"
            del.textSize = 16f
            del.setTextColor(TEXT_DIM)
            del.setPadding(dp(14), dp(6), dp(6), dp(6))
            del.setOnLongClickListener {
                synchronized(lock) { entries.remove(e) }
                saveEntries()
                rebuildList()
                true
            }
            row.addView(del)
            row.setOnClickListener {
                if (autoRunning) {
                    say("Otomasyon açık. Önce otomasyonu durdur.")
                } else {
                    closeBossWin()
                    runManual { goAndKill(e) }
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(row, lp)
        }
        val w = minOf(dp(440), (screenW * 0.6f).toInt())
        val lp = overlayParams(w)
        lp.height = (screenH * 0.82f).toInt()
        lp.x = (screenW - w) / 2
        lp.y = (screenH - lp.height) / 2
        wm.addView(outer, lp)
        bossWin = outer
        tick()
    }


    // ------------------------------------------------------------------ golden (altın) canavarlar

    private fun isGold(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r >= 150 && g >= 110 && b <= 120 && r - b >= 70 && r >= g && g > b
    }

    /** Haritadaki canlı red boss ikonu: parlak kırmızı/turuncu-kırmızı (sarıdan ayrıdır: yeşil bileşeni düşük). */
    private fun isRedIcon(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r >= 165 && b <= 90 && g in 20..100 && r - g >= 100 && r - b >= 100
    }

    private fun isMiniGold(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 150 && g > 120 && b < 80 && r - b > 90
    }

    /** Kutu çekirdekli aşındırma/genişletme (erode=true: aşındır). Yazıların ince çizgilerini silmek için. */
    private fun boxMorph(src: BooleanArray, w: Int, h: Int, r: Int, erode: Boolean): BooleanArray {
        val full = 2 * r + 1
        val tmp = BooleanArray(w * h)
        val pre = IntArray(maxOf(w, h) + 1)
        for (y in 0 until h) {
            val base = y * w
            pre[0] = 0
            for (x in 0 until w) pre[x + 1] = pre[x] + (if (src[base + x]) 1 else 0)
            for (x in 0 until w) {
                val a = x - r
                val b = x + r
                tmp[base + x] = if (erode) (a >= 0 && b < w && pre[b + 1] - pre[a] == full)
                else (pre[minOf(w, b + 1)] - pre[maxOf(0, a)] > 0)
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            pre[0] = 0
            for (y in 0 until h) pre[y + 1] = pre[y] + (if (tmp[y * w + x]) 1 else 0)
            for (y in 0 until h) {
                val a = y - r
                val b = y + r
                out[y * w + x] = if (erode) (a >= 0 && b < h && pre[b + 1] - pre[a] == full)
                else (pre[minOf(h, b + 1)] - pre[maxOf(0, a)] > 0)
            }
        }
        return out
    }

    /**
     * Açık haritada CANLI golden kafatası ikonları. Parlak sarı pikseller alınır (ölü ikonlar soluk olduğu için girmez),
     * aşındırma yazıların ince çizgilerini siler, kalan kalın lekeler kafatasıdır; birbirine yakın parçalar birleştirilir.
     * Yazılar kafatasına değse de bulunur.
     */
    private fun scanSkulls(b: Bitmap, pred: (Int) -> Boolean = { isGold(it) }): List<PointF> {
        val bw = b.width
        val bh = b.height
        val s = bw / 2560f
        val x0 = (MAP_X0 * bw).toInt()
        val x1 = (MAP_X1 * bw).toInt()
        val y0 = (MAP_Y0 * bh).toInt()
        val y1 = (MAP_Y1 * bh).toInt()
        val w = x1 - x0
        val h = y1 - y0
        val mask = BooleanArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, x0, y0 + y, w, 1)
            for (x in 0 until w) if (pred(row[x])) mask[y * w + x] = true
        }
        val r = maxOf(1, Math.round(2 * s))
        val open = boxMorph(boxMorph(mask, w, h, r, true), w, h, r, false)
        val stack = IntArray(w * h)
        val cx = ArrayList<Float>()
        val cy = ArrayList<Float>()
        val cn = ArrayList<Int>()
        for (start in open.indices) {
            if (!open[start]) continue
            var sp = 0
            stack[sp++] = start
            open[start] = false
            var n = 0
            var sx = 0L
            var sy = 0L
            while (sp > 0) {
                val cur = stack[--sp]
                val px = cur % w
                val py = cur / w
                n++
                sx += px
                sy += py
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (open[ni]) {
                        open[ni] = false
                        stack[sp++] = ni
                    }
                }
            }
            if (n >= 40 * s * s) {
                cx.add(sx.toFloat() / n)
                cy.add(sy.toFloat() / n)
                cn.add(n)
            }
        }
        // bir kafatası genelde iki parçaya ayrılır (göz çukurları): yakın parçaları birleştir
        val out = ArrayList<PointF>()
        val wt = ArrayList<Int>()
        for (i in cx.indices) {
            var j = -1
            for (k in out.indices) {
                val dx = out[k].x - (x0 + cx[i])
                val dy = out[k].y - (y0 + cy[i])
                if (Math.sqrt((dx * dx + dy * dy).toDouble()) < 30 * s) {
                    j = k
                    break
                }
            }
            if (j < 0) {
                out.add(PointF(x0 + cx[i], y0 + cy[i]))
                wt.add(cn[i])
            } else {
                val t = wt[j] + cn[i]
                out[j] = PointF((out[j].x * wt[j] + (x0 + cx[i]) * cn[i]) / t, (out[j].y * wt[j] + (y0 + cy[i]) * cn[i]) / t)
                wt[j] = t
            }
        }
        return out
    }

    /** Mini haritanın ortasında parlak sarı golden ikonu var mı (Goldingen'deki ölüm tespiti). */
    private fun miniGoldAlive(b: Bitmap): Boolean {
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
        if (x1 <= x0 || y1 <= y0) return false
        val w = x1 - x0 + 1
        val row = IntArray(w)
        var hit = 0
        for (y in y0..y1) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (isMiniGold(row[x])) hit++
        }
        return hit.toFloat() / (w * (y1 - y0 + 1)) >= 0.0012f
    }

    private fun loadGolds() {
        synchronized(lock) {
            golds.clear()
            try {
                val a = JSONArray(prefs().getString("golds", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    golds.add(Gold(o.getLong("id"), o.optInt("idx", golds.size + 1),
                        o.getDouble("px").toFloat(), o.getDouble("py").toFloat()))
                    if (!prefs().contains("goldMap") && o.has("map")) prefs().edit().putString("goldMap", o.getString("map")).apply()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun saveGolds() {
        val a = JSONArray()
        synchronized(lock) {
            for (g in golds) {
                val o = JSONObject()
                o.put("id", g.id)
                o.put("idx", g.idx)
                o.put("px", g.px.toDouble())
                o.put("py", g.py.toDouble())
                a.put(o)
            }
        }
        prefs().edit().putString("golds", a.toString()).apply()
    }

    private fun goldSnapshot(): List<Gold> = synchronized(lock) { ArrayList(golds) }

    private fun goldMapName(): String = prefs().getString("goldMap", "") ?: ""

    private fun goldTitle(g: Gold): String {
        val m = goldMapName()
        return (if (m.isEmpty()) "Golden" else shortMap(m)) + " · G#" + g.idx
    }

    /**
     * Golden haritasına gelir. Zaten o haritadaysa ve wire doğruysa bir şey yapmaz. Değilse farm/buff gibi:
     * önce seçilen wire'a geçer, sonra chat'ten golden bağlantısına tıklar, varınca wire'ı doğrular.
     */
    private fun ensureGoldMap() {
        var gm = goldMapName()
        val gw = cfgI("goldWire", 3)
        closeMapIfOpen()
        var cur = readMapWireRetry()
        if (gm.isEmpty()) {
            gm = cur.first
            prefs().edit().putString("goldMap", gm).apply()
            say("Golden haritası: " + gm + " (şu an bulunduğun harita)")
        }
        if (key(cur.first) == key(gm) && (lastLink.isEmpty() || lastLink == "goldLink" || lastLink == "redLink")) {
            if (mapInfo(gm).wires > 1 && cur.second != gw) {
                say("Golden için Wire " + gw + " seçiliyor")
                if (!switchTo(gw)) fail("Wire değişmedi (istenen Wire " + gw + ")")
            }
            return
        }
        val useTp = tpMacroOn(gm)
        if (!useTp && !hasPt("goldLink")) fail("Önce 'Ayar' menüsünden işaretle: goldLink (ya da Bakım'dan " + gm + " için ışınlanma kaydı yap ve Kayıtlı ışın'ı aç)")
        if (mapInfo(cur.first).wires > 1 && cur.second != gw) {
            say("Golden için Wire " + gw + " seçiliyor")
            switchTo(gw)
        }
        say("Golden haritasına gidiliyor")
        if (useTp) {
            teleport(gm)
            lastLink = "goldLink"
        } else {
            openChatLink("goldLink")
            pause(cfgI("mapTravel", 10) * 1000)
        }
        cur = readMapWireRetry()
        if (key(cur.first) != key(gm)) fail("Golden haritasına ışınlanılamadı, şu an: " + cur.first)
        if (mapInfo(gm).wires > 1 && cur.second != gw) {
            if (!switchTo(gw)) fail("Wire değişmedi (istenen Wire " + gw + ")")
        }
    }

    private val goldSkip = ArrayList<Pair<PointF, Long>>()
    @Volatile private var goldIdleUntil = 0L
    @Volatile private var goldHere = false

    /** Otomasyondan: bir golden turu; hiç golden bulunamazsa bir süre golden'a bakmaz. */
    private fun runGold() {
        redHere = false
        routeAt = false
        intrOn = true
        try {
            if (goldCycle(null)) fails = 0
            else goldIdleUntil = System.currentTimeMillis() + cfgI("goldRetry", 15) * 1000L
        } finally {
            intrOn = false
        }
    }

    /**
     * Bir golden turu: haritayı aç, canlı golden'ları tara, en yakınını seç (Random açıksa yarıçap içine girene kadar Random),
     * yürü, oto atak aç, ölünce true. Golden hiç görünmüyorsa false. İşaretli nokta varsa yalnızca onlara gidilir.
     * only verilirse sadece o işaretli noktaya gidilir (listeden dokunuş).
     */
    private fun goldCycle(only: Gold?): Boolean {
        if (farming) {
            setAuto(false)
            farming = false
        }
        if (!goldHere) {
            ensureGoldMap()
            goldHere = true
        }
        var mb = ensureMap()
        refreshScreen()
        val sc = mb.width / 2560f
        val marks = (if (only != null) listOf(only) else goldSnapshot()).map { PointF(it.px * screenW, it.py * screenH) }
        val radius = cfgI("goldRadius", 150).toFloat()
        val useRandom = on("goldRandomOn") && radius > 0f && hasPt("random")
        val maxTry = cfgI("randomMax", 40)
        var tries = 0
        var best: PointF? = null
        var bd = Float.MAX_VALUE
        while (true) {
            val now = System.currentTimeMillis()
            goldSkip.removeAll { it.second < now }
            val me = findPlayer(mb)
            best = null
            bd = Float.MAX_VALUE
            for (k in scanSkulls(mb)) {
                if (marks.isNotEmpty() && marks.none { dist(it, k) <= 45f * sc }) continue
                if (goldSkip.any { dist(it.first, k) <= 30f * sc }) continue
                val d = if (me != null) dist(me, k) else 0f
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            if (best == null) {
                if (useRandom && tries < maxTry) {
                    tries++
                    say("Golden görünmüyor, Random " + tries + "/" + maxTry)
                    tapPoint("random")
                    gameAuto = false
                    pause((cfgF("randomWait", 2.5f) * 1000).toInt())
                    mb = ensureMap()
                    continue
                }
                tapPoint("closeMap")
                pause(600)
                say("Golden görünmüyor")
                return false
            }
            if (!useRandom || me == null || bd <= radius || tries >= maxTry) break
            tries++
            say("En yakın golden " + bd.toInt() + " px uzakta, Random " + tries + "/" + maxTry)
            tapPoint("random")
            gameAuto = false
            pause((cfgF("randomWait", 2.5f) * 1000).toInt())
            mb = ensureMap()
        }
        val tgt = best!!
        val label = "Golden (" + (if (bd < Float.MAX_VALUE / 2) bd.toInt().toString() + " px" else "?") + ")"
        if (stillOnTarget(bd)) {
            say(label + "\nBossun üstündeyiz, oto atak sürüyor")
        } else {
            try {
                approach(mb, tgt.x / screenW, tgt.y / screenH, label, 0f, false)
            } catch (e: StepFail) {
                goldSkip.add(Pair(tgt, System.currentTimeMillis() + 60000L))
                return true
            }
            gameAuto = false
            setAuto(true)
            if (on("angelOn") && hasPt("angel")) {
                tapPoint("angel")
                pause(300)
            }
        }
        say(label + "\nOto atak açık, golden bekleniyor")
        fightMaint()
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        var lastMapChk = t0
        while (true) {
            pause(150)
            if (autoRunning && nextDue() != null) {
                setAuto(false)
                say("Boss zamanı geldi, golden bırakıldı")
                return true
            }
            val b = shot() ?: continue
            if (fightBuff(b)) return true
            val oth = ownerTakenCheck(b)
            if (oth != null) {
                setAuto(false)
                ev("Boss başka oyuncuda (" + oth + "), sıradakine geçiliyor", true, 3)
                say("Boss başka oyuncuda: " + oth)
                goldSkip.add(Pair(tgt, System.currentTimeMillis() + 180000L))
                return true
            }
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniGoldAlive(b)) {
                seen = true
                dead = 0
                say(label + "\nGolden canlı, savaşılıyor")
            } else if (seen) {
                dead++
                say(label + "\nGolden öldü mü? " + dead + "/3")
                if (dead >= 3) break
            } else if (now - t0 > 8000 && now - lastMapChk > 10000) {
                lastMapChk = now
                val mb2 = ensureMap()
                var still = false
                for (k in scanSkulls(mb2)) if (dist(k, tgt) < 35f * sc) still = true
                val me = findPlayer(mb2)
                val onTop = me != null && dist(me, tgt) <= cfgI("arrive", 45) * 1.5f
                tapPoint("closeMap")
                pause(700)
                if (!still && !onTop) break
            }
            if (now - t0 > limit) {
                goldSkip.add(Pair(tgt, System.currentTimeMillis() + 120000L))
                setAuto(false)
                say("Golden zaman aşımı, bu nokta atlandı")
                return true
            }
        }
        lastFightEnd = System.currentTimeMillis()
        goldKills++
        ev("Golden kesildi")
        say("Golden kesildi, sıradakine geçiliyor")
        return true
    }

    /** Haritayı açıp canlı golden ikonlarını gösterir; dokunarak seçilenler bu harita/wire için kaydedilir. */
    private fun goldMark() {
        closeMapIfOpen()
        val info = readMapWireRetry()
        val old = goldMapName()
        if (old.isNotEmpty() && key(old) != key(info.first) && goldSnapshot().isNotEmpty()) {
            synchronized(lock) { golds.clear() }
            saveGolds()
            toast("Golden haritası değişti (" + old + " -> " + info.first + "), eski noktalar silindi")
        }
        prefs().edit().putString("goldMap", info.first).apply()
        val mb = ensureMap()
        val skulls = scanSkulls(mb)
        say("Golden işaretleme: " + info.first + " · " + skulls.size + " ikon bulundu")
        ui { showGoldMark(info.first, skulls) }
    }

    private class GoldMarkView(
        ctx: Context,
        val skulls: List<PointF>,
        val existing: List<PointF>,
        val picked: MutableList<PointF>
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            p.strokeWidth = 5f
            p.textSize = 30f
            for (e in existing) {
                p.style = Paint.Style.STROKE
                p.color = Color.CYAN
                c.drawCircle(e.x, e.y, 34f, p)
            }
            for ((i, k) in skulls.withIndex()) {
                p.style = Paint.Style.STROKE
                p.color = Color.YELLOW
                c.drawCircle(k.x, k.y, 30f, p)
                p.style = Paint.Style.FILL
                c.drawText((i + 1).toString(), k.x + 24f, k.y - 24f, p)
            }
            for (k in picked) {
                p.style = Paint.Style.FILL
                p.color = 0x9900FF00.toInt()
                c.drawCircle(k.x, k.y, 26f, p)
            }
        }
    }

    private fun showGoldMark(map: String, skulls: List<PointF>) {
        removeExtra()
        val picked = ArrayList<PointF>()
        val existing = goldSnapshot().map { PointF(it.px * screenW, it.py * screenH) }
        val view = GoldMarkView(this, skulls, existing, picked)
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x22000000)
        fl.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xEE161920.toInt(), 12)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 12f
        fun refreshInfo() {
            info.text = map + "\nSeçili: " + picked.size + " (sarı halka: bulunan, camgöbeği: kayıtlı)"
        }
        refreshInfo()
        bar.addView(info)
        bar.addView(mkBtn("Hepsini seç") {
            picked.clear()
            picked.addAll(skulls)
            view.invalidate()
            refreshInfo()
        }, LinearLayout.LayoutParams(dp(110), dp(40)))
        bar.addView(mkBtn("Kaydet", OK, 0xFF04200F.toInt()) {
            var added = 0
            synchronized(lock) {
                for (k in picked) {
                    val px = k.x / screenW
                    val py = k.y / screenH
                    val dup = golds.any { dist(PointF(it.px * screenW, it.py * screenH), k) < 40f }
                    if (dup) continue
                    var idx = 1
                    while (golds.any { it.idx == idx }) idx++
                    golds.add(Gold(System.currentTimeMillis() + added, idx, px, py))
                    added++
                }
            }
            saveGolds()
            removeExtra()
            toast(added.toString() + " golden kaydedildi")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(90), dp(40)))
        bar.addView(mkBtn("İptal") {
            removeExtra()
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(80), dp(40)))
        fl.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val t = PointF(ev.rawX, ev.rawY)
                var near: PointF? = null
                var nd = 55f
                for (k in skulls) {
                    val d = dist(k, t)
                    if (d < nd) {
                        nd = d
                        near = k
                    }
                }
                val spot = near ?: t
                val already = picked.firstOrNull { dist(it, spot) < 30f }
                if (already != null) picked.remove(already) else picked.add(spot)
                view.invalidate()
                refreshInfo()
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

    /** Kayıtlı golden noktalarının listesi: dokununca gidip keser, uzun basılı ✕ siler. */
    private fun toggleGoldWin() {
        if (bossWin != null) {
            val was = goldWinOpen
            closeBossWin()
            if (was) return
        }
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(14), dp(12), dp(14), dp(14))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = "Golden noktalar (" + goldSnapshot().size + ")"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(mkBtn("Kapat ✕") { closeBossWin() }, LinearLayout.LayoutParams(dp(90), dp(36)))
        outer.addView(head)
        val hint = TextView(this)
        hint.text = "İşaretli nokta varsa otomasyon yalnızca onlara gider; hiç yoksa haritadaki tüm canlı golden'lara gider (en yakından). Satıra dokun: sadece oraya git."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(4), 0, dp(4))
        outer.addView(hint)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = goldSnapshot().sortedBy { it.idx }
        if (list.isEmpty()) {
            val e0 = TextView(this)
            e0.text = "Henüz golden noktası yok."
            e0.setTextColor(TEXT_DIM)
            col.addView(e0)
        }
        for (g in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 10)
            row.setPadding(dp(12), dp(8), dp(8), dp(8))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            val n1 = TextView(this)
            n1.text = goldTitle(g)
            n1.textSize = 15f
            n1.typeface = android.graphics.Typeface.DEFAULT_BOLD
            n1.setTextColor(Color.WHITE)
            left.addView(n1)
            val n2 = TextView(this)
            n2.text = goldMapName() + " · Golden " + g.idx
            n2.textSize = 10f
            n2.setTextColor(TEXT_DIM)
            left.addView(n2)
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = TextView(this)
            del.text = "✕"
            del.textSize = 16f
            del.setTextColor(TEXT_DIM)
            del.setPadding(dp(14), dp(6), dp(6), dp(6))
            del.setOnLongClickListener {
                synchronized(lock) { golds.remove(g) }
                saveGolds()
                closeBossWin()
                toggleGoldWin()
                true
            }
            row.addView(del)
            row.setOnClickListener {
                if (autoRunning) {
                    say("Otomasyon açık. Önce otomasyonu durdur.")
                } else {
                    closeBossWin()
                    runManual { goldCycle(g) }
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(row, lp)
        }
        val w = minOf(dp(440), (screenW * 0.6f).toInt())
        val lp = overlayParams(w)
        lp.height = (screenH * 0.82f).toInt()
        lp.x = (screenW - w) / 2
        lp.y = (screenH - lp.height) / 2
        wm.addView(outer, lp)
        bossWin = outer
        goldWinOpen = true
    }


    // ------------------------------------------------------------------ red bosslar (VIP Domain; süre kaydı yok)

    /** Mini haritanın ortasında parlak sarı red boss ikonu var mı (Redingen'deki ölüm tespiti). */
    private fun miniRedAlive(b: Bitmap): Boolean {
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
        if (x1 <= x0 || y1 <= y0) return false
        val w = x1 - x0 + 1
        val row = IntArray(w)
        var hit = 0
        for (y in y0..y1) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (isBossRed(row[x])) hit++
        }
        return hit.toFloat() / (w * (y1 - y0 + 1)) >= 0.0012f
    }

    private fun loadReds() {
        synchronized(lock) {
            reds.clear()
            try {
                val a = JSONArray(prefs().getString("reds", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    reds.add(Red(o.getLong("id"), o.optInt("idx", reds.size + 1),
                        o.getDouble("px").toFloat(), o.getDouble("py").toFloat()))
                    if (!prefs().contains("redMap") && o.has("map")) prefs().edit().putString("redMap", o.getString("map")).apply()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun saveReds() {
        val a = JSONArray()
        synchronized(lock) {
            for (g in reds) {
                val o = JSONObject()
                o.put("id", g.id)
                o.put("idx", g.idx)
                o.put("px", g.px.toDouble())
                o.put("py", g.py.toDouble())
                a.put(o)
            }
        }
        prefs().edit().putString("reds", a.toString()).apply()
    }

    private fun redSnapshot(): List<Red> = synchronized(lock) { ArrayList(reds) }

    private fun redMapName(): String = prefs().getString("redMap", "") ?: ""

    private fun redTitle(g: Red): String {
        val m = redMapName()
        return (if (m.isEmpty()) "Red boss" else shortMap(m)) + " · R#" + g.idx
    }

    /**
     * Red boss haritasına gelir. Zaten o haritadaysa ve wire doğruysa bir şey yapmaz. Değilse farm/buff gibi:
     * önce seçilen wire'a geçer, sonra chat'ten red boss bağlantısına tıklar, varınca wire'ı doğrular.
     */
    private fun ensureRedMap() {
        var gm = redMapName()
        val gw = cfgI("redWire", 1)
        closeMapIfOpen()
        var cur = readMapWireRetry()
        if (gm.isEmpty()) {
            gm = cur.first
            prefs().edit().putString("redMap", gm).apply()
            say("Red boss haritası: " + gm + " (şu an bulunduğun harita)")
        }
        if (key(cur.first) == key(gm) && (lastLink.isEmpty() || lastLink == "goldLink" || lastLink == "redLink")) {
            if (mapInfo(gm).wires > 1 && cur.second != gw) {
                say("Red boss için Wire " + gw + " seçiliyor")
                if (!switchTo(gw)) fail("Wire değişmedi (istenen Wire " + gw + ")")
            }
            return
        }
        val useTp = tpMacroOn(gm)
        if (!useTp && !hasPt("redLink")) fail("Önce 'Ayar' menüsünden işaretle: redLink (ya da Bakım'dan " + gm + " için ışınlanma kaydı yap ve Kayıtlı ışın'ı aç)")
        if (mapInfo(cur.first).wires > 1 && cur.second != gw) {
            say("Red boss için Wire " + gw + " seçiliyor")
            switchTo(gw)
        }
        say("Red boss haritasına gidiliyor")
        if (useTp) {
            teleport(gm)
            lastLink = "redLink"
        } else {
            openChatLink("redLink")
            pause(cfgI("mapTravel", 10) * 1000)
        }
        cur = readMapWireRetry()
        if (key(cur.first) != key(gm)) fail("Red boss haritasına ışınlanılamadı, şu an: " + cur.first)
        if (mapInfo(gm).wires > 1 && cur.second != gw) {
            if (!switchTo(gw)) fail("Wire değişmedi (istenen Wire " + gw + ")")
        }
    }

    private val redSkip = ArrayList<Pair<PointF, Long>>()
    @Volatile private var redIdleUntil = 0L
    @Volatile private var redHere = false

    /** Otomasyondan: bir red boss turu; hiç red boss bulunamazsa bir süre red boss'a bakmaz. */
    private fun runRed() {
        goldHere = false
        intrOn = true
        try {
            if (redCycle(null)) fails = 0
            else redIdleUntil = System.currentTimeMillis() + cfgI("redRetry", 15) * 1000L
        } finally {
            intrOn = false
        }
    }

    /**
     * Bir red boss turu: haritayı aç, canlı red boss'ları tara, en yakınını seç (Random açıksa yarıçap içine girene kadar Random),
     * yürü, oto atak aç, ölünce true. Red boss hiç görünmüyorsa false. İşaretli nokta varsa yalnızca onlara gidilir.
     * only verilirse sadece o işaretli noktaya gidilir (listeden dokunuş).
     */
    private fun redCycle(only: Red?): Boolean {
        if (farming) {
            setAuto(false)
            farming = false
        }
        if (!redHere) {
            ensureRedMap()
            redHere = true
        }
        var mb = ensureMap()
        refreshScreen()
        val sc = mb.width / 2560f
        val marks = (if (only != null) listOf(only) else redSnapshot()).map { PointF(it.px * screenW, it.py * screenH) }
        val radius = cfgI("redRadius", 150).toFloat()
        val useRandom = on("redRandomOn") && radius > 0f && hasPt("random")
        val maxTry = cfgI("randomMax", 40)
        var tries = 0
        var best: PointF? = null
        var bd = Float.MAX_VALUE
        while (true) {
            val now = System.currentTimeMillis()
            redSkip.removeAll { it.second < now }
            val me = findPlayer(mb)
            best = null
            bd = Float.MAX_VALUE
            for (k in scanSkulls(mb) { isRedIcon(it) }) {
                if (marks.isNotEmpty() && marks.none { dist(it, k) <= 45f * sc }) continue
                if (redSkip.any { dist(it.first, k) <= 30f * sc }) continue
                val d = if (me != null) dist(me, k) else 0f
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            if (best == null) {
                if (useRandom && tries < maxTry) {
                    tries++
                    say("Red boss görünmüyor, Random " + tries + "/" + maxTry)
                    tapPoint("random")
                    gameAuto = false
                    pause((cfgF("randomWait", 2.5f) * 1000).toInt())
                    mb = ensureMap()
                    continue
                }
                tapPoint("closeMap")
                pause(600)
                say("Red boss görünmüyor")
                return false
            }
            if (!useRandom || me == null || bd <= radius || tries >= maxTry) break
            tries++
            say("En yakın red boss " + bd.toInt() + " px uzakta, Random " + tries + "/" + maxTry)
            tapPoint("random")
            gameAuto = false
            pause((cfgF("randomWait", 2.5f) * 1000).toInt())
            mb = ensureMap()
        }
        val tgt = best!!
        val label = "Red boss (" + (if (bd < Float.MAX_VALUE / 2) bd.toInt().toString() + " px" else "?") + ")"
        if (stillOnTarget(bd)) {
            say(label + "\nBossun üstündeyiz, oto atak sürüyor")
        } else {
            try {
                approach(mb, tgt.x / screenW, tgt.y / screenH, label, 0f, false)
            } catch (e: StepFail) {
                redSkip.add(Pair(tgt, System.currentTimeMillis() + 60000L))
                return true
            }
            gameAuto = false
            setAuto(true)
            if (on("angelOn") && hasPt("angel")) {
                tapPoint("angel")
                pause(300)
            }
        }
        say(label + "\nOto atak açık, red boss bekleniyor")
        fightMaint()
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        var lastMapChk = t0
        while (true) {
            pause(150)
            if (autoRunning && nextDue() != null) {
                setAuto(false)
                say("Boss zamanı geldi, red boss bırakıldı")
                return true
            }
            val b = shot() ?: continue
            val oth = ownerTakenCheck(b)
            if (oth != null) {
                setAuto(false)
                ev("Boss başka oyuncuda (" + oth + "), sıradakine geçiliyor", true, 3)
                say("Boss başka oyuncuda: " + oth)
                redSkip.add(Pair(tgt, System.currentTimeMillis() + 180000L))
                return true
            }
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniRedAlive(b)) {
                seen = true
                dead = 0
                say(label + "\nRed boss canlı, savaşılıyor")
            } else if (seen) {
                dead++
                say(label + "\nRed boss öldü mü? " + dead + "/3")
                if (dead >= 3) break
            } else if (now - t0 > 8000 && now - lastMapChk > 10000) {
                lastMapChk = now
                val mb2 = ensureMap()
                var still = false
                for (k in scanSkulls(mb2) { isRedIcon(it) }) if (dist(k, tgt) < 35f * sc) still = true
                val me = findPlayer(mb2)
                val onTop = me != null && dist(me, tgt) <= cfgI("arrive", 45) * 1.5f
                tapPoint("closeMap")
                pause(700)
                if (!still && !onTop) break
            }
            if (now - t0 > limit) {
                redSkip.add(Pair(tgt, System.currentTimeMillis() + 120000L))
                setAuto(false)
                say("Red boss zaman aşımı, bu nokta atlandı")
                return true
            }
        }
        lastFightEnd = System.currentTimeMillis()
        redKills++
        ev("Red boss kesildi")
        say("Red boss kesildi, sıradakine geçiliyor")
        return true
    }

    /** Haritayı açıp canlı red boss ikonlarını gösterir; dokunarak seçilenler bu harita/wire için kaydedilir. */
    private fun redMark() {
        closeMapIfOpen()
        val info = readMapWireRetry()
        val old = redMapName()
        if (old.isNotEmpty() && key(old) != key(info.first) && redSnapshot().isNotEmpty()) {
            synchronized(lock) { reds.clear() }
            saveReds()
            toast("Red boss haritası değişti (" + old + " -> " + info.first + "), eski noktalar silindi")
        }
        prefs().edit().putString("redMap", info.first).apply()
        val mb = ensureMap()
        val skulls = scanSkulls(mb) { isRedIcon(it) }
        say("Red boss işaretleme: " + info.first + " · " + skulls.size + " ikon bulundu")
        ui { showRedMark(info.first, skulls) }
    }

    private class RedMarkView(
        ctx: Context,
        val skulls: List<PointF>,
        val existing: List<PointF>,
        val picked: MutableList<PointF>
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            p.strokeWidth = 5f
            p.textSize = 30f
            for (e in existing) {
                p.style = Paint.Style.STROKE
                p.color = Color.CYAN
                c.drawCircle(e.x, e.y, 34f, p)
            }
            for ((i, k) in skulls.withIndex()) {
                p.style = Paint.Style.STROKE
                p.color = Color.RED
                c.drawCircle(k.x, k.y, 30f, p)
                p.style = Paint.Style.FILL
                c.drawText((i + 1).toString(), k.x + 24f, k.y - 24f, p)
            }
            for (k in picked) {
                p.style = Paint.Style.FILL
                p.color = 0x9900FF00.toInt()
                c.drawCircle(k.x, k.y, 26f, p)
            }
        }
    }

    private fun showRedMark(map: String, skulls: List<PointF>) {
        removeExtra()
        val picked = ArrayList<PointF>()
        val existing = redSnapshot().map { PointF(it.px * screenW, it.py * screenH) }
        val view = RedMarkView(this, skulls, existing, picked)
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x22000000)
        fl.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xEE161920.toInt(), 12)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 12f
        fun refreshInfo() {
            info.text = map + "\nSeçili: " + picked.size + " (kırmızı halka: bulunan, camgöbeği: kayıtlı)"
        }
        refreshInfo()
        bar.addView(info)
        bar.addView(mkBtn("Hepsini seç") {
            picked.clear()
            picked.addAll(skulls)
            view.invalidate()
            refreshInfo()
        }, LinearLayout.LayoutParams(dp(110), dp(40)))
        bar.addView(mkBtn("Kaydet", OK, 0xFF04200F.toInt()) {
            var added = 0
            synchronized(lock) {
                for (k in picked) {
                    val px = k.x / screenW
                    val py = k.y / screenH
                    val dup = reds.any { dist(PointF(it.px * screenW, it.py * screenH), k) < 40f }
                    if (dup) continue
                    var idx = 1
                    while (reds.any { it.idx == idx }) idx++
                    reds.add(Red(System.currentTimeMillis() + added, idx, px, py))
                    added++
                }
            }
            saveReds()
            removeExtra()
            toast(added.toString() + " red boss kaydedildi")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(90), dp(40)))
        bar.addView(mkBtn("İptal") {
            removeExtra()
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(80), dp(40)))
        fl.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val t = PointF(ev.rawX, ev.rawY)
                var near: PointF? = null
                var nd = 55f
                for (k in skulls) {
                    val d = dist(k, t)
                    if (d < nd) {
                        nd = d
                        near = k
                    }
                }
                val spot = near ?: t
                val already = picked.firstOrNull { dist(it, spot) < 30f }
                if (already != null) picked.remove(already) else picked.add(spot)
                view.invalidate()
                refreshInfo()
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

    /** Kayıtlı red boss noktalarının listesi: dokununca gidip keser, uzun basılı ✕ siler. */
    private fun toggleRedWin() {
        if (bossWin != null) {
            val was = redWinOpen
            closeBossWin()
            if (was) return
        }
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(14), dp(12), dp(14), dp(14))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = "Red boss noktalar (" + redSnapshot().size + ")"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(mkBtn("Kapat ✕") { closeBossWin() }, LinearLayout.LayoutParams(dp(90), dp(36)))
        outer.addView(head)
        val hint = TextView(this)
        hint.text = "İşaretli nokta varsa otomasyon yalnızca onlara gider; hiç yoksa haritadaki tüm canlı red boss'lara gider (en yakından). Satıra dokun: sadece oraya git."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(4), 0, dp(4))
        outer.addView(hint)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = redSnapshot().sortedBy { it.idx }
        if (list.isEmpty()) {
            val e0 = TextView(this)
            e0.text = "Henüz red boss noktası yok."
            e0.setTextColor(TEXT_DIM)
            col.addView(e0)
        }
        for (g in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 10)
            row.setPadding(dp(12), dp(8), dp(8), dp(8))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            val n1 = TextView(this)
            n1.text = redTitle(g)
            n1.textSize = 15f
            n1.typeface = android.graphics.Typeface.DEFAULT_BOLD
            n1.setTextColor(Color.WHITE)
            left.addView(n1)
            val n2 = TextView(this)
            n2.text = redMapName() + " · Red boss " + g.idx
            n2.textSize = 10f
            n2.setTextColor(TEXT_DIM)
            left.addView(n2)
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = TextView(this)
            del.text = "✕"
            del.textSize = 16f
            del.setTextColor(TEXT_DIM)
            del.setPadding(dp(14), dp(6), dp(6), dp(6))
            del.setOnLongClickListener {
                synchronized(lock) { reds.remove(g) }
                saveReds()
                closeBossWin()
                toggleRedWin()
                true
            }
            row.addView(del)
            row.setOnClickListener {
                if (autoRunning) {
                    say("Otomasyon açık. Önce otomasyonu durdur.")
                } else {
                    closeBossWin()
                    runManual { redCycle(g) }
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(row, lp)
        }
        val w = minOf(dp(440), (screenW * 0.6f).toInt())
        val lp = overlayParams(w)
        lp.height = (screenH * 0.82f).toInt()
        lp.x = (screenW - w) / 2
        lp.y = (screenH - lp.height) / 2
        wm.addView(outer, lp)
        bossWin = outer
        redWinOpen = true
    }


    // ------------------------------------------------------------------ rota (duraklar sırayla gezilir, canlı bosslar kesilir) ve canlı boss kaydı

    /** Rota: harita listesinden seçilen haritalar sırayla gezilir; her haritanın her wire'ında canlı bosslar kesilir. */
    private val route = ArrayList<String>()
    private val routeSkip = ArrayList<Pair<PointF, Long>>()
    private var routeKills = 0
    private var routeWinOpen = false
    @Volatile private var routeIdx = 0
    @Volatile private var routeWire = 1
    @Volatile private var routeAt = false
    @Volatile private var lastBuffChk = 0L

    private fun loadRoute() {
        synchronized(lock) {
            route.clear()
            for (l in (prefs().getString("routeMaps", "") ?: "").split("\n")) if (l.isNotBlank()) route.add(l.trim())
        }
    }

    private fun saveRoute() {
        val t = synchronized(lock) { route.joinToString("\n") }
        prefs().edit().putString("routeMaps", t).apply()
    }

    private fun routeSnapshot(): List<String> = synchronized(lock) { ArrayList(route) }

    private fun resetRoute() {
        routeHiddenDone = false
        routeIdx = 0
        routeWire = 1
        routeAt = false
    }

    /** Karakterin şu anki harita, wire ve haritadaki noktasını okur. */
    private fun readHere(): Triple<String, Int, PointF> {
        closeMapIfOpen()
        val info = readMapWireRetry()
        val mb = ensureMap()
        val me = findPlayer(mb)
        tapPoint("closeMap")
        pause(600)
        if (me == null) fail("Harita noktası alınamadı (yeşil imleç görünmedi). Tekrar bas.")
        return Triple(info.first, info.second, PointF(me.x / mb.width, me.y / mb.height))
    }

    /** Boss yaşıyorken (mezar sayacı yokken) kaydeder: hemen vakti gelmiş sayılır, bot gidip keser ve sayacı kaydeder. */
    private fun doSaveAlive() {
        say("Canlı boss okunuyor...")
        val h = readHere()
        val e = upsert(h.first, h.second, System.currentTimeMillis(), h.third.x, h.third.y)
        ui { rebuildList() }
        say("Canlı boss kaydedildi: " + title(e) + " (vakti geldi sayılır)")
    }

    /** Rotada bir adım: sıradaki haritanın sıradaki wire'ına gider (PM bağlantısı + wire), canlı boss kalmayana kadar keser, sonra sonraki wire/haritaya geçer. */
    private fun runRoute() {
        intrOn = true
        try {
            runRouteInner()
        } finally {
            intrOn = false
        }
    }

    private fun runRouteInner() {
        goldHere = false
        redHere = false
        if (farming) {
            setAuto(false)
            farming = false
        }
        val maps = routeSnapshot()
        if (maps.isEmpty()) return
        if (routeIdx >= maps.size) resetRoute()
        val name = maps[routeIdx]
        val wires = mapInfo(name).wires
        if (routeWire > wires) routeWire = 1
        if (!routeAt) {
            say("Rota " + (routeIdx + 1) + "/" + maps.size + ": " + shortMap(name) + (if (wires > 1) " · Wire " + routeWire else ""))
            ensureLocation(name, routeWire)
            routeAt = true
        }
        if (routeKill()) {
            fails = 0
        } else if (!routeHiddenDone && hiddenPts(name).isNotEmpty()) {
            routeHiddenDone = true
            routeHidden(name)
            fails = 0
        } else {
            routeAt = false
            routeHiddenDone = false
            routeWire++
            if (routeWire > wires) {
                routeWire = 1
                routeIdx = (routeIdx + 1) % maps.size
            }
        }
    }

    private fun routeKill(): Boolean {
        if (farming) {
            setAuto(false)
            farming = false
        }
        var mb = ensureMap()
        refreshScreen()
        val sc = mb.width / 2560f
        val marks = emptyList<PointF>()
        val rmaps = routeSnapshot()
        val ign = (if (rmaps.isEmpty()) emptyList() else ignPts(rmaps[routeIdx % rmaps.size])).map { PointF(it.x * screenW, it.y * screenH) }
        val radius = cfgI("routeRadius", 150).toFloat()
        val useRandom = on("routeRandomOn") && radius > 0f && hasPt("random")
        val maxTry = cfgI("randomMax", 40)
        var tries = 0
        var best: PointF? = null
        var bd = Float.MAX_VALUE
        while (true) {
            val now = System.currentTimeMillis()
            routeSkip.removeAll { it.second < now }
            val me = findPlayer(mb)
            best = null
            bd = Float.MAX_VALUE
            for (k in scanSkulls(mb) { isRedIcon(it) || isBossRed(it) }) {
                if (marks.isNotEmpty() && marks.none { dist(it, k) <= 45f * sc }) continue
                if (routeSkip.any { dist(it.first, k) <= 30f * sc }) continue
                if (ign.any { dist(it, k) <= 40f * sc }) continue
                val d = if (me != null) dist(me, k) else 0f
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            if (best == null) {
                tapPoint("closeMap")
                pause(600)
                say("Boss görünmüyor")
                return false
            }
            if (!useRandom || me == null || bd <= radius || tries >= maxTry) break
            tries++
            say("En yakın boss " + bd.toInt() + " px uzakta, Random " + tries + "/" + maxTry)
            tapPoint("random")
            gameAuto = false
            pause((cfgF("randomWait", 2.5f) * 1000).toInt())
            mb = ensureMap()
        }
        val tgt = best!!
        val label = "Boss (" + (if (bd < Float.MAX_VALUE / 2) bd.toInt().toString() + " px" else "?") + ")"
        if (stillOnTarget(bd)) {
            say(label + "\nBossun üstündeyiz, oto atak sürüyor")
        } else {
            try {
                approach(mb, tgt.x / screenW, tgt.y / screenH, label, 0f, false)
            } catch (e: StepFail) {
                routeSkip.add(Pair(tgt, System.currentTimeMillis() + 60000L))
                return true
            }
            gameAuto = false
            setAuto(true)
            if (on("angelOn") && hasPt("angel")) {
                tapPoint("angel")
                pause(300)
            }
        }
        say(label + "\nOto atak açık, boss bekleniyor")
        fightMaint()
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        var lastMapChk = t0
        while (true) {
            pause(150)
            if (autoRunning && nextDue() != null) {
                setAuto(false)
                say("Boss zamanı geldi, boss bırakıldı")
                return true
            }
            val b = shot() ?: continue
            if (fightBuff(b)) return true
            val oth = ownerTakenCheck(b)
            if (oth != null) {
                setAuto(false)
                ev("Boss başka oyuncuda (" + oth + "), sıradakine geçiliyor", true, 3)
                say("Boss başka oyuncuda: " + oth)
                routeSkip.add(Pair(tgt, System.currentTimeMillis() + 180000L))
                return true
            }
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniRedAlive(b)) {
                seen = true
                dead = 0
                say(label + "\nBoss canlı, savaşılıyor")
            } else if (seen) {
                dead++
                say(label + "\nBoss öldü mü? " + dead + "/3")
                if (dead >= 3) break
            } else if (now - t0 > 8000 && now - lastMapChk > 10000) {
                lastMapChk = now
                val mb2 = ensureMap()
                var still = false
                for (k in scanSkulls(mb2) { isRedIcon(it) || isBossRed(it) }) if (dist(k, tgt) < 35f * sc) still = true
                val me = findPlayer(mb2)
                val onTop = me != null && dist(me, tgt) <= cfgI("arrive", 45) * 1.5f
                tapPoint("closeMap")
                pause(700)
                if (!still && !onTop) break
            }
            if (now - t0 > limit) {
                routeSkip.add(Pair(tgt, System.currentTimeMillis() + 120000L))
                setAuto(false)
                say("Boss zaman aşımı, bu nokta atlandı")
                return true
            }
        }
        lastFightEnd = System.currentTimeMillis()
        routeKills++
        ev("Boss kesildi")
        say("Boss kesildi, sıradakine geçiliyor")
        return true
    }

    private fun toggleRouteWin() {
        if (bossWin != null) {
            val was = routeWinOpen
            closeBossWin()
            if (was) return
        }
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(14), dp(12), dp(14), dp(14))
        val list = routeSnapshot()
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = "Rota (" + list.size + " harita)"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(mkBtn("Temizle", BAD) {
            synchronized(lock) { route.clear() }
            saveRoute()
            resetRoute()
            closeBossWin()
            toggleRouteWin()
        }, LinearLayout.LayoutParams(dp(90), dp(36)))
        head.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        head.addView(mkBtn("Kapat ✕") { closeBossWin() }, LinearLayout.LayoutParams(dp(90), dp(36)))
        outer.addView(head)
        val hint = TextView(this)
        hint.text = "Aşağıdaki harita listesinden dokunarak rotaya ekle. Rota açıkken bot haritaları sırayla PM bağlantısından açar, her haritanın tüm wire'larını gezer, canlı bossları keser. Satıra dokun: bir yukarı al. ✕'e uzun bas: sil."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(4), 0, dp(4))
        outer.addView(hint)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun lab(text: String) {
            val l = TextView(this)
            l.text = text
            l.textSize = 11f
            l.setTextColor(TEXT_DIM)
            l.typeface = android.graphics.Typeface.DEFAULT_BOLD
            l.setPadding(0, dp(10), 0, dp(2))
            col.addView(l)
        }
        lab("ROTA SIRASI")
        if (list.isEmpty()) {
            val e0 = TextView(this)
            e0.text = "Rota boş. Aşağıdan harita ekle."
            e0.setTextColor(TEXT_DIM)
            col.addView(e0)
        }
        for ((i, nm) in list.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 10)
            row.setPadding(dp(12), dp(8), dp(8), dp(8))
            val n = TextView(this)
            n.text = "#" + (i + 1)
            n.textSize = 12f
            n.setTextColor(TEXT_DIM)
            row.addView(n, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            val tt = TextView(this)
            tt.text = nm + "  ·  " + mapInfo(nm).wires + " wire"
            tt.textSize = 14f
            tt.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tt.setTextColor(Color.WHITE)
            row.addView(tt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                if (i > 0) {
                    synchronized(lock) {
                        val x = route.removeAt(i)
                        route.add(i - 1, x)
                    }
                    saveRoute()
                    resetRoute()
                    closeBossWin()
                    toggleRouteWin()
                }
            }
            val del = TextView(this)
            del.text = "✕"
            del.textSize = 16f
            del.setTextColor(TEXT_DIM)
            del.setPadding(dp(14), dp(6), dp(6), dp(6))
            del.setOnLongClickListener {
                synchronized(lock) { route.removeAt(i) }
                saveRoute()
                resetRoute()
                closeBossWin()
                toggleRouteWin()
                true
            }
            row.addView(del)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(row, lp)
        }
        lab("HARİTALAR (dokun: rotaya ekle)")
        for (m in maps()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(0xFF11141B.toInt(), 10)
            row.setPadding(dp(12), dp(8), dp(12), dp(8))
            val tt = TextView(this)
            tt.text = "+  " + m.name + "  ·  " + m.wires + " wire"
            tt.textSize = 13f
            tt.setTextColor(Color.WHITE)
            row.addView(tt)
            row.setOnClickListener {
                synchronized(lock) { route.add(m.name) }
                saveRoute()
                closeBossWin()
                toggleRouteWin()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            col.addView(row, lp)
        }
        val w = minOf(dp(440), (screenW * 0.6f).toInt())
        val lp = overlayParams(w)
        lp.height = (screenH * 0.82f).toInt()
        lp.x = (screenW - w) / 2
        lp.y = (screenH - lp.height) / 2
        wm.addView(outer, lp)
        bossWin = outer
        routeWinOpen = true
    }

    // ------------------------------------------------------------------ kayıtlı işlemler (makro): Recycle, HP pot, MP pot

    private class Gest(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dur: Long, val gap: Long)

    private val macroNames = listOf(Pair("recycle", "Recycle"), Pair("pothp", "HP pot al"), Pair("potmp", "MP pot al"), Pair("rndbuy", "Random al"), Pair("angelbuy", "Angel al"))
    private val macroLabels = HashMap<String, TextView>()
    private val macroBase = HashMap<String, String>()
    private var recOverlay: View? = null
    private var recLp: WindowManager.LayoutParams? = null
    private var recBar: LinearLayout? = null
    private var recInfo: TextView? = null
    private var recName = ""
    private val recList = ArrayList<Gest>()
    @Volatile private var recording = false

    private fun macroLoad(name: String): List<Gest> {
        val out = ArrayList<Gest>()
        try {
            val a = JSONArray(prefs().getString("macro_" + name, "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out.add(Gest(o.getDouble(0).toFloat(), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(), o.getDouble(3).toFloat(), o.getLong(4), o.getLong(5)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun macroSave(name: String, l: List<Gest>) {
        val a = JSONArray()
        for (g in l) {
            val o = JSONArray()
            o.put(g.x1.toDouble())
            o.put(g.y1.toDouble())
            o.put(g.x2.toDouble())
            o.put(g.y2.toDouble())
            o.put(g.dur)
            o.put(g.gap)
            a.put(o)
        }
        prefs().edit().putString("macro_" + name, a.toString()).apply()
    }

    private fun macroHas(name: String): Boolean = macroLoad(name).isNotEmpty()

    private fun refreshMacroLabels() {
        for ((k, lbl) in macroLabels) {
            val n = macroLoad(k).size
            lbl.text = (macroBase[k] ?: k) + (if (n > 0) "  ·  " + n + " adım" else "  ·  kayıt yok")
        }
    }

    /** Kaydedilmiş dokunuşları aynı sırayla ve aynı bekleme sürelerinde oynatır. */
    private fun playMacro(name: String): Boolean {
        val l = macroLoad(name)
        if (l.isEmpty()) return false
        refreshScreen()
        for ((i, g) in l.withIndex()) {
            pause(g.gap.coerceIn(150L, 5000L).toInt())
            say("Kayıtlı işlem: " + name + " " + (i + 1) + "/" + l.size)
            val x1 = g.x1 * screenW
            val y1 = g.y1 * screenH
            val x2 = g.x2 * screenW
            val y2 = g.y2 * screenH
            if (Math.abs(x2 - x1) < 30f && Math.abs(y2 - y1) < 30f && g.dur < 200L) tapPx(x1, y1)
            else gesturePx(x1, y1, x2, y2, g.dur.coerceIn(60L, 3000L))
        }
        lastBeat = System.currentTimeMillis()
        return true
    }

    private fun gesturePx(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
        lastBeat = System.currentTimeMillis()
        setThrough(true, x1, y1)
        try {
            Thread.sleep(40)
            if (shizukuReady() && shell(arrayOf("input", "swipe", x1.toInt().toString(), y1.toInt().toString(), x2.toInt().toString(), y2.toInt().toString(), dur.toString()))) {
                sent++
                return true
            }
            val p = Path()
            p.moveTo(x1, y1)
            p.lineTo(x2, y2)
            return runGesture(p, dur)
        } finally {
            setThrough(false)
        }
    }

    private fun setRecThrough(through: Boolean) {
        val latch = CountDownLatch(1)
        ui {
            try {
                val v = recOverlay
                val lp = recLp
                if (v != null && lp != null) applyThrough(v, lp, through)
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    private fun updateRecInfo() {
        ui { recInfo?.text = "● Kayıt: " + recName + " · " + recList.size + " adım" }
    }

    /** Kaydı başlatır: ekran şeffaf bir katmanla örtülür, her dokunuş kaydedilir ve hemen oyuna iletilir. */
    private fun startRecording(name: String) {
        if (autoRunning || busy) {
            say("Otomasyon açıkken kayıt yapılamaz. Önce durdur.")
            return
        }
        if (recording) return
        if (!shizukuReady()) toast("Uyarı: Shizuku izni yok, dokunuşlar yedek yöntemle iletilecek")
        refreshScreen()
        recording = true
        recName = name
        recList.clear()
        panelOpen = false
        updateUi()
        val v = View(this)
        v.setBackgroundColor(0x14FF3030)
        var dx = 0f
        var dy = 0f
        var dt = 0L
        var lastEnd = SystemClock.elapsedRealtime() - 1500L
        // Oyuna iletilen dokunuş kısa süre katmana da düşebilir; bu pencerede gelen dokunuşlar kayda alınmaz (çift sayımı önler)
        var ignoreUntil = 0L
        var skipThis = false
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) skipThis = SystemClock.elapsedRealtime() < ignoreUntil
            if (skipThis) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = ev.rawX
                    dy = ev.rawY
                    dt = SystemClock.elapsedRealtime()
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.elapsedRealtime()
                    val ux = ev.rawX
                    val uy = ev.rawY
                    val dur = now - dt
                    val gap = dt - lastEnd
                    lastEnd = now
                    val isTap = Math.abs(ux - dx) < 30f && Math.abs(uy - dy) < 30f && dur < 200L
                    ignoreUntil = now + (if (isTap) 0L else dur.coerceIn(60L, 3000L)) + 450L
                    recList.add(Gest(dx / screenW, dy / screenH, ux / screenW, uy / screenH, dur, gap))
                    updateRecInfo()
                    val x1 = dx
                    val y1 = dy
                    Thread {
                        setRecThrough(true)
                        try {
                            Thread.sleep(30)
                            if (Math.abs(ux - x1) < 30f && Math.abs(uy - y1) < 30f && dur < 200L) {
                                if (!(shizukuReady() && shell(arrayOf("input", "tap", x1.toInt().toString(), y1.toInt().toString())))) {
                                    val pp = Path()
                                    pp.moveTo(x1, y1)
                                    runGesture(pp, 70L)
                                }
                            } else {
                                val d = dur.coerceIn(60L, 3000L)
                                if (!(shizukuReady() && shell(arrayOf("input", "swipe", x1.toInt().toString(), y1.toInt().toString(), ux.toInt().toString(), uy.toInt().toString(), d.toString())))) {
                                    val pp = Path()
                                    pp.moveTo(x1, y1)
                                    pp.lineTo(ux, uy)
                                    runGesture(pp, d)
                                }
                            }
                        } catch (e: Exception) {
                        }
                        setRecThrough(false)
                    }.start()
                }
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
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xEE161920.toInt(), 12)
        bar.setPadding(dp(10), dp(6), dp(10), dp(6))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 13f
        info.typeface = android.graphics.Typeface.DEFAULT_BOLD
        info.text = "● Kayıt: " + name + " · 0 adım"
        recInfo = info
        bar.addView(info)
        bar.addView(mkBtn("■ Bitir", OK, 0xFF04200F.toInt()) { stopRecording(true) }, LinearLayout.LayoutParams(dp(90), dp(38)).also { it.leftMargin = dp(10) })
        bar.addView(mkBtn("İptal") { stopRecording(false) }, LinearLayout.LayoutParams(dp(70), dp(38)).also { it.leftMargin = dp(6) })
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        blp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        blp.y = dp(8)
        ui {
            wm.addView(v, lp)
            wm.addView(bar, blp)
        }
        recOverlay = v
        recLp = lp
        recBar = bar
        say("Kayıt açık: " + name)
        toast("Kayıt başladı. Şimdi işlemi oyunda yap, bitince ■ Bitir'e bas.")
    }

    private fun stopRecording(save: Boolean) {
        if (!recording) return
        recording = false
        ui {
            try {
                recOverlay?.let { wm.removeView(it) }
                recBar?.let { wm.removeView(it) }
            } catch (e: Exception) {
            }
            recOverlay = null
            recBar = null
            recInfo = null
            recLp = null
            if (save) {
                macroSave(recName, ArrayList(recList))
                refreshMacroLabels()
                toast("Kaydedildi: " + recName + " (" + recList.size + " adım)")
            }
            panelOpen = true
            updateUi()
        }
    }

    // ------------------------------------------------------------------ rota: haritada görünmeyen (sadece mini haritada çıkan) bosslar için gizli noktalar

    @Volatile private var routeHiddenDone = false

    private fun hiddenAll(): JSONObject =
        try {
            JSONObject(prefs().getString("hidden", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun hiddenPts(map: String): List<PointF> {
        val out = ArrayList<PointF>()
        try {
            val a = hiddenAll().optJSONArray(key(map)) ?: return out
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out.add(PointF(o.getDouble(0).toFloat(), o.getDouble(1).toFloat()))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun hiddenSave(map: String, pts: List<PointF>) {
        val all = hiddenAll()
        val a = JSONArray()
        for (p in pts) {
            val o = JSONArray()
            o.put(p.x.toDouble())
            o.put(p.y.toDouble())
            a.put(o)
        }
        if (pts.isEmpty()) all.remove(key(map)) else all.put(key(map), a)
        prefs().edit().putString("hidden", all.toString()).apply()
    }

    /** Haritayı açar; gizli boss noktaları dokunarak işaretlenir (haritada ikon görünmese de bilinen konum). */
    private fun hiddenMark() {
        closeMapIfOpen()
        val info = readMapWireRetry()
        ensureMap()
        refreshScreen()
        say("Gizli nokta işaretleme: " + info.first)
        ui { showHiddenMark(info.first) }
    }

    private fun showHiddenMark(map: String) {
        removeExtra()
        val picked = ArrayList<PointF>()
        val existing = hiddenPts(map).map { PointF(it.x * screenW, it.y * screenH) }
        val view = RedMarkView(this, emptyList(), existing, picked)
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x22000000)
        fl.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xEE161920.toInt(), 12)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 12f
        fun refreshInfo() {
            info.text = map + "\nBossun durduğu yere dokun. Yeni: " + picked.size + " · kayıtlı: " + existing.size + " (camgöbeği)"
        }
        refreshInfo()
        bar.addView(info)
        bar.addView(mkBtn("Kaydet", OK, 0xFF04200F.toInt()) {
            val pts = ArrayList<PointF>(hiddenPts(map))
            for (k in picked) pts.add(PointF(k.x / screenW, k.y / screenH))
            hiddenSave(map, pts)
            removeExtra()
            toast(picked.size.toString() + " gizli nokta kaydedildi (" + map + ")")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(90), dp(40)))
        bar.addView(mkBtn("Haritayı sil", BAD) {
            hiddenSave(map, emptyList())
            removeExtra()
            toast("Bu haritanın gizli noktaları silindi")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(110), dp(40)))
        bar.addView(mkBtn("İptal") {
            removeExtra()
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(80), dp(40)))
        fl.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val t = PointF(ev.rawX, ev.rawY)
                val already = picked.firstOrNull { dist(it, t) < 30f }
                if (already != null) picked.remove(already) else picked.add(t)
                view.invalidate()
                refreshInfo()
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

    /** Haritada ikonu olmayan bossun savaşı: oto atak açılır, mini haritadaki boss ikonu kaybolunca öldü sayılır. */
    private fun hiddenFight(label: String): Boolean {
        gameAuto = false
        setAuto(true)
        if (on("angelOn") && hasPt("angel")) {
            tapPoint("angel")
            pause(300)
        }
        say(label + "\nOto atak açık, gizli boss savaşılıyor")
        fightMaint()
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        while (true) {
            pause(150)
            if (autoRunning && nextDue() != null) {
                setAuto(false)
                say("Boss zamanı geldi, gizli boss bırakıldı")
                return true
            }
            val b = shot() ?: continue
            if (fightBuff(b)) return true
            val oth = ownerTakenCheck(b)
            if (oth != null) {
                setAuto(false)
                ev("Boss başka oyuncuda (" + oth + "), sıradakine geçiliyor", true, 3)
                say("Boss başka oyuncuda: " + oth)
                return false
            }
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniRedAlive(b)) {
                seen = true
                dead = 0
                say(label + "\nGizli boss canlı, savaşılıyor")
            } else if (seen) {
                dead++
                say(label + "\nGizli boss öldü mü? " + dead + "/3")
                if (dead >= 3) break
            } else if (now - t0 > 6000) {
                say(label + "\nBoss görünmedi")
                return false
            }
            if (now - t0 > limit) {
                setAuto(false)
                say("Gizli boss zaman aşımı")
                return true
            }
        }
        lastFightEnd = System.currentTimeMillis()
        routeKills++
        ev("Gizli boss kesildi")
        say("Gizli boss kesildi")
        return true
    }

    /** Bu haritanın gizli noktalarına sırayla gider (rota Random açıksa Random ile); mini haritada boss görürse keser. */
    private fun routeHidden(name: String) {
        val pts = hiddenPts(name)
        for ((i, pt) in pts.withIndex()) {
            val label = "Gizli nokta " + (i + 1) + "/" + pts.size
            say(label + ": gidiliyor")
            val mb = ensureMap()
            try {
                approach(mb, pt.x, pt.y, label, cfgI("routeRadius", 150).toFloat(), on("routeRandomOn"))
            } catch (e: StepFail) {
                continue
            }
            var found = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 4000L) {
                val b = shot() ?: continue
                if (orangeAt(b, "closeMap")) {
                    tapPoint("closeMap")
                    pause(500)
                    continue
                }
                if (miniRedAlive(b)) {
                    found = true
                    break
                }
                pause(300)
            }
            if (!found) {
                say(label + ": boss yok")
                continue
            }
            hiddenFight("Gizli boss")
        }
    }

    // ------------------------------------------------------------------ rota: yoksayılacak (kesilmeyecek) boss noktaları

    private fun ignAll(): JSONObject =
        try {
            JSONObject(prefs().getString("ignorepts", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun ignPts(map: String): List<PointF> {
        val out = ArrayList<PointF>()
        try {
            val a = ignAll().optJSONArray(key(map)) ?: return out
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out.add(PointF(o.getDouble(0).toFloat(), o.getDouble(1).toFloat()))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun ignSave(map: String, pts: List<PointF>) {
        val all = ignAll()
        val a = JSONArray()
        for (p in pts) {
            val o = JSONArray()
            o.put(p.x.toDouble())
            o.put(p.y.toDouble())
            a.put(o)
        }
        if (pts.isEmpty()) all.remove(key(map)) else all.put(key(map), a)
        prefs().edit().putString("ignorepts", all.toString()).apply()
    }

    /** Haritayı açar, canlı boss ikonlarını gösterir; dokunulanlar bu harita için yoksayılır (rota onlara gitmez). */
    private fun ignoreMark() {
        closeMapIfOpen()
        val info = readMapWireRetry()
        val mb = ensureMap()
        refreshScreen()
        val skulls = scanSkulls(mb) { isRedIcon(it) || isBossRed(it) }
        say("Yoksay işaretleme: " + info.first + " · " + skulls.size + " ikon bulundu")
        ui { showIgnoreMark(info.first, skulls) }
    }

    private fun showIgnoreMark(map: String, skulls: List<PointF>) {
        removeExtra()
        val picked = ArrayList<PointF>()
        val existing = ignPts(map).map { PointF(it.x * screenW, it.y * screenH) }
        val view = RedMarkView(this, skulls, existing, picked)
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x22000000)
        fl.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = rounded(0xEE161920.toInt(), 12)
        bar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 12f
        fun refreshInfo() {
            info.text = map + "\nYoksayılacak bossa dokun. Yeni: " + picked.size + " · kayıtlı: " + existing.size + " (camgöbeği)"
        }
        refreshInfo()
        bar.addView(info)
        bar.addView(mkBtn("Kaydet", OK, 0xFF04200F.toInt()) {
            val pts = ArrayList<PointF>(ignPts(map))
            for (k in picked) pts.add(PointF(k.x / screenW, k.y / screenH))
            ignSave(map, pts)
            removeExtra()
            toast(picked.size.toString() + " nokta yoksayılacak (" + map + ")")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(90), dp(40)))
        bar.addView(mkBtn("Haritayı sil", BAD) {
            ignSave(map, emptyList())
            removeExtra()
            toast("Bu haritanın yoksay listesi silindi")
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(110), dp(40)))
        bar.addView(mkBtn("İptal") {
            removeExtra()
            if (!busy) job { closeMapIfOpen() }
        }, LinearLayout.LayoutParams(dp(80), dp(40)))
        fl.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val t = PointF(ev.rawX, ev.rawY)
                var near: PointF? = null
                var nd = 55f
                for (k in skulls) {
                    val d = dist(k, t)
                    if (d < nd) {
                        nd = d
                        near = k
                    }
                }
                val spot = near ?: t
                val already = picked.firstOrNull { dist(it, spot) < 30f }
                if (already != null) picked.remove(already) else picked.add(spot)
                view.invalidate()
                refreshInfo()
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

    // ------------------------------------------------------------------ zaman kayıtları (günlük)

    private fun stampList(): MutableList<Long> =
        prefs().getString("stamps", "")!!.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()

    private fun saveStamps(l: List<Long>) {
        prefs().edit().putString("stamps", l.joinToString(",")).apply()
    }

    private fun fmtStamp(t: Long): String =
        java.text.SimpleDateFormat("dd.MM  HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** Her basışta güncel saati kaydeder. */
    private fun addStamp() {
        val now = System.currentTimeMillis()
        val l = stampList()
        l.add(now)
        saveStamps(l)
        toast("Kaydedildi #" + l.size + "  " + fmtStamp(now))
        if (stampWinOpen) {
            closeBossWin()
            toggleStampWin()
        }
    }

    private fun toggleStampWin() {
        if (bossWin != null) {
            val was = stampWinOpen
            closeBossWin()
            if (was) return
        }
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(14), dp(12), dp(14), dp(14))
        val list = stampList()
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = "Zaman kayıtları (" + list.size + ")"
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(mkBtn("Sıfırla", BAD) {
            saveStamps(emptyList())
            closeBossWin()
            toggleStampWin()
        }, LinearLayout.LayoutParams(dp(90), dp(36)))
        val sp = View(this)
        head.addView(sp, LinearLayout.LayoutParams(dp(6), 1))
        head.addView(mkBtn("Kapat ✕") { closeBossWin() }, LinearLayout.LayoutParams(dp(90), dp(36)))
        outer.addView(head)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (list.isEmpty()) {
            val e0 = TextView(this)
            e0.text = "Henüz kayıt yok. Araçlar sekmesinde \"Zaman kaydet\"e bas."
            e0.setTextColor(TEXT_DIM)
            e0.setPadding(0, dp(8), 0, 0)
            col.addView(e0)
        }
        for (i in list.indices.reversed()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 10)
            row.setPadding(dp(12), dp(8), dp(12), dp(8))
            val n = TextView(this)
            n.text = "#" + (i + 1)
            n.textSize = 12f
            n.setTextColor(TEXT_DIM)
            row.addView(n, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            val tt = TextView(this)
            tt.text = fmtStamp(list[i])
            tt.textSize = 15f
            tt.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tt.setTextColor(Color.WHITE)
            row.addView(tt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (i > 0) {
                val d = TextView(this)
                val sec = (list[i] - list[i - 1]) / 1000
                d.text = "+" + (sec / 60) + "dk " + (sec % 60) + "sn"
                d.textSize = 11f
                d.setTextColor(TEXT_DIM)
                row.addView(d)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            col.addView(row, lp)
        }
        val w = minOf(dp(440), (screenW * 0.6f).toInt())
        val lp = overlayParams(w)
        lp.height = (screenH * 0.82f).toInt()
        lp.x = (screenW - w) / 2
        lp.y = (screenH - lp.height) / 2
        wm.addView(outer, lp)
        bossWin = outer
        stampWinOpen = true
    }

    // ------------------------------------------------------------------ kronometre (ekranda sürüklenebilir)

    private var swWin: LinearLayout? = null
    private var swLp: WindowManager.LayoutParams? = null
    private var swText: TextView? = null
    private var swStartBtn: Button? = null
    private var swRunning = false
    private var swStart = 0L
    private var swAcc = 0L
    private val swTick = object : Runnable {
        override fun run() {
            updateSw()
            if (swWin != null) main.postDelayed(this, 100)
        }
    }

    private fun swElapsed(): Long = swAcc + (if (swRunning) SystemClock.elapsedRealtime() - swStart else 0L)

    private fun fmtSw(ms: Long): String {
        val t = ms / 100
        val tenth = t % 10
        val sec = (t / 10) % 60
        val min = (t / 600) % 60
        val h = t / 36000
        return if (h > 0) String.format("%d:%02d:%02d.%d", h, min, sec, tenth) else String.format("%02d:%02d.%d", min, sec, tenth)
    }

    private fun updateSw() {
        swText?.text = fmtSw(swElapsed())
        swStartBtn?.text = if (swRunning) "Duraklat" else (if (swAcc > 0) "Devam" else "Başlat")
    }

    private fun closeStopwatch() {
        main.removeCallbacks(swTick)
        val w = swWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        swWin = null
        swText = null
        swStartBtn = null
    }

    /** Ekranda her yere sürüklenebilir küçük kronometre. Kapatınca süre durmaz, yeniden açınca devam eder. */
    private fun toggleStopwatch() {
        if (swWin != null) {
            closeStopwatch()
            return
        }
        refreshScreen()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(0xEE161920.toInt(), 14)
        box.setPadding(dp(10), dp(6), dp(10), dp(8))
        val tv = TextView(this)
        tv.textSize = 24f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        swText = tv
        box.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val sb = mkBtn("Başlat", OK, 0xFF04200F.toInt()) {
            if (swRunning) {
                swAcc += SystemClock.elapsedRealtime() - swStart
                swRunning = false
            } else {
                swStart = SystemClock.elapsedRealtime()
                swRunning = true
            }
            updateSw()
        }
        swStartBtn = sb
        row.addView(sb, LinearLayout.LayoutParams(0, dp(34), 1.3f))
        val rb = mkBtn("Sıfırla") {
            swAcc = 0L
            swStart = SystemClock.elapsedRealtime()
            updateSw()
        }
        val rbl = LinearLayout.LayoutParams(0, dp(34), 1f)
        rbl.leftMargin = dp(4)
        row.addView(rb, rbl)
        val cb = mkBtn("✕") { closeStopwatch() }
        val cbl = LinearLayout.LayoutParams(dp(34), dp(34))
        cbl.leftMargin = dp(4)
        row.addView(cb, cbl)
        val rowLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(4)
        box.addView(row, rowLp)

        val lp = overlayParams(dp(190))
        lp.x = prefs().getInt("swx", (screenW * 0.35f).toInt()).coerceIn(0, maxOf(0, screenW - dp(190)))
        lp.y = prefs().getInt("swy", (screenH * 0.25f).toInt()).coerceIn(0, maxOf(0, screenH - dp(100)))
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        // sürükleme: süre yazısından ya da kenar boşluklarından tutulur
        val drag = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downX)).toInt().coerceIn(0, maxOf(0, screenW - box.width))
                    lp.y = (startY + (ev.rawY - downY)).toInt().coerceIn(0, maxOf(0, screenH - box.height))
                    try {
                        wm.updateViewLayout(box, lp)
                    } catch (e: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> prefs().edit().putInt("swx", lp.x).putInt("swy", lp.y).apply()
            }
            true
        }
        tv.setOnTouchListener(drag)
        box.setOnTouchListener(drag)
        wm.addView(box, lp)
        swWin = box
        swLp = lp
        updateSw()
        main.postDelayed(swTick, 100)
    }

    // ------------------------------------------------------------------ sahip kontrolü: boss çubuğunda başka oyuncunun adı

    @Volatile private var ownerLastChk = 0L
    @Volatile private var ownerStrikes = 0
    @Volatile private var ownerSkipped = false

    /** Boss çubuğu satırında kendi adın dışında bir oyuncu adı varsa onu döndürür, yoksa null. */
    private fun ownerOtherName(b: Bitmap): String? {
        val raw = ocrRegion(b, "ownTL", "ownBR") ?: return null
        val t = raw.replace("\n", " ").trim()
        val mine = (prefs().getString("cfg_myName", "yxesky") ?: "yxesky").trim().lowercase()
        if (mine.isEmpty() || t.isEmpty()) return null
        if (t.lowercase().contains(mine)) return null
        val q = maxOf(t.lastIndexOf('\''), t.lastIndexOf('\u2019'), t.lastIndexOf('"'))
        if (q < 0 || q >= t.length - 1) return null
        val tail = t.substring(q + 1).trim()
        val letters = tail.filter { it.isLetterOrDigit() }
        if (letters.length < 3) return null
        if (lev(letters.lowercase(), mine) <= 2) return null
        return tail
    }

    /** Savaş döngüsünde 2 sn'de bir bakar; üst üste 2 okumada başka ad görürse o adı döndürür. */
    private fun ownerTakenCheck(b: Bitmap): String? {
        if (!on("ownerOn") || !hasPt("ownTL") || !hasPt("ownBR")) return null
        val now = System.currentTimeMillis()
        if (now - ownerLastChk < 2000L) return null
        ownerLastChk = now
        val oth = ownerOtherName(b)
        if (oth == null) {
            ownerStrikes = 0
            return null
        }
        ownerStrikes++
        if (ownerStrikes < 2) return null
        ownerStrikes = 0
        return oth
    }

    private fun angelTest() {
        if (!hasPt("angel")) {
            say("Önce Ayar'da angel noktasını işaretle")
            return
        }
        val b = shot() ?: fail("Ekran görüntüsü alınamadı")
        val f = angelFrac(b)
        say("Angel yuvası parlak oran: " + String.format("%.1f", f * 1000f) + "‰ (eşik " + cfgI("angelThr", 30) + "‰) → " + (if (angelPresent(b)) "simge VAR" else "simge YOK") +
            "\nKayıt: " + (if (macroHas("angelbuy")) "var" else "yok"))
    }

    private fun ownerTest() {
        if (!hasPt("ownTL") || !hasPt("ownBR")) {
            say("Önce Ayar > Okuma bölgeleri'nde ownTL ve ownBR'yi işaretle")
            return
        }
        val b = shot() ?: fail("Ekran görüntüsü alınamadı")
        val oth = ownerOtherName(b)
        say("Okunan: '" + lastOcr + "'\n" + (if (oth == null) "Başka oyuncu yok (ya da okunamadı)" else "Başka oyuncu: " + oth))
    }

    // ------------------------------------------------------------------ hata sonrası Golden protokolü

    private var protoWin: View? = null
    private var protoTick: Runnable? = null
    @Volatile private var lastProtoAt = 0L

    private fun closeProto() {
        protoTick?.let { main.removeCallbacks(it) }
        protoTick = null
        val w = protoWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        protoWin = null
    }

    /** Otomasyon hata yüzünden durdu: uyarı verir, 10 sn içinde reddedilmezse Golden protokolünü uygular. */
    @Volatile private var stopReported = false
    @Volatile private var stopWhy = ""

    private fun errorStop(reason: String) {
        stopReported = true
        ev("Otomasyon hata ile durdu: " + reason, true, 5)
        if (!on("protoOn")) {
            ev("Golden protokolü kapalı, uygulanmıyor", true, 3)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastProtoAt < cfgI("protoCooldownMin", 15) * 60000L) {
            ev("Golden protokolü az önce uygulandı, tekrar denenmiyor", true, 4)
            return
        }
        ui { showProtoWarning(reason, false) }
    }

    private fun showProtoWarning(reason: String, test: Boolean) {
        closeProto()
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = framed(PANEL_BG, 18)
        outer.setPadding(dp(20), dp(22), dp(20), dp(18))
        val t = TextView(this)
        t.text = "GOLDEN PROTOKOLÜ"
        t.textSize = 16f
        t.letterSpacing = 0.08f
        t.gravity = Gravity.CENTER
        t.tag = "serif"
        t.typeface = android.graphics.Typeface.SERIF
        t.setTextColor(0xFFC9ADEB.toInt())
        outer.addView(t)
        val msg = TextView(this)
        msg.textSize = 13f
        msg.gravity = Gravity.CENTER
        msg.setTextColor(Color.WHITE)
        msg.setPadding(0, dp(8), 0, dp(10))
        outer.addView(msg)
        var left = cfgI("protoSec", 10).coerceIn(3, 60)
        fun label() {
            msg.text = (if (test) "TEST: " else if (protoIdleMode) "Otomasyon bir süredir bir şey kesmedi:" else "Otomasyon hata verip durdu:") + "\n" + reason + "\n\n" + left + " sn içinde Golden protokolü uygulanacak"
        }
        label()
        outer.addView(mkBtn("Reddet") {
            closeProto()
            protoIdleMode = false
            ev("Golden protokolü reddedildi", true, 3)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        val w = minOf(dp(300), (screenW * 0.85f).toInt())
        val lp = overlayParams(w)
        lp.x = (screenW - w) / 2
        lp.y = (screenH * 0.2f).toInt()
        applyFont(outer)
        wm.addView(outer, lp)
        protoWin = outer
        val tick = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) {
                    closeProto()
                    val idle = protoIdleMode
                    protoIdleMode = false
                    Thread { runGoldenProtocol(test, idle) }.start()
                } else {
                    label()
                    main.postDelayed(this, 1000)
                }
            }
        }
        protoTick = tick
        main.postDelayed(tick, 1000)
    }

    /** Golden protokolü: seçili golden haritasında işaretli noktaları sırayla keser. Golden, Pot, Recycle ve Diril açık; rota, red, farm, boşta farm kapalı. Otomasyon yeniden başlar. */
    /** Menüdeki test düğmeleri: uyarıyla (geri sayım + Reddet) ya da hemen. */
    private fun protoTest(withWarning: Boolean) {
        if (autoRunning || busy) {
            say("Otomasyon çalışıyor. Önce durdur, sonra test et.")
            toast("Önce otomasyonu durdur")
            return
        }
        if (withWarning) ui { showProtoWarning("test", true) } else runGoldenProtocol(true)
    }

    private fun runGoldenProtocol(test: Boolean = false, restart: Boolean = false) {
        if ((autoRunning || busy) && restart) {
            ev("Golden protokolü: çalışan otomasyon durduruluyor", true, 3)
            stopJob()
            var w = 0
            while ((autoRunning || busy) && w < 100) {
                Thread.sleep(100)
                w++
            }
        }
        if (autoRunning || busy) {
            ev("Golden protokolü: otomasyon zaten çalışıyor", true, 3)
            return
        }
        if (!test) lastProtoAt = System.currentTimeMillis()
        val e = prefs().edit()
        for (k in listOf("routeOn", "redOn", "farmOn", "idleFarmOn")) e.putBoolean("cfg_" + k, false)
        e.putBoolean("cfg_goldOn", true).putBoolean("cfg_potOn", true).putBoolean("cfg_recycleOn", true).putBoolean("cfg_reviveOn", true).apply()
        goldHere = false
        redHere = false
        routeAt = false
        updateToggles()
        val gm = goldMapName()
        if (gm.isNotEmpty() && !macroHas("tp_" + gm)) ev("Uyarı: " + gm + " için ışınlanma kaydı yok, chat'ten gidilecek", true, 4)
        ev("Golden protokolü uygulandı, otomasyon başlatılıyor", true, 4)
        protoGold = true
        protoStarting = true
        startAuto()
    }

    // ------------------------------------------------------------------ harita seçici (golden / red boss haritası)

    private var pickWin: View? = null

    private fun closePick() {
        val w = pickWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        pickWin = null
    }

    /** Kayıtlı harita listesinden bir harita seçtirir. Işınlanma kaydı olanların yanında ● görünür. */
    private fun pickMap(title: String, current: String, onPick: (String) -> Unit) {
        closePick()
        refreshScreen()
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = framed(PANEL_BG, 18)
        outer.setPadding(dp(18), dp(20), dp(18), dp(16))
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.letterSpacing = 0.06f
        t.tag = "serif"
        t.typeface = android.graphics.Typeface.SERIF
        t.setTextColor(0xFFC9ADEB.toInt())
        outer.addView(t)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(col)
        outer.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.55f).toInt()).also { it.topMargin = dp(8) })
        for (m in maps()) {
            val rec = macroHas("tp_" + m.name)
            val sel = key(m.name) == key(current)
            val b = mkBtn((if (sel) "✓ " else "") + m.name + (if (rec) "   ●" else ""), if (sel) onFill() else CARD_BG) {
                closePick()
                onPick(m.name)
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            col.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).also { it.topMargin = dp(4) })
        }
        outer.addView(mkBtn("Kapat") { closePick() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).also { it.topMargin = dp(8) })
        val w = minOf(dp(320), (screenW * 0.85f).toInt())
        val lp = overlayParams(w)
        lp.x = (screenW - w) / 2
        lp.y = (screenH * 0.12f).toInt()
        applyFont(outer)
        wm.addView(outer, lp)
        pickWin = outer
    }

    private fun pickGoldMap() {
        pickMap("Golden haritası  (● = ışınlanma kaydı var)", goldMapName()) { name ->
            val old = goldMapName()
            if (old.isNotEmpty() && key(old) != key(name) && goldSnapshot().isNotEmpty()) {
                synchronized(lock) { golds.clear() }
                saveGolds()
                toast("Golden haritası değişti, eski noktalar silindi. Golden işaretle ile yeniden seç.")
            }
            prefs().edit().putString("goldMap", name).apply()
            goldHere = false
            refreshHome()
        }
    }

    private fun pickRedMap() {
        pickMap("Red boss haritası  (● = ışınlanma kaydı var)", redMapName()) { name ->
            val old = redMapName()
            if (old.isNotEmpty() && key(old) != key(name) && redSnapshot().isNotEmpty()) {
                synchronized(lock) { reds.clear() }
                saveReds()
                toast("Red boss haritası değişti, eski noktalar silindi. Red işaretle ile yeniden seç.")
            }
            prefs().edit().putString("redMap", name).apply()
            redHere = false
            refreshHome()
        }
    }

    // ------------------------------------------------------------------ kısayollar penceresi

    private var scWin: LinearLayout? = null
    private var scLp: WindowManager.LayoutParams? = null
    private var scBody: LinearLayout? = null
    private var scEdit = false

    private fun scDefault() = "pot,buff,recycle,angel,t:randomOn"

    private fun scList(): MutableList<String> =
        (prefs().getString("shortcuts", scDefault()) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun scSave(l: List<String>) {
        prefs().edit().putString("shortcuts", l.joinToString(",")).apply()
    }

    private fun quickTap(name: String) {
        if (!hasPt(name)) {
            toast("Önce ayardan işaretle: " + name)
            return
        }
        Thread {
            try {
                tapPoint(name)
            } catch (e: Exception) {
            }
        }.start()
    }

    private class ScItem(val key: String, val label: String, val icon: String, val act: () -> Unit)

    /** Eklenebilir kısayollar. "t:" ile başlayanlar açık/kapalı düğmeleridir. */
    private fun scCatalog(): List<ScItem> {
        val l = ArrayList<ScItem>()
        l.add(ScItem("pot", "Pot", "pot") { runManual { doPots() } })
        l.add(ScItem("recycle", "Recycle", "recycle") { runManual { doRecycle() } })
        l.add(ScItem("buff", "Buff", "buff") { runManual { doBuff(cfgI("minAtk", 0), cfgI("minDef", 0)) } })
        l.add(ScItem("angel", "Angel", "angel") { quickTap("angel") })
        l.add(ScItem("autoAtk", "Oto atak", "sword") { quickTap("autoAtk") })
        l.add(ScItem("shield", "Kalkan", "shield") { quickTap("shield") })
        l.add(ScItem("imp", "Imp", "imp") { quickTap("imp") })
        l.add(ScItem("revive", "Diril", "heart") { quickTap("revive") })
        l.add(ScItem("farm", "Farma git", "skull") { runManual { doFarm() } })
        l.add(ScItem("stop", "Durdur", "pause") { stopJob() })
        l.add(ScItem("alive", "Canlı kaydet", "target") { runManual { doSaveAlive() } })
        l.add(ScItem("stamp", "Zaman kaydet", "pen") { addStamp() })
        l.add(ScItem("sw", "Kronometre", "stopwatch") { toggleStopwatch() })
        val ic = mapOf(
            "potOn" to "pot", "recycleOn" to "recycle", "farmOn" to "skull", "impOn" to "imp", "angelOn" to "angel", "reviveOn" to "heart",
            "buffOn" to "buff", "randomOn" to "dice", "goldOn" to "star", "redOn" to "gem", "routeOn" to "route", "idleFarmOn" to "moon", "macroTpOn" to "pin", "protoOn" to "shield", "ownerOn" to "target", "popupOn" to "popup"
        )
        for (t in TOGGLES) l.add(ScItem("t:" + t.first, t.second, ic[t.first] ?: "dice") {
            prefs().edit().putBoolean("cfg_" + t.first, !on(t.first)).apply()
            updateToggles()
        })
        return l
    }

    private fun scRender() {
        val body = scBody ?: return
        body.removeAllViews()
        val cat = scCatalog()
        val chosen = scList().filter { k -> cat.any { it.key == k } }
        fun grid(keys: List<String>, add: Boolean) {
            for (chunk in keys.chunked(3)) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for ((j, k) in chunk.withIndex()) {
                    val c = cat.first { it.key == k }
                    val isTog = k.startsWith("t:")
                    val state: Boolean? = if (isTog && !scEdit && !add) on(k.substring(2)) else null
                    val label = if (add) "＋ " + c.label else if (scEdit) "✕ " + c.label else c.label
                    val tile = mkTile(c.icon, label, state, dp(64)) {
                        if (add) {
                            val l = scList()
                            if (!l.contains(k)) l.add(k)
                            scSave(l)
                            scRender()
                        } else if (scEdit) {
                            val l = scList()
                            l.remove(k)
                            scSave(l)
                            scRender()
                        } else {
                            c.act()
                            if (isTog) scRender()
                        }
                    }
                    val tl = LinearLayout.LayoutParams(0, dp(64), 1f)
                    if (j > 0) tl.leftMargin = dp(4)
                    row.addView(tile, tl)
                }
                for (j in chunk.size until 3) row.addView(View(this), LinearLayout.LayoutParams(0, dp(64), 1f).also { it.leftMargin = dp(4) })
                val rl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rl.topMargin = dp(4)
                body.addView(row, rl)
            }
        }
        if (chosen.isEmpty() && !scEdit) {
            val t = TextView(this)
            t.text = "Boş. ✎ ile düğme ekle."
            t.textSize = 12f
            t.setTextColor(Color.LTGRAY)
            body.addView(t)
        }
        grid(chosen, false)
        if (scEdit) {
            val h = TextView(this)
            h.text = "Eklenebilir (dokun → ekle)"
            h.textSize = 11f
            h.setTextColor(ACCENT)
            h.setPadding(0, dp(8), 0, 0)
            body.addView(h)
            grid(cat.map { it.key }.filter { !chosen.contains(it) }, true)
        }
        applyFont(body)
    }

    // ------------------------------------------------------------------ yüzen boss ekranı

    private var bfWin: LinearLayout? = null
    private var bfLp: WindowManager.LayoutParams? = null
    private var bfBody: LinearLayout? = null
    private var bfTitle: TextView? = null
    private val bfTimers = HashMap<Long, TextView>()
    private var bfBlink = false

    private fun bfScale(): Float = when (prefs().getInt("bfSize", 1)) { 0 -> 0.8f; 2 -> 1.25f; else -> 1f }

    private fun closeBossFloat() {
        val w = bfWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        bfWin = null
        bfBody = null
        bfTitle = null
        bfTimers.clear()
        prefs().edit().putBoolean("bfOpen", false).apply()
    }

    private fun toggleBossFloat() {
        if (bfWin != null) {
            closeBossFloat()
            return
        }
        refreshScreen()
        val sc = bfScale()
        val width = (dp(210) * sc).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = framed(PANEL_BG, 14)
        box.setPadding(dp(8), dp(6), dp(8), dp(8))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "◈ BOSSLAR"
        title.textSize = 12f
        title.tag = "serif"
        title.typeface = android.graphics.Typeface.SERIF
        title.setTextColor(0xFFC9ADEB.toInt())
        title.gravity = Gravity.CENTER_VERTICAL
        bfTitle = title
        head.addView(title, LinearLayout.LayoutParams(0, dp(30), 1f))
        val sb = mkBtn("A") {
            prefs().edit().putInt("bfSize", (prefs().getInt("bfSize", 1) + 1) % 3).apply()
            closeBossFloat()
            toggleBossFloat()
        }
        sb.textSize = 11f
        head.addView(sb, LinearLayout.LayoutParams(dp(30), dp(28)))
        val cb = mkBtn("✕") { closeBossFloat() }
        cb.textSize = 11f
        head.addView(cb, LinearLayout.LayoutParams(dp(30), dp(28)).also { it.leftMargin = dp(4) })
        box.addView(head)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(body)
        box.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bfBody = body
        applyFont(head)

        val lp = overlayParams(width)
        lp.x = prefs().getInt("bfx", (screenW * 0.05f).toInt()).coerceIn(0, maxOf(0, screenW - width))
        lp.y = prefs().getInt("bfy", (screenH * 0.10f).toInt()).coerceIn(0, maxOf(0, screenH - dp(100)))
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val drag = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downX)).toInt().coerceIn(0, maxOf(0, screenW - box.width))
                    lp.y = (startY + (ev.rawY - downY)).toInt().coerceIn(0, maxOf(0, screenH - box.height))
                    try {
                        wm.updateViewLayout(box, lp)
                    } catch (e: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> prefs().edit().putInt("bfx", lp.x).putInt("bfy", lp.y).apply()
            }
            true
        }
        title.setOnTouchListener(drag)
        wm.addView(box, lp)
        bfWin = box
        bfLp = lp
        prefs().edit().putBoolean("bfOpen", true).apply()
        bfRender()
    }

    /** Süresi en yakın 8 boss: renk şeridi (harita+wire grubu), kısa ad, geri sayım. */
    private fun bfRender() {
        val body = bfBody ?: return
        val sc = bfScale()
        body.removeAllViews()
        bfTimers.clear()
        val list = snapshot().sortedBy { it.endMs }
        if (list.isEmpty()) {
            val t = TextView(this)
            t.text = "Kayıt yok"
            t.textSize = 11f * sc
            t.setTextColor(TEXT_DIM)
            body.addView(t)
        }
        for (e in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(CARD_BG, 8)
            val strip = View(this)
            strip.setBackgroundColor(groupColor(e))
            row.addView(strip, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
            val n = TextView(this)
            n.text = (if (e.id == currentId) "▶ " else "") + shortTitle(e)
            n.textSize = 11f * sc
            n.setTextColor(if (e.id == currentId) ACCENT else Color.WHITE)
            n.setPadding(dp(8), dp((5 * sc).toInt()), dp(4), dp((5 * sc).toInt()))
            row.addView(n, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val tm = TextView(this)
            tm.textSize = 13f * sc
            tm.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tm.setPadding(dp(4), 0, dp(8), 0)
            bfTimers[e.id] = tm
            row.addView(tm)
            row.setOnClickListener {
                if (autoRunning) say("Otomasyon açık. Önce durdur.") else runManual { goAndKill(e) }
            }
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) })
        }
        applyFont(body)
        bfTick()
        bfWin?.post { try { bfWin?.let { w -> bfLp?.let { wm.updateViewLayout(w, it) } } } catch (e: Exception) { } }
    }

    /** Geri sayımları günceller; en yakın boss süresi yaklaşınca çerçeve rengi değişir (≤ gitme süresi: yeşil yanıp söner, ≤ 2 dk: turuncu). */
    private fun bfTick() {
        val w = bfWin ?: return
        val now = System.currentTimeMillis()
        val lead = cfgI("leadSec", 20)
        var minLeft = Long.MAX_VALUE
        for (e in snapshot()) {
            val left = (e.endMs - now) / 1000
            if (left < minLeft) minLeft = left
            val tv = bfTimers[e.id] ?: continue
            tv.text = if (left > 0) fmt(left) else "+" + fmt(-left)
            tv.setTextColor(if (left <= lead) OK else if (left <= 120) WARN else Color.WHITE)
        }
        bfBlink = !bfBlink
        val col = when {
            minLeft == Long.MAX_VALUE -> 0
            minLeft <= lead -> if (bfBlink) OK else 0x663DDC84
            minLeft <= 120 -> WARN
            else -> 0
        }
        if (col != 0) {
            val g = GradientDrawable()
            g.setColor(Color.TRANSPARENT)
            g.cornerRadius = dp(14).toFloat()
            g.setStroke(dp(3), col)
            w.foreground = g
        } else w.foreground = null
        bfTitle?.text = "◈ BOSSLAR" + (if (minLeft != Long.MAX_VALUE && minLeft <= 120) "  " + fmt(maxOf(0L, minLeft)) else "")
    }

    private fun closeShortcuts() {
        val w = scWin ?: return
        try {
            wm.removeView(w)
        } catch (e: Exception) {
        }
        scWin = null
        scBody = null
        scEdit = false
        prefs().edit().putBoolean("scOpen", false).apply()
    }

    private fun toggleShortcuts() {
        if (scWin != null) {
            closeShortcuts()
            return
        }
        refreshScreen()
        val width = dp(250)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = framed(PANEL_BG, 16)
        box.setPadding(dp(12), dp(8), dp(12), dp(12))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "◆ KISAYOLLAR"
        title.textSize = 13f
        title.letterSpacing = 0.1f
        title.tag = "serif"
        title.typeface = android.graphics.Typeface.SERIF
        title.setTextColor(0xFFC9ADEB.toInt())
        head.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f).also { it.gravity = Gravity.CENTER_VERTICAL })
        title.gravity = Gravity.CENTER_VERTICAL
        val eb = mkBtn("✎") {
            scEdit = !scEdit
            scRender()
        }
        head.addView(eb, LinearLayout.LayoutParams(dp(34), dp(30)))
        val cb = mkBtn("✕") { closeShortcuts() }
        head.addView(cb, LinearLayout.LayoutParams(dp(34), dp(30)).also { it.leftMargin = dp(4) })
        box.addView(head)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        box.addView(body)
        scBody = body
        applyFont(head)

        val lp = overlayParams(width)
        lp.x = prefs().getInt("scx", (screenW * 0.05f).toInt()).coerceIn(0, maxOf(0, screenW - width))
        lp.y = prefs().getInt("scy", (screenH * 0.40f).toInt()).coerceIn(0, maxOf(0, screenH - dp(100)))
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val drag = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downX)).toInt().coerceIn(0, maxOf(0, screenW - box.width))
                    lp.y = (startY + (ev.rawY - downY)).toInt().coerceIn(0, maxOf(0, screenH - box.height))
                    try {
                        wm.updateViewLayout(box, lp)
                    } catch (e: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> prefs().edit().putInt("scx", lp.x).putInt("scy", lp.y).apply()
            }
            true
        }
        title.setOnTouchListener(drag)
        box.setOnTouchListener(drag)
        wm.addView(box, lp)
        scWin = box
        scLp = lp
        scEdit = false
        prefs().edit().putBoolean("scOpen", true).apply()
        scRender()
    }

    // ------------------------------------------------------------------ telefon bildirimi ve uzaktan sorgu (ntfy)

    private fun topic(): String = prefs().getString("cfg_ntfy", "")!!.trim()

    private fun ev(msg: String, push: Boolean = false, prio: Int = 3) {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        synchronized(evLog) {
            evLog.addLast(t + " " + msg)
            while (evLog.size > 80) evLog.removeFirst()
        }
        if (push) push(msg, prio)
    }

    private fun push(msg: String, prio: Int = 3) {
        val tp = topic()
        if (tp.isEmpty()) return
        Thread { postNtfy(tp, msg.toByteArray(Charsets.UTF_8), prio, null) }.start()
    }

    private fun postNtfy(tp: String, body: ByteArray, prio: Int, filename: String?) {
        try {
            val c = java.net.URL("https://ntfy.sh/" + tp).openConnection() as java.net.HttpURLConnection
            c.requestMethod = if (filename != null) "PUT" else "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Tags", "robot")
            c.setRequestProperty("Priority", prio.toString())
            c.setRequestProperty("Title", "Richingen")
            if (filename != null) c.setRequestProperty("Filename", filename)
            c.outputStream.use { it.write(body) }
            c.responseCode
            c.disconnect()
        } catch (e: Exception) {
        }
    }

    private fun statusText(): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append(if (autoRunning) "Otomasyon AÇIK (" + ((now - startedAt) / 60000) + " dk)" else "Otomasyon kapalı")
        sb.append("\nBoss " + kills + " · Golden " + goldKills + " · Red " + redKills + " · Rota " + routeKills + " · Ölüm " + deaths + (if (farming) " · farmda" else ""))
        sb.append("\nŞu an: " + lastSay.replace("\n", " · "))
        val nx = snapshot().sortedBy { it.endMs }.take(5)
        if (nx.isNotEmpty()) {
            sb.append("\nSıradakiler:")
            for (e in nx) sb.append("\n " + shortTitle(e) + "  " + (if (e.endMs > now) fmt((e.endMs - now) / 1000) else "doğdu"))
        }
        sb.append("\nSon olaylar:")
        synchronized(evLog) { for (l in evLog.toList().takeLast(6)) sb.append("\n " + l) }
        return sb.toString()
    }

    private fun findProfile(arg: String): String? =
        profileNames(prefs()).firstOrNull { it.equals(arg.trim(), ignoreCase = true) }

    private fun findToggle(arg: String): Triple<String, String, Boolean>? {
        val a = arg.trim().lowercase()
        if (a.isEmpty()) return null
        return TOGGLES.firstOrNull { it.second.lowercase() == a || it.first.lowercase() == a || it.first.lowercase() == a + "on" }
    }

    private var cmdDepth = 0

    /** Kullanıcının uygulamada tanımladığı komut takma adları: "komut = eylem ; eylem". */
    private fun aliasFor(raw: String): String? {
        val low = raw.lowercase().trim()
        val word = low.substringBefore(' ')
        val rest = raw.trim().substringAfter(' ', "").trim()
        for (line in (prefs().getString("ntfyCmds", "") ?: "").lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k.isEmpty() || v.isEmpty()) continue
            if (k == low) return v
            if (k == word && rest.isNotEmpty()) return v + " " + rest
        }
        return null
    }

    private fun handleCmd(cmd: String) {
        if (cmdDepth < 2) {
            val al = aliasFor(cmd)
            if (al != null) {
                cmdDepth++
                try {
                    for (part in al.split(";")) if (part.isNotBlank()) {
                        handleCmd(part)
                        Thread.sleep(1500)
                    }
                } finally {
                    cmdDepth--
                }
                return
            }
        }
        val tp = topic()
        val raw = cmd.trim()
        val first = raw.lowercase().substringBefore(' ')
        val arg = raw.substringAfter(' ', "").trim()
        when (first) {
            "durum", "?", "status" -> push(statusText(), 3)
            "log", "günlük", "gunluk" -> {
                val l = synchronized(evLog) { evLog.toList().takeLast(30) }
                push(if (l.isEmpty()) "Günlük boş" else l.joinToString("\n"), 3)
            }
            "ekran", "screen" -> Thread { sendScreen(tp) }.start()
            "planlar", "plans", "profiller", "profiles" -> {
                val a = prefs().getString("profile_active", "") ?: ""
                val n = profileNames(prefs())
                push(if (n.isEmpty()) "Kayıtlı profil yok" else n.joinToString("\n") { (if (it == a) "● " else "○ ") + it }, 3)
            }
            "plan", "profil", "profile" -> {
                if (first == "plan" && arg.isEmpty()) {
                    handleCmd("planlar")
                    return
                }
                if (first == "plan") {
                    handleCmd("başlat " + arg)
                    return
                }
                val n = findProfile(arg)
                if (n == null) push("Profil bulunamadı: " + arg + "\nKayıtlılar için: profiller", 3)
                else ui {
                    if (autoRunning || busy) push("Otomasyon açık, önce 'durdur' yaz", 3)
                    else {
                        loadProfile(n)
                        push("Profil yüklendi: " + n + "\nBaşlatmak için: başlat", 3)
                    }
                }
            }
            "başlat", "baslat", "start" -> ui {
                if (arg.isNotEmpty()) {
                    val n = findProfile(arg)
                    if (n == null) {
                        push("Profil bulunamadı: " + arg + "\nKayıtlılar için: profiller", 3)
                        return@ui
                    }
                    if (autoRunning || busy) {
                        push("Otomasyon açık, önce 'durdur' yaz", 3)
                        return@ui
                    }
                    loadProfile(n)
                }
                if (autoRunning) {
                    push("Otomasyon zaten çalışıyor", 3)
                    return@ui
                }
                val need = missingForAuto()
                if (need.isNotEmpty()) {
                    push("Başlatılamadı, eksik nokta: " + need.joinToString(", "), 4)
                    return@ui
                }
                startAuto()
                push("Otomasyon başlatıldı" + (if (arg.isNotEmpty()) " · profil: " + arg else ""), 3)
            }
            "durdur", "dur", "stop" -> {
                if (autoRunning || busy) {
                    stopJob()
                    push("Durduruldu", 3)
                } else push("Zaten durmuş", 2)
            }
            "seçenekler", "secenekler", "options" -> push(TOGGLES.joinToString("\n") { (if (on(it.first)) "✓ " else "✗ ") + it.second }, 3)
            "aç", "ac", "kapat" -> {
                val t = findToggle(arg)
                if (t == null) push("Seçenek bulunamadı: " + arg + "\nListe için: seçenekler", 3)
                else {
                    val want = first != "kapat"
                    prefs().edit().putBoolean("cfg_" + t.first, want).apply()
                    ui { updateToggles() }
                    push(t.second + ": " + (if (want) "AÇIK" else "KAPALI"), 3)
                }
            }
            "iptal", "reddet", "cancel" -> ui {
                if (protoWin != null) {
                    closeProto()
                    ev("Golden protokolü uzaktan reddedildi", true, 3)
                } else push("Reddedilecek bir şey yok", 2)
            }
            else -> push("Komutlar: durum · log · ekran · profiller · profil <ad> · başlat [profil] · durdur · seçenekler · aç <seçenek> · kapat <seçenek> · reddet", 2)
        }
    }

    private fun sendScreen(tp: String) {
        var bm: Bitmap? = null
        try {
            if (busy) {
                shotOut = null
                shotReq = true
                var i = 0
                while (i < 40 && shotOut == null) {
                    Thread.sleep(500)
                    i++
                }
                shotReq = false
                bm = shotOut
            } else {
                val g = grab()
                if (g != null) bm = Bitmap.createScaledBitmap(g, g.width / 2, g.height / 2, true)
            }
        } catch (e: Exception) {
        }
        if (bm == null) {
            push("Ekran görüntüsü alınamadı", 2)
            return
        }
        val bos = java.io.ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        postNtfy(tp, bos.toByteArray(), 3, "ekran.jpg")
    }

    private fun startPoller() {
        if (pollRun) return
        pollRun = true
        lastCmdTime = System.currentTimeMillis() / 1000
        val t = Thread {
            while (pollRun) {
                try {
                    Thread.sleep(15000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val tp = topic()
                if (tp.isEmpty()) continue
                try {
                    val c = java.net.URL("https://ntfy.sh/" + tp + "/json?poll=1&since=" + lastCmdTime).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 15000
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    for (line in text.split("\n")) {
                        if (line.isBlank()) continue
                        val j = JSONObject(line)
                        if (j.optString("event") != "message") continue
                        val tm = j.optLong("time")
                        if (tm <= lastCmdTime) continue
                        lastCmdTime = tm
                        if (j.optJSONArray("tags")?.toString()?.contains("robot") == true) continue
                        handleCmd(j.optString("message"))
                    }
                } catch (e: Exception) {
                }
            }
        }
        pollThread = t
        t.start()
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
            gh.setPadding(0, dp(12), 0, dp(6))
            list.addView(gh)
            val gbody = LinearLayout(this)
            gbody.orientation = LinearLayout.VERTICAL
            list.addView(gbody)
            val gk = "mg_" + g.first.take(12)
            var gopen = prefs().getBoolean(gk, true)
            fun gap2() {
                gh.text = (if (gopen) "▾  " else "▸  ") + g.first
                gbody.visibility = if (gopen) View.VISIBLE else View.GONE
            }
            gap2()
            gh.setOnClickListener {
                gopen = !gopen
                prefs().edit().putBoolean(gk, gopen).apply()
                gap2()
            }
            for (n in g.second) {
                val set = prefs().contains(n + "_x") || DEFAULTS.containsKey(n)
                val label = LABELS[n] ?: ("PM'de " + n.removePrefix("link_") + " haritasının ışınlanma bağlantısı")
                val btn = mkBtn((if (set) "✓  " else "✗  ") + label, if (set) CARD_BG else MISSING_BG) {
                    beginCapture(n, label)
                }
                btn.textSize = 11f
                btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                btn.setPadding(dp(12), dp(8), dp(12), dp(8))
                addBtn(gbody, btn)
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

/** Çizgi stilinde simgeler (24x24 ızgarada çizilir, boyuta göre ölçeklenir). */
class IconView(context: android.content.Context, private val kind: String, private val tint: Int) : android.view.View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeJoin = Paint.Join.ROUND
        line.color = tint
        fill.style = Paint.Style.FILL
        fill.color = tint
    }

    private fun arrowArc(c: Canvas, start: Float, sweep: Float) {
        val r = android.graphics.RectF(5f, 5f, 19f, 19f)
        c.drawArc(r, start, sweep, false, line)
        val a = Math.toRadians((start + sweep).toDouble())
        val ex = (12 + 7 * Math.cos(a)).toFloat()
        val ey = (12 + 7 * Math.sin(a)).toFloat()
        val t = a + Math.PI / 2
        val tx = Math.cos(t).toFloat()
        val ty = Math.sin(t).toFloat()
        val nx = Math.cos(a).toFloat()
        val ny = Math.sin(a).toFloat()
        val p = Path()
        p.moveTo(ex + tx * 3.2f, ey + ty * 3.2f)
        p.lineTo(ex + nx * 2.6f, ey + ny * 2.6f)
        p.moveTo(ex + tx * 3.2f, ey + ty * 3.2f)
        p.lineTo(ex - nx * 2.6f, ey - ny * 2.6f)
        c.drawPath(p, line)
    }

    override fun onDraw(c: Canvas) {
        val sc = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24f * sc) / 2f, (height - 24f * sc) / 2f)
        c.scale(sc, sc)
        line.strokeWidth = 1.7f
        val p = Path()
        when (kind) {
            "pot" -> {
                p.moveTo(9f, 3f); p.lineTo(15f, 3f)
                p.moveTo(10f, 3f); p.lineTo(10f, 9f); p.lineTo(5f, 18.5f)
                p.quadTo(4.2f, 21f, 6.8f, 21f); p.lineTo(17.2f, 21f)
                p.quadTo(19.8f, 21f, 19f, 18.5f); p.lineTo(14f, 9f); p.lineTo(14f, 3f)
                p.moveTo(7.5f, 15f); p.lineTo(16.5f, 15f)
                c.drawPath(p, line)
            }
            "recycle" -> {
                arrowArc(c, 200f, 140f)
                arrowArc(c, 20f, 140f)
            }
            "skull" -> {
                p.moveTo(5f, 11f)
                p.arcTo(android.graphics.RectF(5f, 3f, 19f, 17f), 180f, 180f)
                p.lineTo(19f, 14.5f); p.lineTo(16f, 16.5f); p.lineTo(16f, 20.5f)
                p.lineTo(8f, 20.5f); p.lineTo(8f, 16.5f); p.lineTo(5f, 14.5f); p.close()
                c.drawPath(p, line)
                c.drawCircle(9.2f, 11.2f, 1.9f, fill)
                c.drawCircle(14.8f, 11.2f, 1.9f, fill)
                p.reset(); p.moveTo(12f, 13.5f); p.lineTo(11f, 15.5f); p.lineTo(13f, 15.5f); p.close()
                c.drawPath(p, fill)
                p.reset(); p.moveTo(10.5f, 18f); p.lineTo(10.5f, 20.5f); p.moveTo(13.5f, 18f); p.lineTo(13.5f, 20.5f)
                c.drawPath(p, line)
            }
            "imp" -> {
                c.drawCircle(12f, 14f, 6.5f, line)
                p.moveTo(6.5f, 10f); p.lineTo(4f, 3f); p.lineTo(10f, 7.8f)
                p.moveTo(17.5f, 10f); p.lineTo(20f, 3f); p.lineTo(14f, 7.8f)
                c.drawPath(p, line)
                c.drawCircle(9.5f, 13.5f, 1.2f, fill)
                c.drawCircle(14.5f, 13.5f, 1.2f, fill)
                p.reset(); p.moveTo(9.5f, 17.5f); p.quadTo(12f, 19.5f, 14.5f, 17.5f)
                c.drawPath(p, line)
            }
            "angel" -> {
                c.drawOval(android.graphics.RectF(8f, 1.8f, 16f, 5.6f), line)
                p.moveTo(12f, 9f); p.quadTo(5f, 4.5f, 2f, 9.5f); p.quadTo(4f, 16.5f, 12f, 18f)
                p.moveTo(12f, 9f); p.quadTo(19f, 4.5f, 22f, 9.5f); p.quadTo(20f, 16.5f, 12f, 18f)
                p.moveTo(12f, 9f); p.lineTo(12f, 22f)
                c.drawPath(p, line)
            }
            "heart" -> {
                p.moveTo(12f, 20.5f)
                p.cubicTo(3.5f, 14.5f, 2.5f, 9f, 5.8f, 6.3f)
                p.cubicTo(8.3f, 4.3f, 11f, 5.8f, 12f, 8f)
                p.cubicTo(13f, 5.8f, 15.7f, 4.3f, 18.2f, 6.3f)
                p.cubicTo(21.5f, 9f, 20.5f, 14.5f, 12f, 20.5f)
                p.close()
                c.drawPath(p, line)
            }
            "buff" -> {
                p.moveTo(5.5f, 12.5f); p.lineTo(12f, 6f); p.lineTo(18.5f, 12.5f)
                p.moveTo(5.5f, 19f); p.lineTo(12f, 12.5f); p.lineTo(18.5f, 19f)
                c.drawPath(p, line)
            }
            "dice" -> {
                c.drawRoundRect(android.graphics.RectF(3.5f, 3.5f, 20.5f, 20.5f), 4f, 4f, line)
                for (pt in listOf(8.5f to 8.5f, 15.5f to 8.5f, 12f to 12f, 8.5f to 15.5f, 15.5f to 15.5f)) c.drawCircle(pt.first, pt.second, 1.3f, fill)
            }
            "star" -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = (12 + r * Math.cos(a)).toFloat()
                    val y = (12.8 + r * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, line)
            }
            "gem" -> {
                p.moveTo(7f, 4f); p.lineTo(17f, 4f); p.lineTo(21f, 10f); p.lineTo(12f, 21f); p.lineTo(3f, 10f); p.close()
                p.moveTo(3f, 10f); p.lineTo(21f, 10f)
                p.moveTo(9.5f, 4f); p.lineTo(8f, 10f); p.lineTo(12f, 21f)
                p.moveTo(14.5f, 4f); p.lineTo(16f, 10f); p.lineTo(12f, 21f)
                c.drawPath(p, line)
            }
            "route" -> {
                p.moveTo(12f, 2.5f); p.lineTo(19.5f, 20.5f); p.lineTo(12f, 16.5f); p.lineTo(4.5f, 20.5f); p.close()
                c.drawPath(p, line)
            }
            "moon" -> {
                val a = Path(); a.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                val b = Path(); b.addCircle(16.5f, 8.5f, 7.2f, Path.Direction.CW)
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, line)
            }
            "pin" -> {
                p.moveTo(12f, 21.5f)
                p.cubicTo(5f, 14f, 5f, 11f, 5f, 9.5f)
                p.arcTo(android.graphics.RectF(5f, 2.5f, 19f, 16.5f), 180f, 180f)
                p.cubicTo(19f, 11f, 19f, 14f, 12f, 21.5f)
                p.close()
                c.drawPath(p, line)
                c.drawCircle(12f, 9.5f, 2.6f, line)
            }
            "pause" -> {
                c.drawRoundRect(android.graphics.RectF(6.5f, 4.5f, 10.5f, 19.5f), 1.4f, 1.4f, fill)
                c.drawRoundRect(android.graphics.RectF(13.5f, 4.5f, 17.5f, 19.5f), 1.4f, 1.4f, fill)
            }
            "sword" -> {
                p.moveTo(4f, 20f); p.lineTo(18.5f, 5.5f); p.lineTo(20.5f, 3.5f); p.lineTo(20.5f, 7.5f); p.lineTo(6.5f, 21.5f)
                p.moveTo(7f, 13f); p.lineTo(11f, 17f)
                p.moveTo(3f, 21f); p.lineTo(6.5f, 17.5f)
                c.drawPath(p, line)
            }
            "shield" -> {
                p.moveTo(12f, 2.8f); p.lineTo(19.5f, 5.8f); p.lineTo(19.5f, 12f)
                p.quadTo(19.5f, 18.5f, 12f, 21.5f); p.quadTo(4.5f, 18.5f, 4.5f, 12f); p.lineTo(4.5f, 5.8f); p.close()
                c.drawPath(p, line)
            }
            "target" -> {
                c.drawCircle(12f, 12f, 7.5f, line)
                c.drawCircle(12f, 12f, 1.6f, fill)
                p.moveTo(12f, 1.5f); p.lineTo(12f, 6f); p.moveTo(12f, 18f); p.lineTo(12f, 22.5f)
                p.moveTo(1.5f, 12f); p.lineTo(6f, 12f); p.moveTo(18f, 12f); p.lineTo(22.5f, 12f)
                c.drawPath(p, line)
            }
            "popup" -> {
                c.drawRoundRect(android.graphics.RectF(3.5f, 4.5f, 20.5f, 19.5f), 2.2f, 2.2f, line)
                p.moveTo(9f, 9.5f); p.lineTo(15f, 15.5f); p.moveTo(15f, 9.5f); p.lineTo(9f, 15.5f)
                c.drawPath(p, line)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3.2f, line)
                c.drawCircle(12f, 12f, 6.6f, line)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    p.moveTo((12 + 6.6 * Math.cos(a)).toFloat(), (12 + 6.6 * Math.sin(a)).toFloat())
                    p.lineTo((12 + 9.6 * Math.cos(a)).toFloat(), (12 + 9.6 * Math.sin(a)).toFloat())
                }
                c.drawPath(p, line)
            }
            "list" -> {
                p.moveTo(9.5f, 6.5f); p.lineTo(20.5f, 6.5f)
                p.moveTo(9.5f, 12f); p.lineTo(20.5f, 12f)
                p.moveTo(9.5f, 17.5f); p.lineTo(20.5f, 17.5f)
                c.drawPath(p, line)
                for (y in listOf(6.5f, 12f, 17.5f)) c.drawCircle(4.8f, y, 1.3f, fill)
            }
            "stopwatch" -> {
                c.drawCircle(12f, 13.5f, 8f, line)
                p.moveTo(9.5f, 2.5f); p.lineTo(14.5f, 2.5f)
                p.moveTo(12f, 13.5f); p.lineTo(12f, 8.5f)
                p.moveTo(12f, 13.5f); p.lineTo(15.2f, 15.5f)
                p.moveTo(18.5f, 7f); p.lineTo(20f, 5.5f)
                c.drawPath(p, line)
            }
            "pen" -> {
                p.moveTo(4f, 20.5f); p.lineTo(5f, 15.5f); p.lineTo(16.5f, 4f); p.lineTo(20.5f, 8f); p.lineTo(9f, 19.5f); p.close()
                p.moveTo(14f, 6.5f); p.lineTo(18f, 10.5f)
                c.drawPath(p, line)
            }
            else -> c.drawCircle(12f, 12f, 6f, line)
        }
        c.restore()
    }
}

/** Gotik panel çerçevesi: çift hat, köşe süsleri, üstte ve altta mor taş. */
class GothicFrame(private val fillColor: Int, private val radius: Float, private val d: Float) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bt = b.bottom.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        c.drawRoundRect(android.graphics.RectF(l, t, r, bt), radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        paint.color = 0xFF5A5468.toInt()
        c.drawRoundRect(android.graphics.RectF(l + d, t + d, r - d, bt - d), radius, radius, paint)
        paint.strokeWidth = d
        paint.color = 0xFF6A3FA8.toInt()
        c.drawRoundRect(android.graphics.RectF(l + 5 * d, t + 5 * d, r - 5 * d, bt - 5 * d), maxOf(2f, radius - 4 * d), maxOf(2f, radius - 4 * d), paint)
        // köşe süsleri: elmas ve iki kısa kol
        paint.color = 0xFF9A93AD.toInt()
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            val cx = if (sx < 0) l + 11 * d else r - 11 * d
            val cy = if (sy < 0) t + 11 * d else bt - 11 * d
            val path = Path()
            path.moveTo(cx, cy - 3.5f * d); path.lineTo(cx + 3.5f * d, cy); path.lineTo(cx, cy + 3.5f * d); path.lineTo(cx - 3.5f * d, cy); path.close()
            paint.style = Paint.Style.FILL
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d
            c.drawLine(cx + sx * 5f * d, cy, cx + sx * 18f * d, cy, paint)
            c.drawLine(cx, cy + sy * 5f * d, cx, cy + sy * 18f * d, paint)
        }
        gem(c, (l + r) / 2f, t + 5 * d, 6f * d)
        gem(c, (l + r) / 2f, bt - 5 * d, 4.5f * d)
    }

    private fun gem(c: Canvas, cx: Float, cy: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = 0xFF9A93AD.toInt()
        for (sx in listOf(-1f, 1f)) {
            val w = Path()
            w.moveTo(cx + sx * (h + 3 * d), cy)
            w.quadTo(cx + sx * (h + 18 * d), cy - 4 * d, cx + sx * (h + 34 * d), cy)
            c.drawPath(w, paint)
        }
        val g = Path()
        g.moveTo(cx, cy - h); g.lineTo(cx + h * 0.8f, cy); g.lineTo(cx, cy + h); g.lineTo(cx - h * 0.8f, cy); g.close()
        paint.style = Paint.Style.FILL
        paint.color = 0xFF8A35D8.toInt()
        c.drawPath(g, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFD9C4F2.toInt()
        c.drawPath(g, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
