package com.vsmobile.editor

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch

/**
 * Мост между Python-функцией input() и UI-диалогом.
 * Python-поток блокируется на latch, пока пользователь не введёт ответ.
 * Python вызывает [call] по имени через рефлексию — класс и метод сохраняются правилом в proguard-rules.pro.
 */
class InputBridge(private val onPrompt: (String, String) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var result: String? = null

    @Suppress("unused")
    fun call(question: String, output: String): String? {
        val l = CountDownLatch(1)
        result = null
        latch = l
        mainHandler.post { onPrompt(question, output) }
        l.await()
        return result
    }

    fun submit(text: String) {
        result = text
        latch?.countDown()
    }

    fun cancel() {
        result = null
        latch?.countDown()
    }
}

/**
 * Состояние и запуск Python-консоли. Живёт во ViewModel, поэтому выполняющийся скрипт и вывод
 * не пропадают при повороте экрана.
 */
class PythonConsole(private val scope: CoroutineScope) {

    var inputText by mutableStateOf("")
    var isRunning by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var text by mutableStateOf("")
    var waiting by mutableStateOf(false)

    private val inputBridge = InputBridge { _, output ->
        inputText = ""
        text = output
        waiting = true
    }

    fun run(code: String) {
        if (isRunning) return
        isRunning = true
        text = ""
        waiting = false
        visible = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val module = Python.getInstance().getModule("debug_runner")
                    module.callAttr("set_input_callback", PyObject.fromJava(inputBridge))
                    val res = module.callAttr("run_code", code, null).asList()
                    val out = res[0]?.toString() ?: ""
                    val err = res[1]?.toString()
                    if (err.isNullOrEmpty() || err == "None") {
                        out.ifEmpty { "(нет вывода)" }
                    } else {
                        (if (out.isEmpty()) "" else out + "\n") + "❌ Ошибка:\n" + err
                    }
                } catch (e: Throwable) {
                    "Ошибка запуска Python: ${e.message}"
                }
            }
            waiting = false
            text = result
            isRunning = false
        }
    }

    fun stop() {
        inputBridge.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                Python.getInstance().getModule("debug_runner").callAttr("stop_code")
            } catch (_: Throwable) {
            }
        }
    }

    fun submitAnswer() {
        val answer = inputText
        text = text + answer + "\n"
        waiting = false
        inputBridge.submit(answer)
    }
}
