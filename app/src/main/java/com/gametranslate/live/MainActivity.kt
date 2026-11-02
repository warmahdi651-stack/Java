package com.gametranslate.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFF101522.toInt())
        }
        val title = TextView(this).apply {
            text = "GameTranslate"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "ترجمه زنده نوشته‌های روی صفحه بازی به فارسی"
            textSize = 16f
            setTextColor(0xFFCAD2E2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 24)
        }
        status = TextView(this).apply {
            text = "برای شروع، اجازه پنجره شناور را فعال کن."
            textSize = 14f
            setTextColor(0xFFFFD166.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        val start = Button(this).apply {
            text = "فعال‌سازی مترجم زنده"
            setOnClickListener { startTranslation() }
        }
        val stop = Button(this).apply {
            text = "خاموش کردن مترجم"
            setOnClickListener {
                stopService(Intent(this@MainActivity, LiveTranslateService::class.java))
                status.text = "مترجم خاموش شد."
            }
        }
        val note = TextView(this).apply {
            text = "برای خواندن نوشته‌های بازی، اجازه ضبط صفحه لازم است. ترجمه با مدل‌های روی دستگاه انجام می‌شود؛ بار اول ممکن است دانلود زبان کمی زمان ببرد."
            textSize = 12f
            setTextColor(0xFF9BA8BD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 0)
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(start, ViewGroup.LayoutParams(-1, -2))
        root.addView(stop, ViewGroup.LayoutParams(-1, -2))
        root.addView(note, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun startTranslation() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "اجازه پنجره شناور را فعال کن و دوباره بزن."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Deprecated API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, LiveTranslateService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            status.text = "مترجم فعال شد. بازی را باز کن؛ ترجمه در پنجره شناور می‌آید."
        } else if (requestCode == captureRequest) status.text = "اجازه ضبط صفحه داده نشد."
    }
}
