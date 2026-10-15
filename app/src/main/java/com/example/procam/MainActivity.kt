package com.example.procam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var exposure: SeekBar
    private lateinit var zoom: SeekBar
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashIdx = 0
    private var filterIdx = 0
    private val flashNames = listOf("OFF", "AUTO", "ON", "TORCH")
    private val io = Executors.newSingleThreadExecutor()

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun scale(r: Float, g: Float, b: Float) =
        ColorMatrix(floatArrayOf(r,0f,0f,0f,0f, 0f,g,0f,0f,0f, 0f,0f,b,0f,0f, 0f,0f,0f,1f,0f))

    private val filters: List<Pair<String, ColorMatrix>> by lazy {
        listOf(
            "Normal" to ColorMatrix(),
            "B&W" to sat(0f),
            "Sepia" to sat(0f).apply { postConcat(scale(1.07f, 0.74f, 0.43f)) },
            "Warm" to scale(1.12f, 1.0f, 0.85f),
            "Cool" to scale(0.88f, 1.0f, 1.14f),
            "Vivid" to sat(1.7f),
            "Faded" to ColorMatrix(floatArrayOf(
                0.85f,0f,0f,0f,30f, 0f,0.85f,0f,0f,30f, 0f,0f,0.85f,0f,30f, 0f,0f,0f,1f,0f)).apply { preConcat(sat(0.7f)) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        // COMPATIBLE = TextureView, which lets us tint the live preview with a filter
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        info = findViewById(R.id.info)
        exposure = findViewById(R.id.exposure)
        zoom = findViewById(R.id.zoom)

        findViewById<Button>(R.id.btnFlash).setOnClickListener { b ->
            flashIdx = (flashIdx + 1) % flashNames.size
            (b as Button).text = "Flash: ${flashNames[flashIdx]}"
            applyFlash()
        }
        findViewById<Button>(R.id.btnFilter).setOnClickListener { b ->
            filterIdx = (filterIdx + 1) % filters.size
            (b as Button).text = filters[filterIdx].first
            applyPreviewFilter()
        }
        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                   else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { capture() }

        zoom.setOnSeekBarChangeListener(simpleListener { p -> camera?.cameraControl?.setLinearZoom(p / 100f) })
        exposure.setOnSeekBarChangeListener(simpleListener { p ->
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return@simpleListener
            val idx = range.lower + p
            camera?.cameraControl?.setExposureCompensationIndex(idx)
            info.text = "EV $idx"
        })

        // Tap to focus
        previewView.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) {
                val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                v.performClick()
            }
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
    }

    private fun simpleListener(cb: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) cb(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(this,
                CameraSelector.Builder().requireLensFacing(lens).build(), preview, imageCapture)
            camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let {
                exposure.max = it.upper - it.lower
                exposure.progress = -it.lower
            }
            zoom.progress = 0
            applyFlash()
            applyPreviewFilter()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyFlash() {
        val torch = flashIdx == 3
        camera?.cameraControl?.enableTorch(torch)
        imageCapture?.flashMode = when (flashIdx) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun applyPreviewFilter() {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(filters[filterIdx].second) }
        previewView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun capture() {
        val ic = imageCapture ?: return
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                var bmp = image.toBitmap()
                image.close()
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(bmp, 0f, 0f,
                    Paint().apply { colorFilter = ColorMatrixColorFilter(filters[filterIdx].second) })
                save(out)
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun save(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ProCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, s) } }
        runOnUiThread { Toast.makeText(this, "Saved to Pictures/ProCam", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }
}
