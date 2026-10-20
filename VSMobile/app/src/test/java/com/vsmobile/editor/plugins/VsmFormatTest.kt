package com.vsmobile.editor.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmFormatTest {

    private fun v1(): JSONObject = JSONObject()
        .put("format", "vsm")
        .put("version", 1)
        .put("id", "upper-case")
        .put("name", "Upper")
        .put("commands", JSONArray().put(JSONObject().put("id", "upper").put("title", "Upper")))
        .put("code", "function run(c, x) { return {}; }")

    private fun v2(): JSONObject = JSONObject()
        .put("format", "vsm")
        .put("version", 2)
        .put("id", "tools")
        .put("name", "Tools")
        .put("permissions", JSONArray().put("editor"))
        .put("code", "vsm.on('app.started', function() {});")

    @Test
    fun `v1 читается как v2 с правом editor`() {
        val plugin = VsmFormat.parse(v1().toString()).getOrThrow()
        assertEquals(setOf(Permission.EDITOR), plugin.permissions)
    }

    @Test
    fun `неизвестное разрешение отклоняется`() {
        val raw = v2().put("permissions", JSONArray().put("root")).toString()
        val result = VsmFormat.parse(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("root"))
    }

    @Test
    fun `v2 без команд панелей и кода отклоняется`() {
        val raw = v2().put("code", "").toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `v2 только с кодом принимается`() {
        val plugin = VsmFormat.parse(v2().toString()).getOrThrow()
        assertEquals("tools", plugin.id)
        assertTrue(plugin.commands.isEmpty())
    }

    @Test
    fun `панели с повторным id отклоняются`() {
        val panel = JSONObject().put("id", "main").put("title", "Main").put("html", "<p>x</p>")
        val raw = v2().put("panels", JSONArray().put(panel).put(panel)).toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `modules с путём вверх отклоняется`() {
        val raw = v2().put("modules", JSONObject().put("../evil.js", "x")).toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `язык без расширений отклоняется`() {
        val language = JSONObject().put("extensions", JSONArray()).put("keywords", JSONArray().put("if"))
        val raw = v2().put("languages", JSONArray().put(language)).toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `правило с битой регуляркой отклоняется`() {
        val rule = JSONObject().put("regex", "(").put("color", "#FF0000")
        val language = JSONObject()
            .put("extensions", JSONArray().put("foo"))
            .put("rules", JSONArray().put(rule))
        val raw = v2().put("languages", JSONArray().put(language)).toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `файл больше двух мегабайт отклоняется`() {
        val raw = v2().put("code", "a".repeat(2 * 1024 * 1024 + 1)).toString()
        assertTrue(VsmFormat.parse(raw).isFailure)
    }

    @Test
    fun `разрешение clipboard принимается`() {
        val raw = v2().put("permissions", JSONArray().put("clipboard").put("editor")).toString()
        val plugin = VsmFormat.parse(raw).getOrThrow()
        assertEquals(setOf(Permission.CLIPBOARD, Permission.EDITOR), plugin.permissions)
    }

    @Test
    fun `закреплённая команда читается из pin`() {
        val command = JSONObject().put("id", "go").put("title", "Go").put("pin", true)
        val raw = v2().put("commands", JSONArray().put(command)).toString()
        assertTrue(VsmFormat.parse(raw).getOrThrow().commands.single().pinned)
    }
}
