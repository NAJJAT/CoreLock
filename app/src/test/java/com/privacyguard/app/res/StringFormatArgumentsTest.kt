package com.privacyguard.app.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translated string must take the same format arguments as English, and every
 * "%" must start a valid java.util.Formatter specifier. A broken one compiles, passes
 * lint when it no longer looks like a format string ("Live (%1)"), and crashes the
 * screen at runtime with UnknownFormatConversionException.
 */
class StringFormatArgumentsTest {

    /** Source sets whose res/ folders ship strings. Unit tests run in the app module dir. */
    private val resDirs = listOf("src/main/res", "src/enterprise/res", "src/consumer/res")
        .map { File(it) }.filter { it.isDirectory }

    private data class Entry(val locale: String, val file: String, val key: String, val text: String, val pluralItem: String?)

    @Test
    fun every_percent_sign_is_a_valid_format_specifier() {
        val problems = entries().mapNotNull { e ->
            runCatching { formatArgs(e.text) }.exceptionOrNull()?.let { "${e.locale}/${e.file}: ${e.key}${e.pluralItem?.let { "[$it]" } ?: ""} = \"${e.text}\" (${it.message})" }
        }
        if (problems.isNotEmpty()) fail("Invalid format strings:\n" + problems.joinToString("\n"))
    }

    @Test
    fun translations_take_the_same_arguments_as_english() {
        val all = entries()
        val english = all.filter { it.locale == "values" }
        val englishStrings = english.filter { it.pluralItem == null }.associate { it.key to formatArgsOrEmpty(it.text) }
        // For plurals, compare with the English "other" form; a translation may leave the
        // number out of a form ("one item"), but must never add or change an argument.
        val englishPlurals = english.filter { it.pluralItem == "other" }.associate { it.key to formatArgsOrEmpty(it.text) }

        val problems = all.filter { it.locale != "values" }.mapNotNull { e ->
            val args = formatArgsOrEmpty(e.text)
            if (e.pluralItem == null) {
                val expected = englishStrings[e.key] ?: return@mapNotNull null
                if (args != expected) "${e.locale}: ${e.key} has $args, English has $expected" else null
            } else {
                val expected = englishPlurals[e.key] ?: return@mapNotNull null
                if (!expected.containsAll(args)) "${e.locale}: ${e.key}[${e.pluralItem}] has $args, English has $expected" else null
            }
        }
        if (problems.isNotEmpty()) fail("Format arguments differ from English:\n" + problems.joinToString("\n"))
    }

    /** The bug that crashed the Traffic tab on 13 Oct 2026: "$d" lost from "%1$d". */
    @Test
    fun the_traffic_tab_crash_would_have_been_caught() {
        assertTrue(runCatching { formatArgs("Live (%1)") }.isFailure)
        assertTrue(runCatching { formatArgs("Look up %1 in DNS") }.isFailure)
        assertEquals(setOf("1:d"), formatArgs("Live (%1\$d)"))
        assertEquals(setOf("1:s", "2:s"), formatArgs("%1\$s sent · %2\$s received"))
        assertEquals(emptySet<String>(), formatArgs("100%% sure"))
    }

    @Test
    fun the_resource_folders_were_found() {
        assertTrue("no res folders found from ${File(".").absolutePath}", resDirs.isNotEmpty())
        assertTrue(entries().any { it.locale == "values-ar" })
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun entries(): List<Entry> = resDirs.flatMap { res ->
        res.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }.orEmpty()
            .flatMap { dir ->
                dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
                    parse(dir.name, file)
                }
            }
    }

    private fun parse(locale: String, file: File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Entry>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val el = strings.item(i) as Element
            if (el.getAttribute("formatted") == "false") continue
            out += Entry(locale, file.name, el.getAttribute("name"), el.textContent, null)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                out += Entry(locale, file.name, p.getAttribute("name"), item.textContent, item.getAttribute("quantity"))
            }
        }
        return out
    }

    private fun formatArgsOrEmpty(text: String): Set<String> = runCatching { formatArgs(text) }.getOrDefault(emptySet())

    companion object {
        // java.util.Formatter: %[index$][flags][width][.precision]conversion
        private val SPEC = Regex("""%(\d+\$)?([-#+ 0,(<]*)(\d+)?(\.\d+)?([tT]?[a-zA-Z%])""")
        private const val CONVERSIONS = "bBhHsScCdoxXeEfgGaAtT%n"

        /**
         * Arguments a string formats, as "index:conversion" (unnumbered ones numbered in
         * order). Throws for any "%" that java.util.Formatter would reject.
         */
        fun formatArgs(text: String): Set<String> {
            val args = mutableSetOf<String>()
            var next = 1
            var i = 0
            while (i < text.length) {
                if (text[i] != '%') { i++; continue }
                val m = SPEC.matchAt(text, i) ?: throw IllegalArgumentException("bad specifier at ${text.substring(i).take(6)}")
                val conversion = m.groupValues[5]
                if (conversion.last() !in CONVERSIONS) throw IllegalArgumentException("unknown conversion '$conversion'")
                if (conversion != "%" && conversion != "n") {
                    val index = m.groupValues[1].removeSuffix("$").toIntOrNull() ?: next++
                    args += "$index:${conversion.last().lowercaseChar()}"
                }
                i = m.range.last + 1
            }
            return args
        }
    }
}
