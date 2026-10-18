package com.vsmobile.editor.plugins

import android.content.Context
import android.net.Uri
import java.io.File

class PluginStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "plugins").apply { mkdirs() }

    fun list(): List<VsmPlugin> =
        dir.listFiles { file -> file.extension == VsmFormat.EXTENSION }
            .orEmpty()
            .mapNotNull { file -> VsmFormat.parse(file.readText()).getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun install(uri: Uri): Result<VsmPlugin> = runCatching {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Не удалось открыть файл")
        val plugin = VsmFormat.parse(text).getOrThrow()
        File(dir, "${plugin.id}.${VsmFormat.EXTENSION}").writeText(text)
        plugin
    }

    fun remove(id: String): Boolean = File(dir, "$id.${VsmFormat.EXTENSION}").delete()
}
