package com.skul9x.doctruyen.tts.vieneu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * Minimal high-performance NPZ / NPY parser for NumPy float32 tensors.
 * Streams float data directly from zip entries into multidimensional channel arrays
 * or flat buffers using a reusable 64KB ByteBuffer without monolithic intermediate byte allocations.
 */
class NpzReader private constructor(
    private val arrays: Map<String, NpyArray>
) {
    data class NpyArray(
        val shape: IntArray,
        private val rawData: FloatArray? = null,
        val channels: Array<FloatArray>? = null
    ) {
        private var cachedFlat: FloatArray? = rawData

        val data: FloatArray
            get() {
                var flat = cachedFlat
                if (flat == null) {
                    val chs = channels
                    if (chs != null) {
                        val total = chs.sumOf { it.size }
                        val combined = FloatArray(total)
                        var offset = 0
                        for (ch in chs) {
                            System.arraycopy(ch, 0, combined, offset, ch.size)
                            offset += ch.size
                        }
                        flat = combined
                        cachedFlat = combined
                    } else {
                        flat = FloatArray(0)
                        cachedFlat = flat
                    }
                }
                return flat
            }

        val size: Int get() = channels?.sumOf { it.size } ?: (cachedFlat?.size ?: 0)

        constructor(shape: IntArray, data: FloatArray) : this(shape, rawData = data, channels = null)
    }

    val keys: Set<String> get() = arrays.keys

    fun has(key: String): Boolean = arrays.containsKey(key)

    fun getArray(key: String): NpyArray? = arrays[key]

    fun getFloatArray(key: String): FloatArray? = arrays[key]?.data

    fun getFlat(key: String): FloatArray? = arrays[key]?.data

    fun getShape(key: String): IntArray? = arrays[key]?.shape

    fun getScalarFloat(key: String): Float? {
        val arr = arrays[key] ?: return null
        if (arr.channels != null && arr.channels.isNotEmpty() && arr.channels[0].isNotEmpty()) {
            return arr.channels[0][0]
        }
        return if (arr.data.isNotEmpty()) arr.data[0] else null
    }

    fun get2D(key: String): Array<FloatArray>? {
        val arr = arrays[key] ?: return null
        val shape = arr.shape
        if (shape.size != 2) return null
        val rows = shape[0]
        val cols = shape[1]
        val result = Array(rows) { FloatArray(cols) }
        var offset = 0
        val flat = arr.data
        for (r in 0 until rows) {
            System.arraycopy(flat, offset, result[r], 0, cols)
            offset += cols
        }
        return result
    }

    fun getChannelArrays(key: String): Array<FloatArray>? {
        val arr = arrays[key] ?: return null
        if (arr.channels != null) {
            return arr.channels
        }
        val shape = arr.shape
        if (shape.size != 3) return null
        val d0 = shape[0]
        val d1 = shape[1]
        val d2 = shape[2]
        val stride = d1 * d2
        if (arr.data.size < d0 * stride) return null
        val result = Array(d0) { FloatArray(stride) }
        for (ch in 0 until d0) {
            System.arraycopy(arr.data, ch * stride, result[ch], 0, stride)
        }
        return result
    }

    @Deprecated(
        message = "Use getChannelArrays or getFlat to avoid allocating 16,384 separate objects",
        replaceWith = ReplaceWith("getChannelArrays(key)")
    )
    fun get3D(key: String): Array<Array<FloatArray>>? {
        val arr = arrays[key] ?: return null
        val shape = arr.shape
        if (shape.size != 3) return null
        val d0 = shape[0]
        val d1 = shape[1]
        val d2 = shape[2]
        val chs = arr.channels
        if (chs != null && chs.size == d0) {
            return Array(d0) { ch ->
                Array(d1) { row ->
                    FloatArray(d2).also { dest ->
                        System.arraycopy(chs[ch], row * d2, dest, 0, d2)
                    }
                }
            }
        }
        val flat = arr.data
        val result = Array(d0) { Array(d1) { FloatArray(d2) } }
        var offset = 0
        for (i in 0 until d0) {
            for (j in 0 until d1) {
                System.arraycopy(flat, offset, result[i][j], 0, d2)
                offset += d2
            }
        }
        return result
    }

    companion object {
        const val STREAM_BUFFER_SIZE = 64 * 1024
        private val SHAPE_PATTERN = Pattern.compile("""['"]shape['"]\s*:\s*\((.*?)\)""")

        fun read(inputStream: InputStream): NpzReader {
            val map = HashMap<String, NpyArray>()
            val byteBuffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val rawBuffer = byteBuffer.array()

            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val key = if (name.endsWith(".npy")) name.substring(0, name.length - 4) else name
                    val npy = readNpyFromStream(zis, byteBuffer, rawBuffer)
                    if (npy != null) {
                        map[key] = npy
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return NpzReader(map)
        }

        fun read(file: File): NpzReader {
            return FileInputStream(file).use { read(it) }
        }

        private fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
            var total = 0
            while (total < length) {
                val count = inputStream.read(buffer, offset + total, length - total)
                if (count == -1) break
                total += count
            }
            return total
        }

        @androidx.annotation.VisibleForTesting
        internal fun readNpyFromStream(
            stream: InputStream,
            bb: ByteBuffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN),
            rawBuffer: ByteArray = bb.array()
        ): NpyArray? {
            val headerPrefix = ByteArray(10)
            val readPrefix = readFully(stream, headerPrefix, 0, 10)
            if (readPrefix < 10) return null

            // Check magic \x93NUMPY
            if (headerPrefix[0] != 0x93.toByte() ||
                headerPrefix[1] != 'N'.code.toByte() ||
                headerPrefix[2] != 'U'.code.toByte() ||
                headerPrefix[3] != 'M'.code.toByte() ||
                headerPrefix[4] != 'P'.code.toByte() ||
                headerPrefix[5] != 'Y'.code.toByte()
            ) {
                return null
            }

            val major = headerPrefix[6].toInt() and 0xFF
            val headerLen: Int

            if (major == 1) {
                headerLen = (headerPrefix[8].toInt() and 0xFF) or ((headerPrefix[9].toInt() and 0xFF) shl 8)
            } else {
                val lenRest = ByteArray(2)
                val readRest = readFully(stream, lenRest, 0, 2)
                if (readRest < 2) return null
                val b0 = headerPrefix[8].toInt() and 0xFF
                val b1 = headerPrefix[9].toInt() and 0xFF
                val b2 = lenRest[0].toInt() and 0xFF
                val b3 = lenRest[1].toInt() and 0xFF
                headerLen = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }

            if (headerLen <= 0 || headerLen > 65536) return null

            val headerBytes = ByteArray(headerLen)
            val readH = readFully(stream, headerBytes, 0, headerLen)
            if (readH < headerLen) return null

            val headerStr = String(headerBytes, Charsets.US_ASCII)
            val shape = parseShape(headerStr)

            val chunkFloats = rawBuffer.size / 4

            if (shape.size == 3) {
                val d0 = shape[0]
                val d1 = shape[1]
                val d2 = shape[2]
                val stride = d1 * d2
                val channels = Array(d0) { FloatArray(stride) }

                for (ch in 0 until d0) {
                    val dest = channels[ch]
                    var channelOffset = 0
                    var remainingInChannel = stride
                    while (remainingInChannel > 0) {
                        val toReadFloats = minOf(chunkFloats, remainingInChannel)
                        val bytesToRead = toReadFloats * 4
                        val readBytes = readFully(stream, rawBuffer, 0, bytesToRead)
                        if (readBytes < bytesToRead) {
                            break
                        }
                        bb.position(0).limit(bytesToRead)
                        bb.asFloatBuffer().get(dest, channelOffset, toReadFloats)
                        channelOffset += toReadFloats
                        remainingInChannel -= toReadFloats
                    }
                }
                return NpyArray(shape = shape, rawData = null, channels = channels)
            } else {
                val totalElements = if (shape.isEmpty()) 1 else shape.fold(1) { acc, d -> acc * d }
                val data = FloatArray(totalElements)
                var offset = 0
                var remaining = totalElements
                while (remaining > 0) {
                    val toReadFloats = minOf(chunkFloats, remaining)
                    val bytesToRead = toReadFloats * 4
                    val readBytes = readFully(stream, rawBuffer, 0, bytesToRead)
                    if (readBytes < bytesToRead) {
                        break
                    }
                    bb.position(0).limit(bytesToRead)
                    bb.asFloatBuffer().get(data, offset, toReadFloats)
                    offset += toReadFloats
                    remaining -= toReadFloats
                }
                return NpyArray(shape = shape, rawData = data, channels = null)
            }
        }

        fun parseNpy(bytes: ByteArray): NpyArray? {
            val bb = ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val rawBuffer = bb.array()
            return ByteArrayInputStream(bytes).use { bais ->
                readNpyFromStream(bais, bb, rawBuffer)
            }
        }

        private fun parseShape(header: String): IntArray {
            val matcher = SHAPE_PATTERN.matcher(header)
            if (!matcher.find()) return intArrayOf()
            val inside = matcher.group(1)?.trim() ?: return intArrayOf()
            if (inside.isEmpty()) return intArrayOf()

            val parts = inside.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return parts.map { it.toInt() }.toIntArray()
        }

        fun readAllBytes(inputStream: InputStream, expectedSize: Long = -1L): ByteArray {
            if (expectedSize > 0 && expectedSize <= Int.MAX_VALUE) {
                val size = expectedSize.toInt()
                val bytes = ByteArray(size)
                var totalRead = 0
                while (totalRead < size) {
                    val read = inputStream.read(bytes, totalRead, size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                return if (totalRead == size) bytes else bytes.copyOf(totalRead)
            }
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                baos.write(buffer, 0, len)
            }
            return baos.toByteArray()
        }
    }
}
