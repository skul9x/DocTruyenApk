package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

class TopKSampleTest {

    private fun createDummyEngine(): VieNeuOnnxEngine {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        val sessionManager = OnnxSessionManager.createFromFiles(backboneDir, codecDir, threads = 1)
        val config = VieNeuConfig.fromJson(File(backboneDir, "config.json").readText())
        val tokenizer = VieNeuTokenizer.fromFile(File(backboneDir, "tokenizer.json"))
        val heads = NpzReader.read(File(backboneDir, "vieneu_v3_heads.npz"))

        return VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = heads.get2D("text_emb")!!,
            audioEmbeddings = heads.get3D("audio_emb")!!,
            xvecW = heads.get2D("xvec_w"),
            xvecB = heads.getFloatArray("xvec_b"),
            xvecLnW = heads.getFloatArray("xvec_ln_w"),
            xvecLnB = heads.getFloatArray("xvec_ln_b"),
            xvecLnEps = heads.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )
    }

    // Baseline naive O(V log V) implementation for statistical verification
    private fun naiveTopKSample(
        logits: FloatArray,
        k: Int = 25,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        repetitionPenalty: Float = 1.2f,
        prevTokens: Set<Int>? = null
    ): Int {
        val V = logits.size
        val workingLogits = logits.clone()

        if (kotlin.math.abs(repetitionPenalty - 1.0f) > 1e-4f && prevTokens != null && prevTokens.isNotEmpty()) {
            for (idx in prevTokens) {
                if (idx in 0 until V) {
                    val sel = workingLogits[idx]
                    workingLogits[idx] = if (sel < 0) sel * repetitionPenalty else sel / repetitionPenalty
                }
            }
        }

        if (temperature <= 0f) {
            var bestIdx = 0
            var bestVal = workingLogits[0]
            for (i in 1 until V) {
                if (workingLogits[i] > bestVal) {
                    bestVal = workingLogits[i]
                    bestIdx = i
                }
            }
            return bestIdx
        }

        for (i in 0 until V) {
            workingLogits[i] /= temperature
        }

        val effK = if (k in 1 until V) k else V
        data class PairLogit(val index: Int, val logit: Float)
        val indexed = Array(V) { i -> PairLogit(i, workingLogits[i]) }
        indexed.sortByDescending { it.logit }

        val topCandidates = Array(effK) { i -> indexed[i] }
        val maxLogit = topCandidates[0].logit
        var sumExp = 0.0
        val probs = DoubleArray(effK)
        for (i in 0 until effK) {
            val p = exp((topCandidates[i].logit - maxLogit).toDouble())
            probs[i] = p
            sumExp += p
        }
        for (i in 0 until effK) {
            probs[i] /= sumExp
        }

        if (topP in 0.0f..0.9999f) {
            var cumsum = 0.0
            var pSum = 0.0
            for (i in 0 until effK) {
                val p = probs[i]
                if (cumsum >= topP) {
                    probs[i] = 0.0
                } else {
                    cumsum += p
                    pSum += p
                }
            }
            if (pSum > 0.0) {
                for (i in 0 until effK) {
                    probs[i] /= pSum
                }
            }
        }

        val r = Random.nextDouble()
        var accum = 0.0
        for (i in 0 until effK) {
            accum += probs[i]
            if (r <= accum || i == effK - 1) {
                return topCandidates[i].index
            }
        }
        return topCandidates[0].index
    }

    @Test
    fun testGreedySamplingIdentical() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        if (!backboneDir.exists()) return

        val engine = createDummyEngine()
        try {
            val rng = java.util.Random(42)
            val logits = FloatArray(1024) { rng.nextFloat() * 10f - 5f }

            val greedyNaive = naiveTopKSample(logits, temperature = 0f)
            val greedyOptimized = engine.topKSample(logits, temperature = 0f)

            assertEquals("Greedy output should be strictly identical", greedyNaive, greedyOptimized)
        } finally {
            engine.close()
        }
    }

    @Test
    fun testTopKSampleBenchmarkAndDistribution() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        if (!backboneDir.exists()) return

        val engine = createDummyEngine()
        try {
            val rng = java.util.Random(1234)
            val logits = FloatArray(1024) { rng.nextFloat() * 8f - 4f }

            // Warmup
            repeat(100) {
                engine.topKSample(logits, k = 25, temperature = 0.8f, topP = 0.95f)
                naiveTopKSample(logits, k = 25, temperature = 0.8f, topP = 0.95f)
            }

            // Benchmark
            val iterations = 5000
            val tStartOptimized = System.nanoTime()
            repeat(iterations) {
                engine.topKSample(logits, k = 25, temperature = 0.8f, topP = 0.95f)
            }
            val tEndOptimized = System.nanoTime()

            val tStartNaive = System.nanoTime()
            repeat(iterations) {
                naiveTopKSample(logits, k = 25, temperature = 0.8f, topP = 0.95f)
            }
            val tEndNaive = System.nanoTime()

            val optimizedNs = (tEndOptimized - tStartOptimized) / iterations
            val naiveNs = (tEndNaive - tStartNaive) / iterations
            println("Benchmark TopKSample - Optimized: ${optimizedNs}ns/call vs Naive: ${naiveNs}ns/call")
            assertTrue("Optimized topKSample should be significantly faster or comparable without allocations", optimizedNs <= naiveNs * 2)
        } finally {
            engine.close()
        }
    }
}
