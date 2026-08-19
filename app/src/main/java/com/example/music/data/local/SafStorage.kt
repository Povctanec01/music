package com.example.music.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.music.domain.AudioFormats
import java.io.IOException

class SafStorage(private val context: Context) {

    fun root(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Нет доступа к выбранной папке")

    fun createOrOpenFile(treeUri: Uri, relativePath: String): DocumentFile {
        val parts = relativePath.split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) {
            throw IOException("Некорректный путь: $relativePath")
        }
        var dir = root(treeUri)
        for (i in 0 until parts.lastIndex) {
            val name = parts[i]
            dir = dir.findFile(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: throw IOException("Не удалось создать папку $name")
        }
        val fileName = parts.last()
        val existing = dir.findFile(fileName)
        if (existing != null && existing.isFile) return existing
        val mime = AudioFormats.mimeForFileName(fileName)
        return dir.createFile(mime, fileName)
            ?: dir.createFile(mime, fileName.substringBeforeLast('.'))
            ?: throw IOException("Не удалось создать файл $fileName")
    }
}
