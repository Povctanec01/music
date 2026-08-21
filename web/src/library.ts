import type { MusicTrack } from './types'

export type LibraryTab = 'recommendations' | 'tracks' | 'artists' | 'albums' | 'folders' | 'favorites'

export type SortMode = 'title' | 'artist' | 'date_added' | 'duration'

export const SORT_MODES: { id: SortMode; label: string }[] = [
  { id: 'title', label: 'По названию' },
  { id: 'artist', label: 'По исполнителю' },
  { id: 'date_added', label: 'По дате добавления' },
  { id: 'duration', label: 'По длительности' },
]

export const LIBRARY_TABS: { id: LibraryTab; label: string }[] = [
  { id: 'recommendations', label: 'Рекомендации' },
  { id: 'tracks', label: 'Треки' },
  { id: 'artists', label: 'Исполнители' },
  { id: 'albums', label: 'Альбомы' },
  { id: 'folders', label: 'Папки' },
  { id: 'favorites', label: 'Избранное' },
]

export type LibraryGroup = {
  key: string
  label: string
  tracks: MusicTrack[]
  artUrl?: string
}

export function artistLabel(track: MusicTrack) {
  return track.artist.trim() || 'Неизвестный исполнитель'
}

export function albumLabel(track: MusicTrack) {
  return track.album.trim() || 'Неизвестный альбом'
}

export function folderLabel(track: MusicTrack) {
  const parts = track.relativePath.split('/')
  if (parts.length <= 1) return 'Корень'
  return parts.slice(0, -1).join('/')
}

export function favoriteTracks(tracks: MusicTrack[], favorites: Set<string>) {
  return tracks.filter((track) => favorites.has(track.id))
}

export function sortTracks(tracks: MusicTrack[], mode: SortMode): MusicTrack[] {
  const copy = [...tracks]
  switch (mode) {
    case 'title':
      return copy.sort((a, b) => a.title.localeCompare(b.title, 'ru'))
    case 'artist':
      return copy.sort((a, b) => {
        const byArtist = artistLabel(a).localeCompare(artistLabel(b), 'ru')
        return byArtist !== 0 ? byArtist : a.title.localeCompare(b.title, 'ru')
      })
    case 'date_added':
      return copy.sort((a, b) => b.addedAt - a.addedAt || a.title.localeCompare(b.title, 'ru'))
    case 'duration':
      return copy.sort((a, b) => b.durationMs - a.durationMs || a.title.localeCompare(b.title, 'ru'))
  }
}

export function groupNewestAddedAt(tracks: MusicTrack[]) {
  return tracks.reduce((max, track) => Math.max(max, track.addedAt), 0)
}

export function sortGroups(groups: LibraryGroup[], mode: SortMode): LibraryGroup[] {
  if (mode === 'date_added') {
    return [...groups].sort(
      (a, b) => groupNewestAddedAt(b.tracks) - groupNewestAddedAt(a.tracks) || a.label.localeCompare(b.label, 'ru'),
    )
  }
  if (mode === 'duration') {
    return [...groups].sort((a, b) => {
      const maxA = Math.max(...a.tracks.map((track) => track.durationMs))
      const maxB = Math.max(...b.tracks.map((track) => track.durationMs))
      return maxB - maxA || a.label.localeCompare(b.label, 'ru')
    })
  }
  return [...groups].sort((a, b) => a.label.localeCompare(b.label, 'ru'))
}

export function groupTracks(
  tracks: MusicTrack[],
  tab: 'artists' | 'albums' | 'folders',
  sortMode: SortMode,
): LibraryGroup[] {
  const keyFn = tab === 'artists' ? artistLabel : tab === 'albums' ? albumLabel : folderLabel
  const map = new Map<string, MusicTrack[]>()
  for (const track of tracks) {
    const key = keyFn(track)
    const bucket = map.get(key)
    if (bucket) bucket.push(track)
    else map.set(key, [track])
  }
  const groups = [...map.entries()].map(([label, groupItems]) => ({
    key: `${tab}:${label}`,
    label,
    tracks: sortTracks(groupItems, sortMode),
    artUrl: groupItems.find((track) => track.artUrl)?.artUrl,
  }))
  return sortGroups(groups, sortMode)
}

export function formatAddedAt(timestampSec: number) {
  if (!timestampSec) return '—'
  const date = new Date(timestampSec * 1000)
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatRelativeAddedAt(timestampSec: number) {
  if (!timestampSec) return '—'
  const deltaMs = Date.now() - timestampSec * 1000
  const days = Math.floor(deltaMs / 86_400_000)
  if (days <= 0) return 'Сегодня'
  if (days === 1) return 'Вчера'
  if (days < 7) return `${days} дн. назад`
  if (days < 30) return `${Math.floor(days / 7)} нед. назад`
  return formatAddedAt(timestampSec)
}
