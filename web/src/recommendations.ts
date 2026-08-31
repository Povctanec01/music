import type { ListeningBehavior, MusicTrack, RecommendationSection } from './types'

export function buildRecommendationSections(
  tracks: MusicTrack[],
  behavior: Record<string, ListeningBehavior>,
  favorites: Set<string>,
): RecommendationSection[] {
  if (!tracks.length) return []

  const recommended = tracks
    .filter((track) => scoreTrack(track, behavior[track.id], favorites) > 1.2)
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
  const newOnDevice = [...tracks].sort((a, b) => b.addedAt - a.addedAt).slice(0, 12)

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
    section('new', 'New On Server', 'Недавно добавленные файлы на Orange Pi', newOnDevice),
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
  return shuffleWeight(track, behavior, favorites)
}

/** Weight for shuffle: frequent listens win more often; base > 0 keeps old tracks in play. */
export function shuffleWeight(
  track: MusicTrack,
  behavior: ListeningBehavior | undefined,
  favorites: Set<string>,
) {
  const item = behavior ?? emptyBehavior()
  const favoriteBoost = favorites.has(track.id) ? 4 : 0
  const playScore = item.playCount * 2.2
  const completionScore = item.completionCount * 3
  const listenScore = Math.min(item.totalListenedMs / 60_000, 15) * 0.35
  const skipPenalty = item.skipCount * 1.6
  const recencyBoost = item.lastPlayedAt
    ? 1.4 / (1 + (Date.now() - item.lastPlayedAt) / 86_400_000)
    : 0
  return Math.max(0.2, 1 + favoriteBoost + playScore + completionScore + listenScore + recencyBoost - skipPenalty)
}

export function pickWeightedShuffle(
  pool: MusicTrack[],
  behavior: Record<string, ListeningBehavior>,
  favorites: Set<string>,
  excludeIds: string[] = [],
): MusicTrack | null {
  if (!pool.length) return null
  const candidates = pool.filter((track) => !excludeIds.includes(track.id))
  const list = candidates.length ? candidates : pool
  if (list.length === 1) return list[0]

  // ~20% exploration: rarely played / long-idle tracks ("old songs")
  if (Math.random() < 0.2) {
    const exploration = [...list]
      .sort((a, b) => {
        const playDiff = (behavior[a.id]?.playCount ?? 0) - (behavior[b.id]?.playCount ?? 0)
        if (playDiff !== 0) return playDiff
        return (behavior[a.id]?.lastPlayedAt ?? 0) - (behavior[b.id]?.lastPlayedAt ?? 0)
      })
      .slice(0, Math.max(1, Math.min(24, Math.floor(list.length / 3))))
    return exploration[Math.floor(Math.random() * exploration.length)] ?? list[0]
  }

  const weights = list.map((track) => shuffleWeight(track, behavior[track.id], favorites))
  const total = weights.reduce((sum, value) => sum + value, 0)
  let ticket = Math.random() * total
  for (let index = 0; index < list.length; index += 1) {
    ticket -= weights[index]
    if (ticket <= 0) return list[index]
  }
  return list[list.length - 1]
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
