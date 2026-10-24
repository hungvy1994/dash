package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Nút tròn có ký hiệu vẽ bằng code (không cần file ảnh). */
class IconButton(ctx: Context, private val type: String) : View(ctx) {
    var ring = 0            // màu viền, 0 = không viền
        set(v) { field = v; invalidate() }
    var active = false      // rec: đang ghi, lock: đang khóa
        set(v) { field = v; invalidate() }
    var label = ""          // chữ cho nút chất lượng
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E1E1E.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }

    init { isClickable = true; isFocusable = true }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (!isEnabled) 0.35f else if (isPressed) 0.6f else 1f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cx = w / 2f; val cy = height / 2f; val u = w / 100f
        c.drawCircle(cx, cy, cx - 1f, bg)
        fg.strokeWidth = 5f * u
        val rc = if (type == "rec") Color.WHITE else ring
        if (rc != 0) { fg.color = rc; c.drawCircle(cx, cy, cx - 3f * u, fg) }
        fg.color = Color.WHITE
        when (type) {
            "rec" -> if (active) c.drawRoundRect(cx - 18 * u, cy - 18 * u, cx + 18 * u, cy + 18 * u, 4 * u, 4 * u, fill)
                     else c.drawCircle(cx, cy, 26 * u, red)
            "cam" -> {
                c.drawRoundRect(cx - 26 * u, cy - 14 * u, cx + 26 * u, cy + 20 * u, 6 * u, 6 * u, fg)
                c.drawLine(cx - 10 * u, cy - 14 * u, cx - 6 * u, cy - 21 * u, fg)
                c.drawLine(cx - 6 * u, cy - 21 * u, cx + 6 * u, cy - 21 * u, fg)
                c.drawLine(cx + 6 * u, cy - 21 * u, cx + 10 * u, cy - 14 * u, fg)
                c.drawCircle(cx, cy + 3 * u, 9 * u, fg)
            }
            "dim" -> {
                val p = Path().apply { addCircle(cx, cy, 22 * u, Path.Direction.CW) }
                p.op(Path().apply { addCircle(cx + 11 * u, cy - 8 * u, 19 * u, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                c.drawPath(p, fill)
            }
            "gal" -> {
                c.drawRoundRect(cx - 26 * u, cy - 20 * u, cx + 26 * u, cy + 20 * u, 6 * u, 6 * u, fg)
                c.drawPath(Path().apply { moveTo(cx - 6 * u, cy - 10 * u); lineTo(cx - 6 * u, cy + 10 * u); lineTo(cx + 12 * u, cy); close() }, fill)
            }
            "pip" -> {
                c.drawRoundRect(cx - 26 * u, cy - 20 * u, cx + 26 * u, cy + 20 * u, 4 * u, 4 * u, fg)
                c.drawRect(cx + 2 * u, cy, cx + 20 * u, cy + 13 * u, fill)
            }
            "set" -> {
                c.drawCircle(cx, cy, 15 * u, fg)
                c.drawCircle(cx, cy, 6 * u, fg)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(cx + (15 * u * cos(a)).toFloat(), cy + (15 * u * sin(a)).toFloat(),
                        cx + (25 * u * cos(a)).toFloat(), cy + (25 * u * sin(a)).toFloat(), fg)
                }
            }
            "qual" -> {
                fill.textAlign = Paint.Align.CENTER; fill.typeface = Typeface.DEFAULT_BOLD
                fill.textSize = (if (label.length > 4) 24 else 30) * u
                c.drawText(label, cx, cy + 10 * u, fill)
            }
            "lock" -> {
                c.drawRoundRect(cx - 18 * u, cy - 4 * u, cx + 18 * u, cy + 20 * u, 4 * u, 4 * u, fill)
                c.drawArc(RectF(cx - 11 * u, cy - 22 * u, cx + 11 * u, cy + 8 * u), 180f, 180f, false, fg)
                c.drawLine(cx + 11 * u, cy - 7 * u, cx + 11 * u, cy - 4 * u, fg)
                if (active) c.drawLine(cx - 11 * u, cy - 7 * u, cx - 11 * u, cy - 4 * u, fg)
            }
            "save" -> {
                c.drawLine(cx, cy - 22 * u, cx, cy + 6 * u, fg)
                c.drawLine(cx - 12 * u, cy - 6 * u, cx, cy + 6 * u, fg)
                c.drawLine(cx + 12 * u, cy - 6 * u, cx, cy + 6 * u, fg)
                c.drawLine(cx - 20 * u, cy + 20 * u, cx + 20 * u, cy + 20 * u, fg)
            }
            "del" -> {
                c.drawLine(cx - 20 * u, cy - 12 * u, cx + 20 * u, cy - 12 * u, fg)
                c.drawLine(cx - 7 * u, cy - 12 * u, cx - 7 * u, cy - 19 * u, fg)
                c.drawLine(cx - 7 * u, cy - 19 * u, cx + 7 * u, cy - 19 * u, fg)
                c.drawLine(cx + 7 * u, cy - 19 * u, cx + 7 * u, cy - 12 * u, fg)
                c.drawRoundRect(cx - 14 * u, cy - 12 * u, cx + 14 * u, cy + 22 * u, 3 * u, 3 * u, fg)
                c.drawLine(cx - 5 * u, cy - 4 * u, cx - 5 * u, cy + 14 * u, fg)
                c.drawLine(cx + 5 * u, cy - 4 * u, cx + 5 * u, cy + 14 * u, fg)
            }
        }
    }
}

class MainActivity : AppCompatActivity() {
    private val segMs get() = longArrayOf(1, 3, 5)[prefs.getInt("seg", 1)] * 60_000L   // độ dài mỗi đoạn
    private val impactMs2 = 15f           // ngưỡng va chạm (m/s2 lệch khỏi trọng lực); tăng nếu bị khóa nhầm khi đường xóc

    private val h = Handler(Looper.getMainLooper())
    private val tickH = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("dashcam", MODE_PRIVATE) }
    private lateinit var provider: ProcessCameraProvider
    private lateinit var pvRear: PreviewView
    private lateinit var pvFront: PreviewView
    private lateinit var tvHud: TextView
    private lateinit var barView: LinearLayout
    private lateinit var bQual: IconButton
    private lateinit var bRec: IconButton
    private lateinit var capRec: TextView
    private lateinit var bMode: IconButton
    private lateinit var capMode: TextView
    private lateinit var bDim: IconButton
    private var vcs = listOf<Pair<String, VideoCapture<Recorder>>>()
    private val cur = mutableMapOf<String, Recording>()
    private val locked = mutableSetOf<String>()
    private var running = false
    private var dim = false
    private var qLabel = "720p"
    private var qBps = 2_500_000
    private val modes = arrayOf("Camera sau", "Camera trước", "Cả hai cam")

    @Volatile private var speedKmh = 0f
    @Volatile private var lat = Double.NaN
    @Volatile private var lon = Double.NaN
    @Volatile private var lastFix = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGps()
            if (r[Manifest.permission.CAMERA] == true) initCamera()
            else Toast.makeText(this, "Cần quyền camera để ghi hình", Toast.LENGTH_LONG).show()
        }
        ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION))
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(accel, it, SensorManager.SENSOR_DELAY_GAME) }
        tickH.post(tick)
    }

    /* ---------- GPS và va chạm ---------- */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
            override fun onLocationChanged(l: Location) {
                speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
                lat = l.latitude; lon = l.longitude; lastFix = System.currentTimeMillis()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("old API") override fun onStatusChanged(p: String?, st: Int, e: Bundle?) {}
        })
    }

    private val accel = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!running) return
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (abs(g - 9.81f) > impactMs2) locked.addAll(cur.keys)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun overlayText(fmt: SimpleDateFormat): String {
        val fresh = System.currentTimeMillis() - lastFix < 5000
        val sp = if (fresh) "${speedKmh.roundToInt()} km/h" else "-- km/h"
        val pos = if (fresh && !lat.isNaN()) "   %.5f, %.5f".format(Locale.US, lat, lon) else ""
        return fmt.format(Date()) + "   " + sp + pos
    }

    private val hudFmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
    private val tick = object : Runnable {
        override fun run() {
            tvHud.text = overlayText(hudFmt) + if (locked.isNotEmpty()) "   ĐÃ KHÓA ĐOẠN" else ""
            tickH.postDelayed(this, 1000)
        }
    }

    /* Vẽ giờ và tốc độ lên video, giữ chữ luôn thẳng dù xoay hoặc lật */
    private fun makeEffect(rel: Int, mirror: Boolean): OverlayEffect {
        val ht = HandlerThread("overlay").also { it.start() }
        val fx = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(ht.looper)) { }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
        val txt = Paint().apply { color = Color.WHITE; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val bg = Paint().apply { color = 0x99000000.toInt() }
        fx.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val bw = frame.size.width.toFloat(); val bh = frame.size.height.toFloat()
            val swap = rel == 90 || rel == 270
            val ow = if (swap) bh else bw; val oh = if (swap) bw else bh
            val m = Matrix().apply {
                postRotate(rel.toFloat())
                when (rel) { 90 -> postTranslate(bh, 0f); 180 -> postTranslate(bw, bh); 270 -> postTranslate(0f, bw) }
                if (mirror) postScale(-1f, 1f, ow / 2f, 0f)
            }
            val inv = Matrix(); m.invert(inv); c.setMatrix(inv)
            val f = oh / 24f
            txt.textSize = f
            c.drawRect(0f, oh - f * 1.9f, ow, oh, bg)
            c.drawText(overlayText(fmt), f * 0.6f, oh - f * 0.6f, txt)
            true
        }
        return fx
    }

    /* ---------- Giao diện ---------- */
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val row = LinearLayout(this)
        fun pv() = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        pvRear = pv(); pvFront = pv()
        row.addView(pvRear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(pvFront, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(row)
        tvHud = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(24, 16, 24, 16) }
        root.addView(tvHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // Nút tròn nhỏ chỉnh nhanh chất lượng
        bQual = IconButton(this, "qual").apply { contentDescription = "Chất lượng ghi hình"; setOnClickListener { cycleQuality() } }
        root.addView(bQual, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })

        barView = LinearLayout(this).apply { setBackgroundColor(0x66000000); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        fun item(type: String, caption: String, size: Int, click: () -> Unit): Pair<IconButton, TextView> {
            val b = IconButton(this, type).apply { contentDescription = caption; setOnClickListener { click() } }
            val t = TextView(this).apply { text = caption; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER; setShadowLayer(3f, 0f, 1f, Color.BLACK) }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(b, LinearLayout.LayoutParams(dp(size), dp(size)))
                addView(t)
            }
            barView.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b to t
        }
        item("cam", "Camera", 52) { applyChange { prefs.edit().putInt("mode", (prefs.getInt("mode", 0) + 1) % 3).apply() } }
            .let { bMode = it.first; capMode = it.second }
        item("rec", "Ghi", 68) { if (running) stopAll() else startAll() }
            .let { bRec = it.first; capRec = it.second }
        item("dim", "Tối màn hình", 52) {
            dim = !dim
            window.attributes = window.attributes.also { it.screenBrightness = if (dim) 0.01f else -1f }
            ui()
        }.let { bDim = it.first }
        item("gal", "Thư viện", 52) { if (running) stopAll(); startActivity(Intent(this, GalleryActivity::class.java)) }
        item("pip", "Thu nhỏ", 52) { enterPip() }
        item("set", "Cài đặt", 52) { showSettings() }
        root.addView(barView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        ui()
    }

    private fun ui() {
        bRec.active = running
        capRec.text = if (running) "Dừng" else "Ghi"
        capMode.text = modes[prefs.getInt("mode", 0)]
        bDim.ring = if (dim) 0xFFFFB300.toInt() else 0
        bQual.label = qLabel
        bQual.ring = intArrayOf(0xFF66BB6A.toInt(), 0xFFFFB300.toInt(), 0xFFE53935.toInt())[prefs.getInt("q", 1)]
    }

    /* Mọi thay đổi cấu hình: dừng ghi, áp dụng, rồi ghi tiếp bằng video mới */
    private fun applyChange(after: (() -> Unit)? = null, change: () -> Unit) {
        val was = running
        if (was) stopAll()
        change()
        val go = Runnable { bind(); if (was) startAll(); after?.invoke() }
        if (was) h.postDelayed(go, 900) else go.run()
    }

    private fun cycle(k: String, n: Int, def: Int) = prefs.edit().putInt(k, (prefs.getInt(k, def) + 1) % n).apply()
    private fun toggle(k: String) = prefs.edit().putBoolean(k, !prefs.getBoolean(k, false)).apply()

    private fun cycleQuality() {
        applyChange(after = {
            Toast.makeText(this, "$qLabel, khoảng ${(qBps * 7.5 / 1_000_000).roundToInt()} MB mỗi phút cho mỗi camera", Toast.LENGTH_LONG).show()
        }) { cycle("q", 3, 1) }
    }

    /* ---------- Cài đặt và dung lượng ---------- */
    private fun capGb() = prefs.getFloat("capGb", 5f)
    private fun fmtGb(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.1f".format(Locale.US, f)

    private fun usageText(): String {
        val fs = dir().listFiles { x -> x.extension == "mp4" }?.toList() ?: emptyList()
        val lk = fs.filter { it.name.startsWith("LOCK_") }.sumOf { it.length() }
        val un = fs.sumOf { it.length() } - lk
        fun g(b: Long) = "%.2f GB".format(Locale.US, b / 1073741824.0)
        return "Đang dùng: ${g(un)} (chưa khóa), ${g(lk)} (đã khóa). Còn trống trên máy: ${g(dir().usableSpace)}."
    }

    private fun showSettings() {
        val segs = intArrayOf(1, 3, 5)
        fun onOff(k: String) = if (prefs.getBoolean(k, false)) "bật" else "tắt"
        val c = capGb()
        val items = arrayOf(
            "Dung lượng tối đa: " + if (c <= 0f) "không giới hạn" else fmtGb(c) + " GB",
            "Độ dài mỗi đoạn: " + segs[prefs.getInt("seg", 1)] + " phút",
            "Lật ngang: " + onOff("fh"),
            "Lật dọc: " + onOff("fv"))
        AlertDialog.Builder(this).setTitle("Cài đặt").setItems(items) { _, w ->
            when (w) {
                0 -> askCap()
                1 -> { cycle("seg", segs.size, 1); showSettings() }
                2 -> { applyChange { toggle("fh") }; showSettings() }
                3 -> { applyChange { toggle("fv") }; showSettings() }
            }
        }.setPositiveButton("Đóng", null).show()
    }

    private fun askCap() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmtGb(capGb())); selectAll()
        }
        AlertDialog.Builder(this).setTitle("Dung lượng tối đa (GB)")
            .setMessage("Chỉ tính video chưa khóa. Video đã khóa nằm ngoài mức này và không bị tự xóa. Nhập 0 để không giới hạn.\n\n" + usageText())
            .setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                et.text.toString().replace(',', '.').toFloatOrNull()?.let { prefs.edit().putFloat("capGb", it.coerceAtLeast(0f)).apply() }
                showSettings()
            }
            .setNegativeButton("Hủy") { _, _ -> showSettings() }.show()
    }

    /* ---------- Thu nhỏ thành cửa sổ nổi để xem Maps, vẫn ghi bình thường ---------- */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "Máy không hỗ trợ cửa sổ thu nhỏ. Hãy dùng chia đôi màn hình.", Toast.LENGTH_LONG).show()
            return
        }
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    override fun onUserLeaveHint() {
        if (running && Build.VERSION.SDK_INT >= 26) enterPip()   // bấm Home khi đang ghi thì tự thu nhỏ
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val v = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        barView.visibility = v; tvHud.visibility = v; bQual.visibility = v
    }

    /* ---------- Camera ---------- */
    private fun initCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    /** Chọn độ phân giải thật theo khả năng của từng camera. lv: 0 thấp, 1 vừa, 2 cao. */
    private fun cfg(sel: CameraSelector, lv: Int, dual: Boolean): Triple<Quality, String, Int> {
        val info = sel.filter(provider.availableCameraInfos).first()
        val sup = QualitySelector.getSupportedQualities(info)
        var order = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).filter { it in sup }
        if (dual) order = order.filter { it == Quality.HD || it == Quality.SD }.ifEmpty { order.takeLast(1) }
        if (order.isEmpty()) return Triple(Quality.LOWEST, "?", 2_000_000)
        val q = when (lv) { 0 -> order.last(); 2 -> order.first(); else -> order.firstOrNull { it == Quality.HD } ?: order.last() }
        val size = QualitySelector.getResolution(info, q)
        val px = (size?.width ?: 1280) * (size?.height ?: 720)
        val k = when (lv) { 0 -> 0.07; 2 -> 0.08; else -> 0.09 }
        val bps = (px * 30 * k).toInt().coerceIn(800_000, 30_000_000)
        val name = when (q) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; else -> "480p" }
        return Triple(q, name, bps)
    }

    @Suppress("DEPRECATION")
    private fun bind() {
        provider.unbindAll()
        var mode = prefs.getInt("mode", 0)
        if (mode == 2 && provider.availableConcurrentCameraInfos.isEmpty()) {
            Toast.makeText(this, "Máy này không mở được 2 camera cùng lúc, dùng camera sau", Toast.LENGTH_LONG).show()
            mode = 0
        }
        val fh = prefs.getBoolean("fh", false)
        val fv = prefs.getBoolean("fv", false)
        val base = windowManager.defaultDisplay.rotation
        // Lật ngang = mirror; lật dọc = mirror + xoay 180; cả hai = xoay 180
        val mirror = fh != fv
        val rot = if (fv) (base + 2) % 4 else base
        for (p in listOf(pvRear, pvFront)) { p.scaleX = if (fh) -1f else 1f; p.scaleY = if (fv) -1f else 1f }
        pvRear.visibility = if (mode == 1) View.GONE else View.VISIBLE
        pvFront.visibility = if (mode == 0) View.GONE else View.VISIBLE
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA

        fun make(pv: PreviewView, sel: CameraSelector, fx: Boolean): Pair<UseCaseGroup, VideoCapture<Recorder>> {
            val (qual, name, bps) = cfg(sel, prefs.getInt("q", 1), mode == 2)
            qLabel = name; qBps = bps
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(qual, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(bps).build()
            val vc = VideoCapture.Builder(rec)
                .setMirrorMode(if (mirror) MirrorMode.MIRROR_MODE_ON else MirrorMode.MIRROR_MODE_OFF)
                .setTargetRotation(rot).build()
            val p = Preview.Builder().setTargetRotation(rot).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val gb = UseCaseGroup.Builder().addUseCase(p).addUseCase(vc)
            if (fx) {
                val rel = sel.filter(provider.availableCameraInfos).first().getSensorRotationDegrees(rot)
                gb.addEffect(makeEffect(rel, mirror))
            }
            return gb.build() to vc
        }
        fun attempt(fx: Boolean) {
            when (mode) {
                0 -> { val (g, v) = make(pvRear, back, fx); provider.bindToLifecycle(this, back, g); vcs = listOf("rear" to v) }
                1 -> { val (g, v) = make(pvFront, front, fx); provider.bindToLifecycle(this, front, g); vcs = listOf("front" to v) }
                else -> {
                    val (g1, v1) = make(pvRear, back, fx); val (g2, v2) = make(pvFront, front, fx)
                    provider.bindToLifecycle(listOf(SingleCameraConfig(back, g1, this), SingleCameraConfig(front, g2, this)))
                    vcs = listOf("rear" to v1, "front" to v2)
                }
            }
        }
        try { attempt(true) } catch (e: Exception) {
            try {
                provider.unbindAll(); attempt(false)
                Toast.makeText(this, "Máy này không in được giờ và tốc độ lên video, vẫn ghi bình thường", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                vcs = emptyList()
                Toast.makeText(this, "Không mở được camera: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
        ui()
    }

    /* ---------- Ghi hình ---------- */
    private fun startAll() { if (vcs.isEmpty()) return; running = true; locked.clear(); vcs.forEach { startRec(it.second, it.first) }; ui() }

    private fun stopAll() {
        running = false; h.removeCallbacksAndMessages(null)
        cur.values.forEach { it.stop() }; cur.clear(); ui()
    }

    @SuppressLint("MissingPermission")
    private fun startRec(vc: VideoCapture<Recorder>, tag: String) {
        val f = File(dir(), "${tag}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
        val withAudio = tag == vcs.first().first &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val r = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
            .apply { if (withAudio) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    if (locked.remove(tag)) f.renameTo(File(dir(), "LOCK_" + f.name))
                    prune()
                    if (e.hasError()) { if (running) { stopAll(); Toast.makeText(this, "Dừng ghi do lỗi (${e.error})", Toast.LENGTH_LONG).show() } }
                    else if (running) startRec(vc, tag)
                }
            }
        cur[tag] = r
        h.postDelayed({ r.stop() }, segMs)
    }

    private fun dir(): File = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

    /** Xóa video chưa khóa cũ nhất khi vượt dung lượng tối đa. Video đã khóa (LOCK_) không bao giờ bị tự xóa. */
    private fun prune() {
        val now = System.currentTimeMillis()
        val all = dir().listFiles { x -> x.extension == "mp4" && !x.name.startsWith("LOCK_") } ?: return
        var total = all.sumOf { it.length() }
        val old = all.filter { now - it.lastModified() > 15_000 }.sortedBy { it.lastModified() }.toMutableList()
        val cap = (capGb() * 1073741824.0).toLong()
        if (cap > 0) while (total > cap && old.isNotEmpty()) { val x = old.removeAt(0); total -= x.length(); x.delete() }
        while (dir().usableSpace < 500L shl 20 && old.isNotEmpty()) old.removeAt(0).delete()   // sắp đầy bộ nhớ máy
    }

    override fun onDestroy() {
        stopAll(); tickH.removeCallbacksAndMessages(null)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(accel)
        super.onDestroy()
    }
}
