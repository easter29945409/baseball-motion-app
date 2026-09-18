package com.example.baseballmotionanalyzer.cv

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Google MediaPipe Pose Landmarker 封裝類別 (自檢與安全防護增強版)
 * 負責從 60FPS 畫面中檢測運動員 33 個骨架與四肢端點。
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val poseLandmarkerListener: LandmarkerListener? = null
) {
    private var poseLandmarker: PoseLandmarker? = null

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarkerResult, mpImage: MPImage, timestampMs: Long)
    }

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setDelegate(Delegate.GPU) // 優先使用 GPU/NPU 硬體加速

            val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPoseTrackingConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, image ->
                    val finishTimeMs = SystemClock.uptimeMillis()
                    poseLandmarkerListener?.onResults(result, image, finishTimeMs)
                }
                .setErrorListener { error ->
                    poseLandmarkerListener?.onError(error.message ?: "An unknown error occurred")
                }

            poseLandmarker = PoseLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            // 如果 GPU 初始化失敗，自動降級降載為 CPU 處理，確保 App 穩定不崩潰
            try {
                val fallbackBaseOptions = BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .build()
                val fallbackOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(fallbackBaseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, image ->
                        poseLandmarkerListener?.onResults(result, image, SystemClock.uptimeMillis())
                    }
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, fallbackOptions)
            } catch (fallbackEx: Exception) {
                poseLandmarkerListener?.onError("MediaPipe Pose setup notice: ${fallbackEx.message}")
            }
        }
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        poseLandmarker?.let { landmarker ->
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestampMs)
            } catch (e: Exception) {
                poseLandmarkerListener?.onError("Detection error: ${e.message}")
            }
        }
    }

    fun clear() {
        try {
            poseLandmarker?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        poseLandmarker = null
    }
}
