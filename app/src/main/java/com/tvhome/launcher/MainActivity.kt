package com.tvhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

// ---- Change these ----
private const val LAT = 42.33          // Detroit
private const val LON = -83.05
private const val PROFILE_NAME = "Kuljeet"
// ----------------------

private const val WHITE = 0xFFFFFFFF.toInt()
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class SunsetDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        p.shader = LinearGradient(
            0f, 0f, 0f, h * 0.75f,
            intArrayOf(0xFF1E1646.toInt(), 0xFF6B2F77.toInt(), 0xFFD9547A.toInt(), 0xFFFFA36B.toInt()),
            floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.5f, h * 0.68f, w * 0.35f, 0x88FFD59A.toInt(), 0x00FFD59A, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val cols = intArrayOf(0xFF8A4A7E.toInt(), 0xFF5A2E66.toInt(), 0xFF2E1A47.toInt(), 0xFF160D2B.toInt())
        for (i in cols.indices) {
            val base = h * (0.62f + 0.1f * i)
            val amp = h * (0.16f - 0.025f * i)
            val path = Path()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                val t = x / w * (5f + i * 2f) + i * 1.7f
                path.lineTo(x, base - amp * (abs(sin(t)) + 0.4f * abs(sin(t * 2.3f + i))))
                x += w / 80f
            }
            path.lineTo(w, h)
            path.close()
            p.color = cols[i]
            c.drawPath(path, p)
        }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.OPAQUE
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var bg: ImageView
    private lateinit var dim: View
    private lateinit var dateView: TextView
    private lateinit var cardsTitle: TextView
    private lateinit var cards: LinearLayout
    private lateinit var row: LinearLayout
    private var night = false
    private var lastWeather = 0L
    private var weatherText = ""
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("tv", 0) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun glass(r: Int, c: Int = 0x33FFFFFF) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
    }
    private fun text(s: String, sz: Float, c: Int) = TextView(this).apply {
        text = s; textSize = sz; setTextColor(c)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }

        // top bar: name left, moon right
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(PROFILE_NAME, 20f, WHITE), lp(0, WRAP).apply { weight = 1f })
        val moon = text("\u263E", 26f, WHITE).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = glass(24)
            isFocusable = true; isClickable = true
            setOnClickListener {
                night = !night
                dim.animate().alpha(if (night) 0.65f else 0f).setDuration(200).start()
            }
            setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.15f else 1f; v.scaleY = if (f) 1.15f else 1f }
        }
        top.addView(moon, lp(WRAP, WRAP))
        col.addView(top, lp(MATCH, WRAP))

        dateView = text("", 22f, 0xEEFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        col.addView(dateView, lp(MATCH, WRAP).apply { topMargin = dp(40) })

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            textSize = 210f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(WHITE)
        }
        clock.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = (r - l).toFloat()
            if (w > 0f) clock.paint.shader = LinearGradient(0f, 0f, w, 0f, WHITE, 0xFFFFB3D1.toInt(), Shader.TileMode.CLAMP)
        }
        col.addView(clock, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })

        col.addView(View(this), lp(1, 0).apply { weight = 1f })

        cardsTitle = text("Continue", 16f, 0xCCFFFFFF.toInt()).apply { visibility = View.GONE }
        col.addView(cardsTitle, lp(WRAP, WRAP))
        cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        col.addView(cards, lp(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(16) })

        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(12), 0, dp(12))
        }
        scroll.addView(row)
        col.addView(scroll, lp(MATCH, WRAP))

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        loadWallpaper()
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        refreshCards()
        applyHz(false)
        fetchWeather()
    }

    override fun onStop() {
        // free memory so the app you open gets the whole box
        ui.removeCallbacksAndMessages(null)
        bg.setImageDrawable(null)
        super.onStop()
    }

    override fun onBackPressed() {}

    private fun loadWallpaper() {
        val f = File(getExternalFilesDir(null), "wallpaper.jpg")
        var d: Drawable? = null
        if (f.exists()) try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 1920) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
            val bm = BitmapFactory.decodeFile(f.path, o2)
            if (bm != null) d = BitmapDrawable(resources, bm)
        } catch (e: Throwable) {}
        bg.setImageDrawable(d ?: SunsetDrawable())
    }

    private fun apps(): List<ResolveInfo> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p != packageName && seen.add(p)) out.add(r)
            }
        }
        return out.sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    private fun open(pkg: String) {
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
    }

    private fun tile(icon: Drawable?, glyph: String?, label: String, click: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val iconBox = FrameLayout(this).apply { background = glass(18) }
        if (icon != null) {
            iconBox.addView(ImageView(this).apply { setImageDrawable(icon) },
                FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        } else {
            iconBox.addView(TextView(this).apply {
                text = glyph; textSize = 30f; setTextColor(WHITE); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        box.addView(iconBox, lp(dp(76), dp(76)))
        val lab = text(label, 14f, WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
        }
        box.addView(lab, lp(dp(96), WRAP))
        box.isFocusable = true; box.isClickable = true
        box.setOnClickListener { click() }
        box.setOnFocusChangeListener { v, f ->
            val s = if (f) 1.18f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            iconBox.background = glass(18, if (f) 0x66FFFFFF else 0x33FFFFFF)
            lab.visibility = if (f) View.VISIBLE else View.INVISIBLE
        }
        return box
    }

    private fun refreshApps() {
        val pm = packageManager
        row.removeAllViews()
        for (r in apps()) {
            val pkg = r.activityInfo.packageName
            row.addView(tile(r.loadIcon(pm), null, r.loadLabel(pm).toString()) { open(pkg) })
        }
        row.addView(tile(null, "\u26A1", "Screen speed") { showSpeed() })
        row.addView(tile(null, "\u2699", "Settings") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) {}
        })
    }

    private fun recentPkgs(): List<String> = try {
        val um = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ok = apps().map { it.activityInfo.packageName }.toSet()
        um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7 * 86400000L, now)
            .filter { it.packageName in ok && it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }.distinct().take(4)
    } catch (e: Throwable) { emptyList() }

    private fun refreshCards() {
        cards.removeAllViews()
        val recent = recentPkgs()
        cardsTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        val pm = packageManager
        for (p in recent) {
            val ai = try { pm.getApplicationInfo(p, 0) } catch (e: Exception) { continue }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(24), dp(12))
                background = glass(20, 0x40FFFFFF)
                isFocusable = true; isClickable = true
                setOnClickListener { open(p) }
                setOnFocusChangeListener { v, f ->
                    val s = if (f) 1.06f else 1f
                    v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                    background = glass(20, if (f) 0x66FFFFFF else 0x40FFFFFF)
                }
            }
            c.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(ai)) }, lp(dp(44), dp(44)))
            c.addView(text(pm.getApplicationLabel(ai).toString(), 18f, WHITE).apply { setPadding(dp(14), 0, 0, 0) })
            cards.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(16) })
        }
    }

    // ---- Screen speed ----
    private fun sameRes(): List<android.view.Display.Mode> {
        val d = windowManager.defaultDisplay
        val cur = d.mode
        return d.supportedModes.filter {
            it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
        }
    }

    private fun showSpeed() {
        val offered = sameRes().map { Math.round(it.refreshRate) }.distinct().sorted().joinToString(", ")
        AlertDialog.Builder(this)
            .setTitle("Screen speed  (box offers: $offered Hz)")
            .setItems(arrayOf("60 Hz", "120 Hz", "Automatic")) { _, i ->
                prefs.edit().putInt("hz", intArrayOf(60, 120, 0)[i]).apply()
                applyHz(true)
            }.show()
    }

    private fun applyHz(msg: Boolean) {
        val hz = prefs.getInt("hz", 0)
        val t = if (hz == 0) null else
            sameRes().minByOrNull { abs(it.refreshRate - hz) }?.takeIf { abs(it.refreshRate - hz) < 2f }
        if (hz != 0 && t == null) {
            if (msg) Toast.makeText(this, "This box does not offer $hz Hz", Toast.LENGTH_LONG).show()
            return
        }
        val lp = window.attributes
        lp.preferredDisplayModeId = t?.modeId ?: 0
        window.attributes = lp
        if (Settings.System.canWrite(this)) try {
            Settings.System.putFloat(contentResolver, "min_refresh_rate", if (hz == 0) 0f else hz.toFloat())
            Settings.System.putFloat(contentResolver, "peak_refresh_rate", if (hz == 0) 240f else hz.toFloat())
        } catch (e: Exception) {}
        if (msg) Toast.makeText(this, if (hz == 0) "Automatic" else "$hz Hz set", Toast.LENGTH_LONG).show()
    }

    // ---- Weather ----
    private fun desc(c: Int) = when (c) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"
        in 51..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; in 95..99 -> "Storm"
        else -> ""
    }

    private fun fetchWeather() {
        dateView.text = dateFmt.format(Date()) + weatherText
        if (System.currentTimeMillis() - lastWeather < 900000) return
        lastWeather = System.currentTimeMillis()
        Thread {
            try {
                val c = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code&temperature_unit=fahrenheit")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                val s = "   \u2600  ${Math.round(j.getDouble("temperature_2m"))}\u00B0  ${desc(j.getInt("weather_code"))}"
                ui.post { weatherText = s; dateView.text = dateFmt.format(Date()) + s }
            } catch (e: Exception) { lastWeather = 0 }
        }.start()
    }
}
