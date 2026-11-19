package com.example.music.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.music.domain.AudioFormats
import com.example.music.domain.metadata.TrackMetadataReader
import com.example.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.io.IOException

class MusicScanner(private val context: Context) {

    suspend fun scan(
        treeUri: Uri,
        cachedTracksByRelativePath: Map<String, Track> = emptyMap(),
    ): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(treeUri, treeDocId, "", tracks, cachedTracksByRelativePath)
        tracks.sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    private suspend fun walk(
        treeUri: Uri,
        documentId: String,
        relativeDir: String,
        out: MutableList<Track>,
        cachedTracksByRelativePath: Map<String, Track>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                if (name.startsWith(".")) continue
                val mime = cursor.getString(mimeIdx)
                val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                val modified = if (cursor.isNull(modifiedIdx)) 0L else cursor.getLong(modifiedIdx)
                val childRel = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, childRel, out, cachedTracksByRelativePath)
                } else if (AudioFormats.isAudio(mime, name)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += readMetadata(
                        uri = uri,
                        displayName = name,
                        mime = mime.orEmpty(),
                        relativePath = childRel,
                        size = size,
                        lastModified = modified,
                        cachedTracksByRelativePath = cachedTracksByRelativePath,
                    )
                }
            }
        }
    }

    private suspend fun readMetadata(
        uri: Uri,
        displayName: String,
        mime: String,
        relativePath: String,
        size: Long,
        lastModified: Long,
        cachedTracksByRelativePath: Map<String, Track>,
    ): Track {
        // Пропускаем повторное чтение, если файл не изменился и метаданные уже полные.
        val cacheKey = relativePath.replace('\\', '/').lowercase()
        val cached = cachedTracksByRelativePath[cacheKey]
        val unchanged = cached != null && cached.size == size && cached.addedAt == lastModified && lastModified > 0
        val checksum = if (unchanged && cached?.sha256 != null) cached.sha256 else checksum(uri)
        if (cached != null && cached.size == size && cached.addedAt == lastModified && checksum != null && cached.sha256 == checksum &&
            !TrackMetadataReader.needsMetadataRefresh(cached, displayName)
        ) {
            return cached.copy(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                relativePath = relativePath.replace('\\', '/'),
                sha256 = checksum,
            )
        }

        val metadata = TrackMetadataReader.read(context, uri, displayName)
        return Track(
            id = uri.toString(),
            uri = uri,
            displayName = displayName,
            relativePath = relativePath.replace('\\', '/'),
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs,
            size = size,
            year = metadata.year,
            trackNumber = metadata.trackNumber,
            addedAt = lastModified,
            mimeType = mime.ifBlank { AudioFormats.mimeForFileName(displayName) },
            sha256 = checksum,
        )
    }

    private suspend fun checksum(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: IOException) { null }
}
