package com.vsmobile.editor.plugins

import org.json.JSONArray
import org.json.JSONObject

data class PluginCommand(val id: String, val title: String)

data class VsmPlugin(
    val id: String,
    val name: String,
    val pluginVersion: String,
    val description: String,
    val commands: List<PluginCommand>,
    val code: String,
    val permissions: Set<String>,
    val events: Set<String>,
    val files: Map<String, String>,
    val timeoutMs: Long
)

object VsmFormat {
    const val EXTENSION = "vsm"
    private const val FORMAT = "vsm"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_TIMEOUT_MS = 60_000L
    private const val MAX_TIMEOUT_MS = 600_000L
    private val idRegex = Regex("[a-z0-9][a-z0-9_-]{1,63}")
    private val moduleRegex = Regex("[\\w./-]{1,100}")

    val permissionTitles: Map<String, String> = linkedMapOf(
        "editor" to "Читать и изменять открытый файл",
        "fs.read" to "Читать файлы проекта",
        "fs.write" to "Создавать, менять, переименовывать и удалять файлы проекта",
        "ui" to "Показывать уведомления и диалоги",
        "net" to "Отправлять сетевые запросы",
        "storage" to "Хранить свои данные на устройстве",
        "ai" to "Обращаться к ИИ-помощнику от вашего имени",
        "clipboard" to "Читать и менять буфер обмена"
    )

    val eventTitles: Map<String, String> = linkedMapOf(
        "projectOpened" to "при открытии проекта",
        "fileOpened" to "при открытии файла",
        "fileSaved" to "при сохранении файла"
    )

    fun parse(raw: String): Result<VsmPlugin> = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл плагина больше 2 МБ" }
        val json = JSONObject(raw)
        require(json.optString("format") == FORMAT) { "Это не файл плагина .vsm" }
        val version = json.optInt("version")
        require(version == 1 || version == 2) { "Неподдерживаемая версия формата .vsm" }
        val id = json.optString("id")
        require(idRegex.matches(id)) { "Некорректный id плагина: используйте латиницу, цифры, «-» и «_»" }
        val name = json.optString("name").trim()
        require(name.isNotEmpty()) { "В плагине не указано название" }
        val commandsJson = json.optJSONArray("commands") ?: JSONArray()
        val commands = (0 until commandsJson.length()).map { index ->
            val item = commandsJson.getJSONObject(index)
            val commandId = item.optString("id")
            require(idRegex.matches(commandId)) { "Некорректный id команды: $commandId" }
            PluginCommand(commandId, item.optString("title").ifBlank { commandId })
        }
        require(commands.map { it.id }.toSet().size == commands.size) { "Повторяются id команд" }
        val events = stringSet(json.optJSONArray("events"))
        events.forEach { require(it in eventTitles) { "Неизвестное событие: $it" } }
        require(commands.isNotEmpty() || events.isNotEmpty()) { "В плагине нет ни команд, ни событий" }
        val permissions = if (version == 1) setOf("editor") else stringSet(json.optJSONArray("permissions"))
        permissions.forEach { require(it in permissionTitles) { "Неизвестное разрешение: $it" } }
        val code = json.optString("code")
        require(code.isNotBlank()) { "В плагине пустой код" }
        val files = json.optJSONObject("files")?.let { obj ->
            obj.keys().asSequence().associateWith { key ->
                require(moduleRegex.matches(key)) { "Некорректное имя файла в files: $key" }
                obj.getString(key)
            }
        } ?: emptyMap()
        VsmPlugin(
            id = id,
            name = name,
            pluginVersion = json.optString("pluginVersion").ifBlank { "1.0" },
            description = json.optString("description"),
            commands = commands,
            code = code,
            permissions = permissions,
            events = events,
            files = files,
            timeoutMs = json.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1_000L, MAX_TIMEOUT_MS)
        )
    }

    private fun stringSet(array: JSONArray?): Set<String> =
        if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
}
