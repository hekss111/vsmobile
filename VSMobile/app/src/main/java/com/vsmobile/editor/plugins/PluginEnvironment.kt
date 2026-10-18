package com.vsmobile.editor.plugins

import java.util.concurrent.atomic.AtomicBoolean

data class FsEntry(val name: String, val isDir: Boolean)

interface PluginEnvironment {
    val abort: AtomicBoolean

    fun projectName(): String

    fun editorText(): String
    fun editorSetText(text: String)
    fun editorSelection(): Pair<Int, Int>
    fun editorSetSelection(start: Int, end: Int)
    fun editorReplaceSelection(text: String)
    fun editorFileName(): String
    fun editorOpenFile(path: String)
    fun editorOpenFiles(): List<String>
    fun editorSave()

    fun fsList(path: String): List<FsEntry>
    fun fsRead(path: String): String
    fun fsWrite(path: String, text: String)
    fun fsExists(path: String): Boolean
    fun fsMkdir(path: String)
    fun fsRemove(path: String)
    fun fsRename(path: String, newName: String)

    fun toast(text: String)
    fun alert(title: String, message: String)
    fun confirm(title: String, message: String): Boolean
    fun prompt(title: String, label: String, initial: String): String?
    fun select(title: String, items: List<String>): Int
    fun showOutput(title: String, text: String)

    fun clipboardRead(): String
    fun clipboardWrite(text: String)

    fun aiAsk(prompt: String, system: String?): String
}
