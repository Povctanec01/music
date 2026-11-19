import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLoudnessController } from '../src/loudness.ts'

test('normalization measures input, supports bypass and resumes a suspended context', () => {
  let frame: () => void = () => {}
  let resumes = 0
  const connections: string[] = []
  const param = () => ({ value: 1, setTargetAtTime(value: number) { this.value = value } })
  const node = (name: string) => ({
    name, gain: param(), threshold: param(), knee: param(), ratio: param(), attack: param(), release: param(),
    fftSize: 2048,
    connect(next: { name: string }) { connections.push(`${name}->${next.name}`) },
    disconnect() {},
    getFloatTimeDomainData(data: Float32Array) { data.fill(0.06) },
  })
  const source = node('source')
  const gains = [node('auto'), node('master')]
  const compressor = node('compressor')
  let gainIndex = 0
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { AudioContext: class {
    state = 'suspended'
    currentTime = 0
    destination = node('destination')
    createMediaElementSource() { return source }
    createGain() { return gains[gainIndex++] }
    createDynamicsCompressor() { return compressor }
    createAnalyser() { return node('analyser') }
    async resume() { resumes++; this.state = 'running' }
    async close() {}
  } } })
  Object.defineProperty(globalThis, 'requestAnimationFrame', { configurable: true, value: (next: () => void) => { frame = next; return 1 } })
  Object.defineProperty(globalThis, 'cancelAnimationFrame', { configurable: true, value: () => {} })
  const audio = { volume: 0.5, paused: false } as HTMLAudioElement
  const controller = createLoudnessController(audio)
  assert.ok(connections.includes('source->analyser'))
  assert.ok(!connections.includes('auto->analyser'))
  controller.setEnabled(false)
  frame()
  assert.equal(resumes, 1)
  assert.equal(compressor.ratio.value, 1)
  controller.setMasterVolume(0.3)
  assert.equal(gains[1].gain.value, 0.3)
  controller.setEnabled(true)
  for (let i = 0; i < 8; i++) frame()
  assert.ok(gains[0].gain.value > 1)
  controller.resetForTrack()
  assert.equal(gains[0].gain.value, 1)
  controller.dispose()
})
