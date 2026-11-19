/** Decode audio and build normalized peak bars for waveform rendering. */

export async function decodeWaveformPeaks(blob: Blob, barCount = 600): Promise<number[]> {
  const buffer = await blob.arrayBuffer()
  const ctx = new AudioContext()
  let decoded: AudioBuffer
  try {
    decoded = await ctx.decodeAudioData(buffer.slice(0))
  } finally {
    await ctx.close()
  }

  const channel = decoded.getChannelData(0)
  if (!Number.isInteger(barCount) || barCount <= 0) throw new Error('Неверное число столбцов')
  const peaks: number[] = []

  for (let i = 0; i < barCount; i += 1) {
    const start = Math.floor(i * channel.length / barCount)
    const end = Math.min(Math.max(start + 1, Math.floor((i + 1) * channel.length / barCount)), channel.length)
    let max = 0
    for (let j = start; j < end; j += 1) {
      max = Math.max(max, Math.abs(channel[j]))
    }
    peaks.push(max)
  }

  const peakMax = Math.max(...peaks, 0.001)
  return peaks.map((value) => value / peakMax)
}

export function formatTimePrecise(sec: number) {
  if (!Number.isFinite(sec) || sec < 0) return '00:00.0'
  const tenths = Math.round(sec * 10)
  const minutes = Math.floor(tenths / 600)
  const seconds = (tenths % 600) / 10
  return `${String(minutes).padStart(2, '0')}:${seconds.toFixed(1).padStart(4, '0')}`
}

export function parseTimePrecise(value: string): number | null {
  const match = value.trim().match(/^(\d+):(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const minutes = Number(match[1])
  const seconds = Number(match[2])
  if (!Number.isFinite(minutes) || !Number.isFinite(seconds) || seconds >= 60) return null
  return minutes * 60 + seconds
}
