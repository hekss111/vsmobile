package com.vsmobile.editor.plugins

import org.json.JSONArray
import org.json.JSONObject

data class PluginCommand(val id: String, val title: String, val pinned: Boolean = false)

data class PluginPanel(val id: String, val title: String, val html: String)

data class HighlightRule(val regex: String, val color: String, val italic: Boolean)

data class PluginLanguage(
    val extensions: List<String>,
    val keywords: List<String>,
    val rules: List<HighlightRule>
)

data class VsmPlugin(
    val id: String,
    val name: String,
    val pluginVersion: String,
    val description: String,
    val author: String,
    val permissions: Set<Permission>,
    val commands: List<PluginCommand>,
    val panels: List<PluginPanel>,
    val languages: List<PluginLanguage>,
    val modules: Map<String, String>,
    val code: String
)

object VsmFormat {
    const val EXTENSION = "vsm"
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val FORMAT = "vsm"
    private val idRegex = Regex("[a-z0-9][a-z0-9_-]{1,63}")
    private val moduleRegex = Regex("[A-Za-z0-9_][A-Za-z0-9_./-]*\\.js")
    private val extensionRegex = Regex("[a-z0-9]{1,10}")
    private val colorRegex = Regex("#[0-9A-Fa-f]{6}")

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

        val permissions = if (version == 1) setOf(Permission.EDITOR) else parsePermissions(json.optJSONArray("permissions"))
        val commands = parseCommands(json.optJSONArray("commands"))
        val panels = if (version == 1) emptyList() else parsePanels(json.optJSONArray("panels"))
        val languages = if (version == 1) emptyList() else parseLanguages(json.optJSONArray("languages"))
        val modules = if (version == 1) emptyMap() else parseModules(json.optJSONObject("modules"))
        val code = json.optString("code")

        if (version == 1) {
            require(commands.isNotEmpty()) { "В плагине нет ни одной команды" }
            require(code.isNotBlank()) { "В плагине пустой код" }
        } else {
            require(commands.isNotEmpty() || panels.isNotEmpty() || code.isNotBlank()) {
                "В плагине нет ни команд, ни панелей, ни кода"
            }
        }

        VsmPlugin(
            id = id,
            name = name,
            pluginVersion = json.optString("pluginVersion").ifBlank { "1.0" },
            description = json.optString("description"),
            author = json.optString("author"),
            permissions = permissions,
            commands = commands,
            panels = panels,
            languages = languages,
            modules = modules,
            code = code
        )
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }

    private fun parsePermissions(array: JSONArray?): Set<Permission> {
        if (array == null) return emptySet()
        return (0 until array.length()).map { index ->
            val key = array.optString(index)
            requireNotNull(Permission.fromKey(key)) { "Неизвестное разрешение: $key" }
        }.toSet()
    }

    private fun parseCommands(array: JSONArray?): List<PluginCommand> {
        val commands = objects(array).map { item ->
            val commandId = item.optString("id")
            require(idRegex.matches(commandId)) { "Некорректный id команды: $commandId" }
            PluginCommand(commandId, item.optString("title").ifBlank { commandId }, item.optBoolean("pin", false))
        }
        require(commands.map { it.id }.toSet().size == commands.size) { "Повторяются id команд" }
        return commands
    }

    private fun parsePanels(array: JSONArray?): List<PluginPanel> {
        val panels = objects(array).map { item ->
            val panelId = item.optString("id")
            require(idRegex.matches(panelId)) { "Некорректный id панели: $panelId" }
            val html = item.optString("html")
            require(html.isNotBlank()) { "У панели $panelId пустой html" }
            PluginPanel(panelId, item.optString("title").ifBlank { panelId }, html)
        }
        require(panels.map { it.id }.toSet().size == panels.size) { "Повторяются id панелей" }
        return panels
    }

    private fun parseLanguages(array: JSONArray?): List<PluginLanguage> = objects(array).map { item ->
        val extensions = strings(item.optJSONArray("extensions")).map { it.lowercase().removePrefix(".") }
        require(extensions.isNotEmpty()) { "У языка нет расширений" }
        extensions.forEach { require(extensionRegex.matches(it)) { "Некорректное расширение языка: $it" } }
        val rules = objects(item.optJSONArray("rules")).map { rule ->
            val regex = rule.optString("regex")
            require(runCatching { Regex(regex) }.isSuccess && regex.isNotEmpty()) { "Некорректная регулярка: $regex" }
            val color = rule.optString("color")
            require(colorRegex.matches(color)) { "Цвет правила должен быть в формате #RRGGBB: $color" }
            HighlightRule(regex, color, rule.optBoolean("italic", false))
        }
        PluginLanguage(extensions, strings(item.optJSONArray("keywords")), rules)
    }

    private fun parseModules(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            require(moduleRegex.matches(key) && !key.contains("..")) { "Некорректный путь модуля: $key" }
            result[key] = json.getString(key)
        }
        return result
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
}
