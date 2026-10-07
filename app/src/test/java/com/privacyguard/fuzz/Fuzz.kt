package com.privacyguard.fuzz

import kotlin.random.Random

/**
 * Small deterministic mutation fuzzer for parsers that read untrusted bytes.
 *
 * Each iteration takes a valid seed (or pure noise), applies a few random
 * mutations and calls the target. Any exception fails the test with the seed,
 * iteration and input hex, so a failure can be turned into a regression test.
 * A fixed RNG seed keeps CI runs reproducible.
 */
object Fuzz {

    private val INTERESTING_BYTES = intArrayOf(0x00, 0x01, 0x7F, 0x80, 0xFF, 0xC0, 0x3F)
    private val INTERESTING_SHORTS = intArrayOf(0x0000, 0x0001, 0x7FFF, 0x8000, 0xFFFF, 0xC00C)

    fun run(
        name: String,
        seeds: List<ByteArray>,
        iterations: Int = 20_000,
        rngSeed: Long = 0x5EED,
        target: (ByteArray) -> Unit,
    ) {
        val rng = Random(rngSeed)
        for (i in 0 until iterations) {
            val input = if (seeds.isEmpty() || rng.nextInt(10) == 0) {
                rng.nextBytes(rng.nextInt(0, 2048))
            } else {
                mutate(seeds[rng.nextInt(seeds.size)], rng)
            }
            try {
                target(input)
            } catch (t: Throwable) {
                throw AssertionError(
                    "$name threw ${t::class.java.simpleName} at iteration $i (rngSeed=$rngSeed) " +
                        "for ${input.size}B input: ${input.toHex()}",
                    t,
                )
            }
        }
    }

    private fun mutate(seed: ByteArray, rng: Random): ByteArray {
        var b = seed.copyOf()
        repeat(rng.nextInt(1, 5)) {
            b = when (rng.nextInt(8)) {
                0 -> b.also { if (it.isNotEmpty()) { val p = rng.nextInt(it.size); it[p] = (it[p].toInt() xor (1 shl rng.nextInt(8))).toByte() } }
                1 -> b.also { if (it.isNotEmpty()) it[rng.nextInt(it.size)] = INTERESTING_BYTES.random(rng).toByte() }
                2 -> b.also { if (it.size >= 2) { val p = rng.nextInt(it.size - 1); val v = INTERESTING_SHORTS.random(rng); it[p] = (v ushr 8).toByte(); it[p + 1] = v.toByte() } }
                3 -> if (b.isEmpty()) b else b.copyOf(rng.nextInt(b.size))                    // truncate
                4 -> b + rng.nextBytes(rng.nextInt(1, 64))                                       // extend
                5 -> if (b.isEmpty()) b else { val p = rng.nextInt(b.size); b.copyOfRange(0, p) + rng.nextBytes(rng.nextInt(1, 16)) + b.copyOfRange(p, b.size) }
                6 -> if (b.size < 2) b else { val p = rng.nextInt(b.size); val n = rng.nextInt(1, minOf(32, b.size - p) + 1); b.copyOfRange(0, p) + b.copyOfRange(p + n, b.size) }
                else -> b.also { if (it.isNotEmpty()) it[rng.nextInt(it.size)] = rng.nextInt(256).toByte() }
            }
        }
        return b
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
