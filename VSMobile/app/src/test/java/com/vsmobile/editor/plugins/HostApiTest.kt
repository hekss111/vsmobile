package com.vsmobile.editor.plugins

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private class FakeEnv : HostEnv {
    var text: String? = "hello world"
    var selection: Pair<Int, Int>? = 0 to 5
    var fsReads = 0
    var failWith: RuntimeException? = null
    var clipboard = ""
    override fun editorText() = text
    override fun editorSetText(text: String): Boolean { if (this.text == null) return false; this.text = text; return true }
    override fun editorSelection() = selection
    override fun editorSetSelection(start: Int, end: Int): Boolean { selection = start to end; return true }
    override fun editorFileName() = "a.txt"
    override fun editorTabs() = listOf("a.txt", "b.txt")
    override fun openFile(parts: List<String>) = true
    override fun fsRead(parts: List<String>): String? { fsReads++; failWith?.let { throw it }; return "data" }
    override fun fsWrite(parts: List<String>, text: String) = true
    override fun fsList(parts: List<String>) = listOf("src/", "a.txt")
    override fun fsExists(parts: List<String>) = true
    override fun fsMkdir(parts: List<String>) = true
    override fun fsRemove(parts: List<String>) = true
    override fun fsRename(parts: List<String>, newName: String) = true
    override fun fsMove(parts: List<String>, targetParts: List<String>) = true
    override fun toast(text: String) {}
    override fun confirm(title: String, text: String) = true
    override fun prompt(title: String, label: String, initial: String): String? = initial
    override fun pick(title: String, items: List<String>) = 1
    override fun openPanel(id: String) = true
    override fun closePanel(id: String) {}
    override fun postToPanel(id: String, json: String) {}
    var emitted = ""
    override fun emitToPlugin(event: String, json: String) { emitted = "$event|$json" }
    override fun http(spec: HttpSpec) = HttpResult(200, mapOf("a" to "b"), "ok")
    override fun aiAsk(prompt: String) = "answer"
    override fun pythonRun(code: String) = PythonResult("out", null)
    override fun clipboardRead() = clipboard
    override fun clipboardWrite(text: String) { clipboard = text }
    override fun appInfo() = AppInfo("VS Mobile", "1.0", null)
    override fun log(text: String) {}
}

class HostApiTest {

    private lateinit var dir: File
    private lateinit var env: FakeEnv

    private val hooks = object : BlockingHooks {
        override fun <T> blocking(block: () -> T): T = block()
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("hostapi").toFile()
        env = FakeEnv()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun api(granted: Set<Permission>, limit: Long = 4L * 1024 * 1024, panel: String? = null) =
        HostApi(granted, env, PluginStorage(File(dir, "data.json"), limit), hooks, panel)

    private fun code(response: String): String? = JSONObject(response).optString("e").ifEmpty { null }

    @Test
    fun `вызов без права возвращает permission_denied`() {
        assertEquals("permission_denied", code(api(setOf(Permission.EDITOR)).call("fs.read", "[\"a.txt\"]")))
        assertEquals(0, env.fsReads)
    }

    @Test
    fun `неизвестный метод возвращает unknown_method`() {
        assertEquals("unknown_method", code(api(Permission.values().toSet()).call("nope", "[]")))
    }

    @Test
    fun `аргументы не массивом дают bad_args`() {
        assertEquals("bad_args", code(api(setOf(Permission.EDITOR)).call("editor.setText", "{}")))
    }

    @Test
    fun `путь с точками возвращает path_denied и env не вызывается`() {
        assertEquals("path_denied", code(api(setOf(Permission.FILES)).call("fs.read", "[\"../x\"]")))
        assertEquals(0, env.fsReads)
    }

    @Test
    fun `хранилище возвращает записанное значение`() {
        val api = api(setOf(Permission.STORAGE))
        api.call("storage.set", "[\"k\",\"v\"]")
        assertEquals("v", JSONObject(api.call("storage.get", "[\"k\"]")).getString("r"))
    }

    @Test
    fun `хранилище сверх лимита даёт storage_full`() {
        val api = api(setOf(Permission.STORAGE), limit = 20)
        assertEquals("storage_full", code(api.call("storage.set", "[\"k\",\"${"x".repeat(50)}\"]")))
    }

    @Test
    fun `editor getText без вкладки даёт no_active_file`() {
        env.text = null
        assertEquals("no_active_file", code(api(setOf(Permission.EDITOR)).call("editor.getText", "[]")))
    }

    @Test
    fun `исключение в env превращается в host_error`() {
        env.failWith = IllegalStateException("сбой")
        assertEquals("host_error", code(api(setOf(Permission.FILES)).call("fs.read", "[\"a.txt\"]")))
    }

    @Test
    fun `http со схемой file даёт bad_args`() {
        val response = api(setOf(Permission.NETWORK)).call("http.request", "[{\"url\":\"file:///etc/passwd\"}]")
        assertEquals("bad_args", code(response))
    }

    @Test
    fun `panel post вне панели даёт bad_args`() {
        assertEquals("bad_args", code(api(setOf(Permission.UI)).call("panel.post", "[1]")))
    }

    @Test
    fun `panel post из панели доходит до плагина`() {
        api(setOf(Permission.UI), panel = "notes").call("panel.post", "[{\"a\":1}]")
        assertEquals("panel.message:notes|{\"a\":1}", env.emitted)
    }

    @Test
    fun `replaceSelection заменяет выделение и ставит курсор после вставки`() {
        api(setOf(Permission.EDITOR)).call("editor.replaceSelection", "[\"HEY\"]")
        assertEquals("HEY world", env.text)
        assertEquals(3 to 3, env.selection)
    }

    @Test
    fun `insertAt вне текста даёт bad_args`() {
        assertEquals("bad_args", code(api(setOf(Permission.EDITOR)).call("editor.insertAt", "[999,\"x\"]")))
    }

    @Test
    fun `rename с слешем в имени даёт path_denied`() {
        assertEquals("path_denied", code(api(setOf(Permission.FILES)).call("fs.rename", "[\"a.txt\",\"x/y\"]")))
    }

    @Test
    fun `буфер обмена пишется и читается при праве clipboard`() {
        val api = api(setOf(Permission.CLIPBOARD))
        api.call("clipboard.write", "[\"abc\"]")
        assertEquals("abc", JSONObject(api.call("clipboard.read", "[]")).getString("r"))
    }

    @Test
    fun `app info доступен без разрешений`() {
        val response = JSONObject(api(emptySet()).call("app.info", "[]"))
        assertEquals("VS Mobile", response.getJSONObject("r").getString("name"))
        assertFalse(response.has("e"))
        assertTrue(response.has("r"))
    }
}
