package com.example.dashcam

import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
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
    private var files = listOf<File>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { setBackgroundColor(Color.BLACK) }
        list = ListView(this).apply { setBackgroundColor(0xFF222222.toInt()) }
        val empty = TextView(this).apply {
            text = "Chưa có video nào.\nHãy bấm Ghi ở màn hình chính, ghi vài chục giây rồi quay lại."
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(32, 32, 32, 32)
        }
        list.emptyView = empty
        val left = FrameLayout(this).apply { setBackgroundColor(0xFF222222.toInt()); addView(list); addView(empty) }
        video = VideoView(this)
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(video, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        setContentView(root)
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        list.setOnItemClickListener { _, _, i, _ -> play(files[i]) }
        list.setOnItemLongClickListener { _, _, i, _ -> menu(files[i]); true }
        refresh()
    }

    private fun dir() = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

    private fun refresh() {
        files = (dir().listFiles { f -> f.extension == "mp4" } ?: emptyArray()).sortedByDescending { it.lastModified() }
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files.map {
            (if (it.name.startsWith("LOCK_")) "[KHÓA] " else "") +
                (if (it.name.contains("front_")) "Trước  " else "Sau  ") +
                fmt.format(Date(it.lastModified())) + "   " + it.length() / 1048576 + " MB"
        })
    }

    private fun play(f: File) { video.setVideoPath(f.path); video.start() }

    private fun menu(f: File) {
        val locked = f.name.startsWith("LOCK_")
        AlertDialog.Builder(this).setItems(arrayOf("Xem", if (locked) "Mở khóa" else "Khóa", "Lưu vào Thư viện máy", "Xóa")) { _, w ->
            when (w) {
                0 -> play(f)
                1 -> { f.renameTo(File(f.parentFile, if (locked) f.name.removePrefix("LOCK_") else "LOCK_" + f.name)); refresh() }
                2 -> export(f)
                3 -> AlertDialog.Builder(this).setMessage("Xóa video này?")
                    .setPositiveButton("Xóa") { _, _ -> f.delete(); refresh() }.setNegativeButton("Hủy", null).show()
            }
        }.show()
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
