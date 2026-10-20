package com.vsmobile.editor.plugins

object PathRules {

    fun split(path: String): List<String>? {
        if (path.isEmpty()) return emptyList()
        if (path.startsWith("/") || path.contains('\\')) return null
        val trimmed = path.removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts
    }

    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
}
