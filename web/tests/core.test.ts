import assert from 'node:assert/strict'
import { test } from 'node:test'
import { favoriteTracks, groupTracks, sortTracks } from '../src/library.ts'
import { buildRecommendationSections, emptyBehavior, pickWeightedShuffle, shuffleWeight } from '../src/recommendations.ts'
import { decodeWaveformPeaks, formatTimePrecise, parseTimePrecise } from '../src/waveform.ts'
import { loadBehavior, loadFavorites, loadVolume, saveFavorites } from '../src/storage.ts'
import { trimAudioFile } from '../src/trimAudio.ts'
import type { MusicTrack } from '../src/types.ts'

const track = (id: string, extra: Partial<MusicTrack> = {}): MusicTrack => ({
  id, title: id, artist: 'Artist', album: 'Album', durationMs: 1000, size: 100,
  relativePath: `Artist/Album/${id}.wav`, source: 'remote', addedAt: 1, ...extra,
})
const tracks = [track('B'), track('A', { addedAt: 2, durationMs: 2000 }), track('C', { artist: 'Other' })]

test('sorts without mutating the source list', () => {
  assert.deepEqual(sortTracks(tracks, 'title').map(t => t.id), ['A', 'B', 'C'])
  assert.equal(sortTracks(tracks, 'date_added')[0].id, 'A')
  assert.equal(sortTracks(tracks, 'duration')[0].id, 'A')
  assert.deepEqual(tracks.map(t => t.id), ['B', 'A', 'C'])
})

test('groups artists, albums, folders and filters favorites', () => {
  assert.equal(groupTracks(tracks, 'artists', 'title').length, 2)
  assert.equal(groupTracks(tracks, 'albums', 'title')[0].tracks.length, 3)
  assert.equal(groupTracks(tracks, 'folders', 'title')[0].label, 'Artist/Album')
  assert.deepEqual(favoriteTracks(tracks, new Set(['C'])).map(t => t.id), ['C'])
})

test('shuffle handles empty/single pools and exclusions', () => {
  assert.equal(pickWeightedShuffle([], {}, new Set()), null)
  assert.equal(pickWeightedShuffle([tracks[0]], {}, new Set())?.id, 'B')
  for (let i = 0; i < 100; i++) {
    assert.equal(pickWeightedShuffle(tracks, {}, new Set(), ['A', 'B'])?.id, 'C')
  }
})

test('recommendations reflect history and favorites', () => {
  const behavior = { A: { ...emptyBehavior(), playCount: 2, totalListenedMs: 31000, lastPlayedAt: Date.now() } }
  const sections = buildRecommendationSections(tracks, behavior, new Set(['C']))
  assert.equal(sections.find(s => s.key === 'continue')?.tracks[0].id, 'A')
  assert.equal(sections.find(s => s.key === 'favorites')?.tracks[0].id, 'C')
  assert.ok(shuffleWeight(tracks[0], undefined, new Set(['B'])) > shuffleWeight(tracks[0], undefined, new Set()))
  assert.ok(shuffleWeight(tracks[0], { ...emptyBehavior(), skipCount: 100 }, new Set()) > 0)
  assert.deepEqual(buildRecommendationSections([], {}, new Set()), [])
})

test('time formatting carries rounding across minute boundaries', () => {
  assert.equal(formatTimePrecise(59.96), '01:00.0')
  assert.equal(formatTimePrecise(125.25), '02:05.3')
  assert.equal(formatTimePrecise(NaN), '00:00.0')
  assert.equal(parseTimePrecise('02:05.3'), 125.3)
  assert.equal(parseTimePrecise('00:60'), null)
  assert.equal(parseTimePrecise('garbage'), null)
})

test('storage survives malformed JSON values and unavailable localStorage', () => {
  const values = new Map<string, string>()
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  } })
  for (const value of ['null', '{}', '123', 'invalid']) {
    values.set('music-web-favorites', value)
    assert.deepEqual(loadFavorites(), [])
  }
  values.set('music-web-favorites', '["A",12,null]')
  assert.deepEqual(loadFavorites(), ['A'])
  values.set('music-web-behavior', '{"A":null,"B":{"playCount":-4}}')
  assert.equal(loadBehavior().B.playCount, 0)
  values.set('music-web-volume', '100')
  assert.equal(loadVolume(), 1)
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new Error('blocked') } })
  assert.deepEqual(loadFavorites(), [])
  assert.doesNotThrow(() => saveFavorites(['A']))
})

test('waveform includes the last sample and spans a short file', async () => {
  const samples = new Float32Array([0, 0, 0, 0, 0, 0, 1])
  Object.defineProperty(globalThis, 'AudioContext', { configurable: true, value: class {
    async decodeAudioData() { return { getChannelData: () => samples } }
    async close() {}
  } })
  assert.deepEqual(await decodeWaveformPeaks(new Blob(), 3), [0, 0, 1])
})

test('trimming validates range and writes stereo PCM WAV with exact frames', async () => {
  const data = [new Float32Array([0, 0.5, -0.5, 1]), new Float32Array([1, -1, 0, 0.5])]
  const buffer = { sampleRate: 4, length: 4, numberOfChannels: 2, getChannelData: (ch: number) => data[ch] }
  Object.defineProperty(globalThis, 'AudioContext', { configurable: true, value: class {
    async decodeAudioData() { return buffer }
    async close() {}
  } })
  Object.defineProperty(globalThis, 'OfflineAudioContext', { configurable: true, value: class {
    destination = {}
    copied: Float32Array[] = []
    createBuffer(channels: number, length: number, sampleRate: number) {
      return { channels, length, sampleRate, copyToChannel: (samples: Float32Array, ch: number) => { this.copied[ch] = samples } }
    }
    createBufferSource() { return { buffer: null, connect() {}, start() {} } }
    async startRendering() { return { ...buffer, length: this.copied[0].length, getChannelData: (ch: number) => this.copied[ch] } }
  } })
  const blob = await trimAudioFile(new Blob(), 0.25, 0.75)
  const view = new DataView(await blob.arrayBuffer())
  assert.equal(blob.type, 'audio/wav')
  assert.equal(view.getUint16(22, true), 2)
  assert.equal(view.getUint32(40, true), 8)
  assert.equal(view.getInt16(44, true), 16383)
  assert.equal(view.getInt16(46, true), -32768)
  await assert.rejects(trimAudioFile(new Blob(), NaN, 1), /диапазон/)
  await assert.rejects(trimAudioFile(new Blob(), 1, 0), /диапазон/)
})
