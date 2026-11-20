package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/**
 * Port of VieNeu-TTS v3 Turbo ONNX Inference Engine to Kotlin.
 */
open class VieNeuOnnxEngine(
    val sessionManager: OnnxSessionManager,
    val config: VieNeuConfig,
    val tokenizer: VieNeuTokenizer,
    val textEmbeddings: Array<FloatArray>,           // (textVocabSize, hiddenSize)
    val audioEmbeddings: Array<FloatArray>,          // (nVq, audioVocabSize * hiddenSize)
    val xvecW: Array<FloatArray>?,                   // (hiddenSize, speakerEmbeddingDim)
    val xvecB: FloatArray?,                          // (hiddenSize)
    val xvecLnW: FloatArray?,                        // (hiddenSize)
    val xvecLnB: FloatArray?,                        // (hiddenSize)
    val xvecLnEps: Float = 1e-5f
) : AutoCloseable {

    @Deprecated("Use flat channel arrays Array<FloatArray> to avoid 16,384 array allocations")
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
        audioEmbeddings = Array(audioEmbeddings.size) { ch ->
            val vSize = audioEmbeddings[ch].size
            val hSize = if (vSize > 0) audioEmbeddings[ch][0].size else config.hiddenSize
            val flat = FloatArray(vSize * hSize)
            for (v in 0 until vSize) {
                System.arraycopy(audioEmbeddings[ch][v], 0, flat, v * hSize, hSize)
            }
            flat
        },
        xvecW = xvecW,
        xvecB = xvecB,
        xvecLnW = xvecLnW,
        xvecLnB = xvecLnB,
        xvecLnEps = xvecLnEps
    )

    val quantizationType: OnnxSessionManager.QuantizationType
        get() = sessionManager.quantizationType

    val executionProvider: String
        get() = sessionManager.executionProvider

    private val lock = ReentrantLock()
    private val env: OrtEnvironment get() = sessionManager.env

    private var closed = false
    val isClosed: Boolean
        get() = closed || sessionManager.isClosed

    @Volatile
    private var isCancelledFlag = false

    @Volatile
    private var currentCancellationSignal: (() -> Boolean)? = null

    @Volatile
    internal var onFrameStep: ((frameIndex: Int) -> Unit)? = null

    fun cancel() {
        isCancelledFlag = true
    }

    fun resetCancellation() {
        isCancelledFlag = false
    }

    // Pre-allocated buffers for topKSample to eliminate per-call allocations
    private var workingLogitsBuffer = FloatArray(1024)
    private var allIndicesBuffer = IntArray(1024) { it }
    private var topKIndicesBuffer = IntArray(256)
    private var topKLogitsBuffer = FloatArray(256)
    private var probsBuffer = DoubleArray(256)

    // Flat audio embeddings direct reference alias (zero memory duplication)
    val flatAudioEmbeddings: Array<FloatArray> get() = audioEmbeddings

    // Pre-allocated channel logits buffer
    private val channelLogitsBuffer: Array<FloatArray> = Array(audioEmbeddings.size) { ch ->
        val vSize = if (config.hiddenSize > 0) audioEmbeddings[ch].size / config.hiddenSize else 1024
        FloatArray(vSize)
    }

    // Reusable single-embedding buffer for acousticFrame decode
    private val acousticEmbBuffer = FloatArray(config.hiddenSize)

    fun copyAudioEmbedding(ch: Int, code: Int, dest: FloatArray, destOffset: Int = 0) {
        val offset = code * config.hiddenSize
        System.arraycopy(audioEmbeddings[ch], offset, dest, destOffset, config.hiddenSize)
    }

    private var cachedEmptyPastK: OnnxTensor? = null
    private var cachedEmptyPastV: OnnxTensor? = null
    private var cachedAcousticPosTensor0: OnnxTensor? = null
    private val cachedAcousticChannelPosTensors = arrayOfNulls<OnnxTensor>(16)
    private var acousticDirectTok0Buffer: FloatBuffer? = null
    private var acousticDirectEmbBuffer: FloatBuffer? = null
    private val acousticFeed0 = HashMap<String, OnnxTensor>(4)
    private val acousticFeed = HashMap<String, OnnxTensor>(4)

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
    fun getOrCreateAcousticPosTensor0(): OnnxTensor {
        val tensor = cachedAcousticPosTensor0
        if (tensor != null) return tensor
        val newTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(0L, 1L)),
            longArrayOf(1, 2)
        )
        cachedAcousticPosTensor0 = newTensor
        return newTensor
    }

    @Synchronized
    fun getOrCreateAcousticChannelPosTensor(ch: Int): OnnxTensor {
        val existing = cachedAcousticChannelPosTensors[ch]
        if (existing != null) return existing
        val newTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf((ch + 1).toLong())),
            longArrayOf(1, 1)
        )
        cachedAcousticChannelPosTensors[ch] = newTensor
        return newTensor
    }

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
        repetitionPenalty: Float = 1.2f,
        cancellationSignal: (() -> Boolean)? = null
    ): FloatArray {
        return inferWithTiming(
            phonemes = phonemes,
            voiceName = voiceName,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty,
            cancellationSignal = cancellationSignal
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
        repetitionPenalty: Float = 1.2f,
        cancellationSignal: (() -> Boolean)? = null
    ): FloatArray {
        return inferWithTiming(
            phonemes = phonemes,
            speakerEmb = speakerEmb,
            refCodes = refCodes,
            temperature = temperature,
            topK = topK,
            topP = topP,
            maxNewFrames = maxNewFrames,
            repetitionPenalty = repetitionPenalty,
            cancellationSignal = cancellationSignal
        ).pcm
    }

    fun inferWithTiming(
        phonemes: String,
        voiceName: String = "Trúc Ly",
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        maxNewFrames: Int = 600,
        repetitionPenalty: Float = 1.2f,
        cancellationSignal: (() -> Boolean)? = null
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
            repetitionPenalty = repetitionPenalty,
            cancellationSignal = cancellationSignal
        )
    }
    internal data class SynthesisPassResult(
        val pcm: FloatArray,
        val frames: List<IntArray>,
        val backboneDurationMs: Long,
        val codecDurationMs: Long
    ) {
        val timing: InferenceTiming
            get() = InferenceTiming(backboneDurationMs, codecDurationMs)
    }

    internal open fun synthesizePass(
        phonemes: String,
        speakerEmb: FloatArray?,
        refCodes: Array<IntArray>?,
        temperature: Float,
        topK: Int,
        topP: Float,
        maxNewFrames: Int,
        repetitionPenalty: Float
    ): SynthesisPassResult {
        val isCancelledCheck = {
            isCancelledFlag ||
            currentCancellationSignal?.invoke() == true ||
            Thread.currentThread().isInterrupted
        }

        if (isCancelledCheck()) {
            throw kotlinx.coroutines.CancellationException("Inference cancelled before prefill")
        }

        val backboneStart = System.currentTimeMillis()
        val anchor = speakerAnchor(speakerEmb)
        val styleId = config.defaultStyleTokenId
        val rows = buildRows(phonemes, refCodes, styleId)
        val promptEmbeds = embedRows(rows, anchor) // shape (1, T, H)
        val tPrompt = rows.size
        val H = config.hiddenSize
        val L = config.numHiddenLayers // 12

        // 1. Prefill Step
        val h = FloatArray(H) // size H (last hidden vector, reused across decode steps)
        val prefillInputs = HashMap<String, OnnxTensor>(1)
        val promptTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(promptEmbeds),
            longArrayOf(1, tPrompt.toLong(), H.toLong())
        )
        prefillInputs["inputs_embeds"] = promptTensor

        val effectiveMaxFrames = min(maxNewFrames, maxExpectedFrames(phonemes))
        val frames = ArrayList<IntArray>()
        val hist = if (kotlin.math.abs(repetitionPenalty - 1.0f) > 1e-4f) {
            val vSize = if (config.hiddenSize > 0) audioEmbeddings[0].size / config.hiddenSize else 1024
            RepetitionHistory(nChannels = config.nVq, window = 64, vocabSize = vSize)
        } else {
            null
        }

        var currentDecodeResult: OrtSession.Result? = null
        try {
            currentDecodeResult = try {
                sessionManager.prefillSession.run(prefillInputs)
            } finally {
                promptTensor.close()
            }

            val hiddenTensor = currentDecodeResult.get(0) as OnnxTensor
            val hiddenFb = hiddenTensor.floatBuffer
            val hiddenFlat = FloatArray(hiddenFb.remaining())
            hiddenFb.get(hiddenFlat)

            // Extract last hidden row: hidden[0, tPrompt-1, :]
            System.arraycopy(hiddenFlat, (tPrompt - 1) * H, h, 0, H)

            // 2. Autoregressive Loop with Zero-Copy KV-Cache Ping-Pong
            val decodeInputs = HashMap<String, OnnxTensor>(2 + 2 * L)

            for (t in 0 until effectiveMaxFrames) {
                if (isCancelledCheck()) {
                    throw kotlinx.coroutines.CancellationException("Inference cancelled at autoregressive frame $t")
                }

                onFrameStep?.invoke(t)

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

                if (isCancelledCheck()) {
                    throw kotlinx.coroutines.CancellationException("Inference cancelled after acoustic frame $t")
                }

                // Build decode step slot: [sgs, codes[0], ..., codes[15]]
                val slotRow = IntArray(config.nVq + 1)
                slotRow[0] = config.speechGenerationStartTokenId
                System.arraycopy(codes, 0, slotRow, 1, config.nVq)

                val slotEmbeds = embedRows(arrayOf(slotRow), anchor) // shape (1, 1, H)
                val seTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(slotEmbeds),
                    longArrayOf(1, 1, H.toLong())
                )
                val posTensor = try {
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(longArrayOf((tPrompt + t).toLong())),
                        longArrayOf(1, 1)
                    )
                } catch (e: Throwable) {
                    seTensor.close()
                    throw e
                }

                decodeInputs.clear()
                decodeInputs["inputs_embeds"] = seTensor
                decodeInputs["position_ids"] = posTensor

                val activeResult = currentDecodeResult!!
                for (i in 0 until L) {
                    decodeInputs["past_k_$i"] = activeResult.get(1 + i) as OnnxTensor
                    decodeInputs["past_v_$i"] = activeResult.get(1 + L + i) as OnnxTensor
                }

                val nextResult: OrtSession.Result = try {
                    sessionManager.decodeSession.run(decodeInputs)
                } finally {
                    seTensor.close()
                    posTensor.close()
                }

                val nextHiddenTensor = nextResult.get(0) as OnnxTensor
                val nextHiddenFb = nextHiddenTensor.floatBuffer
                nextHiddenFb.get(h) // Reusable h array (FloatArray(H)) without allocating

                // Free previous step native memory and advance ping-pong pointer
                activeResult.close()
                currentDecodeResult = nextResult
            }
        } finally {
            currentDecodeResult?.close()
            currentDecodeResult = null
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

        if (isCancelledCheck()) {
            throw kotlinx.coroutines.CancellationException("Inference cancelled before codec decode")
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
        repetitionPenalty: Float = 1.2f,
        cancellationSignal: (() -> Boolean)? = null
    ): EngineInferenceResult = lock.withLock {
        if (isClosed) {
            throw IllegalStateException("VieNeuOnnxEngine is closed")
        }

        isCancelledFlag = false
        currentCancellationSignal = cancellationSignal
        try {
            val isCancelledCheck = {
                isCancelledFlag ||
                cancellationSignal?.invoke() == true ||
                Thread.currentThread().isInterrupted
            }

            if (isCancelledCheck()) {
                throw kotlinx.coroutines.CancellationException("Inference cancelled before start")
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

            // Acoustic tail trimming bypass: attempt direct acoustic cutoff of trailing hallucination bursts
            val trimmedPcm = BabbleGuard.trimBabbleTail(pass0.pcm, syllableCount)
            if (trimmedPcm != null) {
                val trimmedBursts = BabbleGuard.countSpeechBursts(trimmedPcm)
                if (trimmedBursts == syllableCount) {
                    // Successfully trimmed trailing babble! Accept immediately without neural re-inference.
                    return EngineInferenceResult(trimmedPcm, pass0.timing)
                }
            }

            // Suspect babble detected and acoustic trimming was insufficient! Retry loop: up to MAX_RETRIES (2) with varied temperature
            var totalBackboneMs = pass0.backboneDurationMs
            var totalCodecMs = pass0.codecDurationMs

            for (retry in 1..BabbleGuard.MAX_RETRIES) {
                if (isCancelledCheck()) {
                    throw kotlinx.coroutines.CancellationException("Inference cancelled before babble retry $retry")
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
        } finally {
            currentCancellationSignal = null
        }
    }

    internal fun acousticFrame(
        h: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        hist: RepetitionHistory?
    ): Pair<IntArray, Boolean> {
        val H = config.hiddenSize
        val sgs = config.speechGenerationStartTokenId
        val txt = textEmbeddings[sgs] // size H

        // First step: tok = [cond, txt], position_ids = [[0, 1]]
        if (acousticDirectTok0Buffer == null) {
            val bb = ByteBuffer.allocateDirect(2 * H * 4).order(ByteOrder.nativeOrder())
            acousticDirectTok0Buffer = bb.asFloatBuffer()
        }
        val tokFb = acousticDirectTok0Buffer!!
        tokFb.clear()
        tokFb.put(h, 0, H)
        tokFb.put(txt, 0, H)
        tokFb.flip()

        val tokTensor = OnnxTensor.createTensor(env, tokFb, longArrayOf(1, 2, H.toLong()))
        val posTensor0 = getOrCreateAcousticPosTensor0()
        val emptyPastK = getOrCreateEmptyPastK()
        val emptyPastV = getOrCreateEmptyPastV()

        acousticFeed0.clear()
        acousticFeed0["token_emb"] = tokTensor
        acousticFeed0["position_ids"] = posTensor0
        acousticFeed0["past_k_0"] = emptyPastK
        acousticFeed0["past_v_0"] = emptyPastV

        val slot0 = FloatArray(H)
        val vec0 = FloatArray(H)
        val codes = IntArray(config.nVq)

        var currentAcousticResult: OrtSession.Result? = null
        try {
            currentAcousticResult = try {
                sessionManager.acousticSession.run(acousticFeed0)
            } finally {
                tokTensor.close()
                // posTensor0 is cached and reused across frames, do NOT close
            }

            val hiddenTensor = currentAcousticResult.get(0) as OnnxTensor
            val hiddenFb = hiddenTensor.floatBuffer
            hiddenFb.get(slot0)
            hiddenFb.get(vec0)

            // Sample code for channel 0
            codes[0] = sampleCode(0, vec0, temperature, topK, topP, repetitionPenalty, hist)
            hist?.add(0, codes[0])

            val vec = FloatArray(H)

            if (acousticDirectEmbBuffer == null) {
                val bb = ByteBuffer.allocateDirect(H * 4).order(ByteOrder.nativeOrder())
                acousticDirectEmbBuffer = bb.asFloatBuffer()
            }
            val embFb = acousticDirectEmbBuffer!!

            // Subsequent channels 1..15 with Zero-Copy KV-Cache Ping-Pong and persistent position tensors
            for (ch in 1 until config.nVq) {
                val prevCode = codes[ch - 1]
                copyAudioEmbedding(ch - 1, prevCode, acousticEmbBuffer)

                embFb.clear()
                embFb.put(acousticEmbBuffer, 0, H)
                embFb.flip()

                val embTensor = OnnxTensor.createTensor(env, embFb, longArrayOf(1, 1, H.toLong()))
                val posTensor = getOrCreateAcousticChannelPosTensor(ch)
                val activeAcousticResult = currentAcousticResult!!

                acousticFeed.clear()
                acousticFeed["token_emb"] = embTensor
                acousticFeed["position_ids"] = posTensor
                acousticFeed["past_k_0"] = activeAcousticResult.get(1) as OnnxTensor
                acousticFeed["past_v_0"] = activeAcousticResult.get(2) as OnnxTensor

                val nextResult: OrtSession.Result = try {
                    sessionManager.acousticSession.run(acousticFeed)
                } finally {
                    embTensor.close()
                    // posTensor is cached and reused across frames, do NOT close
                }

                val nextHiddenTensor = nextResult.get(0) as OnnxTensor
                val nextHiddenFb = nextHiddenTensor.floatBuffer
                nextHiddenFb.get(vec)

                activeAcousticResult.close()
                currentAcousticResult = nextResult

                codes[ch] = sampleCode(ch, vec, temperature, topK, topP, repetitionPenalty, hist)
                hist?.add(ch, codes[ch])
            }
        } finally {
            currentAcousticResult?.close()
            currentAcousticResult = null
            acousticFeed0.clear()
            acousticFeed.clear()
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
        val audioVocabSize = if (H > 0) audioEmbeddings[ch].size / H else 1024
        val logits = channelLogitsBuffer[ch]
        val flatEmb = audioEmbeddings[ch]

        projectAcousticLogits(vec, flatEmb, logits, H, audioVocabSize)

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
        val audioVocabSize = if (H > 0) audioEmbeddings[ch].size / H else 1024
        val logits = channelLogitsBuffer[ch]
        val flatEmb = audioEmbeddings[ch]

        projectAcousticLogits(vec, flatEmb, logits, H, audioVocabSize)

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
        channel: Int = 0,
        rng: kotlin.random.Random? = null
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
        val r = rng?.nextDouble() ?: Random.nextDouble()
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
                    val chEmb = audioEmbeddings[ch]
                    val audioOffset = audioId * H
                    for (h in 0 until H) {
                        result[offset + h] += chEmb[audioOffset + h]
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

    @Suppress("DEPRECATION")
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            try {
                cachedEmptyPastK?.close()
                cachedEmptyPastK = null
            } catch (_: Exception) {}
            try {
                cachedEmptyPastV?.close()
                cachedEmptyPastV = null
            } catch (_: Exception) {}
            try {
                cachedAcousticPosTensor0?.close()
                cachedAcousticPosTensor0 = null
            } catch (_: Exception) {}
            for (i in cachedAcousticChannelPosTensors.indices) {
                try {
                    cachedAcousticChannelPosTensors[i]?.close()
                    cachedAcousticChannelPosTensors[i] = null
                } catch (_: Exception) {}
            }
            acousticDirectTok0Buffer = null
            acousticDirectEmbBuffer = null
            acousticFeed0.clear()
            acousticFeed.clear()
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
    }

    /**
     * Helper holder for float cache tensors (past_k / past_v).
     * @deprecated Superseded by zero-copy native OnnxTensor ping-pong chaining in Phase 02.
     */
    @Deprecated("Superseded by zero-copy native OnnxTensor ping-pong chaining in Phase 02")
    @Suppress("DEPRECATION")
    data class CacheTensor(
        var shape: LongArray,
        var data: FloatArray
    ) {
        fun toOnnxTensor(env: OrtEnvironment): OnnxTensor {
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }

        fun recycle() {
            Pool.release(this)
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
                    cached
                } else {
                    CacheTensor(shape, data)
                }
            }

            @Synchronized
            fun release(tensor: CacheTensor) {
                if (pool.size < MAX_POOL_SIZE) {
                    tensor.data = FloatArray(0)
                    tensor.shape = LongArray(0)
                    pool.addLast(tensor)
                } else {
                    tensor.data = FloatArray(0)
                    tensor.shape = LongArray(0)
                }
            }

            @Synchronized
            fun clear() {
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
            val audioEmb = headsReader.getChannelArrays("audio_emb")
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

        /**
         * Multi-accumulator unrolled dot product (4 independent accumulators to break CPU pipeline latency).
         */
        fun computeUnrolledDot(vec: FloatArray, flatEmb: FloatArray, offset: Int, H: Int): Float {
            val unrollLimit = H and 3.inv()
            var d0 = 0f
            var d1 = 0f
            var d2 = 0f
            var d3 = 0f
            var h = 0
            while (h < unrollLimit) {
                val o = offset + h
                d0 += vec[h] * flatEmb[o]
                d1 += vec[h + 1] * flatEmb[o + 1]
                d2 += vec[h + 2] * flatEmb[o + 2]
                d3 += vec[h + 3] * flatEmb[o + 3]
                h += 4
            }
            var dot = (d0 + d1) + (d2 + d3)
            while (h < H) {
                dot += vec[h] * flatEmb[offset + h]
                h++
            }
            return dot
        }

        /**
         * Standard sequential dot product for reference and equivalence verification.
         */
        fun computeScalarDot(vec: FloatArray, flatEmb: FloatArray, offset: Int, H: Int): Float {
            var dot = 0f
            for (h in 0 until H) {
                dot += vec[h] * flatEmb[offset + h]
            }
            return dot
        }

        /**
         * Accelerated GEMV (matrix-vector multiplication): logits = flatEmb @ vec
         * where flatEmb is [vocabSize, H] stored in row-major contiguous format,
         * vec is [H], and logits is [vocabSize].
         *
         * Uses 16-element unrolling with 4 multi-accumulators matching the exact IEEE 754
         * associative summation order of the reference 4-accumulator baseline, guaranteeing
         * mathematical equivalence (< 10^-5 absolute error, bitwise 0.0 diff on float addition)
         * while maximizing ART/JVM loop unrolling and CPU instruction-level parallelism.
         */
        fun projectAcousticLogits(
            vec: FloatArray,
            flatEmb: FloatArray,
            logits: FloatArray,
            H: Int,
            vocabSize: Int
        ) {
            val unrollLimit = H and 15.inv()
            for (v in 0 until vocabSize) {
                val offset = v * H
                var d0 = 0f
                var d1 = 0f
                var d2 = 0f
                var d3 = 0f
                var h = 0
                while (h < unrollLimit) {
                    val o = offset + h
                    d0 = (((d0 + vec[h] * flatEmb[o]) + (vec[h + 4] * flatEmb[o + 4]))
                            + (vec[h + 8] * flatEmb[o + 8])) + (vec[h + 12] * flatEmb[o + 12])
                    d1 = (((d1 + vec[h + 1] * flatEmb[o + 1]) + (vec[h + 5] * flatEmb[o + 5]))
                            + (vec[h + 9] * flatEmb[o + 9])) + (vec[h + 13] * flatEmb[o + 13])
                    d2 = (((d2 + vec[h + 2] * flatEmb[o + 2]) + (vec[h + 6] * flatEmb[o + 6]))
                            + (vec[h + 10] * flatEmb[o + 10])) + (vec[h + 14] * flatEmb[o + 14])
                    d3 = (((d3 + vec[h + 3] * flatEmb[o + 3]) + (vec[h + 7] * flatEmb[o + 7]))
                            + (vec[h + 11] * flatEmb[o + 11])) + (vec[h + 15] * flatEmb[o + 15])
                    h += 16
                }
                var dot = (d0 + d1) + (d2 + d3)
                while (h < H) {
                    dot += vec[h] * flatEmb[offset + h]
                    h++
                }
                logits[v] = dot
            }
        }

        /**
         * Single row accelerated dot product matching projectAcousticLogits summation pattern.
         */
        fun computeAcceleratedDot(vec: FloatArray, flatEmb: FloatArray, offset: Int, H: Int): Float {
            val unrollLimit = H and 15.inv()
            var d0 = 0f
            var d1 = 0f
            var d2 = 0f
            var d3 = 0f
            var h = 0
            while (h < unrollLimit) {
                val o = offset + h
                d0 = (((d0 + vec[h] * flatEmb[o]) + (vec[h + 4] * flatEmb[o + 4]))
                        + (vec[h + 8] * flatEmb[o + 8])) + (vec[h + 12] * flatEmb[o + 12])
                d1 = (((d1 + vec[h + 1] * flatEmb[o + 1]) + (vec[h + 5] * flatEmb[o + 5]))
                        + (vec[h + 9] * flatEmb[o + 9])) + (vec[h + 13] * flatEmb[o + 13])
                d2 = (((d2 + vec[h + 2] * flatEmb[o + 2]) + (vec[h + 6] * flatEmb[o + 6]))
                        + (vec[h + 10] * flatEmb[o + 10])) + (vec[h + 14] * flatEmb[o + 14])
                d3 = (((d3 + vec[h + 3] * flatEmb[o + 3]) + (vec[h + 7] * flatEmb[o + 7]))
                        + (vec[h + 11] * flatEmb[o + 11])) + (vec[h + 15] * flatEmb[o + 15])
                h += 16
            }
            var dot = (d0 + d1) + (d2 + d3)
            while (h < H) {
                dot += vec[h] * flatEmb[offset + h]
                h++
            }
            return dot
        }
    }
}
