package com.vsmobile.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile

data class RecentFolder(val treeUri: Uri, val doc: DocumentFile)

/** Результат чтения файла: текст либо причина, по которой файл нельзя открыть в редакторе. */
sealed interface ReadResult {
    data class Ok(val text: String) : ReadResult
    data class Failed(val reason: String) : ReadResult
}

/** Узел дерева файлов: имя и тип прочитаны один раз (каждое обращение к DocumentFile.name/isDirectory — запрос к провайдеру). */
data class FileNode(val file: DocumentFile, val name: String, val isDir: Boolean)

/** Проект в списке на главном экране с подсчитанным числом элементов. */
data class ProjectItem(val doc: DocumentFile, val name: String, val count: Int)

class FileRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vsmobile", Context.MODE_PRIVATE)
    private val recentKey = "recent_folders"
    private val maxRecent = 12
    private val maxTextBytes = 2 * 1024 * 1024
    private val accessFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    var rootUri: Uri? = null
        private set

    init {
        val saved = prefs.getString("root_uri", null)
        if (saved != null) rootUri = Uri.parse(saved)
    }

    fun setRootUri(uri: Uri) {
        rootUri = uri
        persistPermission(uri)
        prefs.edit().putString("root_uri", uri.toString()).apply()
    }

    fun persistPermission(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, accessFlags)
        } catch (_: SecurityException) {
        }
    }

    private fun releasePermission(uri: Uri) {
        if (uri == rootUri) return
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, accessFlags)
        } catch (_: SecurityException) {
        }
    }

    fun hasPersistedAccess(uri: Uri): Boolean {
        return appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun openFolder(treeUri: Uri): DocumentFile? {
        persistPermission(treeUri)
        val doc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        if (!doc.exists() || !doc.isDirectory) {
            removeRecent(treeUri)
            return null
        }
        addRecent(treeUri)
        return doc
    }

    private fun readRecentUris(): List<Uri> {
        val raw = prefs.getString(recentKey, "") ?: ""
        return raw.split('\n').filter { it.isNotBlank() }.map { Uri.parse(it) }
    }

    private fun writeRecentUris(list: List<Uri>) {
        prefs.edit().putString(recentKey, list.joinToString("\n") { it.toString() }).apply()
    }

    fun addRecent(treeUri: Uri) {
        val old = readRecentUris()
        val updated = (listOf(treeUri) + old.filter { it != treeUri }).take(maxRecent)
        old.filter { it !in updated }.forEach { releasePermission(it) }
        writeRecentUris(updated)
    }

    fun removeRecent(treeUri: Uri) {
        val old = readRecentUris()
        writeRecentUris(old.filter { it != treeUri })
        releasePermission(treeUri)
    }

    fun clearRecent() {
        val old = readRecentUris()
        writeRecentUris(emptyList())
        old.forEach { releasePermission(it) }
    }

    fun recentFolders(): List<RecentFolder> {
        return readRecentUris().mapNotNull { uri ->
            val doc = DocumentFile.fromTreeUri(appContext, uri)
            if (doc != null && doc.exists() && doc.canRead()) RecentFolder(uri, doc) else null
        }
    }

    private fun getRootDocument(): DocumentFile? {
        val uri = rootUri ?: return null
        return DocumentFile.fromTreeUri(appContext, uri)
    }

    /** Тяжёлые запросы к провайдеру — вызывать вне главного потока. */
    fun listProjects(): List<ProjectItem> {
        val root = getRootDocument() ?: return emptyList()
        return root.listFiles()
            .filter { it.isDirectory }
            .map { ProjectItem(it, it.name ?: "?", it.listFiles().size) }
            .sortedBy { it.name.lowercase() }
    }

    fun createProject(name: String): DocumentFile? {
        val root = getRootDocument() ?: return null
        return root.createDirectory(sanitize(name))
    }

    /** Папки сначала, затем файлы, по имени. Вызывать вне главного потока. */
    fun listNodes(dir: DocumentFile): List<FileNode> {
        return dir.listFiles()
            .map { FileNode(it, it.name ?: "?", it.isDirectory) }
            .sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun listChildren(dir: DocumentFile): List<DocumentFile> = listNodes(dir).map { it.file }

    fun createFolder(parent: DocumentFile, name: String): DocumentFile? {
        return parent.createDirectory(sanitize(name))
    }

    fun createFile(parent: DocumentFile, name: String): DocumentFile? {
        val clean = sanitize(name)
        // С MIME "text/plain" системный провайдер дописывает «.txt» к любому имени, кроме текстовых
        // (Main.kt → Main.kt.txt). Берём MIME по реальному расширению, а для неизвестных —
        // octet-stream: так имя остаётся ровно таким, как ввёл пользователь.
        val ext = clean.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val file = parent.createFile(mime, clean) ?: return null
        if (file.name != clean) {
            // Резервный вариант: провайдер всё равно переименовал файл — пробуем вернуть имя.
            file.renameTo(clean)
        }
        return file
    }

    /**
     * Читает файл как UTF-8. При ошибке, слишком большом или бинарном файле возвращает [ReadResult.Failed],
     * а не пустую строку — иначе пустая вкладка при сохранении затёрла бы настоящий файл.
     */
    fun readTextSafe(file: DocumentFile): ReadResult {
        if (file.length() > maxTextBytes) return ReadResult.Failed("Файл слишком большой (больше 2 МБ)")
        return try {
            val bytes = appContext.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return ReadResult.Failed("Не удалось открыть файл")
            when {
                bytes.size > maxTextBytes -> ReadResult.Failed("Файл слишком большой (больше 2 МБ)")
                bytes.any { it == 0.toByte() } -> ReadResult.Failed("Бинарный файл нельзя открыть в редакторе")
                else -> ReadResult.Ok(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            android.util.Log.e("FileRepository", "Не удалось прочитать ${file.uri}", e)
            ReadResult.Failed("Не удалось прочитать файл: ${e.message}")
        }
    }

    /** Возвращает true, если файл успешно записан. */
    fun writeText(file: DocumentFile, text: String): Boolean {
        return try {
            val stream = appContext.contentResolver.openOutputStream(file.uri, "wt") ?: return false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            android.util.Log.e("FileRepository", "Не удалось записать ${file.uri}", e)
            false
        }
    }

    fun rename(file: DocumentFile, newName: String): Boolean {
        return file.renameTo(sanitize(newName))
    }

    fun delete(file: DocumentFile): Boolean {
        return file.delete()
    }

    /**
     * Переносит файл или папку [source] из [sourceParent] в [targetDir].
     * Сначала пробует нативное перемещение провайдера, иначе — копирование + удаление.
     * Возвращает null при успехе или текст ошибки для пользователя.
     */
    fun move(source: DocumentFile, sourceParent: DocumentFile, targetDir: DocumentFile): String? {
        val name = source.name ?: return "Не удалось определить имя"
        if (targetDir.findFile(name) != null) return "В папке уже есть «$name»"
        try {
            val moved = DocumentsContract.moveDocument(
                appContext.contentResolver, source.uri, sourceParent.uri, targetDir.uri
            )
            if (moved != null) return null
        } catch (_: Exception) {
            // Провайдер не поддерживает move — уходим в копирование
        }
        return try {
            if (copyInto(source, targetDir) == null) return "Не удалось скопировать «$name»"
            if (source.delete()) null else "Скопировано, но оригинал удалить не удалось"
        } catch (e: Exception) {
            android.util.Log.e("FileRepository", "Ошибка перемещения", e)
            "Ошибка перемещения: ${e.message}"
        }
    }

    /** Рекурсивное копирование; при сбое удаляет частичную копию и возвращает null. */
    private fun copyInto(source: DocumentFile, targetDir: DocumentFile): DocumentFile? {
        val name = source.name ?: return null
        if (source.isDirectory) {
            val dir = targetDir.createDirectory(name) ?: return null
            for (child in source.listFiles()) {
                if (copyInto(child, dir) == null) {
                    dir.delete()
                    return null
                }
            }
            return dir
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val file = targetDir.createFile(mime, name) ?: return null
        if (file.name != name) file.renameTo(name)
        return try {
            val resolver = appContext.contentResolver
            val input = resolver.openInputStream(source.uri)
            val output = resolver.openOutputStream(file.uri, "w")
            if (input == null || output == null) {
                input?.close()
                output?.close()
                file.delete()
                null
            } else {
                input.use { i -> output.use { o -> i.copyTo(o) } }
                file
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
