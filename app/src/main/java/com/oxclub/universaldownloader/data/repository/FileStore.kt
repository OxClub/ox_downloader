package com.oxclub.universaldownloader.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.oxclub.universaldownloader.data.network.FolderNotAccessibleException
import com.oxclub.universaldownloader.data.preferences.SettingsRepository
import com.oxclub.universaldownloader.domain.model.DuplicatePolicy
import com.oxclub.universaldownloader.extractor.ResourceDetector
import com.oxclub.universaldownloader.utils.FileNames
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

data class SavedFile(val uri: String, val name: String, val size: Long)

/**
 * All file-system access. Data is downloaded into an app-private partial file and then copied into
 * either MediaStore Downloads/UniversalDownloader (default, no permission needed) or a folder the
 * user picked with the Storage Access Framework.
 */
class FileStore(private val context: Context, private val settings: SettingsRepository) {
    private val resolver get() = context.contentResolver

    // ---------- partial files ----------
    private val partialDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").apply { mkdirs() }

    fun partialFile(id: Long): File = File(partialDir, "$id.part")
    fun deletePartial(id: Long) { partialFile(id).delete() }
    fun partialFiles(): List<File> = partialDir.listFiles()?.toList().orEmpty()

    fun availableBytes(): Long = try {
        StatFs(partialDir.path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    // ---------- destination ----------
    private suspend fun treeDoc(): DocumentFile? {
        val tree = settings.current().downloadTreeUri ?: return null
        val doc = DocumentFile.fromTreeUri(context, Uri.parse(tree))
        if (doc == null || !doc.exists() || !doc.canWrite()) throw FolderNotAccessibleException()
        return doc
    }

    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val tree = treeDoc()
            if (tree != null) tree.findFile(name) != null else queryMediaStore(name) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun queryMediaStore(name: String): Uri? {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(name, "$DEFAULT_RELATIVE/"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        return null
    }

    /** Copies the finished partial file to its final location. Returns null when policy is SKIP and the name exists. */
    suspend fun saveFromTemp(temp: File, desiredName: String, policy: DuplicatePolicy): SavedFile? =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext().job
            val tree = treeDoc()
            if (tree != null) saveToTree(tree, temp, desiredName, policy, job) else saveToMediaStore(temp, desiredName, policy, job)
        }

    private fun mimeFor(name: String): String =
        ResourceDetector.mimeForExtension(FileNames.extensionOf(name)) ?: "application/octet-stream"

    private fun saveToMediaStore(temp: File, desiredName: String, policy: DuplicatePolicy, job: Job): SavedFile? {
        var name = desiredName
        val existing = queryMediaStore(name)
        if (existing != null) {
            when (policy) {
                DuplicatePolicy.SKIP -> return null
                DuplicatePolicy.REPLACE -> resolver.delete(existing, null, null)
                DuplicatePolicy.RENAME -> name = FileNames.uniqueName(name) { queryMediaStore(it) != null }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, DEFAULT_RELATIVE)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create a file in Downloads")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Unable to open the destination file")
            out.use { o -> temp.inputStream().use { copy(it, o, job) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return SavedFile(uri.toString(), displayName(uri) ?: name, temp.length())
    }

    private fun saveToTree(tree: DocumentFile, temp: File, desiredName: String, policy: DuplicatePolicy, job: Job): SavedFile? {
        var name = desiredName
        val existing = tree.findFile(name)
        if (existing != null) {
            when (policy) {
                DuplicatePolicy.SKIP -> return null
                DuplicatePolicy.REPLACE -> existing.delete()
                DuplicatePolicy.RENAME -> name = FileNames.uniqueName(name) { tree.findFile(it) != null }
            }
        }
        val doc = tree.createFile(mimeFor(name), name) ?: throw IOException("Unable to create a file in the chosen folder")
        try {
            val out = resolver.openOutputStream(doc.uri, "w") ?: throw IOException("Unable to open the destination file")
            out.use { o -> temp.inputStream().use { copy(it, o, job) } }
        } catch (e: Throwable) {
            runCatching { doc.delete() }
            throw e
        }
        return SavedFile(doc.uri.toString(), doc.name ?: name, temp.length())
    }

    private fun copy(input: InputStream, out: OutputStream, job: Job) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            job.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.flush()
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---------- operations on finished files ----------
    suspend fun delete(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
            else resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun uriExists(uriString: String?): Boolean = withContext(Dispatchers.IO) {
        if (uriString == null) return@withContext false
        try {
            resolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Renames a finished file; returns the new URI string or null on failure. */
    suspend fun rename(uriString: String, newName: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.renameDocument(resolver, uri, newName)?.toString()
            } else {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                if (resolver.update(uri, v, null, null) > 0) uriString else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Copies a finished file into [treeUri] (a folder picked by the user) and removes the original. */
    suspend fun moveTo(uriString: String, name: String, treeUri: String): SavedFile? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext null
            if (!tree.canWrite()) return@withContext null
            val finalName = FileNames.uniqueName(name) { tree.findFile(it) != null }
            val doc = tree.createFile(mimeFor(finalName), finalName) ?: return@withContext null
            var size = 0L
            val input = resolver.openInputStream(Uri.parse(uriString)) ?: return@withContext null
            val out = resolver.openOutputStream(doc.uri, "w") ?: return@withContext null
            input.use { i -> out.use { o ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    job.ensureActive()
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    size += n
                }
            } }
            delete(uriString)
            SavedFile(doc.uri.toString(), doc.name ?: finalName, size)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- information ----------
    /** (file count, total bytes) inside the download folder. */
    suspend fun folderStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        try {
            val tree = treeDoc()
            if (tree != null) {
                val files = tree.listFiles().filter { it.isFile }
                files.size to files.sumOf { it.length() }
            } else {
                var count = 0
                var bytes = 0L
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads.SIZE),
                    "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf("$DEFAULT_RELATIVE/"),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) { count++; bytes += c.getLong(0) }
                }
                count to bytes
            }
        } catch (e: Exception) {
            0 to 0L
        }
    }

    /** Human readable location of a SAF tree, e.g. "Download/Movies". */
    fun describeTree(treeUri: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        id.substringAfter(':', id).ifEmpty { "/" }
    } catch (e: Exception) {
        treeUri
    }

    companion object {
        const val DEFAULT_RELATIVE = "Download/UniversalDownloader"
        const val DEFAULT_LABEL = "Downloads/UniversalDownloader"
    }
}
