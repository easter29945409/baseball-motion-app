package com.example.baseballmotionanalyzer.analytics

import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 棒球物理與運動學計算引擎 (極簡高效版)
 * 專為 Samsung S24 優化，無多餘計算開銷，提供打擊初速、仰角與飛行距離預估。
 */
object BaseballPhysicsEngine {

    private const val GRAVITY = 9.81f

    data class AnalysisMetrics(
        val speedKmh: Float = 0f,               // 擊球初速/揮棒速度 (km/h)
        val launchAngleDeg: Float = 0f,         // 出球仰角 (度)
        val estimatedDistanceMeters: Float = 0f, // 預估飛行距離 (公尺)
        val angularVelocityDegSec: Float = 0f,  // 軀幹轉體角速度 (deg/s)
        val isCorrectDirection: Boolean = true,  // 揮棒方向性是否正確 (符合右打 +X / 左打 -X)
        val playerHeightCm: Float = 175f,        // 打者身高標定 (cm)
        val isCalibrated: Boolean = false       // 是否已根據身高完成比例標定
    )

    /**
     * 根據打者身高 (cm) 與畫面上打者頭頂至腳踝的像素高度，精確導出比例尺
     * @param playerHeightCm 打者真實身高 (例: 175 cm)
     * @param personPixelHeight 畫面上打者頭部至腳踝的像素距離
     * @return 比例尺 (meters per pixel)
     */
    fun calculateScaleFromPlayerHeight(playerHeightCm: Float, personPixelHeight: Float): Float {
        if (personPixelHeight <= 10f) return 0.0015f // 預設安全比例尺
        val heightMeters = playerHeightCm / 100f
        return heightMeters / personPixelHeight
    }

    /**
     * 計算兩幀之間雙肩連線斜率角度變化，得到軀幹旋轉角速度 (deg/s)
     */
    fun calculateTrunkAngularVelocity(
        prevLsX: Float, prevLsY: Float, prevRsX: Float, prevRsY: Float,
        curLsX: Float, curLsY: Float, curRsX: Float, curRsY: Float,
        fps: Float = 60f
    ): Float {
        val prevAngle = Math.toDegrees(atan2((prevRsY - prevLsY).toDouble(), (prevRsX - prevLsX).toDouble())).toFloat()
        val curAngle = Math.toDegrees(atan2((curRsY - curLsY).toDouble(), (curRsX - curLsX).toDouble())).toFloat()
        val diffAngle = kotlin.math.abs(curAngle - prevAngle)
        val dtSec = 1.0f / fps
        return diffAngle / dtSec
    }

    /**
     * 根據 60FPS 兩幀間的座標位移與運動學特徵計算物理數據
     */
    fun calculateMetrics(
        p1X: Float, p1Y: Float,
        p2X: Float, p2Y: Float,
        pixelToMeterScale: Float,
        playerHeightCm: Float,
        isRightHanded: Boolean = true,
        trunkAngularVelocity: Float = 0f,
        fps: Float = 60f
    ): AnalysisMetrics {
        val dxPixels = p2X - p1X
        val dyPixels = p1Y - p2Y // 螢幕 Y 軸向下轉換

        // 方向性檢查：右打需正向向右出棒 (dx > 0)，左打需正向向左出棒 (dx < 0)
        val isCorrectDirection = if (isRightHanded) (dxPixels > 0f) else (dxPixels < 0f)

        val distancePixels = sqrt(dxPixels.pow(2) + dyPixels.pow(2))
        val distanceMeters = distancePixels * pixelToMeterScale

        // 每幀時間間隔 (秒) (60FPS -> 0.01667s)
        val dtSec = 1.0f / fps

        // 速度 (m/s) -> 轉成 km/h
        val speedMs = distanceMeters / dtSec
        val speedKmh = speedMs * 3.6f

        // 仰角角度 (-180 ~ +180 度)
        val angleRad = atan2(dyPixels, dxPixels)
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // 拋物線預估飛行距離
        val estimatedDistance = if (angleDeg > 0 && angleDeg < 75 && isCorrectDirection) {
            val rad = Math.toRadians((angleDeg * 2).toDouble())
            ((speedMs.toDouble().pow(2) * sin(rad)) / GRAVITY).toFloat()
        } else {
            0f
        }

        return AnalysisMetrics(
            speedKmh = speedKmh,
            launchAngleDeg = angleDeg,
            estimatedDistanceMeters = estimatedDistance,
            angularVelocityDegSec = trunkAngularVelocity,
            isCorrectDirection = isCorrectDirection,
            playerHeightCm = playerHeightCm,
            isCalibrated = true
        )
    }
}
