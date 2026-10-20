package com.vsmobile.editor.plugins

data class HttpSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?
)

data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)

data class PythonResult(val output: String, val error: String?)

data class AppInfo(val name: String, val version: String, val project: String?)

interface TabBound {
    fun bindTab(uri: String?)
}

interface BlockingHooks {
    fun <T> blocking(block: () -> T): T
}

interface HostEnv {
    fun editorText(): String?
    fun editorSetText(text: String): Boolean
    fun editorSelection(): Pair<Int, Int>?
    fun editorSetSelection(start: Int, end: Int): Boolean
    fun editorFileName(): String?
    fun editorTabs(): List<String>
    fun openFile(parts: List<String>): Boolean
    fun fsRead(parts: List<String>): String?
    fun fsWrite(parts: List<String>, text: String): Boolean
    fun fsList(parts: List<String>): List<String>?
    fun fsExists(parts: List<String>): Boolean
    fun fsMkdir(parts: List<String>): Boolean
    fun fsRemove(parts: List<String>): Boolean
    fun fsRename(parts: List<String>, newName: String): Boolean
    fun fsMove(parts: List<String>, targetParts: List<String>): Boolean
    fun toast(text: String)
    fun confirm(title: String, text: String): Boolean
    fun prompt(title: String, label: String, initial: String): String?
    fun pick(title: String, items: List<String>): Int
    fun openPanel(id: String): Boolean
    fun closePanel(id: String)
    fun postToPanel(id: String, json: String)
    fun emitToPlugin(event: String, json: String)
    fun http(spec: HttpSpec): HttpResult
    fun aiAsk(prompt: String): String
    fun pythonRun(code: String): PythonResult
    fun clipboardRead(): String
    fun clipboardWrite(text: String)
    fun appInfo(): AppInfo
    fun log(text: String)
}
