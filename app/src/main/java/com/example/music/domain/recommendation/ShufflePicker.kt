package com.example.music.domain.recommendation

import com.example.music.domain.model.Track
import kotlin.random.Random

/**
 * Weighted random for shuffle / radio mode:
 * frequently played tracks are more likely, but rarely-heard ("old") tracks
 * still surface via a base weight and an occasional exploration pick.
 */
object ShufflePicker {
    private const val EXPLORATION_CHANCE = 0.2f
    private const val MIN_WEIGHT = 0.2

    fun pickNext(
        pool: List<Track>,
        behavior: Map<String, TrackBehavior>,
        favorites: Set<String>,
        excludeIds: Collection<String> = emptyList(),
        random: Random = Random.Default,
    ): Track? {
        if (pool.isEmpty()) return null
        val candidates = pool.filter { it.id !in excludeIds }.ifEmpty { pool }

        if (candidates.size == 1) return candidates.first()

        if (random.nextFloat() < EXPLORATION_CHANCE) {
            val exploration = explorationCandidates(candidates, behavior)
            if (exploration.isNotEmpty()) return exploration[random.nextInt(exploration.size)]
        }

        val weights = candidates.map { weight(it, behavior[it.id], favorites) }
        return weightedRandom(candidates, weights, random)
    }

    fun weight(
        track: Track,
        behavior: TrackBehavior?,
        favorites: Set<String>,
    ): Double {
        val item = behavior ?: TrackBehavior()
        val favoriteBoost = if (track.id in favorites) 4.0 else 0.0
        val playScore = item.playCount * 2.2
        val completionScore = item.completionCount * 3.0
        val listenScore = (item.totalListenedMs / 60_000.0).coerceAtMost(15.0) * 0.35
        val skipPenalty = item.skipCount * 1.6
        val recencyBoost = if (item.lastPlayedAt > 0L) {
            val days = ((System.currentTimeMillis() - item.lastPlayedAt).coerceAtLeast(0L) / 86_400_000.0)
            1.4 / (1.0 + days)
        } else {
            0.0
        }
        // Base > 0 so never-played / long-idle tracks still appear sometimes.
        return (1.0 + favoriteBoost + playScore + completionScore + listenScore + recencyBoost - skipPenalty)
            .coerceAtLeast(MIN_WEIGHT)
    }

    private fun explorationCandidates(
        candidates: List<Track>,
        behavior: Map<String, TrackBehavior>,
    ): List<Track> {
        val ranked = candidates.sortedWith(
            compareBy<Track> { behavior[it.id]?.playCount ?: 0 }
                .thenBy { behavior[it.id]?.lastPlayedAt ?: 0L }
                .thenBy { it.addedAt },
        )
        val take = (ranked.size / 3).coerceIn(1, 24)
        return ranked.take(take)
    }

    private fun weightedRandom(
        items: List<Track>,
        weights: List<Double>,
        random: Random,
    ): Track {
        val total = weights.sum()
        if (total <= 0.0) return items[random.nextInt(items.size)]
        var ticket = random.nextDouble() * total
        for (index in items.indices) {
            ticket -= weights[index]
            if (ticket <= 0.0) return items[index]
        }
        return items.last()
    }
}
