package com.vsmobile.editor.plugins

import com.chaquo.python.Python

object PythonBridge {

    @Synchronized
    fun run(code: String): PythonResult {
        val module = Python.getInstance().getModule("debug_runner")
        val result = module.callAttr("run_code", code, null).asList()
        val output = result[0]?.toString() ?: ""
        val error = result[1]?.toString()?.takeIf { it.isNotEmpty() && it != "None" }
        return PythonResult(output, error)
    }
}
