package com.vsmobile.editor.plugins

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private class ApiError(val code: String, message: String) : Exception(message)

class HostApi(
    private val granted: Set<Permission>,
    private val env: HostEnv,
    private val storage: PluginStorage,
    private val hooks: BlockingHooks,
    private val panelId: String? = null
) {

    private class Method(val permission: Permission?, val handler: (JSONArray) -> Any?)

    fun call(method: String, argsJson: String): String {
        val args = try {
            JSONArray(argsJson)
        } catch (e: JSONException) {
            return failure("bad_args", "Аргументы должны быть массивом JSON")
        }
        val entry = methods[method] ?: return failure("unknown_method", "Неизвестный метод: $method")
        val needed = entry.permission
        if (needed != null && needed !in granted) {
            return failure("permission_denied", "Нет разрешения: ${needed.key}")
        }
        return try {
            success(entry.handler(args))
        } catch (e: ApiError) {
            failure(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            failure("host_error", e.message ?: "Ошибка приложения")
        }
    }

    private fun success(value: Any?): String {
        val wrapped: Any = when (value) {
            null -> JSONObject.NULL
            is List<*> -> JSONArray(value)
            else -> value
        }
        return JSONObject().put("r", wrapped).toString()
    }

    private fun failure(code: String, message: String): String =
        JSONObject().put("e", code).put("m", message).toString()

    private fun str(args: JSONArray, index: Int): String {
        val value = if (index < args.length()) args.opt(index) else null
        return value as? String ?: throw ApiError("bad_args", "Аргумент ${index + 1} должен быть строкой")
    }

    private fun int(args: JSONArray, index: Int): Int {
        val value = if (index < args.length()) args.opt(index) else null
        return (value as? Number)?.toInt() ?: throw ApiError("bad_args", "Аргумент ${index + 1} должен быть числом")
    }

    private fun parts(args: JSONArray, index: Int): List<String> {
        val raw = str(args, index)
        return PathRules.split(raw) ?: throw ApiError("path_denied", "Недопустимый путь: $raw")
    }

    private fun noFile(): Nothing = throw ApiError("no_active_file", "Нет открытого файла")

    private fun notFound(): Nothing = throw ApiError("not_found", "Файл или папка не найдены")

    private fun done(ok: Boolean, message: String) {
        if (!ok) throw ApiError("host_error", message)
    }

    private fun toJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    private fun request(args: JSONArray): HttpSpec {
        val raw = args.optJSONObject(0) ?: throw ApiError("bad_args", "Нужен объект запроса")
        val url = raw.optString("url")
        val lower = url.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw ApiError("bad_args", "Разрешены только адреса http и https")
        }
        val headers = LinkedHashMap<String, String>()
        raw.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
        val body = if (raw.has("body") && !raw.isNull("body")) raw.optString("body") else null
        return HttpSpec(url, raw.optString("method", "GET").uppercase().ifEmpty { "GET" }, headers, body)
    }

    private val methods: Map<String, Method> = mapOf(
        "log" to Method(null) { a -> env.log(str(a, 0)); null },
        "app.info" to Method(null) { _ ->
            val info = env.appInfo()
            JSONObject().put("name", info.name).put("version", info.version)
                .put("project", info.project ?: JSONObject.NULL)
        },

        "editor.getText" to Method(Permission.EDITOR) { _ -> env.editorText() ?: noFile() },
        "editor.setText" to Method(Permission.EDITOR) { a ->
            if (!env.editorSetText(str(a, 0))) noFile()
            null
        },
        "editor.getSelection" to Method(Permission.EDITOR) { _ ->
            val selection = env.editorSelection() ?: noFile()
            JSONObject().put("start", selection.first).put("end", selection.second)
        },
        "editor.setSelection" to Method(Permission.EDITOR) { a ->
            if (!env.editorSetSelection(int(a, 0), int(a, 1))) noFile()
            null
        },
        "editor.replaceSelection" to Method(Permission.EDITOR) { a ->
            val insert = str(a, 0)
            val text = env.editorText() ?: noFile()
            val selection = env.editorSelection() ?: noFile()
            val start = minOf(selection.first, selection.second).coerceIn(0, text.length)
            val end = maxOf(selection.first, selection.second).coerceIn(0, text.length)
            if (!env.editorSetText(text.substring(0, start) + insert + text.substring(end))) noFile()
            env.editorSetSelection(start + insert.length, start + insert.length)
            null
        },
        "editor.insertAt" to Method(Permission.EDITOR) { a ->
            val offset = int(a, 0)
            val insert = str(a, 1)
            val text = env.editorText() ?: noFile()
            if (offset !in 0..text.length) throw ApiError("bad_args", "Позиция вне текста")
            if (!env.editorSetText(text.substring(0, offset) + insert + text.substring(offset))) noFile()
            null
        },
        "editor.fileName" to Method(Permission.EDITOR) { _ -> env.editorFileName() },
        "editor.tabs" to Method(Permission.EDITOR) { _ -> env.editorTabs() },
        "editor.openFile" to Method(Permission.FILES) { a ->
            if (!env.openFile(parts(a, 0))) notFound()
            null
        },

        "fs.read" to Method(Permission.FILES) { a -> env.fsRead(parts(a, 0)) ?: notFound() },
        "fs.write" to Method(Permission.FILES) { a ->
            done(env.fsWrite(parts(a, 0), str(a, 1)), "Не удалось записать файл")
            null
        },
        "fs.list" to Method(Permission.FILES) { a -> env.fsList(parts(a, 0)) ?: notFound() },
        "fs.exists" to Method(Permission.FILES) { a -> env.fsExists(parts(a, 0)) },
        "fs.mkdir" to Method(Permission.FILES) { a ->
            done(env.fsMkdir(parts(a, 0)), "Не удалось создать папку")
            null
        },
        "fs.remove" to Method(Permission.FILES) { a ->
            done(env.fsRemove(parts(a, 0)), "Не удалось удалить")
            null
        },
        "fs.rename" to Method(Permission.FILES) { a ->
            val newName = str(a, 1)
            if (!PathRules.isSafeName(newName)) throw ApiError("path_denied", "Недопустимое имя: $newName")
            done(env.fsRename(parts(a, 0), newName), "Не удалось переименовать")
            null
        },
        "fs.move" to Method(Permission.FILES) { a ->
            done(env.fsMove(parts(a, 0), parts(a, 1)), "Не удалось переместить")
            null
        },

        "storage.get" to Method(Permission.STORAGE) { a -> storage.get(str(a, 0)) },
        "storage.set" to Method(Permission.STORAGE) { a ->
            if (!storage.set(str(a, 0), str(a, 1))) throw ApiError("storage_full", "Хранилище плагина заполнено")
            null
        },
        "storage.remove" to Method(Permission.STORAGE) { a -> storage.remove(str(a, 0)); null },

        "ui.toast" to Method(Permission.UI) { a -> env.toast(str(a, 0)); null },
        "ui.confirm" to Method(Permission.UI) { a -> hooks.blocking { env.confirm(str(a, 0), str(a, 1)) } },
        "ui.prompt" to Method(Permission.UI) { a ->
            hooks.blocking { env.prompt(str(a, 0), str(a, 1), if (a.length() > 2) str(a, 2) else "") }
        },
        "ui.pick" to Method(Permission.UI) { a ->
            hooks.blocking { env.pick(str(a, 0), strings(a.optJSONArray(1))) }
        },
        "ui.openPanel" to Method(Permission.UI) { a -> env.openPanel(str(a, 0)) },
        "ui.closePanel" to Method(Permission.UI) { a -> env.closePanel(str(a, 0)); null },
        "ui.postToPanel" to Method(Permission.UI) { a ->
            env.postToPanel(str(a, 0), toJson(a.opt(1)))
            null
        },
        "panel.post" to Method(Permission.UI) { a ->
            val id = panelId ?: throw ApiError("bad_args", "panel.post доступен только из панели")
            env.emitToPlugin("panel.message:$id", toJson(a.opt(0)))
            null
        },

        "http.request" to Method(Permission.NETWORK) { a ->
            val result = env.http(request(a))
            val headers = JSONObject()
            result.headers.forEach { (k, v) -> headers.put(k, v) }
            JSONObject().put("status", result.status).put("headers", headers).put("body", result.body)
        },
        "ai.ask" to Method(Permission.AI) { a -> env.aiAsk(str(a, 0)) },
        "python.run" to Method(Permission.PYTHON) { a ->
            val result = env.pythonRun(str(a, 0))
            JSONObject().put("output", result.output).put("error", result.error ?: JSONObject.NULL)
        },
        "clipboard.read" to Method(Permission.CLIPBOARD) { _ -> env.clipboardRead() },
        "clipboard.write" to Method(Permission.CLIPBOARD) { a -> env.clipboardWrite(str(a, 0)); null }
    )
}
