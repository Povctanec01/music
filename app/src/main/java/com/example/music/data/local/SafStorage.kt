package com.example.music.data.local

import android.content.Context
import android.graphics.Bitmap
import com.example.music.domain.art.TrackArtCache
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.music.domain.AudioFormats
import com.example.music.domain.art.TrackSidecarArt
import java.io.IOException
import java.io.File
import java.util.UUID
import com.example.music.data.download.DownloadFiles

class SafStorage(private val context: Context) {

    /** Rename only track-matched covers, after a verified private copy was saved.
     * Hidden copies preserve user files and do not hide audio with a folder-wide .nomedia. */
    fun hideLegacySidecars(treeUri: Uri, audioPaths: List<String>, save: (String, ByteArray) -> Boolean) {
        for ((folder, paths) in audioPaths.groupBy { it.substringBeforeLast('/', "") }) {
            val parent = runCatching { directory(treeUri, if (folder.isEmpty()) emptyList() else splitRelativePath(folder), false) }
                .getOrNull() ?: continue
            val files = parent.listFiles()
            val audioNames = files.filter { it.isFile }.mapNotNull { it.name?.lowercase() }.toSet()
            val covers = paths.filter { it.substringAfterLast('/').lowercase() in audioNames }.flatMap { path ->
                TrackSidecarArt.sidecarFileNames(TrackSidecarArt.stemFromAudioPath(path)).map { it.lowercase() to path }
            }.groupBy({ it.first }, { it.second })
            for (file in files) {
                val name = file.name ?: continue
                val matching = covers[name.lowercase()] ?: continue
                if (!file.isFile) continue
                runCatching {
                    val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return@runCatching
                    if (matching.all { save(it, bytes) } && parent.findFile(".music-cover-$name") == null) {
                        file.renameTo(".music-cover-$name")
                    }
                }
            }
        }
    }

    /** Providers without rename support fail safely instead of overwriting the old audio. */
    fun replaceVerified(treeUri: Uri, relativePath: String, source: File, checksum: String?, canContinue: () -> Boolean = { true }): Uri {
        DownloadFiles.verify(source, source.length(), checksum)
        val parts = splitRelativePath(relativePath)
        val parent = directory(treeUri, parts.dropLast(1), create = true)
        val name = parts.last()
        val temp = parent.createFile("application/octet-stream", ".music-stage-${UUID.randomUUID()}")
            ?: throw IOException("Не удалось создать временный файл")
        var backup: DocumentFile? = null
        var committed = false
        try {
            context.contentResolver.openOutputStream(temp.uri, "rwt")?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!canContinue()) throw IOException("Замена приостановлена или отменена")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Нет доступа к временному файлу")
            val actual = context.contentResolver.openInputStream(temp.uri)?.use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                var size = 0L
                while (true) {
                    if (!canContinue()) throw IOException("Замена приостановлена или отменена")
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count); size += count
                }
                size to digest.digest().joinToString("") { "%02x".format(it) }
            } ?: throw IOException("Не удалось проверить временный файл")
            if (actual.first != source.length() || (!checksum.isNullOrBlank() && !actual.second.equals(checksum, true))) {
                throw IOException("Проверка записанного файла не пройдена")
            }
            if (!canContinue()) throw IOException("Замена приостановлена или отменена")
            parent.findFileIgnoreCase(name)?.let { existing ->
                if (!existing.isFile || !existing.renameTo(".music-backup-${UUID.randomUUID()}")) {
                    throw IOException("Папка не поддерживает безопасную замену файлов")
                }
                backup = existing
            }
            if (!temp.renameTo(name)) throw IOException("Не удалось завершить безопасную замену")
            committed = true
            runCatching { backup?.delete() }
            return temp.uri
        } finally {
            if (!committed) {
                temp.delete()
                if (backup?.renameTo(name) == false) throw IOException("Исходный файл сохранён под резервным именем в выбранной папке")
            }
        }
    }

    fun moveToTrash(treeUri: Uri, relativePath: String): String {
        val parts = splitRelativePath(relativePath)
        val parent = directory(treeUri, parts.dropLast(1), create = false)
        val file = parent.findFileIgnoreCase(parts.last()) ?: throw IOException("Файл не найден")
        val hidden = ".music-trash-${UUID.randomUUID()}"
        if (!file.renameTo(hidden)) throw IOException("Папка не поддерживает перемещение в корзину")
        return (parts.dropLast(1) + hidden).joinToString("/")
    }

    fun restoreFromTrash(treeUri: Uri, originalPath: String, hiddenPath: String) {
        val parts = splitRelativePath(originalPath)
        val hiddenParts = splitRelativePath(hiddenPath)
        require(parts.dropLast(1) == hiddenParts.dropLast(1))
        val parent = directory(treeUri, parts.dropLast(1), create = false)
        if (parent.findFileIgnoreCase(parts.last()) != null) throw IOException("Файл с таким именем уже существует")
        val hidden = parent.findFileIgnoreCase(hiddenParts.last()) ?: throw IOException("Файл в корзине не найден")
        if (!hidden.renameTo(parts.last())) throw IOException("Не удалось восстановить файл")
    }

    private fun directory(treeUri: Uri, parts: List<String>, create: Boolean): DocumentFile {
        var current = root(treeUri)
        for (name in parts) current = current.findFileIgnoreCase(name)?.takeIf { it.isDirectory }
            ?: (if (create) current.createDirectory(name) else null)
            ?: throw IOException("Папка не найдена: $name")
        return current
    }

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
        val sidecarName = ".music-cover-$stem.jpg"
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
        val sidecar = parent.findFileIgnoreCase(".music-cover-$stem.jpg")
            ?: parent.findFileIgnoreCase("$stem.jpg") ?: return null
        if (!sidecar.isFile) return null
        val bytes = context.contentResolver.openInputStream(sidecar.uri)?.use { it.readBytes() } ?: return null
        return TrackArtCache.decode(bytes)
            ?.takeIf { it.width > 1 && it.height > 1 }
    }

    fun writeSidecarImage(treeUri: Uri, audioRelativePath: String, bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        val sidecarPath = hiddenSidecarPath(audioRelativePath)
        return writeBytes(treeUri, sidecarPath, "image/jpeg", bytes)
    }

    fun readSidecarBitmap(treeUri: Uri, audioRelativePath: String): Bitmap? {
        val sidecarPath = TrackSidecarArt.primarySidecarRelativePath(audioRelativePath)
        val bytes = readBytes(treeUri, hiddenSidecarPath(audioRelativePath))
            ?: readBytes(treeUri, sidecarPath) ?: return null
        return TrackArtCache.decode(bytes)
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

    private fun hiddenSidecarPath(path: String): String {
        val normal = TrackSidecarArt.primarySidecarRelativePath(path)
        val folder = normal.substringBeforeLast('/', "")
        return (if (folder.isEmpty()) "" else "$folder/") + ".music-cover-${normal.substringAfterLast('/')}"
    }

    private fun DocumentFile.findFileIgnoreCase(name: String): DocumentFile? {
        findFile(name)?.let { return it }
        return listFiles()?.firstOrNull { file ->
            file.name?.equals(name, ignoreCase = true) == true
        }
    }
}
