package com.vsmobile.editor.plugins

import java.util.concurrent.ConcurrentHashMap

class PluginManager(
    private val store: PluginStore,
    private val envFactory: (String) -> HostEnv,
    private val shim: String,
    private val onHung: (String) -> Unit
) {

    private val engines = ConcurrentHashMap<String, PluginEngine>()
    private val envs = ConcurrentHashMap<String, HostEnv>()

    fun startAll() {
        store.list().filter { it.enabled }.forEach { start(it) }
    }

    fun restart(id: String) {
        stop(id)
        store.list().firstOrNull { it.plugin.id == id && it.enabled }?.let { start(it) }
    }

    fun stop(id: String) {
        engines.remove(id)?.stop()
        envs.remove(id)
    }

    fun emit(event: String, payloadJson: String) {
        engines.values.forEach { it.emit(event, payloadJson) }
    }

    fun engine(id: String): PluginEngine? = engines[id]

    fun runCommand(
        id: String,
        commandId: String,
        context: PluginContext,
        tabUri: String?
    ): Result<PluginResult> {
        val engine = engines[id] ?: return Result.failure(IllegalStateException("Плагин не запущен"))
        val env = envs[id]
        (env as? TabBound)?.bindTab(tabUri)
        try {
            return engine.runCommand(commandId, context)
        } finally {
            (env as? TabBound)?.bindTab(null)
        }
    }

    private fun start(item: InstalledPlugin) {
        val id = item.plugin.id
        val env = envFactory(id)
        envs[id] = env
        val engine = PluginEngine(
            installed = item,
            shim = shim,
            apiFactory = { hooks ->
                HostApi(item.granted, env, PluginStorage(store.storageFile(id)), hooks)
            },
            onHung = { hungId ->
                store.setEnabled(hungId, false)
                engines.remove(hungId)
                envs.remove(hungId)
                onHung(hungId)
            }
        )
        engines[id] = engine
        engine.start().onFailure { error ->
            engines.remove(id)
            envs.remove(id)
            env.toast("Плагин «${item.plugin.name}»: ${error.message}")
        }
    }
}
