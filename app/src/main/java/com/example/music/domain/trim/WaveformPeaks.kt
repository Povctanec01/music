package com.example.music.domain.trim

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

object WaveformPeaks {
    suspend fun load(context: Context, uri: Uri, barCount: Int = 600): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, context, uri)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return@withContext placeholderPeaks(barCount)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext placeholderPeaks(barCount)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val estimatedSamples = max(1L, durationUs * sampleRate / 1_000_000L)

            val peaks = FloatArray(barCount)
            var sampleIndex = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleRetries = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: break
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            idleRetries += 1
                            if (idleRetries > MAX_IDLE_RETRIES) outputDone = true
                        }
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                    else -> if (outIndex >= 0) {
                        idleRetries = 0
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = ShortArray(bufferInfo.size / 2)
                            outBuffer.asShortBuffer().get(shorts)
                            for (sample in shorts) {
                                val bar = ((sampleIndex * barCount) / estimatedSamples)
                                    .toInt()
                                    .coerceIn(0, barCount - 1)
                                val amp = abs(sample.toFloat() / Short.MAX_VALUE)
                                peaks[bar] = max(peaks[bar], amp)
                                sampleIndex += 1
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()

            if (sampleIndex == 0L) return@withContext placeholderPeaks(barCount)

            val peakMax = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
            FloatArray(barCount) { index -> peaks[index] / peakMax }
        } catch (_: Exception) {
            placeholderPeaks(barCount)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun setDataSource(extractor: MediaExtractor, context: Context, uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Пустой путь к файлу")
            extractor.setDataSource(path)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun placeholderPeaks(barCount: Int): FloatArray {
        return FloatArray(barCount) { index ->
            val t = index.toFloat() / barCount
            (0.25f + 0.55f * abs(kotlin.math.sin(t * 18f))).coerceIn(0.1f, 1f)
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_RETRIES = 100
}
