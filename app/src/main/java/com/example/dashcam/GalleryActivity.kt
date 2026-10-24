package com.example.dashcam

import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var video: VideoView
    private lateinit var title: TextView
    private lateinit var bLock: IconButton
    private lateinit var capLock: TextView
    private lateinit var bSave: IconButton
    private lateinit var bDel: IconButton
    private var files = listOf<File>()
    private var sel: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val dark = 0xFF222222.toInt()
        val root = LinearLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Bên trái: danh sách
        list = ListView(this).apply { setBackgroundColor(dark); choiceMode = ListView.CHOICE_MODE_SINGLE }
        val empty = TextView(this).apply {
            text = "Chưa có video nào.\nHãy bấm Ghi ở màn hình chính, ghi vài chục giây rồi quay lại."
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(32, 32, 32, 32)
        }
        list.emptyView = empty
        val left = FrameLayout(this).apply { setBackgroundColor(dark); addView(list); addView(empty) }
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Bên phải: tên video, trình phát, hàng nút
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24, 12, 24, 12) }
        video = VideoView(this)
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        val bar = LinearLayout(this).apply { setBackgroundColor(dark); gravity = Gravity.CENTER }
        val dp = resources.displayMetrics.density
        fun btn(type: String, caption: String, onClick: () -> Unit): Pair<IconButton, TextView> {
            val b = IconButton(this, type).apply { contentDescription = caption; setOnClickListener { onClick() } }
            val t = TextView(this).apply { text = caption; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8)
                addView(b, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
                addView(t)
            }
            bar.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b to t
        }
        btn("lock", "Khóa") { toggleLock() }.let { bLock = it.first; capLock = it.second }
        bSave = btn("save", "Lưu vào máy") { sel?.let { export(it) } }.first
        bDel = btn("del", "Xóa") { confirmDelete() }.first
        right.addView(title)
        right.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        right.addView(bar)
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        setContentView(root)

        list.setOnItemClickListener { _, _, i, _ -> sel = files[i]; play(files[i]); updateBar() }
        refresh()
    }

    private fun dir() = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

    private fun label(f: File) =
        (if (f.name.startsWith("LOCK_")) "[KHÓA] " else "") +
            (if (f.name.contains("front_")) "Trước  " else "Sau  ") +
            fmt.format(Date(f.lastModified())) + "   " + f.length() / 1048576 + " MB"

    private fun refresh() {
        files = (dir().listFiles { f -> f.extension == "mp4" } ?: emptyArray()).sortedByDescending { it.lastModified() }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, files.map { label(it) })
        sel?.let { s ->
            val i = files.indexOfFirst { it.name == s.name }
            if (i >= 0) list.setItemChecked(i, true) else sel = null
        }
        updateBar()
    }

    private fun updateBar() {
        val f = sel
        title.text = f?.let { label(it) } ?: "Chọn một video ở danh sách bên trái"
        val isLocked = f?.name?.startsWith("LOCK_") == true
        capLock.text = if (isLocked) "Mở khóa" else "Khóa"
        bLock.active = isLocked
        listOf(bLock, bSave, bDel).forEach { it.isEnabled = f != null }
    }

    private fun play(f: File) { video.setVideoPath(f.path); video.start() }

    private fun toggleLock() {
        val f = sel ?: return
        val n = File(f.parentFile, if (f.name.startsWith("LOCK_")) f.name.removePrefix("LOCK_") else "LOCK_" + f.name)
        if (f.renameTo(n)) { sel = n; refresh() } else Toast.makeText(this, "Không đổi được trạng thái khóa", Toast.LENGTH_LONG).show()
    }

    private fun confirmDelete() {
        val f = sel ?: return
        AlertDialog.Builder(this).setMessage("Xóa video này?")
            .setPositiveButton("Xóa") { _, _ -> video.stopPlayback(); f.delete(); sel = null; refresh() }
            .setNegativeButton("Hủy", null).show()
    }

    private fun export(f: File) {
        if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "Android dưới 10: hãy lấy file bằng adb", Toast.LENGTH_LONG).show(); return }
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Dashcam")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { Toast.makeText(this, "Không lưu được", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "Đang lưu...", Toast.LENGTH_SHORT).show()
        Thread {
            try { contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } } catch (e: Exception) { }
            runOnUiThread { Toast.makeText(this, "Đã lưu vào Movies/Dashcam, xem trong ứng dụng Thư viện", Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onStop() { video.stopPlayback(); super.onStop() }
}
