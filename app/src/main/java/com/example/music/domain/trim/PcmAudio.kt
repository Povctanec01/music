package com.example.music.domain.trim

/** Sample-accurate slicing of interleaved 16-bit PCM frames. */
internal object PcmAudio {
    fun slice(
        bytes: ByteArray,
        presentationTimeUs: Long,
        startUs: Long,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        require(sampleRate > 0 && channels > 0)
        val frameSize = channels * 2
        val frames = bytes.size / frameSize
        fun frameAt(timeUs: Long): Int {
            val delta = (timeUs - presentationTimeUs).coerceAtLeast(0L)
            return ((delta * sampleRate + 999_999L) / 1_000_000L)
                .coerceAtMost(frames.toLong()).toInt()
        }
        val start = frameAt(startUs)
        val end = frameAt(endUs).coerceAtLeast(start)
        return bytes.copyOfRange(start * frameSize, end * frameSize)
    }
}
