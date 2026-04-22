package com.privacyguard.app.vpn.tunnel

import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class TunWriter(
    private var tunFileDescriptor: FileDescriptor? = null
) {
    companion object {
        private const val TAG = "TunWriter"
    }

    private val totalPacketsWritten = AtomicLong(0)
    private val totalBytesWritten = AtomicLong(0)

    fun setFileDescriptor(fileDescriptor: FileDescriptor) {
        tunFileDescriptor = fileDescriptor
    }

    fun write(data: ByteArray): Boolean {
        val fileDescriptor = tunFileDescriptor ?: return false
        return try {
            Os.write(fileDescriptor, data, 0, data.size)
            totalPacketsWritten.incrementAndGet()
            totalBytesWritten.addAndGet(data.size.toLong())
            true
        } catch (_: Exception) {
            try {
                FileOutputStream(fileDescriptor).use { output ->
                    output.write(data)
                    output.flush()
                }
                totalPacketsWritten.incrementAndGet()
                totalBytesWritten.addAndGet(data.size.toLong())
                true
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write packet to TUN", e)
                false
            }
        }
    }

    fun getTotalPacketsWritten(): Long = totalPacketsWritten.get()

    fun getTotalBytesWritten(): Long = totalBytesWritten.get()
}
