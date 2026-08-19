package com.example.music.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.example.music.domain.AudioFormats
import com.example.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicScanner(private val context: Context) {

    suspend fun scan(treeUri: Uri): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(treeUri, treeDocId, "", tracks)
        tracks.sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }

    private fun walk(
        treeUri: Uri,
        documentId: String,
        relativeDir: String,
        out: MutableList<Track>,
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
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                if (name.startsWith(".")) continue
                val mime = cursor.getString(mimeIdx)
                val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                val modified = if (cursor.isNull(modifiedIdx)) 0L else cursor.getLong(modifiedIdx)
                val childRel = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, childRel, out)
                } else if (AudioFormats.isAudio(mime, name)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += readMetadata(uri, name, mime.orEmpty(), childRel, size, modified)
                }
            }
        }
    }

    private fun readMetadata(
        uri: Uri,
        displayName: String,
        mime: String,
        relativePath: String,
        size: Long,
        lastModified: Long,
    ): Track {
        val fallbackTitle = displayName.substringBeforeLast('.')
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')
                ?.trim()
                ?.toIntOrNull()
            Track(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                relativePath = relativePath.replace('\\', '/'),
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                size = size,
                year = year,
                trackNumber = trackNumber,
                addedAt = lastModified,
                mimeType = mime.ifBlank { AudioFormats.mimeForFileName(displayName) },
            )
        } catch (_: Exception) {
            Track(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                relativePath = relativePath.replace('\\', '/'),
                title = fallbackTitle,
                artist = "",
                album = "",
                durationMs = 0L,
                size = size,
                year = null,
                trackNumber = null,
                addedAt = lastModified,
                mimeType = mime.ifBlank { AudioFormats.mimeForFileName(displayName) },
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
