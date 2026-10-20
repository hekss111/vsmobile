package com.vsmobile.editor.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelThemeTest {

    private val tokens = PanelTokens("#1E1E1E", "#252526", "#3C3C3C", "#569CD6", "#D4D4D4", "#9DA5B4", "#F48771")
    private val panel = PluginPanel("notes", "Заметки", "<p id=\"x\">Привет</p>")

    @Test
    fun `css содержит все переменные vsm`() {
        val css = PanelTheme.css(tokens)
        listOf("bg", "surface", "divider", "accent", "text", "muted", "danger", "font", "mono", "radius").forEach {
            assertTrue("--vsm-$it", css.contains("--vsm-$it:"))
        }
    }

    @Test
    fun `document без network запрещает connect`() {
        val doc = PanelTheme.document(panel, tokens, false, "var shim=1;")
        assertTrue(doc.contains("connect-src 'none'"))
        assertFalse(doc.contains("connect-src https:"))
    }

    @Test
    fun `document с network разрешает https`() {
        val doc = PanelTheme.document(panel, tokens, true, "var shim=1;")
        assertTrue(doc.contains("connect-src https:"))
    }

    @Test
    fun `document содержит panelId shim и html панели`() {
        val doc = PanelTheme.document(panel, tokens, false, "var shim=1;")
        assertTrue(doc.contains("window.__panelId=\"notes\""))
        assertTrue(doc.contains("var shim=1;"))
        assertTrue(doc.contains("<p id=\"x\">Привет</p>"))
    }

    @Test
    fun `базовые селекторы обёрнуты в where`() {
        val css = PanelTheme.css(tokens)
        assertTrue(css.contains(":where(button)"))
        assertFalse(Regex("(^|\\n)button\\s*\\{").containsMatchIn(css))
    }
}
