package com.privacyguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgPanel
import com.privacyguard.app.ui.theme.PgTextMuted
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * 7-day × 24-hour activity heatmap.
 * [grid] is Array<IntArray> of size 7×24, indexed [dayOfWeek(Mon=0)][hour].
 */
@Composable
fun TemporalHeatmap(
    grid: Array<IntArray>,
    modifier: Modifier = Modifier,
) {
    val maxVal = grid.flatMap { it.toList() }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val locale = LocalConfiguration.current.locales[0]
    // Row index 0 is Monday; names come from the locale ("Mon", "ma", "lun.", "الاثنين").
    val dayLabels = remember(locale) {
        // java.time needs API 26 and minSdk is 24, so use DateFormatSymbols (Sunday = 1).
        val names = DateFormatSymbols.getInstance(locale).shortWeekdays
        listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY).map { names[it] }
    }

    Column(modifier = modifier) {
        // Hour axis labels (0, 6, 12, 18, 23)
        Row(modifier = Modifier.padding(start = 34.dp, end = 2.dp)) {
            val labelHours = listOf(0, 6, 12, 18, 23)
            var lastFrac = 0f
            labelHours.forEach { h ->
                val frac = h / 23f
                Spacer(modifier = Modifier.weight((frac - lastFrac).coerceAtLeast(0.001f)))
                Text(
                    text = "%02d".format(h),
                    fontSize = 8.sp,
                    color = PgTextMuted,
                )
                lastFrac = frac
            }
            Spacer(modifier = Modifier.weight((1f - lastFrac).coerceAtLeast(0.001f)))
        }

        Spacer(modifier = Modifier.height(3.dp))

        grid.forEachIndexed { dayIdx, hours ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(15.dp),
            ) {
                Text(
                    text = dayLabels.getOrElse(dayIdx) { "" },
                    maxLines = 1,
                    fontSize = 8.sp,
                    color = PgTextMuted,
                    modifier = Modifier.width(30.dp),
                )
                Canvas(modifier = Modifier.weight(1f).height(12.dp)) {
                    val cellW = size.width / 24f
                    val cellH = size.height
                    // Canvas is not mirrored by Compose, but the hour labels above are;
                    // flip the columns in RTL so hour 0 sits under the "00" label.
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    hours.forEachIndexed { hour, count ->
                        val col = if (rtl) 23 - hour else hour
                        val intensity = count.toFloat() / maxVal.toFloat()
                        val color = lerp(PgPanel, PgAccent, intensity.coerceIn(0f, 1f))
                        drawRect(
                            color = color,
                            topLeft = Offset(col * cellW + 0.8f, 0f),
                            size = Size((cellW - 1.6f).coerceAtLeast(0f), cellH),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

