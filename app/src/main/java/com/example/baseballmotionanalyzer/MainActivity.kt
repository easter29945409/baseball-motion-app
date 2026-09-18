package com.example.baseballmotionanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.baseballmotionanalyzer.analytics.BaseballPhysicsEngine
import com.example.baseballmotionanalyzer.cv.PoseLandmarkerHelper
import com.example.baseballmotionanalyzer.ui.BaseballOverlayView
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BaseballOverlayView
    private lateinit var btnHeight: Button
    private lateinit var tvSpeed: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvDistance: TextView

    private var playerHeightCm: Float = 175f
    private var ballTrajectory = mutableListOf<Pair<Float, Float>>()
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        btnHeight = findViewById(R.id.btnHeight)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAngle = findViewById(R.id.tvAngle)
        tvDistance = findViewById(R.id.tvDistance)

        btnHeight.setOnClickListener { showHeightInputDialog() }

        if (checkCameraPermission()) {
            startCameraAndAnalysis()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraAndAnalysis()
        } else {
            Toast.makeText(this, "需要相機權限以進行追蹤", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCameraAndAnalysis() {
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = this,
            poseLandmarkerListener = object : PoseLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {}

                override fun onResults(
                    result: PoseLandmarkerResult,
                    mpImage: MPImage,
                    timestampMs: Long
                ) {
                    runOnUiThread {
                        result.landmarks().firstOrNull()?.let { landmarks ->
                            val noseY = landmarks[0].y()
                            val ankleY = (landmarks[27].y() + landmarks[28].y()) / 2f
                            val personPixelHeight = abs(ankleY - noseY) * 720f

                            val scale = BaseballPhysicsEngine.calculateScaleFromPlayerHeight(
                                playerHeightCm = playerHeightCm,
                                personPixelHeight = personPixelHeight
                            )

                            val wristIndex = 15
                            if (landmarks.size > wristIndex) {
                                val wrist = landmarks[wristIndex]
                                val curX = wrist.x() * 1280f
                                val curY = wrist.y() * 720f

                                val prev = ballTrajectory.lastOrNull() ?: Pair(curX - 30f, curY + 20f)

                                val metrics = BaseballPhysicsEngine.calculateMetrics(
                                    p1X = prev.first, p1Y = prev.second,
                                    p2X = curX, p2Y = curY,
                                    pixelToMeterScale = scale,
                                    playerHeightCm = playerHeightCm,
                                    fps = 60f
                                )

                                tvSpeed.text = String.format("%.1f km/h", metrics.speedKmh)
                                tvAngle.text = String.format("%.1f°", metrics.launchAngleDeg)
                                tvDistance.text = String.format("%.1f m", metrics.estimatedDistanceMeters)

                                if (ballTrajectory.size >= 15) ballTrajectory.removeAt(0)
                                ballTrajectory.add(Pair(curX, curY))
                            }
                        }

                        overlayView.setResults(result, ballTrajectory)
                    }
                }
            }
        )

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val cameraExecutor = Executors.newSingleThreadExecutor()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmap()
                    val matrix = Matrix().apply {
                        postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )

                    poseLandmarkerHelper?.detectAsync(
                        bitmap = rotatedBitmap,
                        timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showHeightInputDialog() {
        val input = EditText(this).apply {
            setText(playerHeightCm.toInt().toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("設定打者身高標定")
            .setMessage("請輸入打者真實身高 (cm)：")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                val parsed = input.text.toString().toFloatOrNull() ?: 175f
                playerHeightCm = parsed
                btnHeight.text = "📏 身高標定: ${playerHeightCm.toInt()} cm"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        poseLandmarkerHelper?.clear()
    }
}
