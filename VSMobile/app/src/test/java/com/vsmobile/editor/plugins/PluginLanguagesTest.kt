package com.vsmobile.editor.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLanguagesTest {

    private fun installed(extension: String, enabled: Boolean = true): InstalledPlugin {
        val language = PluginLanguage(
            extensions = listOf(extension),
            keywords = listOf("when"),
            rules = listOf(HighlightRule("#\\w+", "#FF8800", true))
        )
        val plugin = VsmPlugin(
            id = "lang", name = "Lang", pluginVersion = "1.0", description = "", author = "",
            permissions = emptySet(), commands = emptyList(), panels = emptyList(),
            languages = listOf(language), modules = emptyMap(), code = "x"
        )
        return InstalledPlugin(plugin, emptySet(), enabled)
    }

    @Test
    fun `расширение из плагина находит язык`() {
        PluginLanguages.rebuild(listOf(installed("foo")))
        val language = PluginLanguages.find("FOO")
        assertNotNull(language)
        assertTrue("when" in language!!.keywords)
        assertEquals(0xFFFF8800L, language.rules.single().colorArgb)
    }

    @Test
    fun `встроенное расширение не перекрывается`() {
        PluginLanguages.rebuild(listOf(installed("kt")))
        assertNull(PluginLanguages.find("kt"))
    }

    @Test
    fun `выключенный плагин не даёт язык`() {
        PluginLanguages.rebuild(listOf(installed("foo", enabled = false)))
        assertNull(PluginLanguages.find("foo"))
    }

    @Test
    fun `rebuild без плагинов убирает язык`() {
        PluginLanguages.rebuild(listOf(installed("foo")))
        PluginLanguages.rebuild(emptyList())
        assertNull(PluginLanguages.find("foo"))
    }
}
