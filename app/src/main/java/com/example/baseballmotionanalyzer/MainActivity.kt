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
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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

data class CameraLensInfo(
    val cameraId: String,
    val facingName: String,
    val maxFps: Int,
    val maxResLabel: String,
    val isSelected: Boolean
)

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BaseballOverlayView
    private lateinit var btnTabCamera: Button
    private lateinit var btnTabHistory: Button
    private lateinit var btnBackToCamera: Button
    private lateinit var btnHeight: Button
    private lateinit var btnBatterSide: Button
    private lateinit var btnDistance: Button
    private lateinit var btnSettings: Button
    private lateinit var tvSpeed: TextView
    private lateinit var tvAngle: TextView
    private lateinit var tvAngularVelocity: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvTotalSwings: TextView
    private lateinit var tvFps: TextView
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
    private var currentCameraFps: Float = 60f // 動態相機幀率 (依據選取鏡頭硬體動態決定 60f / 120f)

    private var isRightHanded: Boolean = true
    private var selectedCameraId: String = "0"
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
        btnDistance = findViewById(R.id.btnDistance)
        btnSettings = findViewById(R.id.btnSettings)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAngle = findViewById(R.id.tvAngle)
        tvAngularVelocity = findViewById(R.id.tvAngularVelocity)
        tvDistance = findViewById(R.id.tvDistance)
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvTotalSwings = findViewById(R.id.tvTotalSwings)
        tvFps = findViewById(R.id.tvFps)
        cameraContainer = findViewById(R.id.cameraContainer)
        historyContainer = findViewById(R.id.historyContainer)

        btnHeight.setOnClickListener { showHeightInputDialog() }
        btnBatterSide.setOnClickListener { toggleBatterSide() }
        btnDistance.setOnClickListener { cycleCameraDistance() }
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

    private fun cycleCameraLens() {
        val cameraList = getAvailableCameraList()
        if (cameraList.isEmpty()) return

        val currentIndex = cameraList.indexOfFirst { it.cameraId == selectedCameraId }
        val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % cameraList.size else 0
        val nextLens = cameraList[nextIndex]

        selectedCameraId = nextLens.cameraId
        Toast.makeText(this, "📷 已切換至 鏡頭 #${nextLens.cameraId} (${nextLens.facingName})", Toast.LENGTH_SHORT).show()
        startCameraAndAnalysis()
    }

    private fun cycleCameraDistance() {
        val presets = floatArrayOf(3.0f, 3.5f, 4.0f, 4.5f, 5.0f, 6.0f)
        val currentIndex = presets.indexOfFirst { abs(it - cameraDistanceMeters) < 0.1f }
        val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % presets.size else 2
        cameraDistanceMeters = presets[nextIndex]
        btnDistance.text = String.format("🎥 距離: %.1fm", cameraDistanceMeters)
        Toast.makeText(this, "🎥 已切換拍攝距離為 ${cameraDistanceMeters} 米 (本壘板動態縮放對齊中)", Toast.LENGTH_SHORT).show()
        startCameraAndAnalysis()
    }

    private fun switchToCameraTab() {
        if (cameraContainer.visibility == View.VISIBLE) {
            // 已在相機頁面時，點按 🎥 按鈕即可一鍵輪播切換下一個鏡頭
            cycleCameraLens()
            return
        }

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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCameraAndAnalysis() {
        val cameraList = getAvailableCameraList()
        val selectedLens = cameraList.firstOrNull { it.cameraId == selectedCameraId } ?: cameraList.firstOrNull()
        currentCameraFps = (selectedLens?.maxFps ?: 60).toFloat()
        if (::tvFps.isInitialized) {
            tvFps.text = "🟢 ${currentCameraFps.toInt()} FPS"
        }

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
                                        currentCameraFps
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
                                    fps = currentCameraFps
                                )

                                // 揮棒後定格呈現與出球 CV 追蹤 (Post-Swing Peak Latching Mode)
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
                                        // 揮棒動作剛剛結束 -> 寫入歷史並啟動定格冷卻
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

                                val maxLen = (flightCaptureFrames * 3).coerceAtLeast(10)
                                if (ballTrajectory.size >= maxLen) ballTrajectory.removeAt(0)
                                ballTrajectory.add(Pair(curX, curY))
                            }
                        }

                        overlayView.setResults(result, ballTrajectory, isRightHanded, minDetectionConfidence, cameraDistanceMeters)
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
                    .addCameraFilter { cameraInfos ->
                        val filtered = cameraInfos.filter { cameraInfo ->
                            try {
                                val c2Info = Camera2CameraInfo.from(cameraInfo)
                                c2Info.cameraId == selectedCameraId
                            } catch (e: Exception) {
                                false
                            }
                        }
                        if (filtered.isNotEmpty()) filtered else cameraInfos
                    }
                    .build()

                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding error: ${e.message}")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getAvailableCameraList(): List<CameraLensInfo> {
        val list = mutableListOf<CameraLensInfo>()
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "後置鏡頭 🔴"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置鏡頭 🟢"
                    else -> "外接鏡頭 🔵"
                }

                val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val upperFpsList = fpsRanges?.map { it.upper }?.distinct()?.sortedDescending() ?: listOf(60)
                val maxFps = upperFpsList.firstOrNull() ?: 60

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

                list.add(
                    CameraLensInfo(
                        cameraId = id,
                        facingName = facingName,
                        maxFps = maxFps,
                        maxResLabel = maxResLabel,
                        isSelected = (id == selectedCameraId)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying camera list: ${e.message}")
        }
        if (list.isEmpty()) {
            list.add(CameraLensInfo("0", "後置主鏡頭 🔴", 60, "FHD (1920x1080)", true))
        }
        return list
    }

    private fun getCameraHardwareSpecs(): String {
        val cameraList = getAvailableCameraList()
        val sb = StringBuilder()
        sb.append("📷 全機偵測到 ${cameraList.size} 顆鏡頭規格 (以最高 FPS 幀率為主體)：\n")

        for (lens in cameraList) {
            val selectMark = if (lens.isSelected) " [使用中 ⭐]" else ""
            if (lens.maxFps >= 120) {
                sb.append("⚡ 鏡頭 #${lens.cameraId} (${lens.facingName}$selectMark):\n")
                sb.append("   • 最高 ${lens.maxFps} FPS ➔ 對應畫質 FHD (1920x1080)\n")
                sb.append("   • 常規 60 FPS ➔ 對應畫質 ${lens.maxResLabel}\n")
            } else {
                sb.append("⚡ 鏡頭 #${lens.cameraId} (${lens.facingName}$selectMark): 最高 ${lens.maxFps} FPS ➔ 對應最高畫質 ${lens.maxResLabel}\n")
            }
        }
        return sb.toString().trimEnd()
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

        val hwSpecs = getCameraHardwareSpecs()
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

        val cameraList = getAvailableCameraList()

        val tvLensTitle = TextView(this).apply {
            text = "1. 📷 選擇動態偵測鏡頭 (全機共 ${cameraList.size} 顆鏡頭可隨時切換)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 10, 0, 4)
        }

        val rgLens = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        cameraList.forEach { lens ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                val activeTag = if (lens.isSelected) " [目前使用中 ⭐]" else ""
                text = "鏡頭 #${lens.cameraId} (${lens.facingName}$activeTag): 最高 ${lens.maxFps} FPS (${lens.maxResLabel})"
                setTextColor(if (lens.isSelected) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
                textSize = 12f
                tag = lens.cameraId
                isChecked = lens.isSelected
            }
            rgLens.addView(rb)
        }
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
                val checkedRbId = rgLens.checkedRadioButtonId
                if (checkedRbId != -1) {
                    val checkedRb = rgLens.findViewById<RadioButton>(checkedRbId)
                    val newCameraId = checkedRb?.tag as? String ?: "0"
                    selectedCameraId = newCameraId
                }

                cooldownSec = etCooldown.text.toString().toFloatOrNull() ?: 1.5f
                triggerSpeedKmh = etThreshold.text.toString().toFloatOrNull() ?: 50f
                minDetectionConfidence = (etMinDetect.text.toString().toFloatOrNull() ?: 0.65f).coerceIn(0.10f, 0.99f)
                minTrackingConfidence = (etMinTrack.text.toString().toFloatOrNull() ?: 0.50f).coerceIn(0.10f, 0.99f)
                cameraDistanceMeters = (etCameraDistance.text.toString().toFloatOrNull() ?: 4.0f).coerceIn(3.0f, 6.0f)
                playerHeightCm = etHeight.text.toString().toFloatOrNull() ?: 175f
                homePlateWidthCm = etPlateWidth.text.toString().toFloatOrNull() ?: 43.2f
                speedMultiplier = etMultiplier.text.toString().toFloatOrNull() ?: 1.00f
                flightCaptureFrames = (etFlightFrames.text.toString().toIntOrNull() ?: 5).coerceIn(3, 10)
                isBaseballMode = rbBaseball.isChecked

                btnHeight.text = "📏 身高: ${playerHeightCm.toInt()} cm"
                btnDistance.text = String.format("🎥 距離: %.1fm", cameraDistanceMeters)
                Toast.makeText(this, "設定已更新並載入 鏡頭 #${selectedCameraId}", Toast.LENGTH_SHORT).show()

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
