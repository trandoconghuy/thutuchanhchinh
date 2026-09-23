package com.example.cccdscanner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnStartScan: Button
    private lateinit var resultContainer: ScrollView
    private lateinit var txtResult: TextView
    private lateinit var btnRescan: Button

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanning = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnStartScan = findViewById(R.id.btnStartScan)
        resultContainer = findViewById(R.id.resultContainer)
        txtResult = findViewById(R.id.txtResult)
        btnRescan = findViewById(R.id.btnRescan)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnStartScan.setOnClickListener { checkAndStartCamera() }
        btnRescan.setOnClickListener {
            resultContainer.visibility = View.GONE
            btnStartScan.visibility = View.VISIBLE
            checkAndStartCamera()
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndStartCamera() {
        if (allPermissionsGranted()) {
            btnStartScan.visibility = View.GONE
            resultContainer.visibility = View.GONE
            viewFinder.visibility = View.VISIBLE
            startCameraSession()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            checkAndStartCamera()
        }
    }

    private fun startCameraSession() {
        isScanning = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            val barcodeScanner = BarcodeScanning.getClient()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(barcodeScanner, imageProxy) } }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) { exc.printStackTrace() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            val rawValue = barcode.rawValue
                            if (!rawValue.isNullOrEmpty() && isScanning) {
                                isScanning = false
                                runOnUiThread { onScanSuccess(rawValue) }
                                break
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else { imageProxy.close() }
    }

    private fun onScanSuccess(rawText: String) {
        try {
            val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneG.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {}

        cameraProvider?.unbindAll()
        viewFinder.visibility = View.GONE
        resultContainer.visibility = View.VISIBLE
        txtResult.text = parseCCCD(rawText)
    }

    private fun parseCCCD(text: String): String {
        val p = text.split("|")
        return if (p.size >= 6) {
            """
            • Số CCCD: ${p[0]}
            • Số CMND cũ: ${p[1].ifEmpty { "Không có" }}
            • Họ và tên: ${p[2]}
            • Ngày sinh: ${p[3]}
            • Giới tính: ${p[4]}
            • Nơi thường trú: ${p[5]}
            • Ngày cấp: ${if (p.size > 6) p[6] else "Không có"}
            """.trimIndent()
        } else { "Dữ liệu thô:\n$text" }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
