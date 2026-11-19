package com.example.music.data.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest

object DownloadFiles {
    fun stagedFile(directory: File, folder: String, path: String, version: String?): File {
        directory.mkdirs()
        val key = MessageDigest.getInstance("SHA-256").digest("$folder|$path|$version".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.part")
    }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verify(file: File, size: Long, checksum: String?) {
        if (file.length() != size) throw IOException("Файл скачан не полностью")
        if (!checksum.isNullOrBlank() && !sha256(file).equals(checksum, ignoreCase = true)) {
            throw IOException("Контрольная сумма не совпала. Обновите список и повторите загрузку")
        }
    }
}
