package com.example.music.ui.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

private val EditorBg = Color(0xFF0A1A33)
private val WaveBg = Color(0x66122E52)
private val WaveActive = Color(0xFF39E06A)
private val WaveDim = Color(0x4739E06A)
private val HandleColor = Color(0xFF2DE2E6)
private val DimOverlay = Color(0x73000000)

@Composable
fun WaveformTrimEditor(
    fileName: String,
    durationSec: Float,
    startSec: Float,
    endSec: Float,
    currentSec: Float,
    peaks: FloatArray,
    loadingPeaks: Boolean,
    busy: Boolean,
    playing: Boolean,
    onStartChange: (Float) -> Unit,
    onEndChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = EditorBg,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = Color(0x1F2DE2E6), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "✂  Обрезка",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color(0xFFF3F7FF),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onReset, enabled = !busy) { Text("↺ Сбросить") }
                    TextButton(onClick = onClose, enabled = !busy) { Text("×") }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(220.dp),
            ) {
                WaveformCanvas(
                    modifier = Modifier.fillMaxSize(),
                    fileName = fileName,
                    durationSec = durationSec,
                    startSec = startSec,
                    endSec = endSec,
                    currentSec = currentSec,
                    peaks = peaks,
                    enabled = !busy,
                    onStartChange = onStartChange,
                    onEndChange = onEndChange,
                    onSeek = onSeek,
                )
                if (loadingPeaks) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onTogglePlay, enabled = !busy && !loadingPeaks) {
                        Text(if (playing) "⏸" else "▶")
                    }
                    Text(
                        "${formatTimePrecise(startSec)} – ${formatTimePrecise(endSec)}",
                        color = Color(0xFF2DE2E6),
                    )
                }
                Button(onClick = onSave, enabled = !busy && endSec > startSec) {
                    Text(if (busy) "Сохранение…" else "Сохранить")
                }
            }
        }
    }
}

@Composable
private fun WaveformCanvas(
    modifier: Modifier,
    fileName: String,
    durationSec: Float,
    startSec: Float,
    endSec: Float,
    currentSec: Float,
    peaks: FloatArray,
    enabled: Boolean,
    onStartChange: (Float) -> Unit,
    onEndChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val handleWidthPx = with(density) { 48.dp.toPx() }

        fun secToX(sec: Float) = if (durationSec <= 0f) 0f else (sec / durationSec) * widthPx
        fun xToSec(x: Float) = if (widthPx <= 0f || durationSec <= 0f) 0f else (x / widthPx) * durationSec

        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(color = WaveBg, cornerRadius = CornerRadius(14f, 14f))

            val midY = size.height / 2f
            val maxBarH = size.height / 2f - 18f
            val barW = if (peaks.isNotEmpty()) size.width / peaks.size else 1f
            val startX = secToX(startSec)
            val endX = secToX(endSec)
            val playX = secToX(currentSec)

            fun drawWave(color: Color, clipStart: Float, clipEnd: Float) {
                clipRect(left = clipStart, top = 0f, right = clipEnd, bottom = size.height) {
                    peaks.forEachIndexed { index, peak ->
                        val x = index * barW
                        val h = peak * maxBarH
                        drawRect(
                            color = color,
                            topLeft = Offset(x, midY - h),
                            size = Size(max(1f, barW), h * 2f),
                        )
                    }
                }
            }

            drawWave(WaveDim, 0f, size.width)
            drawWave(WaveActive, startX, endX)
            drawRect(color = DimOverlay, topLeft = Offset(0f, 0f), size = Size(startX, size.height))
            drawRect(color = DimOverlay, topLeft = Offset(endX, 0f), size = Size(size.width - endX, size.height))

            fun drawHandle(x: Float) {
                drawRect(color = HandleColor, topLeft = Offset(x - 3f, 8f), size = Size(6f, size.height - 16f))
            }

            drawHandle(startX)
            drawHandle(endX)

            drawLine(
                color = Color.White,
                start = Offset(playX, 10f),
                end = Offset(playX, size.height - 10f),
                strokeWidth = 2f,
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(playX - 28f, 2f),
                size = Size(56f, 22f),
                cornerRadius = CornerRadius(11f, 11f),
            )
        }

        if (enabled && durationSec > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationSec, startSec, endSec) {
                        detectTapGestures { offset ->
                            onSeek(xToSec(offset.x).coerceIn(startSec, endSec))
                        }
                    },
            )
            TrimHandle(
                centerX = secToX(startSec),
                widthPx = widthPx,
                handleWidthPx = handleWidthPx,
                onDrag = { x -> onStartChange(xToSec(x).coerceAtMost(endSec - 0.05f)) },
            )
            TrimHandle(
                centerX = secToX(endSec),
                widthPx = widthPx,
                handleWidthPx = handleWidthPx,
                onDrag = { x -> onEndChange(xToSec(x).coerceAtLeast(startSec + 0.05f)) },
            )
            TrimHandle(
                centerX = secToX(currentSec),
                widthPx = widthPx,
                handleWidthPx = handleWidthPx,
                onDrag = { x -> onSeek(xToSec(x).coerceIn(startSec, endSec)) },
            )
        }

        Text(
            text = fileName,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 12.dp, end = 12.dp),
            color = Color(0xB8DBE6FF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = formatTimePrecise(startSec),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            color = HandleColor,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = formatTimePrecise(endSec),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            color = HandleColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TrimHandle(
    centerX: Float,
    widthPx: Float,
    handleWidthPx: Float,
    onDrag: (Float) -> Unit,
) {
    val half = handleWidthPx / 2f
    val left = (centerX - half).coerceIn(0f, max(0f, widthPx - handleWidthPx))
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), 0) }
            .width(with(LocalDensity.current) { handleWidthPx.toDp() })
            .fillMaxHeight()
            .pointerInput(centerX) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onDrag(change.position.x.coerceIn(0f, widthPx))
                }
            },
    )
}

private fun formatTimePrecise(sec: Float): String {
    if (!sec.isFinite() || sec < 0f) return "00:00.0"
    val minutes = (sec / 60f).toInt()
    val seconds = sec % 60f
    val tenths = ((seconds * 10f).roundToInt() % 10).coerceIn(0, 9)
    val wholeSeconds = seconds.toInt()
    return "${minutes.toString().padStart(2, '0')}:${wholeSeconds.toString().padStart(2, '0')}.$tenths"
}
