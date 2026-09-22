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
 * Google MediaPipe Pose Landmarker 封裝類別 (修復 MediaPipe 0.10.14 API 方法名稱)
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val minDetectionConfidence: Float = 0.65f,
    private val minTrackingConfidence: Float = 0.50f,
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
                .setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(Delegate.GPU)

            val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinPoseDetectionConfidence(minDetectionConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setMinPosePresenceConfidence(minDetectionConfidence)
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
            try {
                val fallbackBaseOptions = BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(Delegate.CPU)
                    .build()
                val fallbackOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(fallbackBaseOptions)
                    .setMinPoseDetectionConfidence(minDetectionConfidence)
                    .setMinTrackingConfidence(minTrackingConfidence)
                    .setMinPosePresenceConfidence(minDetectionConfidence)
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
