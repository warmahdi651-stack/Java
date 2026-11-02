package com.gametranslate.live

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

private data class OverlayItem(val bounds: RectF, val translated: String, val background: Int)

class LiveTranslateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TranslationOverlayView? = null
    private lateinit var recognizer: TextRecognizer
    private var translator: Translator? = null
    private var busy = false
    @Volatile private var modelReady = false
    private var lastCaptureAt = 0L
    private var lastFrameSignature = ""
    private val cache = object : LinkedHashMap<String, String>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 250
    }
    private val translatedItems = LinkedHashMap<String, OverlayItem>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PERSIAN)
            .build()
        translator = Translation.getClient(options)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        if (projection == null) {
            val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra("projectionData", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent?.getParcelableExtra("projectionData") as? Intent)
            }
            if (code != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            startCapture()
            showStatus("در حال آماده‌سازی مترجم…")
            prepareTranslationModel()
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val channelId = "gametranslate_live"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "ترجمه زنده بازی", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("GameTranslate فعال است")
            .setContentText("ترجمه روی نوشته‌های بازی نمایش داده می‌شود.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(77, notification)
    }

    private fun prepareTranslationModel() {
        translator?.downloadModelIfNeeded()
            ?.addOnSuccessListener {
                modelReady = true
                lastCaptureAt = 0L
                showStatus("آماده است؛ متن انگلیسی بازی را ترجمه می‌کنم.")
            }
            ?.addOnFailureListener {
                modelReady = false
                showStatus("دانلود مدل ترجمه ناموفق بود؛ اینترنت را بررسی کن و دوباره برنامه را اجرا کن.")
            }
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
        reader?.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (!busy && modelReady && now - lastCaptureAt >= 550) {
                busy = true
                lastCaptureAt = now
                processFrame(image)
            } else {
                image.close()
            }
        }, handler)
        display = projection?.createVirtualDisplay(
            "GameTranslateCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )
    }

    private fun processFrame(image: Image) {
        var bitmap: Bitmap? = null
        try {
            bitmap = imageToBitmap(image)
            image.close()
            val frame = bitmap
            recognizer.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener { result ->
                    try {
                        handleRecognizedText(result, frame)
                    } catch (_: Exception) {
                        if (!frame.isRecycled) frame.recycle()
                        busy = false
                    }
                }
                .addOnFailureListener {
                    if (!frame.isRecycled) frame.recycle()
                    busy = false
                }
        } catch (_: Exception) {
            try { image.close() } catch (_: Exception) {}
            bitmap?.recycle()
            busy = false
        }
    }

    private fun handleRecognizedText(result: Text, frame: Bitmap) {
        val frameWidth = frame.width
        val frameHeight = frame.height
        // Translate individual OCR lines, not whole paragraphs. This keeps
        // overlay rectangles tight and prevents a large block from covering UI.
        val blocks = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim()
                val latinLetters = value.count { it in 'A'..'Z' || it in 'a'..'z' }
                if (latinLetters < 2) null
                else Triple(value, Rect(box), backgroundColor(frame, box))
            }
        }.take(18)

        if (blocks.isEmpty()) {
            lastFrameSignature = ""
            handler.post { translatedItems.clear(); overlay?.setItems(emptyList(), frameWidth, frameHeight) }
            frame.recycle()
            busy = false
            return
        }

        // Include position and size so identical text moving on screen also moves its overlay.
        val signature = blocks.joinToString("|") {
            it.first + "@" + it.second.left + "," + it.second.top + "," + it.second.right + "," + it.second.bottom + "#" + it.third
        }
        if (signature == lastFrameSignature) {
            frame.recycle()
            busy = false
            return
        }
        lastFrameSignature = signature
        handler.post { overlay?.setItems(emptyList(), frameWidth, frameHeight) }

        val pending = blocks.size
        var completed = 0
        val results = mutableListOf<OverlayItem>()
        fun finishOne() {
            completed++
            if (completed >= pending) {
                handler.post {
                    translatedItems.clear()
                    results.forEachIndexed { index, item -> translatedItems[index.toString()] = item }
                    overlay?.setItems(results, frameWidth, frameHeight)
                }
                frame.recycle()
                busy = false
            }
        }

        blocks.forEach { (sourceText, bounds, bg) ->
            val cached = synchronized(cache) { cache[sourceText] }
            if (cached != null) {
                results.add(OverlayItem(RectF(bounds), cached, bg))
                finishOne()
            } else {
                translator?.translate(sourceText)
                    ?.addOnSuccessListener { translated ->
                        synchronized(cache) { cache[sourceText] = translated }
                        results.add(OverlayItem(RectF(bounds), translated, bg))
                        finishOne()
                    }
                    ?.addOnFailureListener { finishOne() }
                    ?: finishOne()
            }
        }
    }

    private fun backgroundColor(bitmap: Bitmap, box: Rect): Int {
        // Sample a thin perimeter rather than only four corners; median values
        // reduce the influence of bright glyph pixels and isolated UI details.
        val samples = ArrayList<Int>(64)
        val left = max(0, box.left - 4)
        val right = min(bitmap.width - 1, box.right + 4)
        val top = max(0, box.top - 4)
        val bottom = min(bitmap.height - 1, box.bottom + 4)
        if (right < left || bottom < top) return Color.rgb(28, 28, 28)

        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 12)
        var x = left
        while (x <= right) {
            if (top < box.top) samples.add(bitmap.getPixel(x, top))
            if (bottom > box.bottom && bottom != top) samples.add(bitmap.getPixel(x, bottom))
            x += stepX
        }
        var y = top
        while (y <= bottom) {
            if (left < box.left) samples.add(bitmap.getPixel(left, y))
            if (right > box.right && right != left) samples.add(bitmap.getPixel(right, y))
            y += stepY
        }
        if (samples.isEmpty()) return Color.rgb(28, 28, 28)
        fun median(channel: (Int) -> Int): Int {
            val values = samples.map(channel).sorted()
            return values[values.size / 2]
        }
        return Color.rgb(
            median { Color.red(it) },
            median { Color.green(it) },
            median { Color.blue(it) }
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return result
    }

    private fun ensureOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val view = TranslationOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlay = view
        } catch (_: Exception) { overlay = null }
    }

    private fun showStatus(message: String) {
        handler.post {
            ensureOverlay()
            overlay?.setStatus(message)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        translator?.close()
        super.onDestroy()
    }
}

private class TranslationOverlayView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    @Volatile private var items: List<OverlayItem> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    @Volatile private var status: String? = null

    fun setItems(value: List<OverlayItem>, frameWidth: Int, frameHeight: Int) {
        items = value.toList()
        sourceWidth = max(1, frameWidth)
        sourceHeight = max(1, frameHeight)
        status = null
        invalidate()
    }

    fun setStatus(value: String) {
        status = value
        items = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val message = status
        if (message != null) {
            fillPaint.color = 0xCC151515.toInt()
            val rect = RectF(24f, 70f, width - 24f, 132f)
            canvas.drawRoundRect(rect, 12f, 12f, fillPaint)
            textPaint.textSize = 22f
            canvas.drawText(message, rect.centerX(), rect.centerY() + 8f, textPaint)
            return
        }

        val scaleX = width.toFloat() / sourceWidth.toFloat()
        val scaleY = height.toFloat() / sourceHeight.toFloat()
        canvas.save()
        canvas.scale(scaleX, scaleY)
        items.forEach { item ->
            val source = item.bounds
            val padX = max(7f, source.height() * 0.12f)
            val padY = max(5f, source.height() * 0.10f)
            val rect = RectF(
                max(0f, source.left - padX),
                max(0f, source.top - padY),
                min(sourceWidth.toFloat(), source.right + padX),
                min(sourceHeight.toFloat(), source.bottom + padY)
            )
            fillPaint.color = item.background
            canvas.drawRect(rect, fillPaint)

            val fontSize = max(14f, min(25f, source.height() * 0.72f))
            textPaint.textSize = fontSize
            textPaint.color = if (luminance(item.background) < 0.42) Color.WHITE else Color.BLACK
            val textWidth = max(30, rect.width().toInt())
            var layout = StaticLayout.Builder
                .obtain(item.translated, 0, item.translated.length, textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()
            // Long Persian translations must not spill over neighboring game UI.
            while (layout.height > rect.height() && textPaint.textSize > 10f) {
                textPaint.textSize = max(10f, textPaint.textSize - 1f)
                layout = StaticLayout.Builder
                    .obtain(item.translated, 0, item.translated.length, textPaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .build()
            }
            canvas.save()
            canvas.clipRect(rect)
            val verticalOffset = max(0f, (rect.height() - layout.height) / 2f)
            canvas.translate(rect.left, rect.top + verticalOffset)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun luminance(color: Int): Double {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
    }
}
