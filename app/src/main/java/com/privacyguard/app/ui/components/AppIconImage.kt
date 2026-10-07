package com.privacyguard.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.privacyguard.app.core.apps.InstalledAppsCache
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgPanelMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIconImage(
    packageName: String,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // A cached icon shows immediately, so scrolling back never flashes the placeholder.
    val icon = produceState(
        initialValue = AppIconCache.get(packageName, sizePx),
        key1 = packageName,
        key2 = sizePx,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName, sizePx) }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(PgPanelMuted),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon.value
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                tint = PgInfo,
                modifier = Modifier.size((size.value * 0.55f).dp),
            )
        }
    }
}

/**
 * Shared icon cache. Icons are drawn at the size they are shown (e.g. 40 dp),
 * not decoded at full resolution, and kept in an LRU bounded by bytes.
 */
private object AppIconCache {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 32).toInt().coerceIn(4 shl 20, 24 shl 20)
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // The package-list version in the key drops icons of apps that were updated.
    private fun key(packageName: String, sizePx: Int) =
        "$packageName:$sizePx:${InstalledAppsCache.version.value}"

    fun get(packageName: String, sizePx: Int): ImageBitmap? = cache.get(key(packageName, sizePx))

    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx).asImageBitmap()
    }.getOrNull()?.also { cache.put(key(packageName, sizePx), it) }
}

private fun Drawable.toBitmap(sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    return bmp
}
