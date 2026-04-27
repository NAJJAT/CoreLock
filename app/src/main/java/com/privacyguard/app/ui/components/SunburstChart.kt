package com.privacyguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.privacyguard.app.ui.theme.PgPanel

data class SunburstSlice(val label: String, val value: Float, val color: Color)
data class SunburstRing(val slices: List<SunburstSlice>)

@Composable
fun SunburstChart(
    rings: List<SunburstRing>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy) * 0.94f
            val ringW = maxR / (rings.size.coerceAtLeast(1)).toFloat()

            rings.forEachIndexed { idx, ring ->
                val innerR = ringW * idx + ringW * 0.1f
                val outerR = innerR + ringW * 0.82f
                val total = ring.slices.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(0.001f)
                var startAngle = -90f

                ring.slices.forEach { slice ->
                    val sweep = (360f * slice.value / total).coerceAtLeast(0.5f)
                    val topLeft = Offset(cx - outerR, cy - outerR)
                    val arcSize = Size(outerR * 2f, outerR * 2f)
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep - 1.5f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = ringW * 0.75f),
                    )
                    startAngle += sweep
                }
            }

            // Dark center
            drawCircle(PgPanel, radius = ringW * 0.42f, center = Offset(cx, cy))
        }
    }
}
