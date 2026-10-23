package com.privacyguard.platform.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for V-03 (security test 2026-10-14): below Android 13 a receiver
 * registered with a plain Context.registerReceiver is exported, so any app could
 * send it STOP_VPN. Every dynamic receiver must go through ContextCompat with an
 * explicit RECEIVER_NOT_EXPORTED / RECEIVER_EXPORTED flag.
 */
class ReceiverExportTest {

    private val roots = listOf("src/main/java", "src/enterprise/java", "src/debug/java")

    @Test
    fun every_dynamic_receiver_has_an_explicit_export_flag() {
        val files = roots.map(::File).filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(files.size > 50)

        val offenders = files.flatMap { file ->
            val text = file.readText()
            // A file may declare its own helper called registerReceiver(context).
            val ownHelper = Regex("fun registerReceiver\\(").containsMatchIn(text)
            Regex("(?<![A-Za-z.])(?:ContextCompat\\.)?registerReceiver\\(").findAll(text).mapNotNull { m ->
                val before = text.substring(maxOf(0, m.range.first - 4), m.range.first)
                if (before.endsWith("fun ")) return@mapNotNull null
                val call = callText(text, m.range.last)
                val viaCompat = m.value.startsWith("ContextCompat.")
                if (!viaCompat && ownHelper) return@mapNotNull null
                val flagged = "RECEIVER_NOT_EXPORTED" in call || "RECEIVER_EXPORTED" in call
                if (viaCompat && flagged) null
                else "${file.path}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
            }.toList()
        }
        assertTrue("registerReceiver without ContextCompat + explicit export flag:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** The argument list of the call whose "(" is at [open], up to its matching ")". */
    private fun callText(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }
}
