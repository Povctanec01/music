/** Web Audio loudness leveling for an HTMLMediaElement. */

const TARGET_RMS = 0.12
const MIN_GAIN = 0.35
const MAX_GAIN = 2.8
const SMOOTHING = 0.18

export type LoudnessController = {
  setEnabled: (enabled: boolean) => void
  setMasterVolume: (volume: number) => void
  resetForTrack: () => void
  dispose: () => void
}

export function createLoudnessController(audio: HTMLAudioElement): LoudnessController {
  const AudioCtx = window.AudioContext || (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!AudioCtx) {
    return {
      setEnabled: () => undefined,
      setMasterVolume: (volume) => {
        audio.volume = volume
      },
      resetForTrack: () => undefined,
      dispose: () => undefined,
    }
  }

  const ctx = new AudioCtx()
  const source = ctx.createMediaElementSource(audio)
  const autoGain = ctx.createGain()
  const masterGain = ctx.createGain()
  const compressor = ctx.createDynamicsCompressor()
  const analyser = ctx.createAnalyser()

  compressor.threshold.value = -24
  compressor.knee.value = 18
  compressor.ratio.value = 3
  compressor.attack.value = 0.01
  compressor.release.value = 0.25

  analyser.fftSize = 2048
  const timeData = new Float32Array(analyser.fftSize)

  source.connect(autoGain)
  // Measure the input, before applying gain, to avoid a feedback loop.
  source.connect(analyser)
  autoGain.connect(compressor)
  compressor.connect(masterGain)
  masterGain.connect(ctx.destination)

  let enabled = true
  let currentGain = 1
  let raf = 0
  let sampleCount = 0
  let rmsAccum = 0

  autoGain.gain.value = 1
  masterGain.gain.value = 1
  audio.volume = 1

  const tick = () => {
    raf = requestAnimationFrame(tick)
    if (ctx.state === 'suspended') void ctx.resume()
    if (!enabled || audio.paused) return

    analyser.getFloatTimeDomainData(timeData)
    let sum = 0
    for (let i = 0; i < timeData.length; i += 1) {
      const sample = timeData[i]
      sum += sample * sample
    }
    const rms = Math.sqrt(sum / timeData.length)
    if (rms < 0.001) return

    rmsAccum += rms
    sampleCount += 1
    if (sampleCount < 8) return

    const avgRms = rmsAccum / sampleCount
    rmsAccum = 0
    sampleCount = 0

    const desired = Math.min(MAX_GAIN, Math.max(MIN_GAIN, TARGET_RMS / avgRms))
    currentGain += (desired - currentGain) * SMOOTHING
    autoGain.gain.setTargetAtTime(enabled ? currentGain : 1, ctx.currentTime, 0.08)
  }
  raf = requestAnimationFrame(tick)

  return {
    setEnabled(next) {
      enabled = next
      compressor.ratio.setTargetAtTime(next ? 3 : 1, ctx.currentTime, 0.05)
      if (!next) {
        currentGain = 1
        autoGain.gain.setTargetAtTime(1, ctx.currentTime, 0.05)
      }
    },
    setMasterVolume(volume) {
      const safe = Math.min(1, Math.max(0, volume))
      masterGain.gain.setTargetAtTime(safe, ctx.currentTime, 0.02)
    },
    resetForTrack() {
      currentGain = 1
      sampleCount = 0
      rmsAccum = 0
      autoGain.gain.setTargetAtTime(1, ctx.currentTime, 0.01)
      if (ctx.state === 'suspended') void ctx.resume()
    },
    dispose() {
      cancelAnimationFrame(raf)
      try {
        source.disconnect()
        autoGain.disconnect()
        analyser.disconnect()
        compressor.disconnect()
        masterGain.disconnect()
      } catch {
        // already disconnected
      }
      void ctx.close()
    },
  }
}
