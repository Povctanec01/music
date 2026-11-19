package com.example.music.domain

import com.example.music.domain.art.ServerArtIndex
import com.example.music.domain.art.TrackArtSources
import com.example.music.domain.art.TrackSidecarArt
import com.example.music.domain.model.RemoteFile
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun recognizesSupportedAudioAndRejectsPlaylists() {
        assertTrue(AudioFormats.isAudio(null, "TRACK.FLAC"))
        assertTrue(AudioFormats.isAudio("audio/mp4", "track"))
        assertFalse(AudioFormats.isAudio("audio/x-mpegurl", "list.m3u"))
        assertFalse(AudioFormats.isAudio("image/jpeg", "cover.jpg"))
        assertEquals("audio/mp4", AudioFormats.mimeForFileName("a.m4a"))
    }

    @Test fun preservesFoldersWhenBuildingSidecars() {
        assertEquals("Artist/Album/Song.jpg", TrackSidecarArt.primarySidecarRelativePath("Artist/Album/Song.flac"))
        assertEquals(listOf("Song.jpg", "Song.jpeg", "Song.png", "Song.webp"), TrackSidecarArt.sidecarFileNames("Song"))
    }

    @Test fun encodesUnicodePercentAndSpacesInUrls() {
        assertEquals("a%20b/%2520.wav", TrackArtSources.encodeRelativePath("a b/%20.wav"))
        assertEquals("http://server/files/a%20b.wav", TrackArtSources.serverFileUrl("http://server/", "a b.wav"))
    }

    @Test fun resolvesServerArtByPathAndYoutubeId() {
        val file = RemoteFile("Artist/Track [TOoC7aXkpMs].m4a", 10, 123)
        val index = ServerArtIndex.fromLibrary(listOf(file))
        assertEquals(file.path, index.resolve(file.path)?.path)
        assertEquals(file.path, index.resolve("Other [TOoC7aXkpMs].mp3")?.path)
        assertNull(index.resolve("missing.wav"))
        assertEquals("TOoC7aXkpMs", TrackArtSources.youtubeVideoId(file.path))
    }
}
