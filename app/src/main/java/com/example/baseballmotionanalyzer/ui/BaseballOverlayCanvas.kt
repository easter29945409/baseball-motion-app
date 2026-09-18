package com.example.baseballmotionanalyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * 即時畫布疊加層 (極簡低功耗版)
 * 無多餘特效與動畫，僅繪製必要的 33 點四肢關節線條與擊球軌跡，確保極致流暢度。
 */
@Composable
fun BaseballOverlayCanvas(
    poseResult: PoseLandmarkerResult?,
    trajectoryPoints: List<Offset>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. 繪製動態殘影軌跡 (Minimal Trajectory Line)
        if (trajectoryPoints.size > 1) {
            val trajectoryPath = Path().apply {
                moveTo(trajectoryPoints.first().x, trajectoryPoints.first().y)
                for (i in 1 until trajectoryPoints.size) {
                    lineTo(trajectoryPoints[i].x, trajectoryPoints[i].y)
                }
            }
            drawPath(
                path = trajectoryPath,
                color = Color(0xFF00E676), // 亮綠軌跡
                style = Stroke(width = 4f)
            )
        }

        // 2. 繪製 33 點骨架與四肢端點 (Minimal Skeleton Overlay)
        poseResult?.landmarks()?.firstOrNull()?.let { landmarkList ->
            val connections = PoseLandmarker.POSE_LANDMARKS

            fun getOffset(index: Int): Offset {
                val lm = landmarkList[index]
                return Offset(lm.x() * canvasWidth, lm.y() * canvasHeight)
            }

            // 繪製骨架連接線 (單色 2px 實線，零繪製負擔)
            connections.forEach { connection ->
                val start = getOffset(connection.start())
                val end = getOffset(connection.end())
                drawLine(
                    color = Color(0xFF00E5FF), // 亮青色實線
                    start = start,
                    end = end,
                    strokeWidth = 3f
                )
            }

            // 標示四肢關鍵節點 (手腕:15,16 / 手肘:13,14 / 腳踝:27,28 / 膝蓋:25,26)
            val keyJointIndices = listOf(15, 16, 13, 14, 27, 28, 25, 26)

            landmarkList.forEachIndexed { index, _ ->
                val point = getOffset(index)
                val isKeyJoint = index in keyJointIndices

                if (isKeyJoint) {
                    drawCircle(
                        color = Color(0xFFFFEA00), // 關節黃點
                        radius = 8f,
                        center = point
                    )
                } else {
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = point
                    )
                }
            }
        }
    }
}
