package com.privacyguard.app.data.remote

import com.privacyguard.app.core.blocklist.BlocklistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class BlocklistDownloader {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000
        private const val MAX_RETRIES = 3
        // The largest supported list (OISD full) is ~10 MB; leave headroom, but bound it.
        private const val MAX_LIST_BYTES = 32L * 1024 * 1024
    }

    suspend fun download(source: BlocklistSource): String? = withContext(Dispatchers.IO) {
        downloadLines(source) { lines -> lines.joinToString("\n") }
    }

    /**
     * Streams the list line by line into [parse] (V-10): a 32 MB list used to be
     * held as one String, then split, then parsed (~240 MB of heap). [parse] runs
     * once per attempt; a failure mid-way (network, size cap) discards its result.
     * The source's mirror (the same file on another host) is tried only after every
     * attempt at the main URL failed.
     */
    suspend fun <T> downloadLines(source: BlocklistSource, parse: (Sequence<String>) -> T): T? = withContext(Dispatchers.IO) {
        for (url in listOfNotNull(source.downloadUrl(), source.mirrorUrl())) {
            downloadFrom(url, parse)?.let { return@withContext it }
        }
        null
    }

    private suspend fun <T> downloadFrom(url: String, parse: (Sequence<String>) -> T): T? {
        if (!url.startsWith("https://", ignoreCase = true)) return null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val connection = URL(url).openConnection() as HttpsURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
                connection.setRequestProperty("Accept", "text/plain, */*")
                if (connection.responseCode in 200..299) {
                    val finalUrl = connection.url.toString()
                    if (!finalUrl.startsWith("https://", ignoreCase = true)) {
                        connection.disconnect()
                        return null
                    }
                    if (connection.contentLengthLong > MAX_LIST_BYTES) {
                        connection.disconnect()
                        return null
                    }
                    return com.privacyguard.app.core.utils.CappedInputStream(connection.inputStream, MAX_LIST_BYTES)
                        .bufferedReader(Charsets.UTF_8).useLines(parse)
                }
                connection.disconnect()
            } catch (_: com.privacyguard.app.core.utils.ResponseTooLargeException) {
                return null   // retrying will not make it smaller
            } catch (_: Exception) {
            }
            delay(2000L * (attempt + 1))
        }

        return null
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
