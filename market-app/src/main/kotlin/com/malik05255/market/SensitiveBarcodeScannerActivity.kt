package com.malik05255.market

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalGetImage::class)
class SensitiveBarcodeScannerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_BARCODE = "barcode"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val capturePolicy = BarcodeCapturePolicy()
    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var torchButton: Button
    private var torchEnabled = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finishWithError("يلزم السماح بالكاميرا لقراءة الباركود")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildUi())

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
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val guide = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.rgb(64, 220, 165))
                cornerRadius = dp(18).toFloat()
            }
        }
        root.addView(
            guide,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(210), Gravity.CENTER).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()) }
        }
        val title = TextView(this).apply {
            text = "قارئ باركود عالي الدقة"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "قرّب الباركود حتى يملأ الإطار • يدعم التكبير والتركيز باللمس"
            textSize = 14f
            setTextColor(0xFFD8FFF1.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        top.addView(title)
        top.addView(statusText)
        root.addView(
            top,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()) }
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
        bottom.addView(close, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        bottom.addView(torchButton, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(
            bottom,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        return root
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener finishWithError("تعذر تشغيل الكاميرا")
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
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
            scanner = createScanner(boundCamera)

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
                val activeScanner = scanner
                if (activeScanner == null) {
                    processing.set(false)
                    proxy.close()
                    return@setAnalyzer
                }
                activeScanner.process(image)
                    .addOnSuccessListener { barcodes -> handleDetections(barcodes) }
                    .addOnCompleteListener {
                        processing.set(false)
                        proxy.close()
                    }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createScanner(boundCamera: Camera): BarcodeScanner {
        val maxZoom = boundCamera.cameraInfo.zoomState.value?.maxZoomRatio?.coerceAtMost(8f) ?: 4f
        val zoomOptions = ZoomSuggestionOptions.Builder { ratio ->
            boundCamera.cameraControl.setZoomRatio(ratio.coerceAtMost(maxZoom))
            true
        }.setMaxSupportedZoomRatio(maxZoom).build()

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_DATA_MATRIX
            )
            .enableAllPotentialBarcodes()
            .setZoomSuggestionOptions(zoomOptions)
            .build()
        return BarcodeScanning.getClient(options)
    }

    private fun handleDetections(barcodes: List<Barcode>) {
        val candidates = barcodes.mapNotNull { barcode ->
            val value = normalizeRetailBarcode(barcode.rawValue) ?: return@mapNotNull null
            val area = barcode.boundingBox?.let { it.width().toLong() * it.height().toLong() } ?: 0L
            value to area
        }.sortedByDescending { it.second }

        val best = candidates.firstOrNull()?.first ?: return
        val confirmed = capturePolicy.observe(best, System.currentTimeMillis()) ?: return
        runOnUiThread {
            statusText.text = "تمت قراءة $confirmed"
            val result = Intent().putExtra(EXTRA_BARCODE, confirmed)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun configureGestures(boundCamera: Camera) {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val state = boundCamera.cameraInfo.zoomState.value ?: return false
                val target = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                boundCamera.cameraControl.setZoomRatio(target)
                return true
            }
        })

        previewView.setOnTouchListener { _: View, event: MotionEvent ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()
                boundCamera.cameraControl.startFocusAndMetering(action)
                statusText.text = "تم ضبط التركيز • ثبّت الباركود داخل الإطار"
            }
            true
        }
    }

    private fun toggleTorch() {
        val boundCamera = camera ?: return
        torchEnabled = !torchEnabled
        boundCamera.cameraControl.enableTorch(torchEnabled)
        torchButton.text = if (torchEnabled) "إطفاء الفلاش" else "تشغيل الفلاش"
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", message))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scanner?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
