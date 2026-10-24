package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
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
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private val segMs = 3 * 60_000L       // độ dài mỗi đoạn
    private val keep = 10                 // số đoạn thường giữ lại cho mỗi camera
    private val keepLocked = 30           // số đoạn đã khóa giữ lại cho mỗi camera
    private val impactMs2 = 15f           // ngưỡng va chạm (m/s2 lệch khỏi trọng lực); tăng nếu bị khóa nhầm khi đường xóc

    private val h = Handler(Looper.getMainLooper())
    private val tickH = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("dashcam", MODE_PRIVATE) }
    private lateinit var provider: ProcessCameraProvider
    private lateinit var pvRear: PreviewView
    private lateinit var pvFront: PreviewView
    private lateinit var tvHud: TextView
    private lateinit var barView: LinearLayout
    private lateinit var bRec: Button
    private lateinit var bMode: Button
    private lateinit var bH: Button
    private lateinit var bV: Button
    private lateinit var bDim: Button
    private var vcs = listOf<Pair<String, VideoCapture<Recorder>>>()
    private val cur = mutableMapOf<String, Recording>()
    private val locked = mutableSetOf<String>()
    private var running = false
    private var dim = false
    private val modes = arrayOf("Camera sau", "Camera trước", "Cả hai (2 file)")

    @Volatile private var speedKmh = 0f
    @Volatile private var lat = Double.NaN
    @Volatile private var lon = Double.NaN
    @Volatile private var lastFix = 0L

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
        root.addView(tvHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val bar = LinearLayout(this).apply { setBackgroundColor(0x99000000.toInt()); gravity = Gravity.CENTER }.also { barView = it }
        fun btn(onClick: () -> Unit) = Button(this).apply {
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        bMode = btn {
            if (running) stopAll()
            prefs.edit().putInt("mode", (prefs.getInt("mode", 0) + 1) % 3).apply(); bind()
        }
        bH = btn { flip("fh") }
        bV = btn { flip("fv") }
        bRec = btn { if (running) stopAll() else startAll() }
        bDim = btn {
            dim = !dim
            window.attributes = window.attributes.also { it.screenBrightness = if (dim) 0.01f else -1f }
            ui()
        }
        btn { if (running) stopAll(); startActivity(android.content.Intent(this, GalleryActivity::class.java)) }.text = "Thư viện"
        btn { enterPip() }.text = "Thu nhỏ"
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        ui()
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
        barView.visibility = v; tvHud.visibility = v
    }

    private fun flip(k: String) {
        if (running) stopAll()
        prefs.edit().putBoolean(k, !prefs.getBoolean(k, false)).apply(); bind()
    }

    private fun ui() {
        bRec.text = if (running) "Dừng" else "Ghi"
        bMode.text = modes[prefs.getInt("mode", 0)]
        bH.text = "Lật ngang: " + if (prefs.getBoolean("fh", false)) "bật" else "tắt"
        bV.text = "Lật dọc: " + if (prefs.getBoolean("fv", false)) "bật" else "tắt"
        bDim.text = if (dim) "Sáng màn hình" else "Tối màn hình"
    }

    /* ---------- Camera ---------- */
    private fun initCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
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
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
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
                    prune(tag)
                    if (e.hasError()) { if (running) { stopAll(); Toast.makeText(this, "Dừng ghi do lỗi (${e.error})", Toast.LENGTH_LONG).show() } }
                    else if (running) startRec(vc, tag)
                }
            }
        cur[tag] = r
        h.postDelayed({ r.stop() }, segMs)
    }

    private fun dir(): File = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

    private fun prune(tag: String) {
        val mine = dir().listFiles { x -> x.name.startsWith(tag + "_") }?.sortedBy { it.lastModified() } ?: return
        mine.dropLast(keep).forEach { it.delete() }
        dir().listFiles { x -> x.name.startsWith("LOCK_" + tag + "_") }?.sortedBy { it.lastModified() }
            ?.dropLast(keepLocked)?.forEach { it.delete() }
        if (dir().usableSpace < 500L shl 20) mine.firstOrNull()?.delete()   // sắp đầy bộ nhớ
    }

    override fun onDestroy() {
        stopAll(); tickH.removeCallbacksAndMessages(null)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(accel)
        super.onDestroy()
    }
}
