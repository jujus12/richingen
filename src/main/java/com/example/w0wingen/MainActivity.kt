package com.example.w0wingen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ayar ekranı: sayısal değerler, açık/kapalı seçenekler ve harita listesi burada girilir. */
class MainActivity : Activity() {

    private class Field(val key: String, val label: String, val hint: String, val def: String, val decimal: Boolean = false)

    private val autoFields = listOf(
        Field("leadSec", "Bossa gitme payı", "Süresi bu kadar saniye kalınca (ya da geçmişse) bossa gidilir", "20"),
        Field("fightTimeout", "Savaş zaman aşımı", "Bir boss için en fazla bekleme (sn)", "300")
    )

    private val farmFields = listOf(
        Field("farmWire", "Farm wire'ı", "Farma giderken önce bu wire'a geçilir", "3"),
        Field("farmWait", "Farm konumuna varış bekleme", "Bağlantıya dokunduktan bu kadar sn sonra oto atak ve kalkan (sn)", "10")
    )

    private val moveFields = listOf(
        Field("radius", "Random yarıçapı", "Oyuncu boss noktasına bu kadar yaklaşınca Random bırakılıp yürünür (piksel, 0 = Random kapalı)", "200"),
        Field("randomWait", "Random sonrası bekleme", "Işınlanma yüklenirken beklenecek süre (sn)", "2.5", true),
        Field("randomMax", "En fazla Random", "Bu kadar denemede yaklaşılamazsa yürümeye geçilir", "40"),
        Field("arrive", "Varış mesafesi", "İmleç kayıtlı noktaya bu kadar yaklaşınca harita kapanır (piksel)", "45"),
        Field("walkTimeout", "Varış zaman aşımı", "Noktaya bu sürede varılamazsa vazgeçilir (sn)", "90"),
        Field("mapTravel", "Haritaya ışınlanma bekleme", "PM'deki bağlantıya tıkladıktan sonra yüklenme (sn)", "10"),
        Field("switchWait", "Wire değişimi bekleme", "Wire seçildikten sonra yüklenme (sn)", "6"),
        Field("sameBossPx", "Aynı boss say", "Aynı harita ve wire'da kayıtlı noktaya bu kadar yakın kayıt aynı boss sayılır (piksel)", "90")
    )

    private val buffFields = listOf(
        Field("minAtk", "Minimum ATK", "ATK ve DEF ikisi de altındaysa buff'a gidilir", "0"),
        Field("minDef", "Minimum DEF", "", "0"),
        Field("buffWire", "Buff wire'ı", "Buff'a giderken önce bu wire'a geçilir", "3"),
        Field("buffTravel", "Buff yürüme süresi", "Buff noktasına varmak için bekleme (sn)", "12"),
        Field("buffWait", "Buff bekleme sınırı", "Buff'ın gelmesini en fazla bu kadar bekle (sn)", "90")
    )

    private val shopFields = listOf(
        Field("potMin", "Pot eşiği", "HP veya MP pot sayısı bunun altına inerse alışverişe gidilir", "20"),
        Field("clickGap", "Tık aralığı", "Pot ve Recycle adımları arasındaki bekleme (sn). Oyundaki panelden de değişir", "1.0", true),
        Field("maintMin", "Bakım aralığı", "Otomasyon açıkken bu kadar dakikada bir pot okunur/alınır, ardından Recycle yapılır (dk)", "20")
    )

    private val BG = 0xFF0F1115.toInt()
    private val CARD = 0xFF1B1F2A.toInt()
    private val INPUT = 0xFF262B3A.toInt()
    private val ACCENT = 0xFF4FC3F7.toInt()
    private val DIM = 0xFF9AA3B5.toInt()

    private val inputs = HashMap<String, EditText>()
    private lateinit var mapsEdit: EditText
    private lateinit var randomOn: Switch
    private lateinit var farmOn: Switch
    private lateinit var buffOn: Switch
    private lateinit var potOn: Switch
    private lateinit var recycleOn: Switch

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))

        val title = TextView(this)
        title.text = "W0wingen"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(ACCENT)
        col.addView(title)
        val sub = TextView(this)
        sub.text = "MU Immortal · boss doğma süresi takibi ve otomasyon"
        sub.textSize = 13f
        sub.setTextColor(DIM)
        sub.setPadding(0, 0, 0, dp(14))
        col.addView(sub)

        val setup = card(col, "Kurulum")
        val steps = TextView(this)
        steps.textSize = 13f
        steps.setTextColor(Color.WHITE)
        steps.setLineSpacing(dp(3).toFloat(), 1f)
        steps.text = "1  Erişilebilirlik ayarlarından W0wingen'i aç.\n" +
            "    Açılmıyorsa: Uygulama bilgisi > ⋮ > Kısıtlı ayarlara izin ver.\n" +
            "2  Shizuku'yu başlat, oyunda panelden Araçlar > Shizuku ile izin ver.\n" +
            "3  Aşağıdaki değerleri ve harita listesini kaydet.\n" +
            "4  Oyunda sağ kenardaki tutamaca dokun, Araçlar > Ayar ile noktaları işaretle, Test ile okumayı dene.\n" +
            "5  Boss kesince Kaydet, hazır olunca Otomasyon."
        setup.addView(steps)
        val acc = button("Erişilebilirlik ayarlarını aç", false)
        acc.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        setup.addView(acc, lp(dp(10)))

        val mapsCard = card(col, "Haritalar")
        val mh = TextView(this)
        mh.text = "Her satır: harita adı;wire sayısı. Ad, mini haritada yazdığı gibi olmalı (Raklion'lar 3 wire, diğerleri 1)."
        mh.textSize = 11f
        mh.setTextColor(DIM)
        mapsCard.addView(mh)
        mapsEdit = EditText(this)
        mapsEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        mapsEdit.setText(prefs().getString("cfg_maps", TimerService.DEFAULT_MAPS))
        mapsEdit.setTextColor(Color.WHITE)
        mapsEdit.textSize = 13f
        mapsEdit.background = rounded(INPUT, 10)
        mapsEdit.setPadding(dp(10), dp(8), dp(10), dp(8))
        mapsEdit.minLines = 6
        mapsEdit.gravity = Gravity.TOP
        mapsCard.addView(mapsEdit, lp(dp(8)))

        val auto = card(col, "Otomasyon")
        for (f in autoFields) addField(auto, f)

        val farm = card(col, "Farm")
        farmOn = sw(farm, "Boss yokken farm konumuna git", "cfg_farmOn", true)
        for (f in farmFields) addField(farm, f)

        val move = card(col, "Işınlanma ve yürüme")
        randomOn = sw(move, "Bossa giderken Random ışınlanma açık", "cfg_randomOn", true)
        for (f in moveFields) addField(move, f)

        val buff = card(col, "Buff")
        buffOn = sw(buff, "Buff alma açık", "cfg_buffOn", false)
        for (f in buffFields) addField(buff, f)

        val shop = card(col, "Pot ve Recycle")
        potOn = sw(shop, "Pot alma açık", "cfg_potOn", true)
        addField(shop, shopFields[0])
        recycleOn = sw(shop, "Recycle açık (pot kontrolünden sonra)", "cfg_recycleOn", true)
        addField(shop, shopFields[1])

        val save = button("Kaydet", true)
        save.setOnClickListener {
            val e = prefs().edit()
            val all = autoFields + farmFields + moveFields + buffFields + shopFields
            for ((k, et) in inputs) {
                val f = all.first { it.key == k }
                val raw = et.text.toString().trim()
                e.putString("cfg_" + k, if (raw.isEmpty()) f.def else raw)
            }
            e.putBoolean("cfg_randomOn", randomOn.isChecked)
            e.putBoolean("cfg_farmOn", farmOn.isChecked)
            e.putBoolean("cfg_buffOn", buffOn.isChecked)
            e.putBoolean("cfg_potOn", potOn.isChecked)
            e.putBoolean("cfg_recycleOn", recycleOn.isChecked)
            val m = mapsEdit.text.toString().trim()
            e.putString("cfg_maps", if (m.isEmpty()) TimerService.DEFAULT_MAPS else m)
            e.apply()
            Toast.makeText(this, "Kaydedildi (oyundaki 'Ayar' menüsünü yeniden aç)", Toast.LENGTH_SHORT).show()
        }
        col.addView(save, lp(dp(6)))

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(col)
        setContentView(sv)
    }

    private fun sw(parent: LinearLayout, text: String, key: String, def: Boolean): Switch {
        val s = Switch(this)
        s.text = text
        s.setTextColor(Color.WHITE)
        s.textSize = 14f
        s.isChecked = prefs().getBoolean(key, def)
        parent.addView(s, lp(dp(4)))
        return s
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        l.topMargin = top
        return l
    }

    private fun card(parent: LinearLayout, heading: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(CARD, 16)
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        val h = TextView(this)
        h.text = heading
        h.textSize = 16f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(ACCENT)
        h.setPadding(0, 0, 0, dp(6))
        c.addView(h)
        parent.addView(c, lp(dp(12)))
        return c
    }

    private fun button(text: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(if (primary) 0xFF04202D.toInt() else Color.WHITE)
        b.background = rounded(if (primary) ACCENT else INPUT, 12)
        b.minHeight = dp(46)
        b.minimumHeight = dp(46)
        return b
    }

    private fun addField(parent: LinearLayout, f: Field) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val l = TextView(this)
        l.text = f.label
        l.textSize = 14f
        l.setTextColor(Color.WHITE)
        left.addView(l)
        if (f.hint.isNotEmpty()) {
            val hnt = TextView(this)
            hnt.text = f.hint
            hnt.textSize = 11f
            hnt.setTextColor(DIM)
            left.addView(hnt)
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val et = EditText(this)
        et.inputType = if (f.decimal) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        et.setText(prefs().getString("cfg_" + f.key, f.def))
        et.setTextColor(Color.WHITE)
        et.textSize = 15f
        et.gravity = Gravity.CENTER
        et.background = rounded(INPUT, 10)
        et.setPadding(dp(8), dp(8), dp(8), dp(8))
        inputs[f.key] = et
        val elp = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.leftMargin = dp(10)
        row.addView(et, elp)

        parent.addView(row, lp(dp(10)))
    }

    private fun prefs() = getSharedPreferences("w0wingen", Context.MODE_PRIVATE)
}
