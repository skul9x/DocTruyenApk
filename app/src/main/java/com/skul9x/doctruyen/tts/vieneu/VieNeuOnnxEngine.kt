package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Port of VieNeu-TTS v3 Turbo ONNX Inference Engine to Kotlin.
 */
class VieNeuOnnxEngine(
    val sessionManager: OnnxSessionManager,
    val config: VieNeuConfig,
    val tokenizer: VieNeuTokenizer,
    val textEmbeddings: Array<FloatArray>,           // (textVocabSize, hiddenSize)
    val audioEmbeddings: Array<Array<FloatArray>>,    // (nVq, audioVocabSize, hiddenSize)
    val xvecW: Array<FloatArray>?,                   // (hiddenSize, speakerEmbeddingDim)
    val xvecB: FloatArray?,                          // (hiddenSize)
    val xvecLnW: FloatArray?,                        // (hiddenSize)
    val xvecLnB: FloatArray?,                        // (hiddenSize)
    val xvecLnEps: Float = 1e-5f
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val env: OrtEnvironment get() = sessionManager.env

    fun infer(
        phonemes: String,
        voiceName: String = "Trúc Ly",
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f
    ): FloatArray {
        val preset = VoicePresets.getVoice(voiceName)
        val spkEmb = preset?.speakerEmb
        val refCodes = preset?.codes
        return infer(
            phonemes = phonemes,
            speakerEmb = spkEmb,
            refCodes = refCodes,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty
        )
    }

    fun infer(
        phonemes: String,
        speakerEmb: FloatArray?,
        refCodes: Array<IntArray>?,
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f
    ): FloatArray = lock.withLock {
        val anchor = speakerAnchor(speakerEmb)
        val styleId = config.defaultStyleTokenId
        val rows = buildRows(phonemes, refCodes, styleId)
        val promptEmbeds = embedRows(rows, anchor) // shape (1, T, H)
        val tPrompt = rows.size
        val H = config.hiddenSize
        val L = config.numHiddenLayers // 12

        // 1. Prefill Step
        var h: FloatArray // size H (last hidden vector)
        var pastK: List<CacheTensor>
        var pastV: List<CacheTensor>

        val prefillInputs = HashMap<String, OnnxTensor>()
        val promptTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(promptEmbeds),
            longArrayOf(1, tPrompt.toLong(), H.toLong())
        )
        prefillInputs["inputs_embeds"] = promptTensor

        sessionManager.prefillSession.run(prefillInputs).use { preResult ->
            promptTensor.close()

            val hiddenTensor = preResult.get(0) as OnnxTensor
            val hiddenFb = hiddenTensor.floatBuffer
            val hiddenFlat = FloatArray(hiddenFb.remaining())
            hiddenFb.get(hiddenFlat)

            // Extract last hidden row: hidden[0, tPrompt-1, :]
            h = FloatArray(H)
            System.arraycopy(hiddenFlat, (tPrompt - 1) * H, h, 0, H)

            val kList = ArrayList<CacheTensor>(L)
            val vList = ArrayList<CacheTensor>(L)
            for (i in 0 until L) {
                val kt = preResult.get(1 + i) as OnnxTensor
                val vt = preResult.get(1 + L + i) as OnnxTensor
                kList.add(CacheTensor.fromOnnxTensor(kt))
                vList.add(CacheTensor.fromOnnxTensor(vt))
            }
            pastK = kList
            pastV = vList
        }

        // 2. Autoregressive Loop
        val frames = ArrayList<IntArray>()
        val hist = if (kotlin.math.abs(repetitionPenalty - 1.0f) > 1e-4f) {
            Array(config.nVq) { HashSet<Int>() }
        } else {
            null
        }

        for (t in 0 until maxNewFrames) {
            val (codes, eos) = acousticFrame(
                h = h,
                temperature = temperature,
                topK = topK,
                topP = topP,
                repetitionPenalty = repetitionPenalty,
                hist = hist
            )
            frames.add(codes)
            if (eos) {
                break
            }

            // Build decode step slot: [sgs, codes[0], ..., codes[15]]
            val slotRow = IntArray(config.nVq + 1)
            slotRow[0] = config.speechGenerationStartTokenId
            System.arraycopy(codes, 0, slotRow, 1, config.nVq)

            val slotEmbeds = embedRows(arrayOf(slotRow), anchor) // shape (1, 1, H)
            val decodeInputs = HashMap<String, OnnxTensor>()
            val seTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(slotEmbeds),
                longArrayOf(1, 1, H.toLong())
            )
            val posTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf((tPrompt + t).toLong())),
                longArrayOf(1, 1)
            )
            decodeInputs["inputs_embeds"] = seTensor
            decodeInputs["position_ids"] = posTensor

            val createdCacheTensors = ArrayList<OnnxTensor>()
            for (i in 0 until L) {
                val kt = pastK[i].toOnnxTensor(env)
                val vt = pastV[i].toOnnxTensor(env)
                createdCacheTensors.add(kt)
                createdCacheTensors.add(vt)
                decodeInputs["past_k_$i"] = kt
                decodeInputs["past_v_$i"] = vt
            }

            sessionManager.decodeSession.run(decodeInputs).use { decResult ->
                seTensor.close()
                posTensor.close()
                for (ct in createdCacheTensors) ct.close()

                val hiddenTensor = decResult.get(0) as OnnxTensor
                val hiddenFb = hiddenTensor.floatBuffer
                h = FloatArray(H)
                hiddenFb.get(h)

                val newKList = ArrayList<CacheTensor>(L)
                val newVList = ArrayList<CacheTensor>(L)
                for (i in 0 until L) {
                    val kt = decResult.get(1 + i) as OnnxTensor
                    val vt = decResult.get(1 + L + i) as OnnxTensor
                    newKList.add(CacheTensor.fromOnnxTensor(kt))
                    newVList.add(CacheTensor.fromOnnxTensor(vt))
                }
                pastK = newKList
                pastV = newVList
            }
        }

        if (frames.isEmpty()) {
            return FloatArray(0)
        }

        // 3. MOSS Codec Decode
        return decodeCodes(frames)
    }

    private fun acousticFrame(
        h: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        hist: Array<HashSet<Int>>?
    ): Pair<IntArray, Boolean> {
        val H = config.hiddenSize
        val nHLoc = config.localNumAttentionHeads // 8
        val hdLoc = config.localHeadDim // 96
        val sgs = config.speechGenerationStartTokenId
        val txt = textEmbeddings[sgs] // size H

        // First step: tok = [cond, txt], position_ids = [[0, 1]]
        val tok = FloatArray(2 * H)
        System.arraycopy(h, 0, tok, 0, H)
        System.arraycopy(txt, 0, tok, H, H)

        val feed0 = HashMap<String, OnnxTensor>()
        val tokTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tok), longArrayOf(1, 2, H.toLong()))
        val posTensor0 = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L, 1L)), longArrayOf(1, 2))
        val emptyPastK = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, nHLoc.toLong(), 0, hdLoc.toLong()))
        val emptyPastV = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, nHLoc.toLong(), 0, hdLoc.toLong()))

        feed0["token_emb"] = tokTensor
        feed0["position_ids"] = posTensor0
        feed0["past_k_0"] = emptyPastK
        feed0["past_v_0"] = emptyPastV

        var slot0: FloatArray
        var pk: CacheTensor
        var pv: CacheTensor
        val codes = IntArray(config.nVq)

        sessionManager.acousticSession.run(feed0).use { out0 ->
            tokTensor.close()
            posTensor0.close()
            emptyPastK.close()
            emptyPastV.close()

            val hiddenTensor = out0.get(0) as OnnxTensor
            val hiddenFb = hiddenTensor.floatBuffer
            val hiddenFlat = FloatArray(hiddenFb.remaining())
            hiddenFb.get(hiddenFlat)

            // hidden shape: (1, 2, H)
            slot0 = FloatArray(H)
            val vec0 = FloatArray(H)
            System.arraycopy(hiddenFlat, 0, slot0, 0, H)
            System.arraycopy(hiddenFlat, H, vec0, 0, H)

            val kt = out0.get(1) as OnnxTensor
            val vt = out0.get(2) as OnnxTensor
            pk = CacheTensor.fromOnnxTensor(kt)
            pv = CacheTensor.fromOnnxTensor(vt)

            // Sample code for channel 0
            codes[0] = sampleCode(0, vec0, temperature, topK, topP, repetitionPenalty, hist?.get(0))
            hist?.get(0)?.add(codes[0])
        }

        // Subsequent channels 1..15
        for (ch in 1 until config.nVq) {
            val prevCode = codes[ch - 1]
            val emb = audioEmbeddings[ch - 1][prevCode] // size H

            val feed = HashMap<String, OnnxTensor>()
            val embTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 1, H.toLong()))
            val posTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((ch + 1).toLong())), longArrayOf(1, 1))
            val pastKTensor = pk.toOnnxTensor(env)
            val pastVTensor = pv.toOnnxTensor(env)

            feed["token_emb"] = embTensor
            feed["position_ids"] = posTensor
            feed["past_k_0"] = pastKTensor
            feed["past_v_0"] = pastVTensor

            sessionManager.acousticSession.run(feed).use { out ->
                embTensor.close()
                posTensor.close()
                pastKTensor.close()
                pastVTensor.close()

                val hiddenTensor = out.get(0) as OnnxTensor
                val hiddenFb = hiddenTensor.floatBuffer
                val vec = FloatArray(H)
                hiddenFb.get(vec)

                val kt = out.get(1) as OnnxTensor
                val vt = out.get(2) as OnnxTensor
                pk = CacheTensor.fromOnnxTensor(kt)
                pv = CacheTensor.fromOnnxTensor(vt)

                codes[ch] = sampleCode(ch, vec, temperature, topK, topP, repetitionPenalty, hist?.get(ch))
                hist?.get(ch)?.add(codes[ch])
            }
        }

        // Text logits for EOS check: slot0 @ text_emb.T
        val textVocabSize = config.textVocabSize
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
        val eos = (argmaxId == config.speechGenerationEndTokenId)

        return Pair(codes, eos)
    }

    private fun sampleCode(
        ch: Int,
        vec: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        prev: Set<Int>?
    ): Int {
        val audioVocabSize = audioEmbeddings[ch].size // 1024
        val logits = FloatArray(audioVocabSize)
        val H = config.hiddenSize

        for (v in 0 until audioVocabSize) {
            val emb = audioEmbeddings[ch][v]
            var dot = 0f
            for (h in 0 until H) {
                dot += vec[h] * emb[h]
            }
            logits[v] = dot
        }

        return topKSample(
            logits = logits,
            k = topK,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            prevTokens = prev
        )
    }

    fun topKSample(
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
        // Find top-k indices
        val indexed = Array(V) { i -> IndexedLogit(i, workingLogits[i]) }
        // Partial sort / full sort top-k
        indexed.sortByDescending { it.logit }

        val topCandidates = Array(effK) { i -> indexed[i] }

        // Softmax over top-k
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

        // Top-P Nucleus filtering
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

        // Random weighted sampling
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

    private data class IndexedLogit(val index: Int, val logit: Float)

    private fun decodeCodes(frames: List<IntArray>): FloatArray {
        val tGen = frames.size
        val nVq = config.nVq // 16
        val flatCodes = IntArray(tGen * nVq)
        for (t in 0 until tGen) {
            val frame = frames[t]
            System.arraycopy(frame, 0, flatCodes, t * nVq, nVq)
        }

        val feed = HashMap<String, OnnxTensor>()
        val codesTensor = OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(flatCodes),
            longArrayOf(1, tGen.toLong(), nVq.toLong())
        )
        val lensTensor = OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(intArrayOf(tGen)),
            longArrayOf(1)
        )

        feed["audio_codes"] = codesTensor
        feed["audio_code_lengths"] = lensTensor

        sessionManager.codecSession.run(feed).use { result ->
            codesTensor.close()
            lensTensor.close()

            val audioTensor = result.get(0) as OnnxTensor
            val shape = audioTensor.info.shape // e.g. [1, 2, numSamples] or [1, 1, numSamples]
            val fb = audioTensor.floatBuffer
            val totalElements = fb.remaining()

            if (shape.size == 3 && shape[1] == 2L) {
                val numSamples = shape[2].toInt()
                val ch0 = FloatArray(numSamples)
                val ch1 = FloatArray(numSamples)
                fb.get(ch0)
                fb.get(ch1)
                val mono = FloatArray(numSamples)
                for (i in 0 until numSamples) {
                    mono[i] = (ch0[i] + ch1[i]) / 2.0f
                }
                return mono
            } else {
                val out = FloatArray(totalElements)
                fb.get(out)
                return out
            }
        }
    }

    fun speakerAnchor(speakerEmb: FloatArray?): FloatArray? {
        if (!config.useSpeakerEmbedding) return null
        if (speakerEmb == null) {
            throw IllegalArgumentException("This model needs a speaker anchor: pass `speakerEmb`")
        }
        val w = xvecW ?: throw IllegalStateException("heads.npz has no xvec_proj weights")
        val b = xvecB ?: throw IllegalStateException("heads.npz has no xvec_b")
        val lnW = xvecLnW ?: throw IllegalStateException("heads.npz has no xvec_ln_w")
        val lnB = xvecLnB ?: throw IllegalStateException("heads.npz has no xvec_ln_b")
        val H = config.hiddenSize
        val spkDim = config.speakerEmbeddingDim

        val v = FloatArray(H)
        var sum = 0.0
        for (i in 0 until H) {
            var dot = 0.0
            val row = w[i]
            for (j in 0 until spkDim) {
                dot += speakerEmb[j].toDouble() * row[j].toDouble()
            }
            val vi = (dot + b[i].toDouble()).toFloat()
            v[i] = vi
            sum += vi
        }

        val mean = (sum / H).toFloat()
        var varSum = 0.0
        for (i in 0 until H) {
            val diff = (v[i] - mean).toDouble()
            varSum += diff * diff
        }
        val variance = (varSum / H).toFloat()
        val std = sqrt(variance + xvecLnEps)

        val anchor = FloatArray(H)
        for (i in 0 until H) {
            val norm = (v[i] - mean) / std
            anchor[i] = norm * lnW[i] + lnB[i]
        }
        return anchor
    }

    fun buildRows(
        phonemes: String,
        refCodes: Array<IntArray>?,
        styleId: Int = config.defaultStyleTokenId
    ): Array<IntArray> {
        val phoneIds = tokenizer.encode(phonemes)
        val textIds = ArrayList<Int>()
        textIds.add(styleId)
        textIds.add(config.textPromptStartTokenId)
        for (id in phoneIds) textIds.add(id)
        textIds.add(config.textPromptEndTokenId)

        val textLen = textIds.size
        val refLen = refCodes?.size ?: 0
        val totalRows = textLen + refLen
        val nVq = config.nVq

        val rows = Array(totalRows) { IntArray(nVq + 1) { config.audioPadTokenId } }
        for (t in 0 until textLen) {
            rows[t][0] = textIds[t]
        }
        if (refCodes != null) {
            for (r in 0 until refLen) {
                val rowIdx = textLen + r
                rows[rowIdx][0] = config.audioRefSlotTokenId
                for (ch in 0 until nVq) {
                    rows[rowIdx][ch + 1] = refCodes[r][ch]
                }
            }
        }
        return rows
    }

    fun embedRows(rows: Array<IntArray>, anchor: FloatArray?): FloatArray {
        val T = rows.size
        val H = config.hiddenSize
        val nVq = config.nVq
        val result = FloatArray(T * H)

        for (t in 0 until T) {
            val textId = rows[t][0]
            val textVec = textEmbeddings[textId]
            val offset = t * H
            for (h in 0 until H) {
                result[offset + h] = textVec[h]
            }

            for (ch in 0 until nVq) {
                val audioId = rows[t][ch + 1]
                if (audioId != config.audioPadTokenId) {
                    val audioVec = audioEmbeddings[ch][audioId]
                    for (h in 0 until H) {
                        result[offset + h] += audioVec[h]
                    }
                }
            }

            if (anchor != null) {
                for (h in 0 until H) {
                    result[offset + h] += anchor[h]
                }
            }
        }
        return result
    }

    override fun close() {
        lock.withLock {
            sessionManager.close()
        }
    }

    /**
     * Helper holder for float cache tensors (past_k / past_v).
     */
    data class CacheTensor(
        val shape: LongArray,
        val data: FloatArray
    ) {
        fun toOnnxTensor(env: OrtEnvironment): OnnxTensor {
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }

        companion object {
            fun fromOnnxTensor(tensor: OnnxTensor): CacheTensor {
                val shape = tensor.info.shape.clone()
                val fb = tensor.floatBuffer
                val array = FloatArray(fb.remaining())
                fb.get(array)
                return CacheTensor(shape, array)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CacheTensor
            if (!shape.contentEquals(other.shape)) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = shape.contentHashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    companion object {
        private const val TAG = "VieNeuOnnxEngine"

        fun create(context: Context, threads: Int = 0): VieNeuOnnxEngine {
            val sessionManager = OnnxSessionManager.create(context, threads)

            val configStr = context.assets.open("${VieNeuConfig.BACKBONE_DIR}/config.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val config = VieNeuConfig.fromJson(configStr)

            val tokenizer = context.assets.open("${VieNeuConfig.BACKBONE_DIR}/tokenizer.json").use {
                VieNeuTokenizer.fromInputStream(it)
            }

            val headsReader = context.assets.open("${VieNeuConfig.BACKBONE_DIR}/vieneu_v3_heads.npz").use {
                NpzReader.read(it)
            }

            val textEmb = headsReader.get2D("text_emb")
                ?: throw IllegalStateException("text_emb missing from vieneu_v3_heads.npz")
            val audioEmb = headsReader.get3D("audio_emb")
                ?: throw IllegalStateException("audio_emb missing from vieneu_v3_heads.npz")
            val xvecW = headsReader.get2D("xvec_w")
            val xvecB = headsReader.getFloatArray("xvec_b")
            val xvecLnW = headsReader.getFloatArray("xvec_ln_w")
            val xvecLnB = headsReader.getFloatArray("xvec_ln_b")
            val xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

            // Load presets
            VoicePresets.loadFromContext(context)

            return VieNeuOnnxEngine(
                sessionManager = sessionManager,
                config = config,
                tokenizer = tokenizer,
                textEmbeddings = textEmb,
                audioEmbeddings = audioEmb,
                xvecW = xvecW,
                xvecB = xvecB,
                xvecLnW = xvecLnW,
                xvecLnB = xvecLnB,
                xvecLnEps = xvecLnEps
            )
        }
    }
}
