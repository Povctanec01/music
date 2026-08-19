import type { ListeningBehavior, MusicTrack, RecommendationSection } from './types'

export function buildRecommendationSections(
  tracks: MusicTrack[],
  behavior: Record<string, ListeningBehavior>,
  favorites: Set<string>,
): RecommendationSection[] {
  if (!tracks.length) return []

  const recommended = tracks
    .filter((track) => scoreTrack(track, behavior[track.id], favorites) > 0.1)
    .sort((a, b) => scoreTrack(b, behavior[b.id], favorites) - scoreTrack(a, behavior[a.id], favorites))
    .slice(0, 12)

  const recentlyPlayed = tracks
    .filter((track) => (behavior[track.id]?.lastPlayedAt ?? 0) > 0)
    .sort((a, b) => (behavior[b.id]?.lastPlayedAt ?? 0) - (behavior[a.id]?.lastPlayedAt ?? 0))
    .slice(0, 12)

  const continueListening = tracks
    .filter((track) => {
      const item = behavior[track.id]
      return !!item && item.totalListenedMs > 30_000 && item.completionCount === 0
    })
    .sort((a, b) => (behavior[b.id]?.lastPlayedAt ?? 0) - (behavior[a.id]?.lastPlayedAt ?? 0))
    .slice(0, 10)

  const favoriteTracks = tracks.filter((track) => favorites.has(track.id)).slice(0, 12)
  const newOnDevice = [...tracks].sort((a, b) => b.id.localeCompare(a.id)).slice(0, 12)

  const artistAffinity = new Map<string, number>()
  recommended.slice(0, 5).forEach((track) => {
    artistAffinity.set(track.artist, (artistAffinity.get(track.artist) ?? 0) + 1)
  })
  const becauseYouLike = tracks
    .filter((track) => !recommended.some((item) => item.id === track.id))
    .sort((a, b) => (artistAffinity.get(b.artist) ?? 0) - (artistAffinity.get(a.artist) ?? 0))
    .slice(0, 12)

  return [
    section('recommended', 'Recommended For You', 'Поведение, повторы, избранное и недавние прослушивания', recommended),
    section('continue', 'Continue Listening', 'Треки, которые ты начал слушать, но не закончил', continueListening),
    section('because', 'Because You Like', 'Похожие по артистам и тому, что ты крутишь чаще', becauseYouLike),
    section('recent', 'Recently Played', 'Быстрый возврат к последним прослушиваниям', recentlyPlayed),
    section('favorites', 'Favorites', 'Все, что ты уже отметил как любимое', favoriteTracks),
    section('new', 'New On Device', 'Недавно добавленные локальные треки', newOnDevice),
  ].filter((item) => item.tracks.length > 0)
}

function section(
  key: string,
  title: string,
  subtitle: string,
  tracks: MusicTrack[],
): RecommendationSection {
  return { key, title, subtitle, tracks }
}

function scoreTrack(
  track: MusicTrack,
  behavior: ListeningBehavior | undefined,
  favorites: Set<string>,
) {
  if (!behavior && !favorites.has(track.id)) return 0
  const item = behavior ?? emptyBehavior()
  const favoriteBoost = favorites.has(track.id) ? 2.5 : 0
  const recencyBoost = item.lastPlayedAt
    ? 1.5 / (1 + (Date.now() - item.lastPlayedAt) / 86_400_000)
    : 0
  return (
    favoriteBoost +
    item.playCount * 0.4 +
    item.completionCount * 0.9 +
    Math.min(item.totalListenedMs / 60_000, 8) * 0.15 +
    recencyBoost -
    item.skipCount * 0.7
  )
}

export function emptyBehavior(): ListeningBehavior {
  return {
    playCount: 0,
    completionCount: 0,
    skipCount: 0,
    totalListenedMs: 0,
    lastPlayedAt: 0,
  }
}
