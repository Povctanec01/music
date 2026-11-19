package com.example.music.domain.trim

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmAudioTest {
    @Test fun clipsBothEdgesOnCompleteStereoFrames() {
        val input = ByteArray(40) { it.toByte() } // 10 stereo frames, 1 ms each
        assertArrayEquals(input.copyOfRange(8, 24), PcmAudio.slice(input, 0, 2_000, 6_000, 1_000, 2))
    }

    @Test fun excludesBuffersBeforeAndAfterSelection() {
        val input = ByteArray(20)
        assertArrayEquals(byteArrayOf(), PcmAudio.slice(input, 0, 20_000, 30_000, 1_000, 1))
        assertArrayEquals(byteArrayOf(), PcmAudio.slice(input, 40_000, 20_000, 30_000, 1_000, 1))
    }

    @Test fun roundsFractionalBoundariesToSampleFrames() {
        val input = ByteArray(20) { it.toByte() }
        assertArrayEquals(input.copyOfRange(6, 14), PcmAudio.slice(input, 10_000, 12_500, 16_500, 1_000, 1))
    }
}
