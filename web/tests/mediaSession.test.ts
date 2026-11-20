import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerMediaActions, updateMediaMetadata, updateMediaPlayback, updateMediaPosition } from '../src/mediaSession.ts'
import type { MusicTrack } from '../src/types.ts'

test('browser controls call play/pause/next/previous and are removed when the queue changes', () => {
  const handlers = new Map<string, Function | null>()
  const session = { setActionHandler(action: string, handler: Function | null) {
    if (action === 'seekto') throw new Error('unsupported')
    handlers.set(action, handler)
  } } as unknown as MediaSession
  const calls: string[] = []
  const dispose = registerMediaActions(session, {
    play: () => calls.push('play'), pause: () => calls.push('pause'),
    nexttrack: () => calls.push('next'), previoustrack: () => calls.push('previous'), seekto: () => {},
  })
  for (const action of ['nexttrack', 'previoustrack', 'pause', 'play']) handlers.get(action)!({ action })
  assert.deepEqual(calls, ['next', 'previous', 'pause', 'play'])
  dispose()
  assert.ok([...handlers.values()].every((handler) => handler === null))
  assert.doesNotThrow(() => registerMediaActions(undefined, {})())
})

test('track changes replace the widget title and artwork, pause state and position are kept in sync', () => {
  let position: MediaPositionState | undefined
  const session = { metadata: null, playbackState: 'none', setPositionState(value?: MediaPositionState) { position = value } } as MediaSession
  class Metadata { constructor(init: MediaMetadataInit) { Object.assign(this, init) } }
  const track = { title: 'First', artist: 'Artist', album: 'Album', artUrl: 'https://example.com/first.jpg' } as MusicTrack
  updateMediaMetadata(session, track, Metadata as typeof MediaMetadata)
  assert.equal(session.metadata?.title, 'First')
  updateMediaMetadata(session, { ...track, title: 'Second', artUrl: 'https://example.com/second.jpg' }, Metadata as typeof MediaMetadata)
  assert.equal(session.metadata?.title, 'Second')
  assert.equal(session.metadata?.artwork[0].src, 'https://example.com/second.jpg')
  updateMediaPlayback(session, true, true)
  assert.equal(session.playbackState, 'playing')
  updateMediaPlayback(session, true, false)
  assert.equal(session.playbackState, 'paused')
  updateMediaPosition(session, 1000, 5000)
  assert.deepEqual(position, { duration: 1, position: 1, playbackRate: 1 })
  updateMediaPosition(session, NaN, 0)
  assert.equal(position, undefined)
  updateMediaMetadata(session, null)
  updateMediaPlayback(session, false, false)
  assert.equal(session.metadata, null)
  assert.equal(session.playbackState, 'none')
})
