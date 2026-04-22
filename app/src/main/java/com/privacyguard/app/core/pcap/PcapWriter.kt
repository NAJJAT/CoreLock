package com.privacyguard.app.core.pcap

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object PcapWriter {

    private const val MAX_FILE_SIZE_BYTES = 100L * 1024L * 1024L
    private const val ROTATION_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val packetQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isCapturingFlag = AtomicBoolean(false)

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var lastCompletedFile: File? = null

    @Volatile
    private var outputStream: FileOutputStream? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var currentFileSize = 0L

    @Volatile
    private var captureStartTime = 0L

    @Volatile
    private var currentFileStartTime = 0L

    fun startCapture(context: Context, filename: String? = null): Boolean {
        if (isCapturingFlag.get()) return false

        return synchronized(this) {
            if (isCapturingFlag.get()) return@synchronized false
            try {
                val captureDir = getCaptureDirectory(context)
                if (!captureDir.exists()) {
                    captureDir.mkdirs()
                }

                val file = File(captureDir, filename ?: generateFileName())
                outputStream = FileOutputStream(file).also { stream ->
                    stream.write(PcapGlobalHeader.toByteArray())
                    stream.flush()
                }

                currentFile = file
                currentFileSize = 24L
                captureStartTime = System.currentTimeMillis()
                currentFileStartTime = captureStartTime
                packetQueue.clear()
                isCapturingFlag.set(true)

                writerThread = thread(name = "PrivacyGuard-PcapWriter", isDaemon = true) {
                    writeLoop()
                }
                true
            } catch (_: Exception) {
                cleanupInternal()
                false
            }
        }
    }

    fun stopCapture(): File? {
        synchronized(this) {
            val fileAtStop = currentFile
            isCapturingFlag.set(false)
            writerThread?.interrupt()
            try {
                writerThread?.join(500)
            } catch (_: InterruptedException) {
            }
            flushQueue()
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Exception) {
            }
            if (fileAtStop != null) {
                lastCompletedFile = fileAtStop
            }
            cleanupInternal(keepLastCompleted = true)
            return fileAtStop
        }
    }

    fun writePacket(packet: ByteArray, timestampMillis: Long = System.currentTimeMillis()) {
        if (!isCapturingFlag.get()) return
        checkAndRotateFile()

        val headerBytes = PcapPacketHeader.fromPacket(packet, timestampMillis).toByteArray()
        val entry = ByteArray(headerBytes.size + packet.size)
        System.arraycopy(headerBytes, 0, entry, 0, headerBytes.size)
        System.arraycopy(packet, 0, entry, headerBytes.size, packet.size)

        packetQueue.add(entry)
        currentFileSize += entry.size
    }

    fun isCapturing(): Boolean = isCapturingFlag.get()

    fun getCurrentFilePath(): String? = currentFile?.absolutePath

    fun getLastCompletedFile(): File? = lastCompletedFile

    fun getCurrentFileSize(): Long = currentFileSize

    fun getCaptureDurationSeconds(): Long {
        if (captureStartTime == 0L) return 0L
        return (System.currentTimeMillis() - captureStartTime) / 1000L
    }

    private fun writeLoop() {
        while (isCapturingFlag.get() || packetQueue.isNotEmpty()) {
            try {
                val packet = packetQueue.poll()
                if (packet == null) {
                    Thread.sleep(20)
                } else {
                    outputStream?.write(packet)
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
        }

        try {
            outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    private fun flushQueue() {
        while (true) {
            val packet = packetQueue.poll() ?: break
            try {
                outputStream?.write(packet)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun checkAndRotateFile() {
        val now = System.currentTimeMillis()
        val shouldRotate = currentFileSize >= MAX_FILE_SIZE_BYTES ||
            (currentFileStartTime != 0L && now - currentFileStartTime >= ROTATION_INTERVAL_MS)
        if (!shouldRotate) return

        synchronized(this) {
            if (!isCapturingFlag.get()) return
            flushQueue()
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Exception) {
            }
            currentFile?.let { lastCompletedFile = it }

            val parent = currentFile?.parentFile ?: return
            val newFile = File(parent, generateFileName())
            outputStream = FileOutputStream(newFile).also { stream ->
                stream.write(PcapGlobalHeader.toByteArray())
                stream.flush()
            }
            currentFile = newFile
            currentFileSize = 24L
            currentFileStartTime = now
        }
    }

    private fun getCaptureDirectory(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(root, "PrivacyGuard")
    }

    private fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "privacyguard_$timestamp.pcap"
    }

    private fun cleanupInternal(keepLastCompleted: Boolean = false) {
        outputStream = null
        writerThread = null
        currentFile = null
        currentFileSize = 0L
        captureStartTime = 0L
        currentFileStartTime = 0L
        packetQueue.clear()
        if (!keepLastCompleted) {
            lastCompletedFile = null
        }
    }
}
