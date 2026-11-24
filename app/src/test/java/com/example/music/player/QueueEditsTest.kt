package com.example.music.player

import org.junit.Assert.*
import org.junit.Test

class QueueEditsTest {
    @Test fun restoringRepeatedTracksKeepsTheSelectedOccurrence() {
        assertEquals(2, restoredQueueIndex(listOf("A", "B", "A", "C"), 2, "A", setOf("A", "B", "C")))
        assertEquals(1, restoredQueueIndex(listOf("A", "B", "A", "C"), 2, "A", setOf("A", "C")))
        assertEquals(-1, restoredQueueIndex(listOf("A", "B"), 1, "B", setOf("A")))
    }
    @Test fun latestRequestIsNextAndUpcomingDuplicatesAreMoved() {
        val first = insertNext(listOf("A", "B", "C", "D"), 0, "C") { a, b -> a == b }
        assertEquals(listOf("A", "C", "B", "D"), first)
        assertEquals(listOf("A", "D", "C", "B"), insertNext(first, 0, "D") { a, b -> a == b })
    }
    @Test fun currentCanBeRepeatedWithoutErasingHistory() {
        assertEquals(listOf("A", "B", "B", "C"), insertNext(listOf("A", "B", "C"), 1, "B") { a, b -> a == b })
        assertEquals(listOf("A", "B", "C", "A"), insertNext(listOf("A", "B", "C"), 2, "A") { a, b -> a == b })
    }
}
