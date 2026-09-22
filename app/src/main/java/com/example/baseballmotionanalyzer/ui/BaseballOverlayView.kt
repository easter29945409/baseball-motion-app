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

    private val homePlatePath = Path()
    private var isRightHanded: Boolean = true

    fun setResults(
        result: PoseLandmarkerResult?,
        points: List<Pair<Float, Float>>,
        isRightHanded: Boolean = true
    ) {
        this.poseResult = result
        this.trajectoryPoints = points
        this.isRightHanded = isRightHanded
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val canvasWidth = width.toFloat()
        val canvasHeight = height.toFloat()

        // 0. 繪製「打擊區引導虛線框」 (Batter Box Overlay)
        val frameLeft = if (isRightHanded) canvasWidth * 0.20f else canvasWidth * 0.50f
        val frameTop = canvasHeight * 0.15f
        val frameRight = if (isRightHanded) canvasWidth * 0.50f else canvasWidth * 0.80f
        val frameBottom = canvasHeight * 0.85f

        canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, guidancePaint)
        val stanceText = if (isRightHanded) "🎯 打者區域 (右打 RHH)" else "🎯 打者區域 (左打 LHH)"
        canvas.drawText(stanceText, frameLeft + 10f, frameTop + 35f, guidanceTextPaint)

        // 繪製標準五邊形「本壘板 (Home Plate)」對齊框 (前平邊朝投手/上、後尖端朝捕手/下)
        val plateCenterX = canvasWidth * 0.50f
        val plateTopY = canvasHeight * 0.76f
        val plateWidth = canvasWidth * 0.12f   // 代表 43.2cm 本壘板
        val plateSideLen = canvasHeight * 0.04f // 21.6cm 側邊
        val plateApexY = plateTopY + canvasHeight * 0.08f // 尖端指向捕手/主審

        homePlatePath.reset()
        homePlatePath.moveTo(plateCenterX - plateWidth / 2f, plateTopY) // 左上 (平邊)
        homePlatePath.lineTo(plateCenterX + plateWidth / 2f, plateTopY) // 右上 (平邊朝投手)
        homePlatePath.lineTo(plateCenterX + plateWidth / 2f, plateTopY + plateSideLen) // 右側邊
        homePlatePath.lineTo(plateCenterX, plateApexY) // 後尖端 (指向捕手/主審 🔻)
        homePlatePath.lineTo(plateCenterX - plateWidth / 2f, plateTopY + plateSideLen) // 左側邊
        homePlatePath.close()

        canvas.drawPath(homePlatePath, guidancePaint)
        canvas.drawText("本壘板 (Home Plate 🔻)", plateCenterX - 90f, plateTopY - 10f, guidanceTextPaint)

        // 1. 繪製殘影動態軌跡
        if (trajectoryPoints.size > 1) {
            trajectoryPath.reset()
            trajectoryPath.moveTo(trajectoryPoints.first().first, trajectoryPoints.first().second)
            for (i in 1 until trajectoryPoints.size) {
                trajectoryPath.lineTo(trajectoryPoints[i].first, trajectoryPoints[i].second)
            }
            canvas.drawPath(trajectoryPath, trajectoryPaint)
        }

        // 2. 繪製 MediaPipe 33 點姿態與四肢端點 (包含可見度/置信度過濾，徹底防止背景鬼影)
        poseResult?.landmarks()?.firstOrNull()?.let { landmarkList ->
            val connections = PoseLandmarker.POSE_LANDMARKS

            connections.forEach { connection ->
                val startLm = landmarkList[connection.start()]
                val endLm = landmarkList[connection.end()]

                val startVisible = startLm.visibility().orElse(1.0f) > 0.5f && startLm.presence().orElse(1.0f) > 0.5f
                val endVisible = endLm.visibility().orElse(1.0f) > 0.5f && endLm.presence().orElse(1.0f) > 0.5f

                if (startVisible && endVisible) {
                    val startX = startLm.x() * canvasWidth
                    val startY = startLm.y() * canvasHeight
                    val endX = endLm.x() * canvasWidth
                    val endY = endLm.y() * canvasHeight

                    canvas.drawLine(startX, startY, endX, endY, skeletonPaint)
                }
            }

            val keyJointIndices = listOf(15, 16, 13, 14, 27, 28, 25, 26)

            landmarkList.forEachIndexed { index, landmark ->
                val isVisible = landmark.visibility().orElse(1.0f) > 0.5f && landmark.presence().orElse(1.0f) > 0.5f
                if (isVisible) {
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
}
