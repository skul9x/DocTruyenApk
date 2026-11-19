package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPipelineChannelConcurrencyTest {

    @Test
    fun testAudioDispatcherWorkerThreadProperties() = runBlocking {
        val pipeline = TTSAudioPipeline()
        try {
            val threadName = withContext(pipeline.audioDispatcher) {
                Thread.currentThread().name
            }
            assertTrue(
                "Thread name must start with AudioTrackWorkerThread, got: $threadName",
                threadName.startsWith("AudioTrackWorkerThread")
            )
        } finally {
            pipeline.release()
        }
    }

    @Test
    fun testChannelBackpressureCapacity() = runBlocking {
        val pipeline = TTSAudioPipeline()
        try {
            val channel = pipeline.chunkChannel

            // 1. Send 4 chunks to channel - all should be accepted without suspending
            for (i in 0 until 4) {
                val sendResult = channel.trySend(Pair(i, ShortArray(100)))
                assertTrue("Chunk $i must be accepted immediately by channel of capacity 4", sendResult.isSuccess)
            }

            // 2. Attempt to send 5th chunk synchronously - must fail because buffer is full
            val fifthTry = channel.trySend(Pair(4, ShortArray(100)))
            assertFalse("5th chunk trySend must fail as capacity 4 is fully saturated", fifthTry.isSuccess)

            // 3. Launch suspending send in coroutine
            var fifthSent = false
            val producerJob = launch(Dispatchers.Default) {
                channel.send(Pair(4, ShortArray(100)))
                fifthSent = true
            }

            // Allow event loop to run; 5th send must remain suspended
            delay(50)
            assertFalse("5th send() must suspend while capacity 4 is full", fifthSent)

            // 4. Consume one chunk to open up a buffer slot
            val received = channel.receive()
            assertEquals(0, received.first)

            // 5. Producer should resume and complete send
            withTimeout(1000) {
                producerJob.join()
            }
            assertTrue("5th send() must resume once a slot is freed", fifthSent)
        } finally {
            pipeline.release()
        }
    }

    @Test
    fun testPassiveReceiveSuspension() = runBlocking {
        val pipeline = TTSAudioPipeline()
        try {
            val channel = pipeline.chunkChannel
            assertTrue("Channel should be initially empty", channel.isEmpty)

            var received = false
            var receivedIndex = -1

            val consumerJob = launch(pipeline.audioDispatcher) {
                val chunk = channel.receive()
                receivedIndex = chunk.first
                received = true
            }

            // Verify consumer is suspended waiting passively without burning CPU
            delay(80)
            assertFalse("Consumer must remain suspended while channel is empty", received)
            assertTrue("Consumer coroutine must still be active and waiting", consumerJob.isActive)

            // Producer sends chunk
            channel.send(Pair(99, ShortArray(100)))

            withTimeout(1000) {
                consumerJob.join()
            }
            assertTrue("Consumer must resume passively as soon as chunk arrives", received)
            assertEquals(99, receivedIndex)
        } finally {
            pipeline.release()
        }
    }

    @Test
    fun testImmediateCancellationResponsiveness() = runBlocking {
        val slowPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                Thread.sleep(150)
                return size
            }
        }

        val pipeline = TTSAudioPipeline(audioPlayer = slowPlayer)
        try {
            val chunks = listOf(
                TextChunk(index = 0, text = "Câu thứ nhất để kiểm tra khả năng dừng ngay lập tức.", pauseType = PauseType.SENTENCE_BREAK),
                TextChunk(index = 1, text = "Câu thứ hai để kiểm tra khi hủy kênh phát.", pauseType = PauseType.SENTENCE_BREAK),
                TextChunk(index = 2, text = "Câu thứ ba kết thúc kiểm tra.", pauseType = PauseType.SENTENCE_BREAK)
            )

            pipeline.startReadingChunks(chunks)
            delay(40)

            // Measure pause responsiveness
            val pauseStart = System.currentTimeMillis()
            pipeline.pause()
            val pauseDuration = System.currentTimeMillis() - pauseStart

            assertTrue("Pipeline pause must terminate within < 50ms, took ${pauseDuration}ms", pauseDuration < 50)
            assertEquals(TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)

            // Resume and measure stop responsiveness
            pipeline.resume()
            delay(30)

            val stopStart = System.currentTimeMillis()
            pipeline.stop()
            val stopDuration = System.currentTimeMillis() - stopStart

            assertTrue("Pipeline stop must terminate within < 50ms, took ${stopDuration}ms", stopDuration < 50)
            assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
        } finally {
            pipeline.release()
        }
    }

    @Test
    fun testNormalCompletionOnChannelClose() = runBlocking {
        val states = Collections.synchronizedList(mutableListOf<TTSAudioPipeline.PipelineState>())

        val fastPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            audioPlayer = fastPlayer,
            preBufferChunkCount = 2
        )
        try {
            val chunks = (0 until 5).map { idx ->
                TextChunk(index = idx, text = "Nội dung thử nghiệm cho câu số $idx.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 0)
            }

            pipeline.startReadingChunks(
                chunkList = chunks,
                onStateChange = { state -> states.add(state) },
                onProgress = { _ -> }
            )

            // Wait for all 5 chunks to synthesize, buffer, stream, and complete to Idle
            withTimeout(5000) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    delay(50)
                }
            }

            assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
            assertEquals(5, pipeline.currentChunkIndex)
            assertTrue("Must have transitioned through Playing state", states.any { it is TTSAudioPipeline.PipelineState.Playing })
        } finally {
            pipeline.release()
        }
    }

    @Test
    fun testSlowConsumerSynthesisBackpressure() = runBlocking {
        val consumedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val pacedPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                Thread.sleep(40)
                consumedCount.incrementAndGet()
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            audioPlayer = pacedPlayer,
            preBufferChunkCount = 1
        )
        try {
            val chunks = (0 until 6).map { idx ->
                TextChunk(index = idx, text = "Câu số $idx cần được đọc với tốc độ điều tiết tự nhiên.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 0)
            }

            pipeline.startReadingChunks(chunks)

            withTimeout(5000) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    delay(50)
                }
            }

            assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
            assertEquals(6, consumedCount.get())
        } finally {
            pipeline.release()
        }
    }
}
