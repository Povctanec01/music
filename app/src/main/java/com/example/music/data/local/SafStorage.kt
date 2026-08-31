package com.example.music.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.music.domain.AudioFormats
import com.example.music.domain.art.TrackSidecarArt
import java.io.IOException

class SafStorage(private val context: Context) {

    fun root(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Нет доступа к выбранной папке")

    fun createOrOpenFile(treeUri: Uri, relativePath: String): DocumentFile {
        val parts = splitRelativePath(relativePath)
        var dir = root(treeUri)
        for (i in 0 until parts.lastIndex) {
            val name = parts[i]
            dir = dir.findFileIgnoreCase(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: throw IOException("Не удалось создать папку $name")
        }
        val fileName = parts.last()
        val existing = dir.findFileIgnoreCase(fileName)
        if (existing != null && existing.isFile) return existing
        val mime = AudioFormats.mimeForFileName(fileName)
        return dir.createFile(mime, fileName)
            ?: dir.createFile(mime, fileName.substringBeforeLast('.'))
            ?: throw IOException("Не удалось создать файл $fileName")
    }

    fun writeSidecarNextToAudio(audioUri: Uri, bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        val audio = DocumentFile.fromSingleUri(context, audioUri) ?: return false
        if (!audio.isFile) return false
        val parent = audio.parentFile ?: return false
        val stem = audio.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: return false
        val sidecarName = "$stem.jpg"
        val sidecar = parent.findFileIgnoreCase(sidecarName)?.takeIf { it.isFile }
            ?: parent.createFile("image/jpeg", sidecarName)
            ?: parent.createFile("image/jpg", sidecarName)
            ?: return false
        val written = context.contentResolver.openOutputStream(sidecar.uri, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
            true
        } ?: false
        if (!written) return false
        val readBack = context.contentResolver.openInputStream(sidecar.uri)?.use { it.available() } ?: 0
        return readBack > 128
    }

    fun readSidecarNextToAudio(audioUri: Uri): Bitmap? {
        val audio = DocumentFile.fromSingleUri(context, audioUri) ?: return null
        if (!audio.isFile) return null
        val parent = audio.parentFile ?: return null
        val stem = audio.name?.substringBeforeLast('.') ?: return null
        val sidecar = parent.findFileIgnoreCase("$stem.jpg") ?: return null
        if (!sidecar.isFile) return null
        val bytes = context.contentResolver.openInputStream(sidecar.uri)?.use { it.readBytes() } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.takeIf { it.width > 1 && it.height > 1 }
    }

    fun writeSidecarImage(treeUri: Uri, audioRelativePath: String, bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        val sidecarPath = TrackSidecarArt.primarySidecarRelativePath(audioRelativePath)
        return writeBytes(treeUri, sidecarPath, "image/jpeg", bytes)
    }

    fun readSidecarBitmap(treeUri: Uri, audioRelativePath: String): Bitmap? {
        val sidecarPath = TrackSidecarArt.primarySidecarRelativePath(audioRelativePath)
        val bytes = readBytes(treeUri, sidecarPath) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.takeIf { it.width > 1 && it.height > 1 }
    }

    fun deleteDocument(uri: Uri): Boolean {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return false
        if (!doc.isFile) return false
        return doc.delete()
    }

    fun deleteRelativeFile(treeUri: Uri, relativePath: String): Boolean {
        val parts = splitRelativePath(relativePath)
        if (parts.isEmpty()) return false
        var dir = root(treeUri)
        for (i in 0 until parts.lastIndex) {
            dir = dir.findFileIgnoreCase(parts[i])?.takeIf { it.isDirectory } ?: return false
        }
        val file = dir.findFileIgnoreCase(parts.last()) ?: return false
        return file.delete()
    }

    fun deleteSidecarsForAudio(treeUri: Uri, audioRelativePath: String, audioUri: Uri? = null) {
        if (audioUri != null) {
            deleteSidecarsNextToAudio(audioUri)
        }
        val dirPart = audioRelativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val stem = TrackSidecarArt.stemFromAudioPath(audioRelativePath)
        TrackSidecarArt.sidecarFileNames(stem).forEach { fileName ->
            val path = if (dirPart.isEmpty()) fileName else "$dirPart/$fileName"
            deleteRelativeFile(treeUri, path)
        }
    }

    fun deleteSidecarsNextToAudio(audioUri: Uri) {
        val audio = DocumentFile.fromSingleUri(context, audioUri) ?: return
        val parent = audio.parentFile ?: return
        val stem = audio.name?.substringBeforeLast('.') ?: return
        TrackSidecarArt.sidecarFileNames(stem).forEach { name ->
            parent.findFileIgnoreCase(name)?.takeIf { it.isFile }?.delete()
        }
    }

    private fun writeBytes(treeUri: Uri, relativePath: String, mimeType: String, bytes: ByteArray): Boolean {
        val parts = splitRelativePath(relativePath)
        var dir = root(treeUri)
        for (i in 0 until parts.lastIndex) {
            val name = parts[i]
            dir = dir.findFileIgnoreCase(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: return false
        }
        val fileName = parts.last()
        val file = dir.findFileIgnoreCase(fileName)?.takeIf { it.isFile }
            ?: dir.createFile(mimeType, fileName)
            ?: return false
        return context.contentResolver.openOutputStream(file.uri, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
            true
        } ?: false
    }

    private fun readBytes(treeUri: Uri, relativePath: String): ByteArray? {
        val parts = splitRelativePath(relativePath)
        if (parts.isEmpty()) return null
        var dir = root(treeUri)
        for (i in 0 until parts.lastIndex) {
            dir = dir.findFileIgnoreCase(parts[i])?.takeIf { it.isDirectory } ?: return null
        }
        val file = dir.findFileIgnoreCase(parts.last())?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    private fun splitRelativePath(relativePath: String): List<String> {
        val parts = relativePath.split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) {
            throw IOException("Некорректный путь: $relativePath")
        }
        return parts
    }

    private fun DocumentFile.findFileIgnoreCase(name: String): DocumentFile? {
        findFile(name)?.let { return it }
        return listFiles()?.firstOrNull { file ->
            file.name?.equals(name, ignoreCase = true) == true
        }
    }
}
