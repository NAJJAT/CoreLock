package com.privacyguard.app.core.pcap

import android.content.Context
import java.io.File

/**
 * Stub PCAP writer.
 */
object PcapWriter {

    @Volatile var isEnabled: Boolean = false
    @Volatile private var capturing: Boolean = false
    private var currentFile: File? = null
    private var lastCompletedFile: File? = null

    fun startCapture(context: Context) {
        if (capturing) return
        capturing = true
        // Stub — real impl would open a pcap file in context.filesDir
    }

    fun stopCapture(): File? {
        if (!capturing) return lastCompletedFile
        capturing = false
        lastCompletedFile = currentFile
        currentFile = null
        return lastCompletedFile
    }

    fun isCapturing(): Boolean = capturing

    fun getLastCompletedFile(): File? = lastCompletedFile

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    fun getOutputFile(): File? = currentFile

    fun write(packet: ByteArray) {
        // Stub — no-op
    }

    fun flush() { /* no-op */ }

    fun close() { /* no-op */ }
}
