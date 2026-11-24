package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Port of VieNeu-TTS v3 Turbo ONNX Inference Engine to Kotlin.
 */
class VieNeuOnnxEngine(
    val sessionManager: OnnxSessionManager,
    val config: VieNeuConfig,
    val tokenizer: VieNeuTokenizer,
    val textEmbeddings: Array<FloatArray>,           // (textVocabSize, hiddenSize)
    audioEmbeddingsIn: Array<Array<FloatArray>>? = null,    // (nVq, audioVocabSize, hiddenSize) - legacy/compat
    val xvecW: Array<FloatArray>?,                   // (hiddenSize, speakerEmbeddingDim)
    val xvecB: FloatArray?,                          // (hiddenSize)
    val xvecLnW: FloatArray?,                        // (hiddenSize)
    val xvecLnB: FloatArray?,                        // (hiddenSize)
    val xvecLnEps: Float = 1e-5f,
    flatAudioEmbeddingsIn: Array<FloatArray>? = null,
    val flatAudioWeights: FloatArray? = null
) : AutoCloseable {

    constructor(
        sessionManager: OnnxSessionManager,
        config: VieNeuConfig,
        tokenizer: VieNeuTokenizer,
        textEmbeddings: Array<FloatArray>,
        audioEmbeddings: Array<Array<FloatArray>>,
        xvecW: Array<FloatArray>?,
        xvecB: FloatArray?,
        xvecLnW: FloatArray?,
        xvecLnB: FloatArray?,
        xvecLnEps: Float = 1e-5f
    ) : this(
        sessionManager = sessionManager,
        config = config,
        tokenizer = tokenizer,
        textEmbeddings = textEmbeddings,
        audioEmbeddingsIn = audioEmbeddings,
        xvecW = xvecW,
        xvecB = xvecB,
        xvecLnW = xvecLnW,
        xvecLnB = xvecLnB,
        xvecLnEps = xvecLnEps,
        flatAudioEmbeddingsIn = null,
        flatAudioWeights = null
    )

    val quantizationType: OnnxSessionManager.QuantizationType
        get() = sessionManager.quantizationType

    val executionProvider: String
        get() = sessionManager.executionProvider

    private val lock = ReentrantLock()
    private val env: OrtEnvironment get() = sessionManager.env

    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun cancel() {
        cancelRequested = true
    }

    fun requestCancel() {
        cancelRequested = true
    }

    fun resetCancel() {
        lock.withLock {
            cancelRequested = false
        }
    }

    private val teardownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    internal var teardownJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var nativeResourcesReleased = false
    val isResourcesReleased: Boolean
        get() = nativeResourcesReleased

    @Volatile
    private var closed = false
    val isClosed: Boolean
        get() = closed || sessionManager.isClosed

    // Pre-allocated buffers for topKSample to eliminate per-call allocations
    private var workingLogitsBuffer = FloatArray(1024)
    private var allIndicesBuffer = IntArray(1024) { it }
    private var topKIndicesBuffer = IntArray(256)
    private var topKLogitsBuffer = FloatArray(256)
    private var probsBuffer = DoubleArray(256)

    // Flat audio embeddings per channel for cache locality and zero-copy slicing
    // shape: flatAudioEmbeddings[ch] has size (audioVocabSize * hiddenSize)
    val flatAudioEmbeddings: Array<FloatArray> = when {
        flatAudioEmbeddingsIn != null -> flatAudioEmbeddingsIn
        audioEmbeddingsIn != null -> Array(audioEmbeddingsIn.size) { ch ->
            val vSize = audioEmbeddingsIn[ch].size
            val hSize = config.hiddenSize
            val flat = FloatArray(vSize * hSize)
            for (v in 0 until vSize) {
                val emb = audioEmbeddingsIn[ch][v]
                System.arraycopy(emb, 0, flat, v * hSize, hSize)
            }
            flat
        }
        flatAudioWeights != null -> {
            val nChannels = config.nVq
            val channelStride = flatAudioWeights.size / nChannels
            Array(nChannels) { ch ->
                val slice = FloatArray(channelStride)
                System.arraycopy(flatAudioWeights, ch * channelStride, slice, 0, channelStride)
                slice
            }
        }
        else -> throw IllegalArgumentException("Either flatAudioEmbeddingsIn, audioEmbeddingsIn, or flatAudioWeights must be provided")
    }

    private var _cachedAudioEmbeddings3D: Array<Array<FloatArray>>? = audioEmbeddingsIn

    val audioEmbeddings: Array<Array<FloatArray>>
        get() {
            val existing = _cachedAudioEmbeddings3D
            if (existing != null) return existing
            val nCh = flatAudioEmbeddings.size
            val H = config.hiddenSize
            val vSize = flatAudioEmbeddings[0].size / H
            val res = Array(nCh) { ch ->
                Array(vSize) { v ->
                    val slice = FloatArray(H)
                    System.arraycopy(flatAudioEmbeddings[ch], v * H, slice, 0, H)
                    slice
                }
            }
            _cachedAudioEmbeddings3D = res
            return res
        }

    // Pre-allocated channel logits buffer
    private val channelLogitsBuffer: Array<FloatArray> = Array(flatAudioEmbeddings.size) { ch ->
        FloatArray(flatAudioEmbeddings[ch].size / config.hiddenSize)
    }

    fun getAudioEmbeddingVector(ch: Int, code: Int): FloatArray {
        val H = config.hiddenSize
        val vec = FloatArray(H)
        System.arraycopy(flatAudioEmbeddings[ch], code * H, vec, 0, H)
        return vec
    }

    fun getTextEmbeddingVector(tokenId: Int): FloatArray {
        return textEmbeddings[tokenId]
    }

    private var cachedEmptyPastK: OnnxTensor? = null
    private var cachedEmptyPastV: OnnxTensor? = null
    private var cachedChannelPosTensors: Array<OnnxTensor>? = null
    private var cachedPosTensor0: OnnxTensor? = null

    @Synchronized
    fun getOrCreateEmptyPastK(): OnnxTensor {
        val tensor = cachedEmptyPastK
        if (tensor != null) return tensor
        val nHLoc = config.localNumAttentionHeads.toLong()
        val hdLoc = config.localHeadDim.toLong()
        val newTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(FloatArray(0)),
            longArrayOf(1, nHLoc, 0, hdLoc)
        )
        cachedEmptyPastK = newTensor
        return newTensor
    }

    @Synchronized
    fun getOrCreateEmptyPastV(): OnnxTensor {
        val tensor = cachedEmptyPastV
        if (tensor != null) return tensor
        val nHLoc = config.localNumAttentionHeads.toLong()
        val hdLoc = config.localHeadDim.toLong()
        val newTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(FloatArray(0)),
            longArrayOf(1, nHLoc, 0, hdLoc)
        )
        cachedEmptyPastV = newTensor
        return newTensor
    }

    @Synchronized
    fun getOrCreateChannelPosTensors(): Array<OnnxTensor> {
        val existing = cachedChannelPosTensors
        if (existing != null) return existing
        val nVq = config.nVq // 16
        val tensors = Array(nVq + 1) { ch ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ch.toLong())), longArrayOf(1, 1))
        }
        cachedChannelPosTensors = tensors
        return tensors
    }

    @Synchronized
    fun getOrCreatePosTensor0(): OnnxTensor {
        val existing = cachedPosTensor0
        if (existing != null) return existing
        val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L, 1L)), longArrayOf(1, 2))
        cachedPosTensor0 = tensor
        return tensor
    }

    internal fun getCachedChannelPosTensorsForTesting(): Array<OnnxTensor>? = cachedChannelPosTensors
    internal fun getCachedPosTensor0ForTesting(): OnnxTensor? = cachedPosTensor0

    data class InferenceTiming(
        val backboneDurationMs: Long,
        val codecDurationMs: Long
    ) {
        val totalDurationMs: Long get() = backboneDurationMs + codecDurationMs
    }

    data class EngineInferenceResult(
        val pcm: FloatArray,
        val timing: InferenceTiming
    )

    fun infer(
        phonemes: String,
        voiceName: String = "Trúc Ly",
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f
    ): FloatArray {
        return inferWithTiming(
            phonemes = phonemes,
            voiceName = voiceName,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty
        ).pcm
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
    ): FloatArray {
        return inferWithTiming(
            phonemes = phonemes,
            speakerEmb = speakerEmb,
            refCodes = refCodes,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty
        ).pcm
    }

    fun inferWithTiming(
        phonemes: String,
        voiceName: String = "Trúc Ly",
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f
    ): EngineInferenceResult {
        val preset = VoicePresets.getVoice(voiceName)
        val spkEmb = preset?.speakerEmb
        val refCodes = preset?.codes
        return inferWithTiming(
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
    private data class SynthesisPassResult(
        val pcm: FloatArray,
        val frames: List<IntArray>,
        val backboneDurationMs: Long,
        val codecDurationMs: Long
    ) {
        val timing: InferenceTiming
            get() = InferenceTiming(backboneDurationMs, codecDurationMs)
    }

    private fun synthesizePass(
        phonemes: String,
        speakerEmb: FloatArray?,
        refCodes: Array<IntArray>?,
        temperature: Float,
        topK: Int,
        topP: Float,
        maxNewFrames: Int,
        repetitionPenalty: Float
    ): SynthesisPassResult {
        val backboneStart = System.currentTimeMillis()
        val anchor = speakerAnchor(speakerEmb)
        val styleId = config.defaultStyleTokenId
        val rows = buildRows(phonemes, refCodes, styleId)
        val promptEmbeds = embedRows(rows, anchor) // shape (1, T, H)
        val tPrompt = rows.size
        val H = config.hiddenSize
        val L = config.numHiddenLayers // 12

        // 1. Prefill Step
        val h = FloatArray(H) // size H (last hidden vector)
        var prevResult: OrtSession.Result? = null

        val prefillInputs = HashMap<String, OnnxTensor>()
        val promptTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(promptEmbeds),
            longArrayOf(1, tPrompt.toLong(), H.toLong())
        )
        prefillInputs["inputs_embeds"] = promptTensor

        // 2. Autoregressive Loop
        val effectiveMaxFrames = min(maxNewFrames, maxExpectedFrames(phonemes))
        val frames = ArrayList<IntArray>()
        val hist = if (kotlin.math.abs(repetitionPenalty - 1.0f) > 1e-4f) {
            RepetitionHistory(nChannels = config.nVq, window = 64, vocabSize = flatAudioEmbeddings[0].size / config.hiddenSize)
        } else {
            null
        }

        val slotRow = IntArray(config.nVq + 1)
        slotRow[0] = config.speechGenerationStartTokenId
        val decodeInputs = HashMap<String, OnnxTensor>(2 * L + 4)

        var promptTensorClosed = false
        try {
            if (cancelRequested || Thread.currentThread().isInterrupted) {
                promptTensor.close()
                promptTensorClosed = true
                throw InterruptedException("Synthesis cancelled")
            }
            val preResult = sessionManager.prefillSession.run(prefillInputs)
            promptTensor.close()
            promptTensorClosed = true

            val hiddenTensor = preResult.get(0) as OnnxTensor
            val hiddenFb = hiddenTensor.floatBuffer
            hiddenFb.position((tPrompt - 1) * H)
            hiddenFb.get(h)

            prevResult = preResult

            for (t in 0 until effectiveMaxFrames) {
                if (cancelRequested || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Synthesis cancelled")
                }

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
                System.arraycopy(codes, 0, slotRow, 1, config.nVq)

                val slotEmbeds = embedRows(arrayOf(slotRow), anchor) // shape (1, 1, H)
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

                decodeInputs.clear()
                decodeInputs["inputs_embeds"] = seTensor
                decodeInputs["position_ids"] = posTensor

                for (i in 0 until L) {
                    decodeInputs["past_k_$i"] = prevResult!!.get(1 + i) as OnnxTensor
                    decodeInputs["past_v_$i"] = prevResult!!.get(1 + L + i) as OnnxTensor
                }

                val decResult = sessionManager.decodeSession.run(decodeInputs)
                seTensor.close()
                posTensor.close()

                val hiddenTensorDec = decResult.get(0) as OnnxTensor
                val hiddenFbDec = hiddenTensorDec.floatBuffer
                hiddenFbDec.position(0)
                hiddenFbDec.get(h)

                // Handover: close old previous result and retain current result natively
                prevResult!!.close()
                prevResult = decResult
            }
        } finally {
            if (!promptTensorClosed) {
                try {
                    promptTensor.close()
                } catch (_: Throwable) {}
            }
            prevResult?.close()
            prevResult = null
        }

        val backboneDuration = System.currentTimeMillis() - backboneStart

        if (frames.isEmpty()) {
            return SynthesisPassResult(
                pcm = FloatArray(0),
                frames = frames,
                backboneDurationMs = backboneDuration,
                codecDurationMs = 0L
            )
        }

        // 3. MOSS Codec Decode
        val codecStart = System.currentTimeMillis()
        val pcm = decodeCodes(frames)
        val codecDuration = System.currentTimeMillis() - codecStart

        return SynthesisPassResult(
            pcm = pcm,
            frames = frames,
            backboneDurationMs = backboneDuration,
            codecDurationMs = codecDuration
        )
    }

    fun inferWithTiming(
        phonemes: String,
        speakerEmb: FloatArray?,
        refCodes: Array<IntArray>?,
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f
    ): EngineInferenceResult = lock.withLock {
        if (isClosed) {
            throw IllegalStateException("VieNeuOnnxEngine is closed")
        }
        if (cancelRequested) {
            throw InterruptedException("Synthesis cancelled")
        }

        val syllableCount = BabbleGuard.countSyllables(phonemes)
        val capFrames = min(maxNewFrames, maxExpectedFrames(phonemes))

        // Initial synthesis pass
        val pass0 = synthesizePass(
            phonemes = phonemes,
            speakerEmb = speakerEmb,
            refCodes = refCodes,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty
        )

        // Guard activation scope: Long chunks (> 2 syllables) bypass energy envelope checks to maintain zero overhead
        if (syllableCount > BabbleGuard.MAX_SHORT_UTTERANCE_SYLLABLES) {
            return EngineInferenceResult(pass0.pcm, pass0.timing)
        }

        var bestCandidate = BabbleGuard.SynthesisCandidate(
            pcm = pass0.pcm,
            frameCount = pass0.frames.size,
            syllableCount = syllableCount,
            timing = pass0.timing
        )

        val isSuspect = BabbleGuard.isBabbleSuspect(
            burstCount = bestCandidate.burstCount,
            syllableCount = syllableCount,
            generatedFrames = pass0.frames.size,
            capFrames = capFrames
        )

        if (!isSuspect) {
            return EngineInferenceResult(bestCandidate.pcm, pass0.timing)
        }

        // Suspect babble detected! Retry loop: up to MAX_RETRIES (2) with varied temperature
        var totalBackboneMs = pass0.backboneDurationMs
        var totalCodecMs = pass0.codecDurationMs

        for (retry in 1..BabbleGuard.MAX_RETRIES) {
            if (cancelRequested || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Synthesis cancelled")
            }
            TtsMetricsLogger.recordBabbleRetry(
                phonemes = phonemes,
                retryAttempt = retry,
                burstCount = bestCandidate.burstCount,
                syllableCount = syllableCount,
                reason = if (pass0.frames.size >= capFrames - 1) "frame_cap_ceiling" else "excess_bursts"
            )

            val retryTemp = when (retry) {
                1 -> (temperature * 0.85f).coerceAtLeast(0.5f)
                else -> (temperature * 0.70f).coerceAtLeast(0.4f)
            }

            val retryPass = synthesizePass(
                phonemes = phonemes,
                speakerEmb = speakerEmb,
                refCodes = refCodes,
                temperature = retryTemp,
                topK = topK,
                topP = topP,
                maxNewFrames = maxNewFrames,
                repetitionPenalty = repetitionPenalty
            )

            totalBackboneMs += retryPass.backboneDurationMs
            totalCodecMs += retryPass.codecDurationMs

            val retryCandidate = BabbleGuard.SynthesisCandidate(
                pcm = retryPass.pcm,
                frameCount = retryPass.frames.size,
                syllableCount = syllableCount,
                timing = retryPass.timing
            )

            bestCandidate = BabbleGuard.babblePrefer(bestCandidate, retryCandidate)

            val retrySuspect = BabbleGuard.isBabbleSuspect(
                burstCount = retryCandidate.burstCount,
                syllableCount = syllableCount,
                generatedFrames = retryPass.frames.size,
                capFrames = capFrames
            )

            if (!retrySuspect) {
                bestCandidate = retryCandidate
                break
            }
        }

        return EngineInferenceResult(
            pcm = bestCandidate.pcm,
            timing = InferenceTiming(totalBackboneMs, totalCodecMs)
        )
    }

    private fun acousticFrame(
        h: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        hist: RepetitionHistory?
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
        val posTensor0 = getOrCreatePosTensor0()
        val emptyPastK = getOrCreateEmptyPastK()
        val emptyPastV = getOrCreateEmptyPastV()

        feed0["token_emb"] = tokTensor
        feed0["position_ids"] = posTensor0
        feed0["past_k_0"] = emptyPastK
        feed0["past_v_0"] = emptyPastV

        val slot0 = FloatArray(H)
        val vec = FloatArray(H)
        val codes = IntArray(config.nVq)
        var prevOut: OrtSession.Result? = null

        var initialTensorsClosed = false
        try {
            val out0 = sessionManager.acousticSession.run(feed0)
            tokTensor.close()
            initialTensorsClosed = true

            val hiddenTensor0 = out0.get(0) as OnnxTensor
            val hiddenFb0 = hiddenTensor0.floatBuffer

            // hidden shape: (1, 2, H)
            hiddenFb0.position(0)
            hiddenFb0.get(slot0)
            hiddenFb0.get(vec)

            // Sample code for channel 0
            codes[0] = sampleCode(0, vec, temperature, topK, topP, repetitionPenalty, hist)
            hist?.add(0, codes[0])

            prevOut = out0

            // Subsequent channels 1..15
            val channelPosTensors = getOrCreateChannelPosTensors()
            val feed = HashMap<String, OnnxTensor>(8)
            for (ch in 1 until config.nVq) {
                if (cancelRequested || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Acoustic frame synthesis cancelled")
                }

                val prevCode = codes[ch - 1]
                val flatEmb = flatAudioEmbeddings[ch - 1]

                val embTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(flatEmb, prevCode * H, H),
                    longArrayOf(1, 1, H.toLong())
                )
                try {
                    feed.clear()
                    feed["token_emb"] = embTensor
                    feed["position_ids"] = channelPosTensors[ch + 1]
                    feed["past_k_0"] = prevOut!!.get(1) as OnnxTensor
                    feed["past_v_0"] = prevOut!!.get(2) as OnnxTensor

                    val out = sessionManager.acousticSession.run(feed)

                    val hiddenTensorCh = out.get(0) as OnnxTensor
                    val hiddenFbCh = hiddenTensorCh.floatBuffer
                    hiddenFbCh.position(0)
                    hiddenFbCh.get(vec)

                    // Handover: close old previous result and retain current result natively
                    prevOut!!.close()
                    prevOut = out
                } finally {
                    embTensor.close()
                }

                codes[ch] = sampleCode(ch, vec, temperature, topK, topP, repetitionPenalty, hist)
                hist?.add(ch, codes[ch])
            }
        } finally {
            if (!initialTensorsClosed) {
                try {
                    tokTensor.close()
                } catch (_: Throwable) {}
            }
            prevOut?.close()
            prevOut = null
        }

        // Text logits for EOS check: slot0 @ text_emb.T
        // W1 optimization: compute dot product for EOS token (token 6) and verify if it dominates vs other candidate tokens
        val eosTokenId = config.speechGenerationEndTokenId
        val eosEmb = textEmbeddings[eosTokenId]
        var eosDot = 0f
        for (h in 0 until H) {
            eosDot += slot0[h] * eosEmb[h]
        }

        // Compare against all other text tokens with early exit if any token is higher
        var isEos = true
        val textVocabSize = config.textVocabSize
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
        val eos = isEos

        return Pair(codes, eos)
    }

    fun sampleCode(
        ch: Int,
        vec: FloatArray,
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        repetitionPenalty: Float = 1.2f,
        hist: RepetitionHistory? = null
    ): Int {
        val H = config.hiddenSize
        val audioVocabSize = flatAudioEmbeddings[ch].size / H // 1024
        val logits = channelLogitsBuffer[ch]
        val flatEmb = flatAudioEmbeddings[ch]

        computeAcousticProjectionLogits(vec, flatEmb, logits, H, audioVocabSize)

        return topKSample(
            logits = logits,
            k = topK,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            repetitionHistory = hist,
            channel = ch
        )
    }

    // Overload for backward compatibility with Set<Int>
    fun sampleCode(
        ch: Int,
        vec: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        prev: Set<Int>?
    ): Int {
        val H = config.hiddenSize
        val audioVocabSize = flatAudioEmbeddings[ch].size / H
        val logits = channelLogitsBuffer[ch]
        val flatEmb = flatAudioEmbeddings[ch]

        computeAcousticProjectionLogits(vec, flatEmb, logits, H, audioVocabSize)

        return topKSample(
            logits = logits,
            k = topK,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            prevTokens = prev
        )
    }

    fun partitionTopK(
        indices: IntArray,
        logits: FloatArray,
        k: Int,
        start: Int = 0,
        end: Int = indices.size - 1
    ) {
        Companion.partitionTopK(indices, logits, k, start, end)
    }

    fun partitionTopK(logits: FloatArray, k: Int = 256): IntArray {
        return Companion.partitionTopK(logits, k)
    }

    fun quickSelectTopK(indices: IntArray, logits: FloatArray, k: Int = 256) {
        Companion.quickSelectTopK(indices, logits, k)
    }

    fun quickSelectTopK(logits: FloatArray, k: Int = 256): IntArray {
        return Companion.quickSelectTopK(logits, k)
    }

    fun topKSample(
        logits: FloatArray,
        k: Int = 25,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        repetitionPenalty: Float = 1.2f,
        prevTokens: Set<Int>? = null,
        repetitionHistory: RepetitionHistory? = null,
        channel: Int = 0
    ): Int {
        val V = logits.size
        if (workingLogitsBuffer.size < V) {
            workingLogitsBuffer = FloatArray(V)
        }
        if (allIndicesBuffer.size < V) {
            allIndicesBuffer = IntArray(V)
        }
        val workingLogits = workingLogitsBuffer
        val allIndices = allIndicesBuffer
        System.arraycopy(logits, 0, workingLogits, 0, V)
        for (i in 0 until V) {
            allIndices[i] = i
        }

        if (kotlin.math.abs(repetitionPenalty - 1.0f) > 1e-4f) {
            if (repetitionHistory != null) {
                repetitionHistory.applyPenalty(channel, workingLogits, repetitionPenalty)
            } else if (prevTokens != null && prevTokens.isNotEmpty()) {
                if (prevTokens is RepetitionHistory.Channel) {
                    prevTokens.applyPenalty(workingLogits, repetitionPenalty)
                } else {
                    for (idx in prevTokens) {
                        if (idx in 0 until V) {
                            val sel = workingLogits[idx]
                            workingLogits[idx] = if (sel < 0f) sel * repetitionPenalty else sel / repetitionPenalty
                        }
                    }
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

        val invTemp = 1.0f / temperature
        for (i in 0 until V) {
            workingLogits[i] *= invTemp
        }

        // QuickSelect top candidate partition (up to 256)
        val numCandidates = min(256, V)
        quickSelectTopK(allIndices, workingLogits, numCandidates)

        val effK = if (k in 1 until numCandidates) k else numCandidates
        if (topKIndicesBuffer.size < effK) {
            topKIndicesBuffer = IntArray(effK)
            topKLogitsBuffer = FloatArray(effK)
            probsBuffer = DoubleArray(effK)
        }
        val topIndices = topKIndicesBuffer
        val topLogits = topKLogitsBuffer
        val probs = probsBuffer

        for (i in 0 until effK) {
            val idx = allIndices[i]
            topIndices[i] = idx
            topLogits[i] = workingLogits[idx]
        }

        // Softmax over top-K candidates
        val maxLogit = topLogits[0]
        var sumExp = 0.0
        for (i in 0 until effK) {
            val p = exp((topLogits[i] - maxLogit).toDouble())
            probs[i] = p
            sumExp += p
        }
        val invSumExp = 1.0 / sumExp
        for (i in 0 until effK) {
            probs[i] *= invSumExp
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
                val invPSum = 1.0 / pSum
                for (i in 0 until effK) {
                    probs[i] *= invPSum
                }
            }
        }

        // Random weighted sampling
        val r = Random.nextDouble()
        var accum = 0.0
        for (i in 0 until effK) {
            accum += probs[i]
            if (r <= accum || i == effK - 1) {
                return topIndices[i]
            }
        }

        return topIndices[0]
    }

    private fun decodeCodes(frames: List<IntArray>): FloatArray {
        if (cancelRequested || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Synthesis cancelled")
        }

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

        try {
            if (cancelRequested || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Synthesis cancelled")
            }

            sessionManager.codecSession.run(feed).use { result ->
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
        } finally {
            try {
                codesTensor.close()
            } catch (_: Throwable) {}
            try {
                lensTensor.close()
            } catch (_: Throwable) {}
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
                    val flatEmb = flatAudioEmbeddings[ch]
                    val embOffset = audioId * H
                    for (h in 0 until H) {
                        result[offset + h] += flatEmb[embOffset + h]
                    }
                }
            }
        }

        // Post-pass SIMD-friendly loop: add anchor to all rows in one pass
        if (anchor != null) {
            for (t in 0 until T) {
                val offset = t * H
                for (h in 0 until H) {
                    result[offset + h] += anchor[h]
                }
            }
        }

        return result
    }

    private fun performResourceTeardown() {
        if (nativeResourcesReleased) return
        nativeResourcesReleased = true
        try {
            cachedEmptyPastK?.close()
            cachedEmptyPastK = null
        } catch (_: Exception) {}
        try {
            cachedEmptyPastV?.close()
            cachedEmptyPastV = null
        } catch (_: Exception) {}
        cachedChannelPosTensors?.forEach { tensor ->
            try { tensor.close() } catch (_: Throwable) {}
        }
        cachedChannelPosTensors = null
        try {
            cachedPosTensor0?.close()
        } catch (_: Throwable) {}
        cachedPosTensor0 = null
        try {
            sessionManager.close()
        } catch (_: Exception) {}
        CacheTensor.Pool.clear()
        workingLogitsBuffer = FloatArray(0)
        allIndicesBuffer = IntArray(0)
        topKIndicesBuffer = IntArray(0)
        topKLogitsBuffer = FloatArray(0)
        probsBuffer = DoubleArray(0)
    }

    override fun close() {
        cancelRequested = true
        closed = true

        if (lock.tryLock()) {
            try {
                performResourceTeardown()
            } finally {
                lock.unlock()
            }
        } else {
            // Lock is held by active inference pass. Offload waiting and resource cleanup
            // to a background coroutine so the calling thread (e.g. Android UI/Main thread) returns immediately.
            teardownJob = teardownScope.launch {
                lock.withLock {
                    performResourceTeardown()
                }
            }
        }
    }

    fun awaitClosed(timeoutMs: Long = 5000): Boolean {
        val job = teardownJob ?: return true
        return kotlinx.coroutines.runBlocking {
            try {
                withTimeout(timeoutMs) {
                    job.join()
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Helper holder for cache tensors (past_k / past_v).
     * Supports zero-copy native OnnxTensor retention and pooling.
     */
    data class CacheTensor(
        var shape: LongArray,
        var data: FloatArray,
        var nativeTensor: OnnxTensor? = null
    ) : AutoCloseable {
        val isNative: Boolean get() = nativeTensor != null

        fun toOnnxTensor(env: OrtEnvironment): OnnxTensor {
            return nativeTensor ?: OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }

        fun recycle() {
            nativeTensor?.close()
            nativeTensor = null
            Pool.release(this)
        }

        override fun close() {
            recycle()
        }

        object Pool {
            private const val MAX_POOL_SIZE = 48
            private val pool = ArrayDeque<CacheTensor>(MAX_POOL_SIZE)

            @Synchronized
            fun obtain(shape: LongArray, data: FloatArray): CacheTensor {
                val cached = if (pool.isNotEmpty()) pool.removeLast() else null
                return if (cached != null) {
                    cached.shape = shape
                    cached.data = data
                    cached.nativeTensor = null
                    cached
                } else {
                    CacheTensor(shape, data, null)
                }
            }

            @Synchronized
            fun obtainNative(tensor: OnnxTensor): CacheTensor {
                val cached = if (pool.isNotEmpty()) pool.removeLast() else null
                val s = tensor.info.shape.clone()
                return if (cached != null) {
                    cached.shape = s
                    cached.data = FloatArray(0)
                    cached.nativeTensor = tensor
                    cached
                } else {
                    CacheTensor(s, FloatArray(0), tensor)
                }
            }

            @Synchronized
            fun release(tensor: CacheTensor) {
                tensor.nativeTensor?.close()
                tensor.nativeTensor = null
                tensor.data = FloatArray(0)
                tensor.shape = LongArray(0)
                if (pool.size < MAX_POOL_SIZE) {
                    pool.addLast(tensor)
                }
            }

            @Synchronized
            fun clear() {
                for (t in pool) {
                    t.nativeTensor?.close()
                    t.nativeTensor = null
                }
                pool.clear()
            }
        }

        companion object {
            fun fromOnnxTensor(tensor: OnnxTensor): CacheTensor {
                val shape = tensor.info.shape.clone()
                val fb = tensor.floatBuffer
                val array = FloatArray(fb.remaining())
                fb.get(array)
                return Pool.obtain(shape, array)
            }

            fun fromNativeOnnxTensor(tensor: OnnxTensor): CacheTensor {
                return Pool.obtainNative(tensor)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CacheTensor
            if (!shape.contentEquals(other.shape)) return false
            if (!data.contentEquals(other.data)) return false
            if (nativeTensor != other.nativeTensor) return false
            return true
        }

        override fun hashCode(): Int {
            var result = shape.contentHashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + (nativeTensor?.hashCode() ?: 0)
            return result
        }
    }

    companion object {
        private const val TAG = "VieNeuOnnxEngine"

        /**
         * Fast matrix-vector projection for acoustic frame sampling: logits = vec @ flatEmb^T
         * Computes vocabSize dot products of dimension H using 8-way unrolled accumulators.
         * Eliminates serial loop-carried dependency stalls and exposes ILP to SIMD pipelines.
         */
        fun computeAcousticProjectionLogits(
            vec: FloatArray,
            flatEmb: FloatArray,
            logits: FloatArray,
            H: Int,
            vocabSize: Int
        ) {
            val hLimit = H - 7
            for (v in 0 until vocabSize) {
                val offset = v * H
                var d0 = 0f
                var d1 = 0f
                var d2 = 0f
                var d3 = 0f
                var d4 = 0f
                var d5 = 0f
                var d6 = 0f
                var d7 = 0f
                var h = 0

                while (h < hLimit) {
                    val o = offset + h
                    d0 += vec[h] * flatEmb[o]
                    d1 += vec[h + 1] * flatEmb[o + 1]
                    d2 += vec[h + 2] * flatEmb[o + 2]
                    d3 += vec[h + 3] * flatEmb[o + 3]
                    d4 += vec[h + 4] * flatEmb[o + 4]
                    d5 += vec[h + 5] * flatEmb[o + 5]
                    d6 += vec[h + 6] * flatEmb[o + 6]
                    d7 += vec[h + 7] * flatEmb[o + 7]
                    h += 8
                }
                while (h < H) {
                    d0 += vec[h] * flatEmb[offset + h]
                    h++
                }
                logits[v] = ((d0 + d1) + (d2 + d3)) + ((d4 + d5) + (d6 + d7))
            }
        }

        const val MAX_FRAMES_PER_PHONE: Double = 2.0
        const val FRAME_CAP_SLACK: Int = 24
        const val SINGLE_WORD_MAX_FRAMES: Int = 13
        const val SINGLE_WORD_MAX_PHONES: Int = 24
        private val FRAME_MARKUP_RE = Regex("""<\|emotion_\d+\|>|</?en>""")

        fun maxExpectedFrames(phonemes: String): Int {
            val stripped = FRAME_MARKUP_RE.replace(phonemes, "")
            val effLen = stripped.length
            var cap = FRAME_CAP_SLACK + ceil(MAX_FRAMES_PER_PHONE * effLen).toInt()
            val words = stripped.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.size <= 1 && effLen <= SINGLE_WORD_MAX_PHONES && !phonemes.contains("<|emotion_")) {
                cap = min(cap, SINGLE_WORD_MAX_FRAMES)
            }
            return cap
        }

        fun create(
            context: Context,
            threads: Int = 0,
            precision: String = "INT8"
        ): VieNeuOnnxEngine {
            val sessionManager = OnnxSessionManager.create(context, threads, precision)

            val normPrecision = when (precision.trim().uppercase()) {
                "INT8" -> "INT8"
                "FP32" -> "FP32"
                else -> "FP32"
            }
            val int8Assets = try { context.assets.list("vieneu/backbone_int8") } catch (_: Exception) { null }
            val backboneAssetDir = if (normPrecision == "INT8" && !int8Assets.isNullOrEmpty()) {
                "vieneu/backbone_int8"
            } else {
                VieNeuConfig.BACKBONE_DIR
            }

            fun openBackboneAsset(filename: String): java.io.InputStream {
                return try {
                    context.assets.open("$backboneAssetDir/$filename")
                } catch (_: Exception) {
                    context.assets.open("${VieNeuConfig.BACKBONE_DIR}/$filename")
                }
            }

            val configStr = openBackboneAsset("config.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val config = VieNeuConfig.fromJson(configStr)

            val tokenizer = openBackboneAsset("tokenizer.json").use {
                VieNeuTokenizer.fromInputStream(it)
            }

            val headsReader = openBackboneAsset("vieneu_v3_heads.npz").use {
                NpzReader.read(it)
            }

            val textEmb = headsReader.get2D("text_emb")
                ?: throw IllegalStateException("text_emb missing from vieneu_v3_heads.npz")
            val audioFlat = headsReader.getChannelFlatArrays("audio_emb")
                ?: headsReader.get3D("audio_emb")?.let { Array(it.size) { ch ->
                    val vSize = it[ch].size
                    val flat = FloatArray(vSize * config.hiddenSize)
                    for (v in 0 until vSize) System.arraycopy(it[ch][v], 0, flat, v * config.hiddenSize, config.hiddenSize)
                    flat
                }} ?: throw IllegalStateException("audio_emb missing from vieneu_v3_heads.npz")
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
                xvecW = xvecW,
                xvecB = xvecB,
                xvecLnW = xvecLnW,
                xvecLnB = xvecLnB,
                xvecLnEps = xvecLnEps,
                flatAudioEmbeddingsIn = audioFlat
            )
        }

        fun partitionTopK(
            indices: IntArray,
            logits: FloatArray,
            k: Int,
            start: Int = 0,
            end: Int = indices.size - 1
        ) {
            if (k <= 0 || start >= end) return
            val targetIndex = min(start + k - 1, end)
            var left = start
            var right = end

            while (left < right) {
                val mid = (left + right) ushr 1
                val pivotIdx = indices[mid]
                val pivotVal = logits[pivotIdx]

                var i = left
                var j = right
                while (i <= j) {
                    while (logits[indices[i]] > pivotVal || (logits[indices[i]] == pivotVal && indices[i] < pivotIdx)) i++
                    while (logits[indices[j]] < pivotVal || (logits[indices[j]] == pivotVal && indices[j] > pivotIdx)) j--
                    if (i <= j) {
                        val tmp = indices[i]
                        indices[i] = indices[j]
                        indices[j] = tmp
                        i++
                        j--
                    }
                }

                if (targetIndex <= j) {
                    right = j
                } else if (targetIndex >= i) {
                    left = i
                } else {
                    break
                }
            }
        }

        fun sortDescending(
            indices: IntArray,
            logits: FloatArray,
            start: Int,
            end: Int
        ) {
            var left = start
            var right = end
            while (left < right) {
                if (right - left < 16) {
                    for (i in left + 1..right) {
                        val key = indices[i]
                        val keyVal = logits[key]
                        var j = i - 1
                        while (j >= left) {
                            val jVal = logits[indices[j]]
                            if (jVal < keyVal || (jVal == keyVal && indices[j] > key)) {
                                indices[j + 1] = indices[j]
                                j--
                            } else {
                                break
                            }
                        }
                        indices[j + 1] = key
                    }
                    return
                }

                val mid = (left + right) ushr 1
                val pivotIdx = indices[mid]
                val pivotVal = logits[pivotIdx]
                var i = left
                var j = right
                while (i <= j) {
                    while (logits[indices[i]] > pivotVal || (logits[indices[i]] == pivotVal && indices[i] < pivotIdx)) i++
                    while (logits[indices[j]] < pivotVal || (logits[indices[j]] == pivotVal && indices[j] > pivotIdx)) j--
                    if (i <= j) {
                        val tmp = indices[i]
                        indices[i] = indices[j]
                        indices[j] = tmp
                        i++
                        j--
                    }
                }

                if (j - left < right - i) {
                    if (left < j) sortDescending(indices, logits, left, j)
                    left = i
                } else {
                    if (i < right) sortDescending(indices, logits, i, right)
                    right = j
                }
            }
        }

        fun quickSelectTopK(indices: IntArray, logits: FloatArray, k: Int = 256) {
            val effK = min(k, indices.size)
            if (effK <= 0) return
            partitionTopK(indices, logits, effK)
            sortDescending(indices, logits, 0, effK - 1)
        }

        fun quickSelectTopK(logits: FloatArray, k: Int = 256): IntArray {
            val V = logits.size
            val effK = min(k, V)
            if (effK <= 0) return IntArray(0)
            val indices = IntArray(V) { it }
            quickSelectTopK(indices, logits, effK)
            return indices.copyOf(effK)
        }

        fun partitionTopK(logits: FloatArray, k: Int = 256): IntArray {
            return quickSelectTopK(logits, k)
        }
    }
}
