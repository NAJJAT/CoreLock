package com.privacyguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.privacyguard.app.ui.statistics.CountryStat
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgPanel
import com.privacyguard.app.ui.theme.PgWarning

// Approximate (x,y) fractions on a rectangular world map [0,1]×[0,1]
private val COUNTRY_COORDS: Map<String, Pair<Float, Float>> = mapOf(
    "US" to (0.22f to 0.38f),   "CA" to (0.19f to 0.27f),   "MX" to (0.21f to 0.47f),
    "BR" to (0.30f to 0.62f),   "AR" to (0.28f to 0.72f),   "CO" to (0.25f to 0.55f),
    "GB" to (0.47f to 0.26f),   "DE" to (0.51f to 0.27f),   "FR" to (0.49f to 0.30f),
    "NL" to (0.50f to 0.26f),   "SE" to (0.52f to 0.21f),   "NO" to (0.51f to 0.19f),
    "FI" to (0.54f to 0.20f),   "PL" to (0.53f to 0.27f),   "CH" to (0.51f to 0.29f),
    "ES" to (0.48f to 0.33f),   "IT" to (0.52f to 0.32f),   "UA" to (0.56f to 0.27f),
    "RU" to (0.65f to 0.21f),   "TR" to (0.58f to 0.34f),   "SA" to (0.60f to 0.43f),
    "AE" to (0.63f to 0.44f),   "EG" to (0.57f to 0.40f),   "ZA" to (0.55f to 0.70f),
    "NG" to (0.50f to 0.53f),   "KE" to (0.59f to 0.57f),   "IN" to (0.68f to 0.44f),
    "CN" to (0.76f to 0.34f),   "JP" to (0.83f to 0.32f),   "KR" to (0.81f to 0.33f),
    "SG" to (0.78f to 0.53f),   "MY" to (0.77f to 0.51f),   "ID" to (0.79f to 0.56f),
    "HK" to (0.79f to 0.38f),   "TW" to (0.81f to 0.39f),   "AU" to (0.82f to 0.66f),
    "NZ" to (0.88f to 0.73f),   "PK" to (0.67f to 0.38f),   "IR" to (0.63f to 0.37f),
    "IL" to (0.58f to 0.37f),   "PH" to (0.82f to 0.47f),   "TH" to (0.77f to 0.47f),
)

@Composable
fun NetworkMapCanvas(
    countries: List<CountryStat>,
    modifier: Modifier = Modifier,
) {
    val maxCount = countries.maxOfOrNull { it.connectionCount }?.toFloat().takeIf { it != null && it > 0 } ?: 1f

    Box(
        modifier = modifier
            .background(PgPanel, RoundedCornerShape(16.dp))
            .padding(2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawGrid()

            // Device at center of screen
            val devicePos = Offset(size.width * 0.50f, size.height * 0.46f)

            countries.forEach { stat ->
                val (rx, ry) = COUNTRY_COORDS[stat.countryCode] ?: return@forEach
                val dest = Offset(size.width * rx, size.height * ry)
                val intensity = (stat.connectionCount.toFloat() / maxCount).coerceIn(0.15f, 1f)
                val arcColor = arcColor(intensity)
                drawCurvedArc(devicePos, dest, arcColor, intensity)
                drawCircle(arcColor, 4f + intensity * 4f, dest)
                drawCircle(arcColor.copy(alpha = 0.25f), 10f + intensity * 5f, dest)
            }

            // Device indicator
            drawCircle(PgAccent, 7f, devicePos)
            drawCircle(PgAccent.copy(alpha = 0.25f), 15f, devicePos)
            drawCircle(Color.White, 3f, devicePos)
        }
    }
}

private fun arcColor(intensity: Float): Color = when {
    intensity > 0.65f -> PgDanger.copy(alpha = 0.75f + intensity * 0.25f)
    intensity > 0.35f -> PgWarning.copy(alpha = 0.65f + intensity * 0.25f)
    else             -> PgAccent.copy(alpha = 0.50f + intensity * 0.4f)
}

private fun DrawScope.drawGrid() {
    val grid = Color(0x12FFFFFF)
    repeat(4) { i -> drawLine(grid, Offset(0f, size.height * (i + 1) / 5f), Offset(size.width, size.height * (i + 1) / 5f)) }
    repeat(6) { i -> drawLine(grid, Offset(size.width * (i + 1) / 7f, 0f), Offset(size.width * (i + 1) / 7f, size.height)) }
    COUNTRY_COORDS.values.forEach { (rx, ry) ->
        drawCircle(Color(0x1AFFFFFF), 2.5f, Offset(size.width * rx, size.height * ry))
    }
}

private fun DrawScope.drawCurvedArc(from: Offset, to: Offset, color: Color, intensity: Float) {
    val controlX = (from.x + to.x) / 2f
    val controlY = (from.y + to.y) / 2f - size.height * 0.12f * intensity
    val path = Path().apply {
        moveTo(from.x, from.y)
        quadraticBezierTo(controlX, controlY, to.x, to.y)
    }
    drawPath(path, color, style = Stroke(width = 1.2f + intensity * 2.2f, cap = StrokeCap.Round))
}
