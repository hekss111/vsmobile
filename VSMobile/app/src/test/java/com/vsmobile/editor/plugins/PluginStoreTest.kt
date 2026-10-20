package com.vsmobile.editor.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginStoreTest {

    private lateinit var dir: File
    private lateinit var store: PluginStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("plugins").toFile()
        store = PluginStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun manifest(version: Int, permissions: List<String>): String = JSONObject()
        .put("format", "vsm")
        .put("version", 2)
        .put("id", "demo")
        .put("name", "Demo")
        .put("pluginVersion", version.toString())
        .put("permissions", JSONArray(permissions))
        .put("code", "vsm.on('app.started', function() {});")
        .toString()

    @Test
    fun `установка и список возвращают плагин с правами`() {
        store.install(manifest(1, listOf("editor", "ui")), setOf(Permission.EDITOR)).getOrThrow()
        val item = store.list().single()
        assertEquals("demo", item.plugin.id)
        assertEquals(setOf(Permission.EDITOR), item.granted)
        assertTrue(item.enabled)
    }

    @Test
    fun `setGranted игнорирует не заявленные в манифесте права`() {
        store.install(manifest(1, listOf("editor")), setOf(Permission.EDITOR)).getOrThrow()
        store.setGranted("demo", setOf(Permission.EDITOR, Permission.NETWORK))
        assertEquals(setOf(Permission.EDITOR), store.list().single().granted)
    }

    @Test
    fun `переустановка не выдаёт новое право без явного granted`() {
        store.install(manifest(1, listOf("editor")), setOf(Permission.EDITOR)).getOrThrow()
        store.install(manifest(2, listOf("editor", "network")), setOf(Permission.EDITOR)).getOrThrow()
        assertEquals(setOf(Permission.EDITOR), store.list().single().granted)
    }

    @Test
    fun `remove удаляет vsm state и data`() {
        store.install(manifest(1, listOf("storage")), setOf(Permission.STORAGE)).getOrThrow()
        store.storageFile("demo").writeText("{}")
        assertTrue(store.remove("demo"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `битый файл пропускается в list`() {
        File(dir, "broken.vsm").writeText("не json")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `выключение плагина сохраняется`() {
        store.install(manifest(1, listOf("editor")), setOf(Permission.EDITOR)).getOrThrow()
        store.setEnabled("demo", false)
        assertFalse(store.list().single().enabled)
    }
}
