package com.example.baseballmotionanalyzer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * 棒球姿態與軌跡繪製 View (超高效標準 Android Custom View)
 * 零記憶體浪費，極致順暢，無任何 Kotlin Compose 相容性依賴問題。
 */
class BaseballOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var poseResult: PoseLandmarkerResult? = null
    private var trajectoryPoints: List<Pair<Float, Float>> = emptyList()

    private val skeletonPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // 亮青色
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val keyJointPaint = Paint().apply {
        color = Color.parseColor("#FFEA00") // 亮黃色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val jointPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val trajectoryPaint = Paint().apply {
        color = Color.parseColor("#00E676") // 亮綠軌跡
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val trajectoryPath = Path()

    fun setResults(result: PoseLandmarkerResult?, points: List<Pair<Float, Float>>) {
        this.poseResult = result
        this.trajectoryPoints = points
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val canvasWidth = width.toFloat()
        val canvasHeight = height.toFloat()

        // 1. 繪製殘影動態軌跡
        if (trajectoryPoints.size > 1) {
            trajectoryPath.reset()
            trajectoryPath.moveTo(trajectoryPoints.first().first, trajectoryPoints.first().second)
            for (i in 1 until trajectoryPoints.size) {
                trajectoryPath.lineTo(trajectoryPoints[i].first, trajectoryPoints[i].second)
            }
            canvas.drawPath(trajectoryPath, trajectoryPaint)
        }

        // 2. 繪製 MediaPipe 33 點姿態與四肢端點
        poseResult?.landmarks()?.firstOrNull()?.let { landmarkList ->
            val connections = PoseLandmarker.POSE_LANDMARKS

            // 骨架連線
            connections.forEach { connection ->
                val startLm = landmarkList[connection.start()]
                val endLm = landmarkList[connection.end()]

                val startX = startLm.x() * canvasWidth
                val startY = startLm.y() * canvasHeight
                val endX = endLm.x() * canvasWidth
                val endY = endLm.y() * canvasHeight

                canvas.drawLine(startX, startY, endX, endY, skeletonPaint)
            }

            // 四肢端點標示 (15, 16, 13, 14, 27, 28, 25, 26)
            val keyJointIndices = listOf(15, 16, 13, 14, 27, 28, 25, 26)

            landmarkList.forEachIndexed { index, landmark ->
                val cx = landmark.x() * canvasWidth
                val cy = landmark.y() * canvasHeight
                val isKey = index in keyJointIndices

                if (isKey) {
                    canvas.drawCircle(cx, cy, 10f, keyJointPaint)
                } else {
                    canvas.drawCircle(cx, cy, 5f, jointPaint)
                }
            }
        }
    }
}
