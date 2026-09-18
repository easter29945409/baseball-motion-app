package com.example.baseballmotionanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.baseballmotionanalyzer.analytics.BaseballPhysicsEngine

/**
 * 高效簡潔版 棒球抬頭顯示面板 (HUD) (修復匯入檔)
 */
@Composable
fun MetricsHUD(
    metrics: BaseballPhysicsEngine.AnalysisMetrics,
    playerHeightCm: Float,
    onPlayerHeightChange: (Float) -> Unit,
    currentFps: Int = 60,
    modifier: Modifier = Modifier
) {
    var showHeightDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showHeightDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "📏 身高標定: ${playerHeightCm.toInt()} cm",
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Surface(
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "🟢 $currentFps FPS",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xEE0F172A), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricItem(
                title = "擊球初速 / 揮棒速",
                value = String.format("%.1f", metrics.speedKmh),
                unit = "km/h",
                color = Color(0xFF00E5FF)
            )

            MetricItem(
                title = "出球仰角",
                value = String.format("%.1f°", metrics.launchAngleDeg),
                unit = "Deg",
                color = Color(0xFFFFEA00)
            )

            MetricItem(
                title = "預估飛行距離",
                value = String.format("%.1f", metrics.estimatedDistanceMeters),
                unit = "Meters",
                color = Color(0xFF00E676)
            )
        }

        if (showHeightDialog) {
            HeightInputDialog(
                currentHeight = playerHeightCm,
                onDismiss = { showHeightDialog = false },
                onConfirm = { newHeight ->
                    onPlayerHeightChange(newHeight)
                    showHeightDialog = false
                }
            )
        }
    }
}

@Composable
private fun MetricItem(
    title: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, color = Color.Gray, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = unit,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

@Composable
private fun HeightInputDialog(
    currentHeight: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var textValue by remember { mutableStateOf(currentHeight.toInt().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定打者身高標定") },
        text = {
            Column {
                Text("輸入打者身高 (cm) 以完成像素對公尺精確標定：", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    label = { Text("身高 (cm)") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = textValue.toFloatOrNull() ?: 175f
                onConfirm(parsed)
            }) {
                Text("確定標定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
