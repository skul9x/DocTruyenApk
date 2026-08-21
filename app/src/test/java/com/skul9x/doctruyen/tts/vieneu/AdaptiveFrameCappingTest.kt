package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class AdaptiveFrameCappingTest {

    @Test
    fun testAdaptiveFrameCappingMonotonicAndBounded() {
        val short = VieNeuOnnxEngine.maxExpectedFrames("aaa bbb ccc")
        val long = VieNeuOnnxEngine.maxExpectedFrames("aaa bbb ccc ".repeat(30))

        assertTrue(
            "Short cap ($short) must be greater than slack (${VieNeuOnnxEngine.FRAME_CAP_SLACK})",
            short > VieNeuOnnxEngine.FRAME_CAP_SLACK
        )
        assertTrue(
            "Long cap ($long) must be greater than short cap ($short)",
            long > short
        )
        assertTrue(
            "Long text frame cap ($long) should exceed 300 so normal chunks are not constrained",
            long > 300
        )
    }

    @Test
    fun testSingleWordHardCap() {
        // Single word <= 24 phonemes -> hard cap 13 frames (~1.0s @ 12.5 fps)
        assertEquals(
            VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES,
            VieNeuOnnxEngine.maxExpectedFrames("tʃˈaː2w.")
        )
        assertEquals(
            VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES,
            VieNeuOnnxEngine.maxExpectedFrames("")
        )
        assertEquals(
            VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES,
            VieNeuOnnxEngine.maxExpectedFrames("   ")
        )

        // Multiple words -> linear formula (not capped at 13)
        assertTrue(
            "Multi-word phrase should exceed single word max frames",
            VieNeuOnnxEngine.maxExpectedFrames("tʃˈaː2w bˈaː6n.") > VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES
        )

        // Emotion cue present -> single word hard cap does not apply (laughter/sigh takes frames)
        assertTrue(
            "Emotion cue with single word should bypass single word max frames",
            VieNeuOnnxEngine.maxExpectedFrames("<|emotion_1|>tʃˈaː2w.") > VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES
        )

        // Abnormally long 'word' (> 24 chars due to stuck normalization) -> fallback to linear formula
        assertTrue(
            "Word longer than 24 chars should bypass single word max frames",
            VieNeuOnnxEngine.maxExpectedFrames("a".repeat(30)) > VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES
        )
    }

    @Test
    fun testMarkupAndEmotionTokensStrippedFromLength() {
        // <en> tags stripped from length calculation
        assertEquals(
            VieNeuOnnxEngine.maxExpectedFrames("abc def"),
            VieNeuOnnxEngine.maxExpectedFrames("<en>abc def</en>")
        )
        assertEquals(
            VieNeuOnnxEngine.maxExpectedFrames("ab cd ef"),
            VieNeuOnnxEngine.maxExpectedFrames("<en>ab cd</en> ef")
        )

        // Emotion tokens stripped from length calculation (though emotion token presence disables single-word 13 cap)
        // Multi-word phrase with and without emotion token should yield identical linear cap
        val phrase = "tʃˈaː2w bˈaː6n hˈoːm nˈaːj."
        val phraseWithEmotion = "<|emotion_2|>$phrase"
        assertEquals(
            VieNeuOnnxEngine.maxExpectedFrames(phrase),
            VieNeuOnnxEngine.maxExpectedFrames(phraseWithEmotion)
        )
    }

    @Test
    fun testEffectiveMaxFramesFormula() {
        val maxNewFramesDefault = 600

        // Short single word cap clamped to 13
        val singleWord = "tʃˈaː2w."
        val effectiveSingleWord = min(maxNewFramesDefault, VieNeuOnnxEngine.maxExpectedFrames(singleWord))
        assertEquals(13, effectiveSingleWord)

        // Medium sentence (~50 phonemes -> 24 + 100 = 124 frames)
        val mediumPhonemes = "kˈaː3w kˈeː5w tʃˈiə6w tʃˈiə6w trˈeːn kˈeː4ɲ rˈaːŋ."
        val expectedMedium = 24 + kotlin.math.ceil(2.0 * mediumPhonemes.length).toInt()
        val effectiveMedium = min(maxNewFramesDefault, VieNeuOnnxEngine.maxExpectedFrames(mediumPhonemes))
        assertEquals(expectedMedium, effectiveMedium)
        assertTrue(effectiveMedium < maxNewFramesDefault)

        // Very long input capped by maxNewFrames
        val veryLongPhonemes = "kˈaː3w kˈeː5w ".repeat(100)
        val effectiveVeryLong = min(maxNewFramesDefault, VieNeuOnnxEngine.maxExpectedFrames(veryLongPhonemes))
        assertEquals(maxNewFramesDefault, effectiveVeryLong)
    }
}
