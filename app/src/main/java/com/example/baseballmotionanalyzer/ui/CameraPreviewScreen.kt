package com.example.baseballmotionanalyzer.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.baseballmotionanalyzer.analytics.BaseballPhysicsEngine
import com.example.baseballmotionanalyzer.cv.PoseLandmarkerHelper
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 相機預覽與 60FPS 姿態分析主畫面 (圖層對齊與極致流暢防護版)
 */
@Composable
fun CameraPreviewScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // UI 狀態管理
    var playerHeightCm by remember { mutableFloatStateOf(175f) }
    var currentPoseResult by remember { mutableStateOf<PoseLandmarkerResult?>(null) }
    var ballTrajectory by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var metrics by remember { mutableStateOf(BaseballPhysicsEngine.AnalysisMetrics()) }

    // MediaPipe Helper 初始化
    val poseLandmarkerHelper = remember {
        PoseLandmarkerHelper(
            context = context,
            poseLandmarkerListener = object : PoseLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String) {}

                override fun onResults(
                    result: PoseLandmarkerResult,
                    mpImage: MPImage,
                    timestampMs: Long
                ) {
                    currentPoseResult = result

                    result.landmarks().firstOrNull()?.let { landmarks ->
                        // 1. 自動根據打者頭頂 (0) 與腳踝 (27,28) 計算像素高度
                        val noseY = landmarks[0].y()
                        val ankleY = (landmarks[27].y() + landmarks[28].y()) / 2f
                        val personPixelHeight = abs(ankleY - noseY) * 720f

                        val scale = BaseballPhysicsEngine.calculateScaleFromPlayerHeight(
                            playerHeightCm = playerHeightCm,
                            personPixelHeight = personPixelHeight
                        )

                        // 2. 追蹤手腕 (15) 揮棒加速度與物理位移
                        val wristIndex = 15
                        if (landmarks.size > wristIndex) {
                            val wristLandmark = landmarks[wristIndex]
                            val curX = wristLandmark.x() * 1280f
                            val curY = wristLandmark.y() * 720f

                            val prevOffset = ballTrajectory.lastOrNull() ?: Offset(curX - 30f, curY + 20f)

                            val calculated = BaseballPhysicsEngine.calculateMetrics(
                                p1X = prevOffset.x, p1Y = prevOffset.y,
                                p2X = curX, p2Y = curY,
                                pixelToMeterScale = scale,
                                playerHeightCm = playerHeightCm,
                                fps = 60f
                            )
                            metrics = calculated

                            ballTrajectory = (ballTrajectory + Offset(curX, curY)).takeLast(15)
                        }
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            poseLandmarkerHelper.clear()
        }
    }

    // Jetpack Compose Box 三層圖層堆疊架構
    Box(modifier = modifier.fillMaxSize()) {
        // 【底層 Layer 0】: CameraX 視訊畫面 (PreviewView)
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

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

                            poseLandmarkerHelper.detectAsync(
                                bitmap = rotatedBitmap,
                                timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            // 關鍵防護：finally 確保 imageProxy 無論如何都會釋放，徹底防止 60FPS 畫面凍結卡頓
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 【中層 Layer 1】: 骨架與軌跡畫布 (與底層畫面 100% 座標精確對齊)
        BaseballOverlayCanvas(
            poseResult = currentPoseResult,
            trajectoryPoints = ballTrajectory
        )

        // 【頂層 Layer 2】: 抬頭顯示與身高設定面板 (置頂不被遮擋)
        MetricsHUD(
            metrics = metrics,
            playerHeightCm = playerHeightCm,
            onPlayerHeightChange = { playerHeightCm = it },
            currentFps = 60
        )
    }
}
