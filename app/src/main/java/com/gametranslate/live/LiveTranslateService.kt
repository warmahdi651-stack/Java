package com.gametranslate.live

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.nio.ByteBuffer

class LiveTranslateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TextView? = null
    private var lastText = ""
    private var busy = false
    private lateinit var recognizers: List<TextRecognizer>
    private val languageId by lazy { LanguageIdentification.getClient() }
    private var lastTranslateAt = 0L

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        recognizers = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        if (projection == null) {
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projectionData", Intent::class.java)
                else @Suppress("DEPRECATION") (intent?.getParcelableExtra("projectionData") as? Intent)
            if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            startCapture()
            showOverlay("GameTranslate روشن شد؛ نوشته‌های بازی را بررسی می‌کنم…")
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val channel = "gametranslate_live"
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(channel, "ترجمه زنده بازی", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
        val n = Notification.Builder(this, channel)
            .setContentTitle("GameTranslate فعال است")
            .setContentText("برای توقف، برنامه را باز کن.")
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
        startForeground(77, n)
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!busy && System.currentTimeMillis() - lastTranslateAt > 1600) {
                busy = true
                lastTranslateAt = System.currentTimeMillis()
                processImage(image)
            } else image.close()
        }, handler)
        display = projection?.createVirtualDisplay(
            "GameTranslateCapture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
    }

    private fun processImage(image: Image) {
        var closed = false
        try {
            val bitmap = imageToBitmap(image)
            image.close()
            closed = true
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizeWithFallback(input, 0) { detectedText ->
                val text = detectedText.lines().map { it.trim() }.filter { it.length >= 2 }.take(8).joinToString("\n")
                if (text.isBlank() || text == lastText) { busy = false; return@recognizeWithFallback }
                lastText = text
                languageId.identifyLanguage(text).addOnSuccessListener { lang ->
                    if (lang == "und" || lang == "fa" || lang == "ar") { busy = false; return@addOnSuccessListener }
                    val source = TranslateLanguage.fromLanguageTag(lang)
                    val target = TranslateLanguage.PERSIAN
                    if (source == null || source == target) { busy = false; return@addOnSuccessListener }
                    val translator = Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                    )
                    translator.downloadModelIfNeeded().addOnSuccessListener {
                        translator.translate(text).addOnSuccessListener { translated ->
                            showOverlay(translated)
                            busy = false
                            translator.close()
                        }.addOnFailureListener { busy = false; translator.close() }
                    }.addOnFailureListener {
                        busy = false
                        translator.close()
                        showOverlay("برای دریافت مدل زبان، اینترنت را روشن کن.")
                    }
                }.addOnFailureListener { busy = false }
            }
        } catch (_: Exception) {
            if (!closed) image.close()
            busy = false
        }
    }

    private fun recognizeWithFallback(input: InputImage, index: Int, done: (String) -> Unit) {
        if (index >= recognizers.size) { done(""); return }
        recognizers[index].process(input)
            .addOnSuccessListener { result ->
                if (result.text.isNotBlank()) done(result.text)
                else recognizeWithFallback(input, index + 1, done)
            }
            .addOnFailureListener { recognizeWithFallback(input, index + 1, done) }
    }

    private fun imageToBitmap(image: Image): android.graphics.Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = android.graphics.Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        return android.graphics.Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun showOverlay(text: String) {
        handler.post {
            if (!Settings.canDrawOverlays(this)) return@post
            if (overlay == null) {
                overlay = TextView(this).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xDD111827.toInt())
                    textSize = 16f
                    setPadding(18, 12, 18, 12)
                    maxLines = 7
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP; y = 70 }
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(overlay, params) }
                catch (_: Exception) { overlay = null }
            }
            overlay?.text = "ترجمه: $text"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        display?.release()
        reader?.close()
        projection?.stop()
        recognizers.forEach { it.close() }
        languageId.close()
        super.onDestroy()
    }
}
