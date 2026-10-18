package com.vsmobile.editor

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vsmobile.editor.net.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

// Позволяют писать `var x by vm::prop` в composable — состояние живёт во ViewModel, имена в коде остаются прежними.
operator fun <T> KMutableProperty0<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()
operator fun <T> KMutableProperty0<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)

/** То, что переживает смерть процесса: проект, раскрытые папки и список вкладок (содержимое берётся с диска). */
private data class UiSnapshot(
    val project: String?,
    val expanded: Set<String>,
    val tabs: List<String>,
    val active: Int
)

/**
 * Состояние редактора: открытый проект, вкладки и файловые операции.
 * Переживает поворот экрана; после смерти процесса проект и вкладки восстанавливаются из [SavedStateHandle]
 * (несохранённые правки при этом уже записаны на диск в ON_STOP).
 */
class EditorViewModel(
    private val app: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(app) {

    val repo = FileRepository(app)
    val account = AccountRepository(app)
    val console = PythonConsole(viewModelScope)

    var currentProject by mutableStateOf<DocumentFile?>(null)
    var expandedDirs by mutableStateOf(setOf<String>())
    val openTabs = mutableStateListOf<OpenTab>()
    var activeTabIndex by mutableStateOf(-1)

    // Все обращения к содержимому файлов сериализуются: чтение не видит недописанный файл, две записи не перемешиваются.
    // Блокировка всегда берётся и отпускается на IO-потоке, поэтому runBlocking на главном потоке не зависнет.
    private val fileMutex = Mutex()

    init {
        viewModelScope.launch {
            restore()
            // Начинаем сохранять состояние только после восстановления, иначе пустое состояние затрёт сохранённое.
            snapshotFlow {
                UiSnapshot(
                    currentProject?.uri?.toString(),
                    expandedDirs,
                    openTabs.map { it.file.uri.toString() },
                    activeTabIndex
                )
            }.collect { s ->
                saved["project_uri"] = s.project
                saved["expanded"] = ArrayList(s.expanded)
                saved["tabs"] = ArrayList(s.tabs)
                saved["active"] = s.active
            }
        }
    }

    private suspend fun restore() {
        val projectUri = saved.get<String>("project_uri") ?: return
        val doc = withContext(Dispatchers.IO) {
            runCatching {
                DocumentFile.fromTreeUri(app, Uri.parse(projectUri))?.takeIf { it.exists() && it.isDirectory }
            }.getOrNull()
        } ?: return
        expandedDirs = saved.get<ArrayList<String>>("expanded")?.toSet() ?: setOf(doc.uri.toString())
        currentProject = doc

        for (uriString in saved.get<ArrayList<String>>("tabs").orEmpty()) {
            val file = runCatching { DocumentFile.fromSingleUri(app, Uri.parse(uriString)) }.getOrNull() ?: continue
            val res = withContext(Dispatchers.IO) { fileMutex.withLock { repo.readTextSafe(file) } }
            if (res is ReadResult.Ok) openTabs.add(OpenTab(file, TextFieldValue(res.text)))
        }
        val active = saved.get<Int>("active") ?: -1
        activeTabIndex = if (openTabs.isEmpty()) -1 else active.coerceIn(0, openTabs.size - 1)
    }

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    fun openProject(doc: DocumentFile) {
        expandedDirs = setOf(doc.uri.toString())
        currentProject = doc
    }

    /** Открывает выбранную папку как проект; текущие вкладки сохраняются и закрываются. */
    fun openFolder(uri: Uri) {
        val doc = repo.openFolder(uri)
        if (doc == null) {
            toast("Не удалось открыть папку")
            return
        }
        saveAllTabs()
        openTabs.clear()
        activeTabIndex = -1
        openProject(doc)
    }

    fun closeProject() {
        saveAllTabs()
        openTabs.clear()
        activeTabIndex = -1
        currentProject = null
    }

    /** Сохраняет вкладку в фоне; вкладка остаётся «грязной» до успешной записи. */
    fun saveTab(idx: Int) {
        if (idx !in openTabs.indices) return
        val tab = openTabs[idx]
        if (!tab.isDirty) return
        val file = tab.file
        val text = tab.content.text
        viewModelScope.launch(Dispatchers.IO) {
            val ok = fileMutex.withLock { repo.writeText(file, text) }
            withContext(Dispatchers.Main) {
                if (ok) {
                    // Пока писали, текст могли изменить — тогда вкладка остаётся «грязной».
                    val i = openTabs.indexOfFirst { it.file.uri == file.uri }
                    if (i >= 0 && openTabs[i].content.text == text) {
                        openTabs[i] = openTabs[i].copy(isDirty = false)
                    }
                } else {
                    toast("Не удалось сохранить «${file.name}»")
                }
            }
        }
    }

    /** Синхронное сохранение: нужно, когда файл сразу после этого переименуют/переместят или процесс могут убить. */
    fun saveTabNow(idx: Int) {
        if (idx !in openTabs.indices) return
        val tab = openTabs[idx]
        if (!tab.isDirty) return
        val ok = runBlocking { fileMutex.withLock { repo.writeText(tab.file, tab.content.text) } }
        if (ok) {
            openTabs[idx] = tab.copy(isDirty = false)
        } else {
            toast("Не удалось сохранить «${tab.file.name}»")
        }
    }

    fun saveAllTabs() {
        for (i in openTabs.indices) saveTab(i)
    }

    fun saveAllTabsNow() {
        for (i in openTabs.indices) saveTabNow(i)
    }

    fun openFile(file: DocumentFile) {
        val existingIdx = openTabs.indexOfFirst { it.file.uri.toString() == file.uri.toString() }
        if (existingIdx >= 0) {
            activeTabIndex = existingIdx
            return
        }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { fileMutex.withLock { repo.readTextSafe(file) } }
            // Пока читали, вкладку могли открыть повторным нажатием.
            val again = openTabs.indexOfFirst { it.file.uri.toString() == file.uri.toString() }
            if (again >= 0) {
                activeTabIndex = again
                return@launch
            }
            when (res) {
                is ReadResult.Ok -> {
                    openTabs.add(OpenTab(file, TextFieldValue(res.text)))
                    activeTabIndex = openTabs.size - 1
                }
                is ReadResult.Failed -> toast(res.reason)
            }
        }
    }

    fun closeTab(idx: Int, save: Boolean = true) {
        if (idx !in openTabs.indices) return
        if (save) saveTab(idx)
        openTabs.removeAt(idx)
        activeTabIndex = when {
            openTabs.isEmpty() -> -1
            idx <= activeTabIndex && activeTabIndex > 0 -> activeTabIndex - 1
            else -> activeTabIndex.coerceAtMost(openTabs.size - 1)
        }
    }

    /** Закрывает вкладки файла/папки (и вложенных), чтобы не держать устаревшие URI после переименования или удаления. */
    fun closeTabsUnder(target: DocumentFile, saveFirst: Boolean) {
        val base = target.uri.toString()
        val affected = openTabs.indices.filter {
            val u = openTabs[it].file.uri.toString()
            u == base || u.startsWith("$base%2F")
        }
        for (i in affected.reversed()) {
            if (saveFirst) saveTabNow(i)
            // Сохранение уже сделано выше (или не нужно при удалении) — фоновая запись не должна гоняться с delete/rename.
            closeTab(i, save = false)
        }
    }
}
