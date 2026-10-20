package com.vsmobile.editor.plugins

import org.json.JSONObject
import java.io.File

class PluginStorage(
    private val file: File,
    private val limitBytes: Long = 4L * 1024 * 1024
) {

    @Synchronized
    fun get(key: String): String? {
        val json = read()
        return if (json.has(key) && !json.isNull(key)) json.getString(key) else null
    }

    @Synchronized
    fun set(key: String, value: String): Boolean {
        val json = read()
        json.put(key, value)
        val text = json.toString()
        if (text.toByteArray(Charsets.UTF_8).size > limitBytes) return false
        file.writeText(text)
        return true
    }

    @Synchronized
    fun remove(key: String) {
        val json = read()
        if (json.has(key)) {
            json.remove(key)
            file.writeText(json.toString())
        }
    }

    private fun read(): JSONObject =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
}
