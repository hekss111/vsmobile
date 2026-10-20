package com.vsmobile.editor.plugins

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class InstalledPlugin(val plugin: VsmPlugin, val granted: Set<Permission>, val enabled: Boolean)

class PluginStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun list(): List<InstalledPlugin> =
        dir.listFiles { file -> file.extension == VsmFormat.EXTENSION }
            .orEmpty()
            .mapNotNull { load(it) }
            .sortedBy { it.plugin.name.lowercase() }

    fun install(raw: String, granted: Set<Permission>): Result<InstalledPlugin> = runCatching {
        val plugin = VsmFormat.parse(raw).getOrThrow()
        File(dir, "${plugin.id}.${VsmFormat.EXTENSION}").writeText(raw)
        val enabled = readState(plugin.id)?.second ?: true
        val effective = granted.intersect(plugin.permissions)
        writeState(plugin.id, effective, enabled)
        InstalledPlugin(plugin, effective, enabled)
    }

    fun setGranted(id: String, granted: Set<Permission>) {
        val current = find(id) ?: return
        writeState(id, granted.intersect(current.plugin.permissions), current.enabled)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = find(id) ?: return
        writeState(id, current.granted, enabled)
    }

    fun remove(id: String): Boolean {
        val removed = File(dir, "$id.${VsmFormat.EXTENSION}").delete()
        File(dir, "$id.state.json").delete()
        File(dir, "$id.data.json").delete()
        return removed
    }

    fun storageFile(id: String): File = File(dir, "$id.data.json")

    private fun find(id: String): InstalledPlugin? {
        val file = File(dir, "$id.${VsmFormat.EXTENSION}")
        return if (file.exists()) load(file) else null
    }

    private fun load(file: File): InstalledPlugin? {
        val plugin = VsmFormat.parse(file.readText()).getOrNull() ?: return null
        val state = readState(plugin.id)
        val granted = state?.first
            ?: if (plugin.permissions == setOf(Permission.EDITOR)) plugin.permissions else emptySet()
        return InstalledPlugin(plugin, granted.intersect(plugin.permissions), state?.second ?: true)
    }

    private fun readState(id: String): Pair<Set<Permission>, Boolean>? {
        val file = File(dir, "$id.state.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("granted") ?: JSONArray()
            val granted = (0 until array.length()).mapNotNull { Permission.fromKey(array.optString(it)) }.toSet()
            granted to json.optBoolean("enabled", true)
        }.getOrNull()
    }

    private fun writeState(id: String, granted: Set<Permission>, enabled: Boolean) {
        val array = JSONArray()
        granted.forEach { array.put(it.key) }
        File(dir, "$id.state.json").writeText(
            JSONObject().put("granted", array).put("enabled", enabled).toString()
        )
    }

    companion object {
        fun forContext(context: Context): PluginStore =
            PluginStore(File(context.applicationContext.filesDir, "plugins"))

        fun readText(context: Context, uri: Uri): Result<String> = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Не удалось открыть файл")
        }
    }
}
