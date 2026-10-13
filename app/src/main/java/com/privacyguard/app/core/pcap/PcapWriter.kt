package com.privacyguard.app.core.pcap

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw packet capture for Wireshark, both directions: what apps send (TunReader) and
 * what the tunnel delivers to them (TunWriter, including answers PrivacyGuard makes
 * itself, such as DNS).
 *
 * The file is plain PCAP so other tools can read it. It stays in the app's private
 * storage, stops at [MAX_BYTES] or when protection stops, and PruneWorker deletes it
 * after a day.
 */
object PcapWriter {

    const val MAX_BYTES = 100L * 1024 * 1024
    private const val DIR = "exports/pcap"

    // Lowered by tests.
    internal var maxBytes = MAX_BYTES
    private var bytesWritten = 0L
    /** True when the last capture ended because it reached [MAX_BYTES]. */
    @Volatile var stoppedAtLimit = false
        private set

    @Volatile var isEnabled: Boolean = false
    @Volatile private var capturing: Boolean = false
    private var outputStream: BufferedOutputStream? = null
    private var currentFile: File? = null
    private var lastCompletedFile: File? = null

    // write() runs on the TUN reader thread, start/stop on the UI: without the lock a
    // stop could close the stream mid-record and leave a truncated, unreadable file.
    @Synchronized
    fun startCapture(context: Context) {
        if (capturing) return
        // files/exports/ is the only app-private folder the FileProvider shares.
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        startCaptureTo(File(dir, "capture_${System.currentTimeMillis()}.pcap"))
    }

    @Synchronized
    internal fun startCaptureTo(file: File) {
        if (capturing) return
        val os = BufferedOutputStream(FileOutputStream(file))
        os.write(globalHeader())
        os.flush()
        bytesWritten = 24
        stoppedAtLimit = false
        outputStream = os
        currentFile = file
        capturing = true
    }

    @Synchronized
    fun stopCapture(): File? {
        if (!capturing) return lastCompletedFile
        capturing = false
        runCatching { outputStream?.flush(); outputStream?.close() }
        outputStream = null
        lastCompletedFile = currentFile
        currentFile = null
        return lastCompletedFile
    }

    /** After "Clear data": the file is gone, so there is nothing left to share. */
    @Synchronized
    fun forgetLastCapture() { lastCompletedFile = null }

    fun isCapturing(): Boolean = capturing

    fun getLastCompletedFile(): File? = lastCompletedFile

    /**
     * The newest finished capture, also after the app restarted (the in-memory
     * reference is gone then, the file is not).
     */
    @Synchronized
    fun lastCapture(context: Context): File? =
        lastCompletedFile?.takeIf { it.exists() }
            ?: File(context.filesDir, DIR).listFiles { f -> f.isFile && f.name.endsWith(".pcap") && f != currentFile }
                ?.maxByOrNull { it.lastModified() }

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    @Synchronized
    fun getOutputFile(): File? = currentFile

    @Synchronized
    fun write(packet: ByteArray) {
        if (!capturing) return
        val os = outputStream ?: return
        val record = packetRecord(packet)
        if (bytesWritten + record.size > maxBytes) {
            stopCapture()
            stoppedAtLimit = true
            return
        }
        runCatching { os.write(record) }.onSuccess { bytesWritten += record.size }
    }

    @Synchronized
    fun flush() {
        runCatching { outputStream?.flush() }
    }

    fun close() {
        stopCapture()
    }

    private fun globalHeader(): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xa1b2c3d4.toInt())
        buf.putShort(2)
        buf.putShort(4)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(65535)
        buf.putInt(101)  // LINKTYPE_RAW
        return buf.array()
    }

    private fun packetRecord(packet: ByteArray): ByteArray {
        val now = System.currentTimeMillis()
        val tsSec  = (now / 1000).toInt()
        val tsUsec = ((now % 1000) * 1000).toInt()
        val len    = packet.size
        val buf    = ByteBuffer.allocate(16 + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(tsSec)
        buf.putInt(tsUsec)
        buf.putInt(len)
        buf.putInt(len)
        buf.put(packet)
        return buf.array()
    }
}
