package com.example.baseballmotionanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
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

import android.util.Log
import android.widget.LinearLayout

data class SwingRecord(
    val id: Int,
    val speedKmh: Float,
    val launchAngleDeg: Float,
    val distanceMeters: Float,
    val angularVelocityDegSec: Float,
    val timestamp: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BaseballOverlayView
    private lateinit var btnTabCamera: Button
    private lateinit var btnTabHistory: Button
    private lateinit var btnHeight: Button
    private lateinit var btnBatterSide: Button
    private lateinit var btnSettings: Button
    private lateinit var tvSpeed: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvAngularVelocity: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvTotalSwings: TextView
    private lateinit var cameraContainer: View
    private lateinit var historyContainer: View

    private var playerHeightCm: Float = 175f
    private var triggerSpeedKmh: Float = 50f
    private var cooldownSec: Float = 1.5f
    private var homePlateWidthCm: Float = 43.2f

    private var isRightHanded: Boolean = true
    private var cameraProvider: ProcessCameraProvider? = null
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    private var ballTrajectory = mutableListOf<Pair<Float, Float>>()
    private val swingHistoryList = mutableListOf<SwingRecord>()
    private var swingCount = 0
    private var lastSwingTimeMs: Long = 0L

    private var prevLsX: Float = 0f
    private var prevLsY: Float = 0f
    private var prevRsX: Float = 0f
    private var prevRsY: Float = 0f
    private var hasPrevShoulders: Boolean = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "BaseballMotionApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        btnTabCamera = findViewById(R.id.btnTabCamera)
        btnTabHistory = findViewById(R.id.btnTabHistory)
        btnHeight = findViewById(R.id.btnHeight)
        btnBatterSide = findViewById(R.id.btnBatterSide)
        btnSettings = findViewById(R.id.btnSettings)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAngle = findViewById(R.id.tvAngle)
        tvAngularVelocity = findViewById(R.id.tvAngularVelocity)
        tvDistance = findViewById(R.id.tvDistance)
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvTotalSwings = findViewById(R.id.tvTotalSwings)
        cameraContainer = findViewById(R.id.cameraContainer)
        historyContainer = findViewById(R.id.historyContainer)

        btnHeight.setOnClickListener { showHeightInputDialog() }
        btnBatterSide.setOnClickListener { toggleBatterSide() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        btnTabCamera.setOnClickListener { switchToCameraTab() }
        btnTabHistory.setOnClickListener { switchToHistoryTab() }

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

    private fun switchToCameraTab() {
        cameraContainer.visibility = View.VISIBLE
        historyContainer.visibility = View.GONE

        btnTabCamera.setBackgroundColor(0xFF00E5FF.toInt())
        btnTabCamera.setTextColor(0xFF000000.toInt())

        btnTabHistory.setBackgroundColor(0xFF1E293B.toInt())
        btnTabHistory.setTextColor(0xFFFFFFFF.toInt())

        if (checkCameraPermission()) {
            startCameraAndAnalysis()
        }
    }

    private fun switchToHistoryTab() {
        // 關鍵需求：切換至歷史數據頁時，立即關閉釋放相機鏡頭省電！
        cameraProvider?.unbindAll()

        cameraContainer.visibility = View.GONE
        historyContainer.visibility = View.VISIBLE

        btnTabHistory.setBackgroundColor(0xFF00E5FF.toInt())
        btnTabHistory.setTextColor(0xFF000000.toInt())

        btnTabCamera.setBackgroundColor(0xFF1E293B.toInt())
        btnTabCamera.setTextColor(0xFFFFFFFF.toInt())

        updateHistoryStats()
    }

    private fun toggleBatterSide() {
        isRightHanded = !isRightHanded
        if (isRightHanded) {
            btnBatterSide.text = "⚾ 右打 (RHH)"
            btnBatterSide.setTextColor(0xFFFFEA00.toInt())
        } else {
            btnBatterSide.text = "⚾ 左打 (LHH)"
            btnBatterSide.setTextColor(0xFF00E5FF.toInt())
        }
    }

    private fun startCameraAndAnalysis() {
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = this,
            poseLandmarkerListener = object : PoseLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e(TAG, "MediaPipe Error: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "MediaPipe AI: $error", Toast.LENGTH_SHORT).show()
                    }
                }

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

                            // 計算雙肩軀幹角速度 (Landmark 11: Left Shoulder, Landmark 12: Right Shoulder)
                            var trunkAngularVel = 0f
                            if (landmarks.size > 12) {
                                val curLsX = landmarks[11].x() * 1280f
                                val curLsY = landmarks[11].y() * 720f
                                val curRsX = landmarks[12].x() * 1280f
                                val curRsY = landmarks[12].y() * 720f

                                if (hasPrevShoulders) {
                                    trunkAngularVel = BaseballPhysicsEngine.calculateTrunkAngularVelocity(
                                        prevLsX, prevLsY, prevRsX, prevRsY,
                                        curLsX, curLsY, curRsX, curRsY,
                                        60f
                                    )
                                }
                                prevLsX = curLsX
                                prevLsY = curLsY
                                prevRsX = curRsX
                                prevRsY = curRsY
                                hasPrevShoulders = true
                            }

                            // 根據手動選擇的打席鎖定主導手腕 (右打: 15 / 左打: 16)
                            val wristIndex = if (isRightHanded) 15 else 16
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
                                    isRightHanded = isRightHanded,
                                    trunkAngularVelocity = trunkAngularVel,
                                    fps = 60f
                                )

                                tvSpeed.text = String.format("%.1f km/h", metrics.speedKmh)
                                tvAngle.text = String.format("%.1f°", metrics.launchAngleDeg)
                                tvAngularVelocity.text = String.format("%.0f deg/s", metrics.angularVelocityDegSec)
                                tvDistance.text = String.format("%.1f m", metrics.estimatedDistanceMeters)

                                // 記錄超高速度觸發點 + 方向性過濾 + 自訂冷卻時間
                                val now = System.currentTimeMillis()
                                val cooldownMs = (cooldownSec * 1000).toLong()
                                if (metrics.speedKmh >= triggerSpeedKmh && metrics.isCorrectDirection && (now - lastSwingTimeMs > cooldownMs)) {
                                    lastSwingTimeMs = now
                                    swingCount++
                                    swingHistoryList.add(
                                        SwingRecord(
                                            id = swingCount,
                                            speedKmh = metrics.speedKmh,
                                            launchAngleDeg = metrics.launchAngleDeg,
                                            distanceMeters = metrics.estimatedDistanceMeters,
                                            angularVelocityDegSec = metrics.angularVelocityDegSec,
                                            timestamp = "揮棒 #${swingCount}"
                                        )
                                    )
                                }

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
            cameraProvider = cameraProviderFuture.get()

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
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
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

    private fun updateHistoryStats() {
        if (swingHistoryList.isEmpty()) {
            tvMaxSpeed.text = "0.0 km/h"
            tvAvgSpeed.text = "0.0 km/h"
            tvTotalSwings.text = "0 次"
        } else {
            val maxSpd = swingHistoryList.maxOf { it.speedKmh }
            val avgSpd = swingHistoryList.map { it.speedKmh }.average().toFloat()
            tvMaxSpeed.text = String.format("%.1f km/h", maxSpd)
            tvAvgSpeed.text = String.format("%.1f km/h", avgSpd)
            tvTotalSwings.text = "${swingHistoryList.size} 次"
        }
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
                btnHeight.text = "📏 身高: ${playerHeightCm.toInt()} cm"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val etCooldown = EditText(this).apply {
            hint = "防重複冷卻時間 (秒) [預設: 1.5]"
            setText(cooldownSec.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val etThreshold = EditText(this).apply {
            hint = "揮棒觸發門檻 (km/h) [預設: 50]"
            setText(triggerSpeedKmh.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val etHeight = EditText(this).apply {
            hint = "打者身高 (cm) [預設: 175]"
            setText(playerHeightCm.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etPlateWidth = EditText(this).apply {
            hint = "本壘板寬度標定 (cm) [預設: 43.2]"
            setText(homePlateWidthCm.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        layout.addView(etCooldown)
        layout.addView(etThreshold)
        layout.addView(etHeight)
        layout.addView(etPlateWidth)

        AlertDialog.Builder(this)
            .setTitle("⚙️ 系統進階參數設定")
            .setView(layout)
            .setPositiveButton("儲存變更") { _, _ ->
                cooldownSec = etCooldown.text.toString().toFloatOrNull() ?: 1.5f
                triggerSpeedKmh = etThreshold.text.toString().toFloatOrNull() ?: 50f
                playerHeightCm = etHeight.text.toString().toFloatOrNull() ?: 175f
                homePlateWidthCm = etPlateWidth.text.toString().toFloatOrNull() ?: 43.2f

                btnHeight.text = "📏 身高: ${playerHeightCm.toInt()} cm"
                Toast.makeText(this, "設定已更新：冷卻 ${cooldownSec}s, 門檻 ${triggerSpeedKmh}km/h", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        poseLandmarkerHelper?.clear()
    }
}
