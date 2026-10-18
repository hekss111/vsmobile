package com.vsmobile.editor.plugins

import android.content.Context
import app.cash.quickjs.QuickJs
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object PluginRuntime {

    private const val MEMORY_LIMIT = 128L * 1024 * 1024

    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "vsm-plugin").apply { isDaemon = true }
    }

    fun dispatch(
        context: Context,
        plugin: VsmPlugin,
        kind: String,
        name: String,
        env: PluginEnvironment,
        payload: JSONObject?
    ): Result<Unit> = runCatching {
        val output = StringBuffer()
        val appContext = context.applicationContext
        val future = pool.submit(Callable { execute(appContext, plugin, kind, name, payload, env, output) })
        try {
            future.get(plugin.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            env.abort.set(true)
            throw IllegalStateException("Плагин не уложился в ${plugin.timeoutMs / 1000} с и был прерван")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (output.isNotBlank()) env.showOutput(plugin.name, output.toString())
        }
    }

    private fun execute(
        appContext: Context,
        plugin: VsmPlugin,
        kind: String,
        name: String,
        payload: JSONObject?,
        env: PluginEnvironment,
        output: StringBuffer
    ) {
        val quickJs = QuickJs.create()
        try {
            quickJs.memoryLimit = MEMORY_LIMIT
            quickJs.set("__host", VsmHost::class.java, HostImpl(appContext, plugin, env, output))
            val meta = JSONObject()
                .put("id", plugin.id)
                .put("name", plugin.name)
                .put("version", plugin.pluginVersion)
                .put("permissions", JSONArray(plugin.permissions.toList()))
            val modules = JSONObject().also { json -> plugin.files.forEach { (key, value) -> json.put(key, value) } }
            quickJs.evaluate(
                "var __meta = JSON.parse(${JSONObject.quote(meta.toString())});" +
                    " var __modules = JSON.parse(${JSONObject.quote(modules.toString())});",
                "bootstrap.js"
            )
            quickJs.evaluate(PluginPrelude.SOURCE, "prelude.js")
            quickJs.evaluate(plugin.code, "${plugin.id}.js")
            val context = if (kind == "command") commandContext(plugin, env) else payload ?: JSONObject()
            val raw = quickJs.evaluate(
                "__dispatch(${JSONObject.quote(kind)}, ${JSONObject.quote(name)}, " +
                    "JSON.parse(${JSONObject.quote(context.toString())}))",
                "dispatch.js"
            ) as? String
            applyResult(plugin, env, raw)
        } finally {
            quickJs.close()
        }
    }

    private fun commandContext(plugin: VsmPlugin, env: PluginEnvironment): JSONObject {
        val context = JSONObject()
        if ("editor" in plugin.permissions) {
            runCatching {
                val (start, end) = env.editorSelection()
                context.put("fileName", env.editorFileName())
                    .put("text", env.editorText())
                    .put("selectionStart", start)
                    .put("selectionEnd", end)
            }
        }
        return context
    }

    private fun applyResult(plugin: VsmPlugin, env: PluginEnvironment, raw: String?) {
        if (raw.isNullOrBlank() || raw == "null") return
        when (val value = JSONTokener(raw).nextValue()) {
            is String -> env.toast(value)
            is JSONObject -> {
                if ("editor" in plugin.permissions && value.has("text") && !value.isNull("text")) {
                    env.editorSetText(value.getString("text"))
                }
                val message = if (value.isNull("message")) "" else value.optString("message")
                if (message.isNotBlank()) env.toast(message)
            }
        }
    }
}
