package com.vsmobile.editor.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.documentfile.provider.DocumentFile
import com.vsmobile.editor.BuildConfig
import com.vsmobile.editor.EditorViewModel
import com.vsmobile.editor.PluginDialog
import com.vsmobile.editor.ReadResult
import com.vsmobile.editor.net.AiRepository
import com.vsmobile.editor.net.ChatMessage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

class AndroidHostEnv(
    private val vm: EditorViewModel,
    private val pluginId: String
) : HostEnv, TabBound {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var boundTab: String? = null

    override fun bindTab(uri: String?) {
        boundTab = uri
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun tabIndex(): Int {
        val uri = boundTab
        return if (uri != null) vm.openTabs.indexOfFirst { it.file.uri.toString() == uri } else vm.activeTabIndex
    }

    override fun editorText(): String? = onMain { vm.openTabs.getOrNull(tabIndex())?.content?.text }

    override fun editorSetText(text: String): Boolean = onMain {
        val index = tabIndex()
        val tab = vm.openTabs.getOrNull(index) ?: return@onMain false
        val selection = tab.content.selection
        val end = selection.end.coerceAtMost(text.length)
        val start = selection.start.coerceAtMost(end)
        vm.openTabs[index] = tab.copy(content = TextFieldValue(text, TextRange(start, end)), isDirty = true)
        true
    }

    override fun editorSelection(): Pair<Int, Int>? = onMain {
        vm.openTabs.getOrNull(tabIndex())?.content?.selection?.let { it.min to it.max }
    }

    override fun editorSetSelection(start: Int, end: Int): Boolean = onMain {
        val index = tabIndex()
        val tab = vm.openTabs.getOrNull(index) ?: return@onMain false
        val length = tab.content.text.length
        vm.openTabs[index] = tab.copy(
            content = TextFieldValue(tab.content.text, TextRange(start.coerceIn(0, length), end.coerceIn(0, length)))
        )
        true
    }

    override fun editorFileName(): String? = onMain { vm.openTabs.getOrNull(tabIndex())?.file?.name }

    override fun editorTabs(): List<String> = onMain { vm.openTabs.map { it.file.name ?: "?" } }

    private fun root(): DocumentFile? = onMain { vm.currentProject }

    private fun resolve(parts: List<String>): DocumentFile? {
        var current = root() ?: return null
        for (name in parts) current = current.findFile(name) ?: return null
        return current
    }

    private fun touchFiles() {
        main.post { vm.fsVersion++ }
    }

    override fun openFile(parts: List<String>): Boolean {
        val file = resolve(parts)?.takeIf { it.isFile } ?: return false
        onMain { vm.openFile(file) }
        return true
    }

    override fun fsRead(parts: List<String>): String? {
        val file = resolve(parts)?.takeIf { it.isFile } ?: return null
        return when (val result = vm.repo.readTextSafe(file)) {
            is ReadResult.Ok -> result.text
            is ReadResult.Failed -> throw IllegalStateException(result.reason)
        }
    }

    override fun fsWrite(parts: List<String>, text: String): Boolean {
        if (parts.isEmpty()) return false
        val parent = resolve(parts.dropLast(1))?.takeIf { it.isDirectory } ?: return false
        val name = parts.last()
        val existing = parent.findFile(name)
        if (existing != null && !existing.isFile) return false
        val file = existing ?: vm.repo.createFile(parent, name) ?: return false
        if (!vm.repo.writeText(file, text)) return false
        onMain {
            val index = vm.openTabs.indexOfFirst { it.file.uri == file.uri }
            if (index >= 0) vm.openTabs[index] = vm.openTabs[index].copy(content = TextFieldValue(text), isDirty = false)
        }
        touchFiles()
        return true
    }

    override fun fsList(parts: List<String>): List<String>? {
        val dir = resolve(parts)?.takeIf { it.isDirectory } ?: return null
        return vm.repo.listNodes(dir).map { if (it.isDir) it.name + "/" else it.name }
    }

    override fun fsExists(parts: List<String>): Boolean = resolve(parts) != null

    override fun fsMkdir(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        val parent = resolve(parts.dropLast(1))?.takeIf { it.isDirectory } ?: return false
        val existing = parent.findFile(parts.last())
        if (existing != null) return existing.isDirectory
        val created = vm.repo.createFolder(parent, parts.last()) != null
        if (created) touchFiles()
        return created
    }

    override fun fsRemove(parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        val file = resolve(parts) ?: return false
        onMain { vm.closeTabsUnder(file, saveFirst = false) }
        val removed = vm.repo.delete(file)
        if (removed) touchFiles()
        return removed
    }

    override fun fsRename(parts: List<String>, newName: String): Boolean {
        if (parts.isEmpty()) return false
        val file = resolve(parts) ?: return false
        onMain { vm.closeTabsUnder(file, saveFirst = true) }
        val renamed = vm.repo.rename(file, newName)
        if (renamed) touchFiles()
        return renamed
    }

    override fun fsMove(parts: List<String>, targetParts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        if (targetParts.size >= parts.size && targetParts.subList(0, parts.size) == parts) return false
        val source = resolve(parts) ?: return false
        val parent = resolve(parts.dropLast(1)) ?: return false
        val target = resolve(targetParts)?.takeIf { it.isDirectory } ?: return false
        onMain { vm.closeTabsUnder(source, saveFirst = true) }
        val moved = vm.repo.move(source, parent, target) == null
        if (moved) touchFiles()
        return moved
    }

    override fun toast(text: String) {
        main.post { Toast.makeText(vm.context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun <T> ask(cancelled: T, create: ((T) -> Unit) -> PluginDialog): T {
        val latch = CountDownLatch(1)
        var result = cancelled
        val dialog = create { value ->
            result = value
            latch.countDown()
        }
        main.post { vm.pluginDialogs.add(dialog) }
        latch.await()
        return result
    }

    override fun confirm(title: String, text: String): Boolean =
        ask(false) { answer -> PluginDialog.Confirm(pluginId, title, text, answer) }

    override fun prompt(title: String, label: String, initial: String): String? =
        ask<String?>(null) { answer -> PluginDialog.Prompt(pluginId, title, label, initial, answer) }

    override fun pick(title: String, items: List<String>): Int =
        ask(-1) { answer -> PluginDialog.Pick(pluginId, title, items, answer) }

    override fun openPanel(id: String): Boolean {
        val declared = vm.pluginStore.list().firstOrNull { it.plugin.id == pluginId }
            ?.plugin?.panels?.any { it.id == id } == true
        if (!declared) return false
        main.post { vm.activePanel = ActivePanel(pluginId, id) }
        return true
    }

    override fun closePanel(id: String) {
        main.post {
            if (vm.activePanel == ActivePanel(pluginId, id)) vm.activePanel = null
        }
    }

    override fun postToPanel(id: String, json: String) {
        val sink = vm.panelSinks["$pluginId/$id"] ?: return
        main.post { sink(json) }
    }

    override fun emitToPlugin(event: String, json: String) {
        vm.plugins.engine(pluginId)?.emit(event, json)
    }

    override fun http(spec: HttpSpec): HttpResult {
        val connection = URL(spec.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = spec.method
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            spec.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (spec.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readLimited(it) } ?: ""
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.joinToString(", ") }
            return HttpResult(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > HTTP_LIMIT_BYTES) throw IllegalStateException("Ответ больше 10 МБ")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    override fun aiAsk(prompt: String): String =
        AiRepository(vm.account).chat(listOf(ChatMessage("user", prompt)), null).text

    override fun pythonRun(code: String): PythonResult {
        if (vm.console.isRunning) return PythonResult("", "Консоль Python занята")
        return PythonBridge.run(code)
    }

    override fun clipboardRead(): String = onMain {
        val manager = vm.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(vm.context)?.toString() ?: ""
    }

    override fun clipboardWrite(text: String) {
        onMain {
            val manager = vm.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("vsm", text))
        }
    }

    override fun appInfo(): AppInfo = AppInfo("VS Mobile", BuildConfig.VERSION_NAME, onMain { vm.currentProject?.name })

    override fun log(text: String) {
        Log.i("VSM:$pluginId", text)
    }

    private companion object {
        const val HTTP_TIMEOUT_MS = 30_000
        const val HTTP_LIMIT_BYTES = 10 * 1024 * 1024
    }
}
