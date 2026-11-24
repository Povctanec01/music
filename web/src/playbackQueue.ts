import type { MusicTrack } from './types.ts'

export type QueueTrack = MusicTrack & { playNext?: boolean }
export type PlaybackQueue = { entries: QueueTrack[]; index: number }
export const emptyQueue = (): PlaybackQueue => ({ entries: [], index: -1 })

export function selectedQueue(tracks: MusicTrack[], selected: MusicTrack): PlaybackQueue {
  const index = tracks.findIndex((track) => track.id === selected.id)
  return index < 0 ? { entries: [selected, ...tracks], index: 0 } : { entries: tracks, index }
}

/** Latest request is immediately next; move upcoming copies but preserve history. */
export function enqueueNext(state: PlaybackQueue, track: MusicTrack): PlaybackQueue {
  if (state.index < 0 || !state.entries[state.index]) return selectedQueue([track], track)
  return {
    entries: [
      ...state.entries.slice(0, state.index + 1),
      { ...track, playNext: true },
      ...state.entries.slice(state.index + 1).filter((entry) => entry.id !== track.id),
    ],
    index: state.index,
  }
}

export function hasPriorityNext(state: PlaybackQueue): boolean {
  return state.index >= 0 && state.entries[state.index + 1]?.playNext === true
}

export function advanceQueue(state: PlaybackQueue): PlaybackQueue {
  const index = state.index + 1
  if (state.index < 0 || index >= state.entries.length) return { ...state, index: -1 }
  const entries = [...state.entries]
  if (entries[index].playNext) entries[index] = { ...entries[index], playNext: false }
  return { entries, index }
}

export function selectQueuedTrack(state: PlaybackQueue, track: MusicTrack): PlaybackQueue {
  const index = state.entries.findIndex((entry) => entry.id === track.id)
  return index < 0 ? { entries: [...state.entries, track], index: state.entries.length } : { ...state, index }
}

export function removeQueuedTrack(state: PlaybackQueue, id: string): PlaybackQueue {
  const current = state.entries[state.index]
  const entries = state.entries.filter((track) => track.id !== id)
  return { entries, index: !current || current.id === id ? -1 : state.entries.slice(0, state.index).filter((track) => track.id !== id).length }
}
