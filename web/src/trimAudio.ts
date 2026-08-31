/** Decode, slice, and encode a WAV blob for the trim editor. */

export async function trimAudioFile(file: Blob, startSec: number, endSec: number): Promise<Blob> {
  const buffer = await file.arrayBuffer()
  const ctx = new AudioContext()
  let decoded: AudioBuffer
  try {
    decoded = await ctx.decodeAudioData(buffer.slice(0))
  } finally {
    await ctx.close()
  }

  const sampleRate = decoded.sampleRate
  const start = Math.max(0, Math.floor(startSec * sampleRate))
  const end = Math.min(decoded.length, Math.floor(endSec * sampleRate))
  if (end <= start) throw new Error('Неверный диапазон обрезки')

  const frameCount = end - start
  const offline = new OfflineAudioContext(decoded.numberOfChannels, frameCount, sampleRate)
  const temp = offline.createBuffer(decoded.numberOfChannels, frameCount, sampleRate)
  for (let channel = 0; channel < decoded.numberOfChannels; channel += 1) {
    const src = decoded.getChannelData(channel).subarray(start, end)
    temp.copyToChannel(src, channel)
  }

  const source = offline.createBufferSource()
  source.buffer = temp
  source.connect(offline.destination)
  source.start()
  const rendered = await offline.startRendering()
  return encodeWav(rendered)
}

function encodeWav(buffer: AudioBuffer): Blob {
  const channels = buffer.numberOfChannels
  const sampleRate = buffer.sampleRate
  const samples = buffer.length
  const bytesPerSample = 2
  const blockAlign = channels * bytesPerSample
  const dataSize = samples * blockAlign
  const headerSize = 44
  const arrayBuffer = new ArrayBuffer(headerSize + dataSize)
  const view = new DataView(arrayBuffer)

  writeString(view, 0, 'RIFF')
  view.setUint32(4, 36 + dataSize, true)
  writeString(view, 8, 'WAVE')
  writeString(view, 12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, channels, true)
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * blockAlign, true)
  view.setUint16(32, blockAlign, true)
  view.setUint16(34, bytesPerSample * 8, true)
  writeString(view, 36, 'data')
  view.setUint32(40, dataSize, true)

  const channelData = Array.from({ length: channels }, (_, i) => buffer.getChannelData(i))
  let offset = 44
  for (let i = 0; i < samples; i += 1) {
    for (let ch = 0; ch < channels; ch += 1) {
      const sample = Math.max(-1, Math.min(1, channelData[ch][i]))
      view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
      offset += 2
    }
  }

  return new Blob([arrayBuffer], { type: 'audio/wav' })
}

function writeString(view: DataView, offset: number, value: string) {
  for (let i = 0; i < value.length; i += 1) {
    view.setUint8(offset + i, value.charCodeAt(i))
  }
}
