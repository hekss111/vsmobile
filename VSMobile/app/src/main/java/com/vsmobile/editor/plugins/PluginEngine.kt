package com.vsmobile.editor.plugins

import app.cash.quickjs.QuickJs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class PluginContext(
    val fileName: String,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

data class PluginResult(val text: String?, val message: String?)

enum class EngineState { RUNNING, HUNG, STOPPED }

interface Bridge {
    fun call(method: String, args: String): String
}

class PluginEngine(
    private val installed: InstalledPlugin,
    private val shim: String,
    private val apiFactory: (BlockingHooks) -> HostApi,
    private val onHung: (String) -> Unit
) {

    @Volatile
    var state: EngineState = EngineState.STOPPED
        private set

    private val id = installed.plugin.id
    private val executor: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "vsm-plugin-$id").apply { isDaemon = true }
    }
    private val watchdog: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "vsm-watchdog-$id").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val timers = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val tokens = AtomicLong(0)
    private val activeToken = AtomicLong(0)
    private val blocked = AtomicInteger(0)

    @Volatile
    private var quickJs: QuickJs? = null

    private val hooks = object : BlockingHooks {
        override fun <T> blocking(block: () -> T): T {
            blocked.incrementAndGet()
            try {
                return block()
            } finally {
                blocked.decrementAndGet()
                if (activeToken.get() != 0L) arm()
            }
        }
    }

    private inner class BridgeImpl(private val api: HostApi) : Bridge {
        override fun call(method: String, args: String): String = when (method) {
            "timer.set" -> timerSet(args)
            "timer.clear" -> timerClear(args)
            else -> api.call(method, args)
        }
    }

    fun start(): Result<Unit> {
        state = EngineState.RUNNING
        val result = execute { _ ->
            val js = QuickJs.create()
            try {
                js.evaluate(
                    "globalThis.__vsmEngine = true; globalThis.__modules = ${JSONObject(installed.plugin.modules)};",
                    "vsm_boot.js"
                )
                js.set("__bridge", Bridge::class.java, BridgeImpl(apiFactory(hooks)))
                js.evaluate(shim, "vsm_shim.js")
                js.evaluate(installed.plugin.code, "$id.js")
            } catch (t: Throwable) {
                runCatching { js.close() }
                throw t
            }
            quickJs = js
        }
        if (result.isFailure) state = EngineState.STOPPED
        return result
    }

    fun emit(event: String, payloadJson: String) {
        if (state != EngineState.RUNNING) return
        executor.execute {
            guarded {
                quickJs?.evaluate(
                    "__vsmEmit(${JSONObject.quote(event)}, ${JSONObject.quote(payloadJson)})",
                    "event.js"
                )
            }
        }
    }

    fun runCommand(commandId: String, context: PluginContext): Result<PluginResult> {
        val ctx = JSONObject()
            .put("fileName", context.fileName)
            .put("text", context.text)
            .put("selectionStart", context.selectionStart)
            .put("selectionEnd", context.selectionEnd)
        return execute { engine ->
            val js = engine ?: throw IllegalStateException("Плагин не запущен")
            val raw = js.evaluate(
                "__vsmCommand(${JSONObject.quote(commandId)}, ${JSONObject.quote(ctx.toString())})",
                "$id-command.js"
            ) as? String ?: "{}"
            val json = JSONObject(raw)
            PluginResult(
                text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null,
                message = json.optString("message").takeIf { it.isNotBlank() }
            )
        }
    }

    fun stop() {
        if (state == EngineState.HUNG) {
            state = EngineState.STOPPED
            return
        }
        state = EngineState.STOPPED
        timers.values.forEach { it.cancel(false) }
        timers.clear()
        executor.execute {
            runCatching { quickJs?.close() }
            quickJs = null
        }
        executor.shutdown()
        watchdog.shutdownNow()
    }

    private fun <T> execute(block: (QuickJs?) -> T): Result<T> {
        if (state != EngineState.RUNNING) return Result.failure(IllegalStateException("Плагин не запущен"))
        val future = CompletableFuture<T>()
        pending.add(future)
        try {
            executor.execute {
                try {
                    future.complete(guarded { block(quickJs) })
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
            return runCatching { future.get() }.recoverCatching { throw (it.cause ?: it) }
        } finally {
            pending.remove(future)
        }
    }

    private fun <T> guarded(block: () -> T): T {
        arm()
        try {
            return block()
        } finally {
            activeToken.set(0)
        }
    }

    private fun arm() {
        val token = tokens.incrementAndGet()
        activeToken.set(token)
        if (watchdog.isShutdown) return
        watchdog.schedule(Runnable {
            if (activeToken.get() == token && blocked.get() == 0) markHung()
        }, CALL_LIMIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun markHung() {
        if (state != EngineState.RUNNING) return
        state = EngineState.HUNG
        quickJs = null
        timers.values.forEach { it.cancel(false) }
        timers.clear()
        pending.forEach { it.completeExceptionally(IllegalStateException("Плагин завис и отключён")) }
        onHung(id)
    }

    private fun timerSet(args: String): String {
        val array = JSONArray(args)
        val timerId = array.getInt(0)
        val delay = array.getLong(1)
        val repeat = array.getBoolean(2)
        if (timers.size >= MAX_TIMERS) {
            return JSONObject().put("e", "host_error").put("m", "Слишком много таймеров").toString()
        }
        val task = Runnable {
            guarded { quickJs?.evaluate("__vsmTimer($timerId)", "timer.js") }
            if (!repeat) timers.remove(timerId)
        }
        timers[timerId] = if (repeat) {
            executor.scheduleWithFixedDelay(task, delay, delay, TimeUnit.MILLISECONDS)
        } else {
            executor.schedule(task, delay, TimeUnit.MILLISECONDS)
        }
        return JSONObject().put("r", JSONObject.NULL).toString()
    }

    private fun timerClear(args: String): String {
        timers.remove(JSONArray(args).getInt(0))?.cancel(false)
        return JSONObject().put("r", JSONObject.NULL).toString()
    }

    companion object {
        const val CALL_LIMIT_MS = 60_000L
        const val MAX_TIMERS = 100
    }
}
