package com.example.baseballmotionanalyzer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * 棒球姿態與軌跡繪製 View (包含本壘板與打擊區輔助引導框)
 */
class BaseballOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var poseResult: PoseLandmarkerResult? = null
    private var trajectoryPoints: List<Pair<Float, Float>> = emptyList()

    private val guidancePaint = Paint().apply {
        color = Color.parseColor("#FFEA00") // 黃色虛線引導框
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
        isAntiAlias = true
    }

    private val guidanceTextPaint = Paint().apply {
        color = Color.parseColor("#FFEA00")
        textSize = 28f
        isAntiAlias = true
    }

    private val skeletonPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // 亮青色
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val keyJointPaint = Paint().apply {
        color = Color.parseColor("#FFEA00") // 亮黃關節
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

        // 0. 繪製「本壘板與打擊區引導虛線框」 (Guidance Frame Overlay)
        val frameLeft = canvasWidth * 0.25f
        val frameTop = canvasHeight * 0.15f
        val frameRight = canvasWidth * 0.75f
        val frameBottom = canvasHeight * 0.85f

        // 繪製打擊者區域引導外框
        canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, guidancePaint)
        canvas.drawText("🎯 請將打者對齊此虛線區域 (Batter Area)", frameLeft + 10f, frameTop + 35f, guidanceTextPaint)

        // 繪製底部分邊本壘板對齊線
        val homePlateY = canvasHeight * 0.82f
        canvas.drawLine(canvasWidth * 0.45f, homePlateY, canvasWidth * 0.55f, homePlateY, guidancePaint)
        canvas.drawText("本壘板 (Home Plate)", canvasWidth * 0.42f, homePlateY + 30f, guidanceTextPaint)

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

            connections.forEach { connection ->
                val startLm = landmarkList[connection.start()]
                val endLm = landmarkList[connection.end()]

                val startX = startLm.x() * canvasWidth
                val startY = startLm.y() * canvasHeight
                val endX = endLm.x() * canvasWidth
                val endY = endLm.y() * canvasHeight

                canvas.drawLine(startX, startY, endX, endY, skeletonPaint)
            }

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
