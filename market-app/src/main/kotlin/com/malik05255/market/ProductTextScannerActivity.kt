package com.malik05255.market

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Local-only package text fallback used after a GTIN lookup misses.
 * Images never leave the phone. ML Kit recognizes Latin text continuously and
 * Tesseract tessdata_best adds offline Arabic-script recognition periodically.
 * Only merged recognized text is sent to the conservative LuLu/Tamimi resolver.
 */
class ProductTextScannerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_INPUT_BARCODE = "input_barcode"
        const val EXTRA_RECOGNIZED_TEXT = "recognized_text"
        private const val ARABIC_OCR_INTERVAL_MS = 3_800L
        private const val BEST_ARABIC_MODEL_MIN_BYTES = 5_000_000L
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val arabicExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val arabicProcessing = AtomicBoolean(false)
    private val recentFrames = ArrayDeque<List<String>>()
    private var camera: Camera? = null
    private var recognizer: TextRecognizer? = null
    private var arabicOcr: TessBaseAPI? = null
    @Volatile private var arabicReady = false
    private var lastArabicOcrAt = 0L
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recognizedTextView: TextView
    private lateinit var useButton: Button
    private lateinit var torchButton: Button
    private var recognizedText: String = ""
    private var torchEnabled = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finishWithError("يلزم السماح بالكاميرا للتعرف على واجهة المنتج")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        initializeArabicOcr()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            isClickable = true
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val guide = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.rgb(64, 220, 165))
                cornerRadius = dp(20).toFloat()
            }
        }
        root.addView(
            guide,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(300), Gravity.CENTER).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = dp(60)
            }
        )

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()) }
        }
        top.addView(TextView(this).apply {
            text = "تعرّف على المنتج من العبوة"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        statusText = TextView(this).apply {
            text = "وجّه الاسم والعلامة والحجم داخل الإطار • عربي + إنجليزي • الصورة لا تغادر جهازك"
            textSize = 13f
            setTextColor(0xFFD8FFF1.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        top.addView(statusText)
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(18))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()) }
        }

        recognizedTextView = TextView(this).apply {
            text = "بانتظار قراءة اسم المنتج..."
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 6
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        val textScroll = ScrollView(this).apply { addView(recognizedTextView) }
        bottom.addView(textScroll, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(104)))

        useButton = Button(this).apply {
            text = "ابحث بهذا الاسم والحجم"
            isEnabled = false
            setOnClickListener { finishWithText() }
        }
        bottom.addView(useButton, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val close = Button(this).apply {
            text = "إلغاء"
            setOnClickListener { finish() }
        }
        torchButton = Button(this).apply {
            text = "تشغيل الفلاش"
            isEnabled = false
            setOnClickListener { toggleTorch() }
        }
        controls.addView(close, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        controls.addView(torchButton, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(controls)

        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    private fun initializeArabicOcr() {
        arabicExecutor.execute {
            runCatching {
                val dataRoot = File(filesDir, "tesseract")
                val tessdata = File(dataRoot, "tessdata")
                if (!tessdata.exists()) tessdata.mkdirs()
                val model = File(tessdata, "ara.traineddata")

                // Builds before the accuracy upgrade may have cached tessdata_fast in filesDir.
                // Replace that smaller model automatically with the bundled tessdata_best model.
                if (!model.exists() || model.length() < BEST_ARABIC_MODEL_MIN_BYTES) {
                    assets.open("tessdata/ara.traineddata").use { input ->
                        FileOutputStream(model, false).use { output -> input.copyTo(output) }
                    }
                }
                check(model.length() >= BEST_ARABIC_MODEL_MIN_BYTES) { "Arabic best model is unavailable" }

                val tess = TessBaseAPI()
                if (!tess.init(dataRoot.absolutePath, "ara", TessBaseAPI.OEM_LSTM_ONLY)) {
                    tess.recycle()
                    error("Arabic Tesseract initialization failed")
                }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                tess.setVariable("preserve_interword_spaces", "1")
                tess.setVariable("user_defined_dpi", "300")
                arabicOcr = tess
                arabicReady = true
                runOnUiThread {
                    statusText.text = "العربي عالي الدقة + الإنجليزي جاهزان • ضع الاسم والحجم داخل الإطار"
                }
            }.onFailure {
                arabicReady = false
                runOnUiThread {
                    statusText.text = "القراءة الإنجليزية جاهزة • تعذر تجهيز العربية على هذا الجهاز"
                }
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
                ?: return@addListener finishWithError("تعذر تشغيل الكاميرا")

            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Throwable) {
                finishWithError("تعذر الوصول إلى الكاميرا الخلفية")
                return@addListener
            }

            val boundCamera = camera ?: return@addListener
            configureGestures(boundCamera)
            torchButton.isEnabled = boundCamera.cameraInfo.hasFlashUnit()
            previewView.post { focusAt(previewView.width / 2f, previewView.height / 2f) }

            analysis.setAnalyzer(cameraExecutor) { proxy ->
                if (!processing.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    processing.set(false)
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                val activeRecognizer = recognizer
                if (activeRecognizer == null) {
                    processing.set(false)
                    proxy.close()
                    return@setAnalyzer
                }
                activeRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        mergeFrame(result.text)
                        maybeRunArabicOcr()
                    }
                    .addOnFailureListener {
                        runOnUiThread { statusText.text = "ثبّت الاسم والحجم داخل الإطار وحاول مرة أخرى" }
                    }
                    .addOnCompleteListener {
                        processing.set(false)
                        proxy.close()
                    }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun maybeRunArabicOcr() {
        if (!arabicReady || arabicProcessing.get()) return
        val now = System.currentTimeMillis()
        if (now - lastArabicOcrAt < ARABIC_OCR_INTERVAL_MS) return
        lastArabicOcrAt = now
        previewView.post {
            val bitmap = previewView.bitmap ?: return@post
            if (!arabicProcessing.compareAndSet(false, true)) {
                bitmap.recycle()
                return@post
            }
            arabicExecutor.execute {
                var prepared: Bitmap? = null
                try {
                    val tess = arabicOcr ?: return@execute
                    prepared = prepareArabicBitmap(bitmap)
                    tess.setImage(prepared)
                    val raw = tess.getUTF8Text().orEmpty()
                    val confidence = tess.meanConfidence()
                    val confident = runCatching {
                        tess.getConfidentText(28, TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    }.getOrDefault("")
                    val text = mergeOcrPasses(raw, confident)
                    if (text.isNotBlank()) {
                        runOnUiThread {
                            mergeFrame(text)
                            if (confidence > 0) {
                                statusText.text = "التقط العربي بثقة $confidence% • تأكد من الاسم والحجم ثم اضغط بحث"
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Latin OCR remains available; Arabic OCR is additive.
                } finally {
                    if (prepared != null && prepared !== bitmap && !prepared.isRecycled) prepared.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    arabicProcessing.set(false)
                }
            }
        }
    }

    private fun prepareArabicBitmap(source: Bitmap): Bitmap {
        val cropWidth = (source.width * 0.92f).toInt().coerceAtLeast(1)
        val cropHeight = (source.height * 0.62f).toInt().coerceAtLeast(1)
        val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val top = (source.height * 0.16f).toInt().coerceIn(0, max(0, source.height - cropHeight))
        val crop = Bitmap.createBitmap(source, left, top, cropWidth.coerceAtMost(source.width - left), cropHeight.coerceAtMost(source.height - top))

        val desiredWidth = 1_600
        val scale = (desiredWidth.toFloat() / crop.width).coerceIn(1f, 1.45f)
        val scaled = if (scale > 1.02f) {
            Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
        } else crop

        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.32f
        val translate = (1f - contrast) * 128f
        grayscale.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        Canvas(output).drawBitmap(
            scaled,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(grayscale)
            }
        )
        if (scaled !== crop && !scaled.isRecycled) scaled.recycle()
        if (!crop.isRecycled) crop.recycle()
        return output
    }

    private fun mergeOcrPasses(raw: String, confident: String): String {
        val unique = LinkedHashMap<String, String>()
        sequenceOf(raw, confident).forEach { block ->
            block.lineSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.length >= 2 }
                .forEach { line -> unique[normalizeKey(line)] = line }
        }
        return unique.values.joinToString("\n").take(3_500)
    }

    private fun mergeFrame(raw: String) {
        val lines = raw.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 2 }
            .distinctBy { normalizeKey(it) }
            .take(20)
            .toList()
        if (lines.isEmpty()) return

        recentFrames.addLast(lines)
        while (recentFrames.size > 5) recentFrames.removeFirst()

        val unique = LinkedHashMap<String, String>()
        recentFrames.forEach { frame ->
            frame.forEach { line -> unique[normalizeKey(line)] = line }
        }
        recognizedText = unique.values.take(40).joinToString("\n").take(4_500)
        recognizedTextView.text = recognizedText
        useButton.isEnabled = recognizedText.length >= 4
    }

    private fun normalizeKey(value: String): String = value.lowercase()
        .replace(Regex("[أإآ]"), "ا")
        .replace('ة', 'ه')
        .replace('ى', 'ي')
        .replace(Regex("[^0-9a-z\\u0600-\\u06ff]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun configureGestures(boundCamera: Camera) {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = boundCamera.cameraInfo.zoomState.value ?: return false
                val next = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                boundCamera.cameraControl.setZoomRatio(next)
                return true
            }
        })
        previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        if (previewView.width <= 0 || previewView.height <= 0) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
        )
    }

    private fun toggleTorch() {
        val boundCamera = camera ?: return
        torchEnabled = !torchEnabled
        boundCamera.cameraControl.enableTorch(torchEnabled)
        torchButton.text = if (torchEnabled) "إيقاف الفلاش" else "تشغيل الفلاش"
    }

    private fun finishWithText() {
        if (recognizedText.length < 4) return
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_INPUT_BARCODE, intent.getStringExtra(EXTRA_INPUT_BARCODE).orEmpty())
                .putExtra(EXTRA_RECOGNIZED_TEXT, recognizedText)
        )
        finish()
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        recognizer?.close()
        recognizer = null
        cameraExecutor.shutdown()
        arabicExecutor.execute {
            runCatching { arabicOcr?.recycle() }
            arabicOcr = null
            arabicReady = false
        }
        arabicExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
