package com.privacyguard.app.core.pcap

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcapWriter {

    @Volatile var isEnabled: Boolean = false
    @Volatile private var capturing: Boolean = false
    private var outputStream: BufferedOutputStream? = null
    private var currentFile: File? = null
    private var lastCompletedFile: File? = null

    fun startCapture(context: Context) {
        if (capturing) return
        val file = File(context.filesDir, "capture_${System.currentTimeMillis()}.pcap")
        val os = BufferedOutputStream(FileOutputStream(file))
        os.write(globalHeader())
        os.flush()
        outputStream = os
        currentFile = file
        capturing = true
    }

    fun stopCapture(): File? {
        if (!capturing) return lastCompletedFile
        capturing = false
        runCatching { outputStream?.flush(); outputStream?.close() }
        outputStream = null
        lastCompletedFile = currentFile
        currentFile = null
        return lastCompletedFile
    }

    fun isCapturing(): Boolean = capturing

    fun getLastCompletedFile(): File? = lastCompletedFile

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    fun getOutputFile(): File? = currentFile

    fun write(packet: ByteArray) {
        if (!capturing) return
        val os = outputStream ?: return
        runCatching { os.write(packetRecord(packet)) }
    }

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
