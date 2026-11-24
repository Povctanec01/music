package com.example.music.player

/** Move an upcoming occurrence to next, preserving history and allowing a repeat of current. */
fun <T> insertNext(items: List<T>, currentIndex: Int, next: T, same: (T, T) -> Boolean): List<T> {
    if (currentIndex !in items.indices) return listOf(next)
    return items.take(currentIndex + 1) + next + items.drop(currentIndex + 1).filterNot { same(it, next) }
}

/** Preserve the selected occurrence when queue IDs repeat or earlier files disappeared. */
fun restoredQueueIndex(ids: List<String>, index: Int, mediaId: String?, available: Set<String>): Int {
    val original = if (index in ids.indices && (mediaId == null || ids[index] == mediaId)) index else ids.indexOf(mediaId)
    return ids.indices.filter { ids[it] in available }.indexOf(original)
}
