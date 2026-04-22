package com.privacyguard.app.data.remote

import com.privacyguard.app.core.blocklist.BlocklistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class BlocklistDownloader {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_RETRIES = 3
    }

    suspend fun download(source: BlocklistSource): String? = withContext(Dispatchers.IO) {
        val url = source.downloadUrl() ?: return@withContext null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
                if (connection.responseCode in 200..299) {
                    return@withContext connection.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (_: Exception) {
            }
            delay(2000L * (attempt + 1))
        }

        null
    }

    suspend fun downloadAll(): Map<BlocklistSource, String?> {
        return buildMap {
            BlocklistSource.values().forEach { source ->
                if (source.downloadUrl() != null) {
                    put(source, download(source))
                }
            }
        }
    }
}
