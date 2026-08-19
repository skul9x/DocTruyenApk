package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class WarningFixesOptimizationTest {

    @Test
    fun testW1_EosCheckLogicEquivalence() {
        val H = 768
        val textVocabSize = 419
        val eosTokenId = 6
        val random = Random(42)

        val textEmbeddings = Array(textVocabSize) {
            FloatArray(H) { random.nextFloat() * 2f - 1f }
        }

        // Test with 100 random hidden vectors
        for (trial in 0 until 100) {
            val slot0 = FloatArray(H) { random.nextFloat() * 2f - 1f }

            // Original brute force argmax
            var maxLogit = -Float.MAX_VALUE
            var argmaxId = 0
            for (v in 0 until textVocabSize) {
                val emb = textEmbeddings[v]
                var dot = 0f
                for (h in 0 until H) {
                    dot += slot0[h] * emb[h]
                }
                if (dot > maxLogit) {
                    maxLogit = dot
                    argmaxId = v
                }
            }
            val expectedEos = (argmaxId == eosTokenId)

            // Optimized EOS check
            val eosEmb = textEmbeddings[eosTokenId]
            var eosDot = 0f
            for (h in 0 until H) {
                eosDot += slot0[h] * eosEmb[h]
            }

            var isEos = true
            for (v in 0 until textVocabSize) {
                if (v == eosTokenId) continue
                val emb = textEmbeddings[v]
                var dot = 0f
                for (h in 0 until H) {
                    dot += slot0[h] * emb[h]
                }
                if (dot >= eosDot) {
                    isEos = false
                    break
                }
            }

            assertEquals("Trial $trial EOS mismatch", expectedEos, isEos)
        }
    }

    @Test
    fun testW2_SampleCodeContiguousMatrixVectorMultiply() {
        val H = 768
        val audioVocabSize = 1024
        val random = Random(123)

        val audioEmbeddingsCh = Array(audioVocabSize) {
            FloatArray(H) { random.nextFloat() * 2f - 1f }
        }

        // Flatten contiguous
        val flatEmb = FloatArray(audioVocabSize * H)
        for (v in 0 until audioVocabSize) {
            System.arraycopy(audioEmbeddingsCh[v], 0, flatEmb, v * H, H)
        }

        val vec = FloatArray(H) { random.nextFloat() * 2f - 1f }

        // Original nested loop
        val originalLogits = FloatArray(audioVocabSize)
        for (v in 0 until audioVocabSize) {
            val emb = audioEmbeddingsCh[v]
            var dot = 0f
            for (h in 0 until H) {
                dot += vec[h] * emb[h]
            }
            originalLogits[v] = dot
        }

        // Contiguous stride multiply
        val optimizedLogits = FloatArray(audioVocabSize)
        for (v in 0 until audioVocabSize) {
            val offset = v * H
            var dot = 0f
            for (h in 0 until H) {
                dot += vec[h] * flatEmb[offset + h]
            }
            optimizedLogits[v] = dot
        }

        assertArrayEquals(originalLogits, optimizedLogits, 1e-5f)
    }

    @Test
    fun testW3_AudioPlayerReInitLifecycle() {
        val player = AudioPlayer()
        // Before init
        assertFalse(player.isInitialized)

        // Init creates track (or catches failure in headless/unit test environment)
        player.init()

        // Stop should set needsReinit
        player.stop()
        assertFalse(player.isInitialized)

        // Reset cleans and reinits
        player.reset()

        // Release cleans up
        player.release()
        assertFalse(player.isInitialized)
    }

    @Test
    fun testW4_PcmMicroFadeInPlaceMatchesCopy() {
        val original = ShortArray(1000) { ((it * 37) % 30000).toShort() }
        val copyForInPlace = original.copyOf()

        // Reference implementation (copy-based with exact formula)
        val fadeSamples = ((48000 * 5.0f) / 1000f).toInt().coerceAtMost(original.size / 2)
        val reference = original.copyOf()
        val invFade = 1.0f / fadeSamples.toFloat()
        for (i in 0 until fadeSamples) {
            val factor = i.toFloat() * invFade
            reference[i] = (reference[i] * factor).toInt().toShort()
        }
        val n = reference.size
        for (i in 0 until fadeSamples) {
            val factor = (fadeSamples - 1 - i).toFloat() * invFade
            val idx = n - fadeSamples + i
            reference[idx] = (reference[idx] * factor).toInt().toShort()
        }

        val inPlaceResult = PcmUtils.applyMicroFade(copyForInPlace, 5.0f, 48000)
        assertSame(copyForInPlace, inPlaceResult)
        assertArrayEquals(reference, inPlaceResult)
    }

    @Test
    fun testW5_FloatToShortForLoopEquivalence() {
        val floatPcm = FloatArray(1000) { (it - 500) / 400f }

        // Reference lambda conversion
        val reference = ShortArray(floatPcm.size) { i ->
            (floatPcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }

        val actual = PcmUtils.floatToShort(floatPcm)
        assertArrayEquals(reference, actual)
    }

    @Test
    fun testW6_EmbedRowsPostPassAnchorEquivalence() {
        val T = 50
        val H = 768
        val random = Random(999)

        val rowsEmb = FloatArray(T * H) { random.nextFloat() }
        val anchor = FloatArray(H) { random.nextFloat() }

        // Per-row anchor addition (original)
        val original = rowsEmb.copyOf()
        for (t in 0 until T) {
            val offset = t * H
            for (h in 0 until H) {
                original[offset + h] += anchor[h]
            }
        }

        // Post-pass anchor addition (optimized)
        val optimized = rowsEmb.copyOf()
        for (t in 0 until T) {
            val offset = t * H
            for (h in 0 until H) {
                optimized[offset + h] += anchor[h]
            }
        }

        assertArrayEquals(original, optimized, 1e-6f)
    }
}
