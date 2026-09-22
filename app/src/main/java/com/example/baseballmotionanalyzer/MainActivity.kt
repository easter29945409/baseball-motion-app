package com.example.baseballmotionanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.*
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
    private lateinit var btnBackToCamera: Button
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

    // 偏好設定與運動學變數
    private var playerHeightCm: Float = 175f
    private var cameraDistanceMeters: Float = 4.0f
    private var homePlateWidthCm: Float = 43.2f
    private var triggerSpeedKmh: Float = 50f
    private var cooldownSec: Float = 1.5f
    private var speedMultiplier: Float = 1.00f
    private var minDetectionConfidence: Float = 0.65f
    private var minTrackingConfidence: Float = 0.50f
    private var flightCaptureFrames: Int = 5 // 出球 CV 追蹤捕捉幀數 (3 ~ 10 幀)
    private var isBaseballMode: Boolean = true // true: 棒球 145g, false: 壘球 190g

    private var isRightHanded: Boolean = true
    private var selectedLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null

    private var ballTrajectory = mutableListOf<Pair<Float, Float>>()
    private val swingHistoryList = mutableListOf<SwingRecord>()
    private var swingCount = 0
    private var lastSwingTimeMs: Long = 0L

    // 揮棒峰值定格鎖定 (Post-Swing Peak Latching)
    private var isSwingingStroke: Boolean = false
    private var peakSpeedKmh: Float = 0f
    private var peakLaunchAngle: Float = 0f
    private var peakDistance: Float = 0f
    private var peakAngularVelocity: Float = 0f

    // 軀幹轉體角速度前幀座標
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
        btnBackToCamera = findViewById(R.id.btnBackToCamera)
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
        btnBackToCamera.setOnClickListener { switchToCameraTab() }

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

        btnTabHistory.setBackgroundColor(0x801E293B.toInt())
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

        btnTabCamera.setBackgroundColor(0x801E293B.toInt())
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
        poseLandmarkerHelper?.clear()
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = this,
            minDetectionConfidence = minDetectionConfidence,
            minTrackingConfidence = minTrackingConfidence,
            poseLandmarkerListener = object : PoseLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e(TAG, "MediaPipe Error: $error")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "AI Notice: $error", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResults(
                    result: PoseLandmarkerResult,
                    mpImage: MPImage,
                    timestampMs: Long
                ) {
                    runOnUiThread {
                        val now = System.currentTimeMillis()
                        val cooldownMs = (cooldownSec * 1000).toLong()
                        val isInCooldown = (now - lastSwingTimeMs < cooldownMs)

                        result.landmarks().firstOrNull()?.let { landmarks ->
                            val noseY = landmarks[0].y()
                            val ankleY = (landmarks[27].y() + landmarks[28].y()) / 2f
                            val personPixelHeight = abs(ankleY - noseY) * 720f

                            val scale = BaseballPhysicsEngine.calculateScaleFromPlayerHeight(
                                playerHeightCm = playerHeightCm,
                                personPixelHeight = personPixelHeight,
                                cameraDistanceMeters = cameraDistanceMeters,
                                speedMultiplier = speedMultiplier
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

                            // 根據打席鎖定主導手腕 (右打: 15 / 左打: 16)
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

                                // 揮棒後定格呈現邏輯 (Post-Swing Peak Latching Mode)
                                if (!isInCooldown) {
                                    if (metrics.speedKmh >= triggerSpeedKmh && metrics.isCorrectDirection) {
                                        isSwingingStroke = true
                                        if (metrics.speedKmh > peakSpeedKmh) {
                                            peakSpeedKmh = metrics.speedKmh
                                            peakLaunchAngle = metrics.launchAngleDeg
                                            peakDistance = metrics.estimatedDistanceMeters
                                            peakAngularVelocity = metrics.angularVelocityDegSec
                                        }
                                    } else if (isSwingingStroke) {
                                        // 揮棒動作剛剛結束 $\rightarrow$ 寫入歷史並啟動定格冷卻
                                        isSwingingStroke = false
                                        lastSwingTimeMs = now
                                        swingCount++
                                        swingHistoryList.add(
                                            SwingRecord(
                                                id = swingCount,
                                                speedKmh = peakSpeedKmh,
                                                launchAngleDeg = peakLaunchAngle,
                                                distanceMeters = peakDistance,
                                                angularVelocityDegSec = peakAngularVelocity,
                                                timestamp = "揮棒 #${swingCount}"
                                            )
                                        )

                                        tvSpeed.text = String.format("%.1f km/h", peakSpeedKmh)
                                        tvAngle.text = String.format("%.1f°", peakLaunchAngle)
                                        tvAngularVelocity.text = String.format("%.0f deg/s", peakAngularVelocity)
                                        tvDistance.text = String.format("%.1f m", peakDistance)

                                        peakSpeedKmh = 0f
                                        peakLaunchAngle = 0f
                                        peakDistance = 0f
                                        peakAngularVelocity = 0f
                                    }
                                }

                                if (ballTrajectory.size >= 15) ballTrajectory.removeAt(0)
                                ballTrajectory.add(Pair(curX, curY))
                            }
                        }

                        overlayView.setResults(result, ballTrajectory, isRightHanded, minDetectionConfidence)
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
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(selectedLensFacing)
                    .build()

                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCameraHardwareSpecs(lensFacing: Int): String {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            val sb = StringBuilder()
            sb.append("📷 全機鏡頭規格 (以最高 FPS 幀率為主體)：\n")

            for ((index, id) in cameraIds.withIndex()) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "後置鏡頭 🔴"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置鏡頭 🟢"
                    else -> "外接鏡頭 🔵"
                }

                val isSelected = (facing == lensFacing)

                // 1. 取得此鏡頭支援的所有 upper FPS 幀率 (由高到低排序，例: 120, 60, 30)
                val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val upperFpsList = fpsRanges?.map { it.upper }?.distinct()?.sortedDescending() ?: listOf(60)
                val maxFps = upperFpsList.firstOrNull() ?: 60

                // 2. 取得此鏡頭支援的最高解析度
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                    ?: map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    ?: emptyArray()

                val maxPixelSize = sizes.maxByOrNull { it.width * it.height }
                val maxResLabel = if (maxPixelSize != null) {
                    val pixels = maxPixelSize.width * maxPixelSize.height
                    when {
                        pixels >= 3840 * 2160 -> "4K (3840x2160)"
                        pixels >= 2560 * 1440 -> "2K (2560x1440)"
                        pixels >= 1920 * 1080 -> "FHD (1920x1080)"
                        else -> "${maxPixelSize.width}x${maxPixelSize.height}"
                    }
                } else {
                    "FHD (1920x1080)"
                }

                val selectMark = if (isSelected) " [使用中 ⭐]" else ""

                // 3. 依 FPS 為主體格式化輸出 (強調最高 FPS 與相對應的最高畫質)
                if (maxFps >= 120) {
                    val resAt120 = if (maxPixelSize != null && maxPixelSize.width >= 3840) "FHD (1920x1080)" else maxResLabel
                    sb.append("⚡ 鏡頭 #$index ($facingName$selectMark):\n")
                    sb.append("   • 最高 $maxFps FPS ➔ 對應畫質 $resAt120\n")
                    sb.append("   • 常規 60 FPS ➔ 對應畫質 $maxResLabel\n")
                } else {
                    sb.append("⚡ 鏡頭 #$index ($facingName$selectMark): 最高 $maxFps FPS ➔ 對應最高畫質 $maxResLabel\n")
                }
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            "📷 鏡頭規格: 支援 60 FPS 高速拍攝 (對應最高 FHD 畫質)"
        }
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
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val hwSpecs = getCameraHardwareSpecs(selectedLensFacing)
        val tvInfo = TextView(this).apply {
            text = "ℹ️ 當前相機硬體規格 (Camera2 Specs):\n$hwSpecs"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(tvInfo)

        fun createLabeledField(titleText: String, defaultVal: String, inputTypeEnum: Int): EditText {
            val tvLabel = TextView(this).apply {
                text = titleText
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(0, 10, 0, 4)
            }
            val etInput = EditText(this).apply {
                setText(defaultVal)
                inputType = inputTypeEnum
                setTextColor(0xFF00E5FF.toInt())
            }
            layout.addView(tvLabel)
            layout.addView(etInput)
            return etInput
        }

        val tvLensTitle = TextView(this).apply {
            text = "1. 📷 選擇相機鏡頭 (Camera Lens)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 10, 0, 4)
        }
        val rgLens = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbBack = RadioButton(this).apply {
            text = "後置主鏡頭"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = (selectedLensFacing == CameraSelector.LENS_FACING_BACK)
        }
        val rbFront = RadioButton(this).apply {
            text = "前置自拍鏡頭"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = (selectedLensFacing == CameraSelector.LENS_FACING_FRONT)
        }
        rgLens.addView(rbBack)
        rgLens.addView(rbFront)
        layout.addView(tvLensTitle)
        layout.addView(rgLens)

        val etCooldown = createLabeledField(
            "2. ⏱️ 防重複揮棒冷卻時間 (秒)",
            cooldownSec.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etThreshold = createLabeledField(
            "3. ⚡ 揮棒觸發速度門檻 (km/h)",
            triggerSpeedKmh.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etMinDetect = createLabeledField(
            "4. 🤖 AI 人體偵測信賴度 (0.10 ~ 0.99)",
            minDetectionConfidence.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etMinTrack = createLabeledField(
            "5. 🎯 AI 關節追蹤信賴度 (0.10 ~ 0.99)",
            minTrackingConfidence.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etCameraDistance = createLabeledField(
            "6. 🎥 預設相機拍攝距離 (公尺)",
            cameraDistanceMeters.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etHeight = createLabeledField(
            "7. 📏 打者真實身高標定 (cm)",
            playerHeightCm.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        )

        val etPlateWidth = createLabeledField(
            "8. 🎯 本壘板寬度標定 (cm)",
            homePlateWidthCm.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etMultiplier = createLabeledField(
            "9. 🎛️ 速度物理修正增益係數 (Multiplier)",
            speedMultiplier.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        val etFlightFrames = createLabeledField(
            "10. 🎯 出球 CV 追蹤捕捉幀數 (3 ~ 10 幀)",
            flightCaptureFrames.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER
        )

        val tvBallTypeTitle = TextView(this).apply {
            text = "11. 🥎 球種選擇 (Ball Type)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 10, 0, 4)
        }
        val rgBallType = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbBaseball = RadioButton(this).apply {
            text = "棒球 (145g)"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = isBaseballMode
        }
        val rbSoftball = RadioButton(this).apply {
            text = "壘球 (190g)"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = !isBaseballMode
        }
        rgBallType.addView(rbBaseball)
        rgBallType.addView(rbSoftball)
        layout.addView(tvBallTypeTitle)
        layout.addView(rgBallType)

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("⚙️ 系統進階參數與相機鏡頭設定")
            .setView(scrollView)
            .setPositiveButton("確定儲存並套用") { _, _ ->
                selectedLensFacing = if (rbBack.isChecked) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                cooldownSec = etCooldown.text.toString().toFloatOrNull() ?: 1.5f
                triggerSpeedKmh = etThreshold.text.toString().toFloatOrNull() ?: 50f
                minDetectionConfidence = (etMinDetect.text.toString().toFloatOrNull() ?: 0.65f).coerceIn(0.10f, 0.99f)
                minTrackingConfidence = (etMinTrack.text.toString().toFloatOrNull() ?: 0.50f).coerceIn(0.10f, 0.99f)
                cameraDistanceMeters = etCameraDistance.text.toString().toFloatOrNull() ?: 4.0f
                playerHeightCm = etHeight.text.toString().toFloatOrNull() ?: 175f
                homePlateWidthCm = etPlateWidth.text.toString().toFloatOrNull() ?: 43.2f
                speedMultiplier = etMultiplier.text.toString().toFloatOrNull() ?: 1.00f
                flightCaptureFrames = (etFlightFrames.text.toString().toIntOrNull() ?: 5).coerceIn(3, 10)
                isBaseballMode = rbBaseball.isChecked

                btnHeight.text = "📏 身高: ${playerHeightCm.toInt()} cm"
                Toast.makeText(this, "設定已更新並重新載入鏡頭與 AI", Toast.LENGTH_SHORT).show()

                startCameraAndAnalysis()
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
