import type { MusicTrack } from './types.ts'

type ActionHandlers = Partial<Record<MediaSessionAction, MediaSessionActionHandler | null>>

/** Optional browser integration must never interrupt the site's audio player. */
export function registerMediaActions(session: MediaSession | undefined, handlers: ActionHandlers): () => void {
  const registered: MediaSessionAction[] = []
  if (session) for (const [action, handler] of Object.entries(handlers)) {
    try {
      session.setActionHandler(action as MediaSessionAction, handler)
      registered.push(action as MediaSessionAction)
    } catch { /* Older browsers may support only some actions. */ }
  }
  return () => {
    for (const action of registered) {
      try { session?.setActionHandler(action, null) } catch { /* Unsupported action. */ }
    }
  }
}

export function updateMediaMetadata(
  session: MediaSession | undefined,
  track: MusicTrack | null,
  Metadata: typeof MediaMetadata | undefined = typeof MediaMetadata === 'undefined' ? undefined : MediaMetadata,
) {
  if (!session) return
  try {
    if (!track) { session.metadata = null; return }
    if (!Metadata) return
    const artwork = track.youtubeArtUrl || track.artUrl
    session.metadata = new Metadata({
      title: track.title,
      artist: track.artist || 'Неизвестный исполнитель',
      album: track.album || '',
      artwork: artwork ? [{ src: artwork }] : [],
    })
  } catch { /* Metadata is optional; transport controls still work. */ }
}

export function updateMediaPlayback(session: MediaSession | undefined, hasTrack: boolean, playing: boolean) {
  if (!session) return
  try { session.playbackState = hasTrack ? (playing ? 'playing' : 'paused') : 'none' } catch { /* Unsupported. */ }
}

export function updateMediaPosition(session: MediaSession | undefined, durationMs: number, positionMs: number) {
  if (!session?.setPositionState) return
  try {
    if (!Number.isFinite(durationMs) || durationMs <= 0) { session.setPositionState(); return }
    session.setPositionState({
      duration: durationMs / 1000,
      position: Math.min(durationMs, Math.max(0, Number.isFinite(positionMs) ? positionMs : 0)) / 1000,
      playbackRate: 1,
    })
  } catch { /* Older Chromium versions do not expose position state. */ }
}
