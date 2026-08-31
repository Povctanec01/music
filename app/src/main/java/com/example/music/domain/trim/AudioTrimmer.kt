package com.example.music.domain.trim

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode audio, slice a range, and encode 16-bit PCM WAV (mirrors web trimAudio.ts). */
object AudioTrimmer {

    suspend fun trimToWav(
        context: Context,
        uri: Uri,
        startSec: Float,
        endSec: Float,
    ): ByteArray = withContext(Dispatchers.Default) {
        require(endSec > startSec) { "Неверный диапазон обрезки" }
        val startUs = (startSec * 1_000_000f).toLong()
        val endUs = (endSec * 1_000_000f).toLong()

        val extractor = MediaExtractor()
        try {
            when (uri.scheme) {
                "file" -> extractor.setDataSource(uri.path ?: error("Пустой путь к файлу"))
                else -> extractor.setDataSource(context, uri, null)
            }
            val trackIndex = findAudioTrack(extractor) ?: error("Аудиодорожка не найдена")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный формат аудио")

            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val pcm = ByteArrayOutputStream()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                if (pts > endUs) {
                                    decoder.queueInputBuffer(
                                        inIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, size, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> {
                            if (outIndex >= 0) {
                                if (bufferInfo.presentationTimeUs >= endUs) {
                                    outputDone = true
                                } else if (bufferInfo.size > 0 &&
                                    bufferInfo.presentationTimeUs >= startUs - 100_000L
                                ) {
                                    val outBuffer = decoder.getOutputBuffer(outIndex)!!
                                    pcm.write(outBuffer.getBytes(bufferInfo))
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }

                if (pcm.size() == 0) error("Не удалось декодировать выбранный фрагмент")
                encodeWav(pcm.toByteArray(), sampleRate, channels)
            } finally {
                runCatching {
                    decoder.stop()
                    decoder.release()
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun ByteBuffer.getBytes(info: MediaCodec.BufferInfo): ByteArray {
        val chunk = ByteArray(info.size)
        position(info.offset)
        limit(info.offset + info.size)
        get(chunk)
        return chunk
    }

    private fun encodeWav(pcm16le: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bytesPerSample = 2
        val blockAlign = channels * bytesPerSample
        val dataSize = pcm16le.size
        val out = ByteArray(44 + dataSize)
        val view = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        fun writeString(offset: Int, value: String) {
            for (i in value.indices) out[offset + i] = value[i].code.toByte()
        }

        writeString(0, "RIFF")
        view.putInt(4, 36 + dataSize)
        writeString(8, "WAVE")
        writeString(12, "fmt ")
        view.putInt(16, 16)
        view.putShort(20, 1)
        view.putShort(22, channels.toShort())
        view.putInt(24, sampleRate)
        view.putInt(28, sampleRate * blockAlign)
        view.putShort(32, blockAlign.toShort())
        view.putShort(34, (bytesPerSample * 8).toShort())
        writeString(36, "data")
        view.putInt(40, dataSize)
        System.arraycopy(pcm16le, 0, out, 44, dataSize)
        return out
    }

    private const val TIMEOUT_US = 10_000L
}
