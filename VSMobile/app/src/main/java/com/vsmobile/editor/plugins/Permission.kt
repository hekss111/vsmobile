package com.vsmobile.editor.plugins

enum class Permission(val key: String, val risk: Int) {
    EDITOR("editor", 1),
    FILES("files", 3),
    NETWORK("network", 3),
    STORAGE("storage", 0),
    UI("ui", 0),
    AI("ai", 2),
    PYTHON("python", 2),
    CLIPBOARD("clipboard", 1);

    companion object {
        fun fromKey(key: String): Permission? = values().firstOrNull { it.key == key }
    }
}
