package com.vsmobile.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Настройки редактора. Сейчас здесь автозакрытие скобок и кавычек — единственное
 * «автодополнение» в приложении: его можно выключить полностью или только для файлов
 * с выбранными расширениями (например, txt, md).
 */
object EditorSettings {

    private const val PREFS = "vsmobile"
    private const val KEY_AUTO_CLOSE = "editor_auto_close"
    private const val KEY_DISABLED_EXT = "editor_auto_close_disabled_ext"
    private const val MAX_EXTENSIONS = 50

    var autoCloseEnabled by mutableStateOf(true)
        private set

    var disabledExtensions by mutableStateOf<Set<String>>(emptySet())
        private set

    private var loaded = false

    /** Безопасно вызывать сколько угодно раз — читает настройки только один раз. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        autoCloseEnabled = prefs.getBoolean(KEY_AUTO_CLOSE, true)
        disabledExtensions = parseExtensions(prefs.getString(KEY_DISABLED_EXT, "") ?: "")
    }

    fun setAutoCloseEnabled(context: Context, enabled: Boolean) {
        autoCloseEnabled = enabled
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CLOSE, enabled).apply()
    }

    /** [raw] — текст из поля ввода: «txt, .md json» (разделители — запятая, точка с запятой, пробел). */
    fun setDisabledExtensions(context: Context, raw: String) {
        disabledExtensions = parseExtensions(raw)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISABLED_EXT, disabledExtensions.joinToString(",")).apply()
    }

    fun disabledExtensionsText(): String = disabledExtensions.sorted().joinToString(", ")

    /** [extension] — расширение файла без точки, как возвращает SyntaxHighlighter.languageFromFileName. */
    fun isAutoCloseActiveFor(extension: String): Boolean =
        autoCloseEnabled && extension.lowercase() !in disabledExtensions

    private fun parseExtensions(raw: String): Set<String> =
        raw.split(Regex("[,;\\s]+"))
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .take(MAX_EXTENSIONS)
            .toSet()
}
