package com.vsmobile.editor.plugins

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface VsmHost {
    fun request(method: String, args: String): String
}

class HostImpl(
    appContext: Context,
    private val plugin: VsmPlugin,
    private val env: PluginEnvironment,
    private val output: StringBuffer
) : VsmHost {

    private val prefs = appContext.getSharedPreferences(prefsName(plugin.id), Context.MODE_PRIVATE)

    override fun request(method: String, args: String): String =
        try {
            check(!env.abort.get()) { "Выполнение плагина прервано" }
            JSONObject().put("value", dispatch(method, JSONArray(args)) ?: JSONObject.NULL).toString()
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }

    private fun JSONArray.text(index: Int): String = if (isNull(index)) "" else optString(index)

    private inline fun <T> allow(permission: String, block: () -> T): T {
        if (permission !in plugin.permissions) {
            throw SecurityException("Плагину не выдано разрешение «$permission»")
        }
        return block()
    }

    private fun dispatch(method: String, a: JSONArray): Any? = when (method) {
        "log" -> {
            if (output.length < MAX_OUTPUT) {
                val level = a.text(0)
                output.append(if (level == "log") "" else "[$level] ").append(a.text(1)).append('\n')
            }
            null
        }
        "sleep" -> {
            Thread.sleep(a.optLong(0).coerceIn(0L, 60_000L))
            null
        }
        "project.name" -> env.projectName()

        "editor.getText" -> allow("editor") { env.editorText() }
        "editor.setText" -> allow("editor") { env.editorSetText(a.text(0)); null }
        "editor.getSelection" -> allow("editor") {
            env.editorSelection().let { JSONObject().put("start", it.first).put("end", it.second) }
        }
        "editor.setSelection" -> allow("editor") { env.editorSetSelection(a.optInt(0), a.optInt(1)); null }
        "editor.replaceSelection" -> allow("editor") { env.editorReplaceSelection(a.text(0)); null }
        "editor.getFileName" -> allow("editor") { env.editorFileName() }
        "editor.openFile" -> allow("editor") { env.editorOpenFile(a.text(0)); null }
        "editor.openFiles" -> allow("editor") { JSONArray(env.editorOpenFiles()) }
        "editor.save" -> allow("editor") { env.editorSave(); null }

        "fs.list" -> allow("fs.read") {
            val array = JSONArray()
            env.fsList(a.text(0)).forEach { array.put(JSONObject().put("name", it.name).put("isDir", it.isDir)) }
            array
        }
        "fs.read" -> allow("fs.read") { env.fsRead(a.text(0)) }
        "fs.exists" -> allow("fs.read") { env.fsExists(a.text(0)) }
        "fs.write" -> allow("fs.write") { env.fsWrite(a.text(0), a.text(1)); null }
        "fs.mkdir" -> allow("fs.write") { env.fsMkdir(a.text(0)); null }
        "fs.remove" -> allow("fs.write") { env.fsRemove(a.text(0)); null }
        "fs.rename" -> allow("fs.write") { env.fsRename(a.text(0), a.text(1)); null }

        "ui.toast" -> allow("ui") { env.toast(a.text(0)); null }
        "ui.alert" -> allow("ui") { env.alert(a.text(0), a.text(1)); null }
        "ui.confirm" -> allow("ui") { env.confirm(a.text(0), a.text(1)) }
        "ui.prompt" -> allow("ui") { env.prompt(a.text(0), a.text(1), a.text(2)) }
        "ui.select" -> allow("ui") {
            val items = a.optJSONArray(1)?.let { array -> (0 until array.length()).map { array.optString(it) } }
                ?: emptyList()
            env.select(a.text(0), items)
        }

        "http.fetch" -> allow("net") { fetch(a.text(0), a.optJSONObject(1)) }

        "storage.get" -> allow("storage") { prefs.getString(a.text(0), null) }
        "storage.set" -> allow("storage") {
            val value = a.text(1)
            require(value.length <= MAX_VALUE) { "Значение больше 1 МБ" }
            prefs.edit().putString(a.text(0), value).apply()
            null
        }
        "storage.remove" -> allow("storage") { prefs.edit().remove(a.text(0)).apply(); null }
        "storage.keys" -> allow("storage") { JSONArray(prefs.all.keys.toList()) }

        "ai.ask" -> allow("ai") { env.aiAsk(a.text(0), a.text(1).ifBlank { null }) }

        "clipboard.read" -> allow("clipboard") { env.clipboardRead() }
        "clipboard.write" -> allow("clipboard") { env.clipboardWrite(a.text(0)); null }

        else -> throw IllegalArgumentException("Неизвестный метод API: $method")
    }

    private fun fetch(url: String, options: JSONObject?): JSONObject {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Некорректный URL: $url")
        val method = (options?.optString("method")?.takeIf { it.isNotBlank() } ?: "GET").uppercase()
        val headers = options?.optJSONObject("headers")
        val body = options?.takeIf { it.has("body") && !it.isNull("body") }?.getString("body")
        val builder = Request.Builder().url(httpUrl)
        headers?.keys()?.forEach { key -> builder.addHeader(key, headers.getString(key)) }
        val contentType = (headers?.optString("Content-Type")?.takeIf { it.isNotBlank() }
            ?: "text/plain; charset=utf-8").toMediaType()
        val requestBody = when {
            body != null -> body.toRequestBody(contentType)
            method in BODY_METHODS -> "".toRequestBody(contentType)
            else -> null
        }
        builder.method(method, requestBody)
        return client.newCall(builder.build()).execute().use { response ->
            val responseHeaders = JSONObject()
            response.headers.names().forEach { responseHeaders.put(it, response.header(it)) }
            JSONObject()
                .put("status", response.code)
                .put("ok", response.isSuccessful)
                .put("headers", responseHeaders)
                .put("body", response.body?.string().orEmpty().take(MAX_BODY))
        }
    }

    companion object {
        private const val MAX_OUTPUT = 200_000
        private const val MAX_VALUE = 1_000_000
        private const val MAX_BODY = 5_000_000
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun prefsName(id: String): String = "vsm_plugin_$id"
    }
}
