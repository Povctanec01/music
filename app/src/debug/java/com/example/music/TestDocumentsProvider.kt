package com.example.music

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Debug-only SAF fixture; never included in the release APK. */
class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var root: File
    private var failFinalRename = false
    private var reads = 0
    override fun onCreate(): Boolean { root = File(context!!.filesDir, "saf-tests").apply { mkdirs() }; return true }
    private fun file(id: String): File {
        val file = if (id == "root") root else File(root, id)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator) && file.canonicalPath != root.canonicalPath) throw FileNotFoundException()
        return file
    }
    private fun id(file: File) = if (file == root) "root" else file.relativeTo(root).invariantSeparatorsPath
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    private fun add(cursor: MatrixCursor, file: File) {
        val values = mapOf<String, Any>(
            Document.COLUMN_DOCUMENT_ID to id(file), Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (file.isDirectory) Document.MIME_TYPE_DIR else if (file.extension == "wav") "audio/wav" else "application/octet-stream",
            Document.COLUMN_SIZE to file.length(), Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0),
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
        addRow(arrayOf<Any>("test", "root", "Music test files", Root.FLAG_SUPPORTS_CREATE))
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).also { add(it, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: columns).also { cursor ->
        file(parentDocumentId).listFiles().orEmpty().forEach { add(cursor, it) }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode == "r") reads++
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(File(displayName).name == displayName)
        val child = File(file(parentDocumentId), displayName)
        if (child.exists()) throw FileNotFoundException("already exists")
        if (mimeType == Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
        return id(child)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val old = file(documentId)
        if (failFinalRename && old.name.startsWith(".music-stage-")) {
            failFinalRename = false
            throw FileNotFoundException("injected rename failure")
        }
        val target = File(old.parentFile, displayName)
        if (target.exists() || !old.renameTo(target)) throw FileNotFoundException("rename failed")
        return id(target)
    }
    override fun deleteDocument(documentId: String) { if (!file(documentId).delete()) throw FileNotFoundException() }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "reset" -> { root.listFiles().orEmpty().forEach { it.deleteRecursively() }; reads = 0; failFinalRename = false; Bundle() }
        "failRename" -> { failFinalRename = true; Bundle() }
        "reads" -> Bundle().apply { putInt("count", reads) }
        "resetReads" -> { reads = 0; Bundle() }
        else -> super.call(method, arg, extras)
    }
}
