package com.example.music.domain.recommendation

import com.example.music.domain.model.Track

class RecommendationEngine {
    fun buildSections(
        tracks: List<Track>,
        behavior: Map<String, TrackBehavior>,
        favorites: Set<String>,
    ): List<RecommendationSection> {
        if (tracks.isEmpty()) return emptyList()

        val recommended = tracks
            .sortedByDescending { recommendationScore(it, behavior[it.id], favorites) }
            .filter { recommendationScore(it, behavior[it.id], favorites) > 0.15f }
            .take(12)

        val recent = tracks
            .filter { behavior[it.id]?.lastPlayedAt ?: 0L > 0L }
            .sortedByDescending { behavior[it.id]?.lastPlayedAt ?: 0L }
            .take(12)

        val continueListening = tracks
            .filter { track ->
                val item = behavior[track.id] ?: return@filter false
                item.totalListenedMs >= 30_000L && item.completionCount == 0 && item.skipCount < item.playCount
            }
            .sortedByDescending { behavior[it.id]?.lastPlayedAt ?: 0L }
            .take(10)

        val favoriteTracks = tracks
            .filter { it.id in favorites }
            .sortedByDescending { recommendationScore(it, behavior[it.id], favorites) }
            .take(12)

        val newOnDevice = tracks
            .sortedByDescending { it.addedAt }
            .take(12)

        val becauseYouLike = buildAffinitySection(tracks, behavior, favorites)

        return buildList {
            if (recommended.isNotEmpty()) {
                add(
                    RecommendationSection(
                        key = "recommended",
                        title = "Recommended For You",
                        subtitle = "На основе повторов, дослушиваний, избранного и недавних запусков",
                        tracks = recommended,
                    ),
                )
            }
            if (continueListening.isNotEmpty()) {
                add(
                    RecommendationSection(
                        key = "continue",
                        title = "Continue Listening",
                        subtitle = "Треки, к которым ты уже возвращался",
                        tracks = continueListening,
                    ),
                )
            }
            if (becauseYouLike.isNotEmpty()) {
                add(
                    RecommendationSection(
                        key = "because",
                        title = "Because You Like",
                        subtitle = "Похоже на то, что ты слушаешь чаще всего",
                        tracks = becauseYouLike,
                    ),
                )
            }
            if (recent.isNotEmpty()) {
                add(
                    RecommendationSection(
                        key = "recent",
                        title = "Recently Played",
                        subtitle = "Последние прослушанные треки",
                        tracks = recent,
                    ),
                )
            }
            if (favoriteTracks.isNotEmpty()) {
                add(
                    RecommendationSection(
                        key = "favorites",
                        title = "Favorites",
                        subtitle = "Быстрый доступ к любимым трекам",
                        tracks = favoriteTracks,
                    ),
                )
            }
            add(
                RecommendationSection(
                    key = "new",
                    title = "New On Device",
                    subtitle = "Недавно добавленные в локальную папку",
                    tracks = newOnDevice,
                ),
            )
        }
    }

    private fun buildAffinitySection(
        tracks: List<Track>,
        behavior: Map<String, TrackBehavior>,
        favorites: Set<String>,
    ): List<Track> {
        val seedTracks = tracks
            .sortedByDescending { recommendationScore(it, behavior[it.id], favorites) }
            .take(5)
        if (seedTracks.isEmpty()) return emptyList()

        val favoriteArtists = seedTracks.map { it.artistOrUnknown }.groupingBy { it }.eachCount()
        val favoriteAlbums = seedTracks.map { it.albumOrUnknown }.groupingBy { it }.eachCount()
        val favoriteFolders = seedTracks.map { it.folder }.groupingBy { it }.eachCount()

        return tracks
            .sortedByDescending { track ->
                favoriteArtists.getOrDefault(track.artistOrUnknown, 0) * 3 +
                    favoriteAlbums.getOrDefault(track.albumOrUnknown, 0) * 2 +
                    favoriteFolders.getOrDefault(track.folder, 0)
            }
            .distinctBy { it.id }
            .take(12)
    }

    private fun recommendationScore(
        track: Track,
        behavior: TrackBehavior?,
        favorites: Set<String>,
    ): Float {
        val item = behavior ?: TrackBehavior()
        val favoriteBoost = if (track.id in favorites) 2.5f else 0f
        val recencyBoost = if (item.lastPlayedAt > 0L) {
            val daysSince = ((System.currentTimeMillis() - item.lastPlayedAt).coerceAtLeast(0L) / 86_400_000f)
            1.5f / (1f + daysSince)
        } else {
            0f
        }
        val playScore = item.playCount * 0.45f
        val completionScore = item.completionCount * 0.9f
        val listenScore = (item.totalListenedMs / 60_000f).coerceAtMost(10f) * 0.18f
        val skipPenalty = item.skipCount * 0.75f
        return favoriteBoost + recencyBoost + playScore + completionScore + listenScore - skipPenalty
    }
}
