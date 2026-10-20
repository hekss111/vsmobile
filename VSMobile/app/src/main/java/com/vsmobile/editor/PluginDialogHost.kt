package com.vsmobile.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class PluginDialog(val source: String, val title: String) {
    class Confirm(source: String, title: String, val text: String, val answer: (Boolean) -> Unit) :
        PluginDialog(source, title)

    class Prompt(
        source: String,
        title: String,
        val label: String,
        val initial: String,
        val answer: (String?) -> Unit
    ) : PluginDialog(source, title)

    class Pick(source: String, title: String, val items: List<String>, val answer: (Int) -> Unit) :
        PluginDialog(source, title)
}

@Composable
fun PluginDialogHost(vm: EditorViewModel) {
    val dialog = vm.pluginDialogs.firstOrNull() ?: return

    fun finish(block: () -> Unit) {
        vm.pluginDialogs.remove(dialog)
        block()
    }

    val title: @Composable () -> Unit = {
        Column {
            Text(dialog.title)
            Text("Плагин ${dialog.source}", color = Color(0xFF9DA5B4), fontSize = 12.sp)
        }
    }

    when (dialog) {
        is PluginDialog.Confirm -> AlertDialog(
            onDismissRequest = { finish { dialog.answer(false) } },
            title = title,
            text = { if (dialog.text.isNotBlank()) Text(dialog.text) },
            confirmButton = { TextButton(onClick = { finish { dialog.answer(true) } }) { Text("ОК") } },
            dismissButton = { TextButton(onClick = { finish { dialog.answer(false) } }) { Text("Отмена") } }
        )

        is PluginDialog.Prompt -> {
            var value by remember(dialog) { mutableStateOf(dialog.initial) }
            AlertDialog(
                onDismissRequest = { finish { dialog.answer(null) } },
                title = title,
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(dialog.label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = { TextButton(onClick = { finish { dialog.answer(value) } }) { Text("ОК") } },
                dismissButton = { TextButton(onClick = { finish { dialog.answer(null) } }) { Text("Отмена") } }
            )
        }

        is PluginDialog.Pick -> AlertDialog(
            onDismissRequest = { finish { dialog.answer(-1) } },
            title = title,
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    dialog.items.forEachIndexed { index, item ->
                        TextButton(
                            onClick = { finish { dialog.answer(index) } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(item) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { finish { dialog.answer(-1) } }) { Text("Отмена") } }
        )
    }
}
