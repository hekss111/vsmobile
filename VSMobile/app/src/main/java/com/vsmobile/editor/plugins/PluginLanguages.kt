package com.vsmobile.editor.plugins

class CompiledRule(val regex: Regex, val colorArgb: Long, val italic: Boolean)

class CompiledLanguage(val keywords: Set<String>, val rules: List<CompiledRule>)

object PluginLanguages {

    private val builtIn = setOf("kt", "kts", "py", "js", "ts", "jsx", "tsx", "java", "c", "cpp", "h", "hpp")

    @Volatile
    private var registry: Map<String, CompiledLanguage> = emptyMap()

    fun rebuild(installed: List<InstalledPlugin>) {
        val map = LinkedHashMap<String, CompiledLanguage>()
        for (item in installed) {
            if (!item.enabled) continue
            for (language in item.plugin.languages) {
                val compiled = compile(language)
                for (extension in language.extensions) {
                    if (extension !in builtIn && extension !in map) map[extension] = compiled
                }
            }
        }
        registry = map
    }

    fun find(extension: String): CompiledLanguage? = registry[extension.lowercase()]

    private fun compile(language: PluginLanguage): CompiledLanguage = CompiledLanguage(
        keywords = language.keywords.toSet(),
        rules = language.rules.mapNotNull { rule ->
            val regex = runCatching { Regex(rule.regex) }.getOrNull() ?: return@mapNotNull null
            val rgb = rule.color.removePrefix("#").toLongOrNull(16) ?: return@mapNotNull null
            CompiledRule(regex, 0xFF000000L or rgb, rule.italic)
        }
    )
}
