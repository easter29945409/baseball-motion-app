package com.example.baseballmotionanalyzer.model

import java.io.Serializable

/**
 * 打者個人資料 Model
 */
data class PlayerProfile(
    val id: Long = 0,
    val jerseyNumber: String,
    val name: String,
    val heightCm: Float = 170f,
    val isRightHanded: Boolean = true
) : Serializable {
    fun getDisplayName(): String {
        val stanceText = if (isRightHanded) "右打" else "左打"
        return "#$jerseyNumber $name (${heightCm.toInt()}cm / $stanceText)"
    }

    companion object {
        fun createGuestProfile(): PlayerProfile {
            return PlayerProfile(
                jerseyNumber = "999",
                name = "訪客",
                heightCm = 170f,
                isRightHanded = true
            )
        }
    }
}
