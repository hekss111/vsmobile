package com.vsmobile.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.plugins.InstalledPlugin
import com.vsmobile.editor.plugins.VsmPlugin

@Composable
fun PinnedCommandsBar(items: List<InstalledPlugin>, onRun: (VsmPlugin, String) -> Unit) {
    val pinned = items.flatMap { item -> item.plugin.commands.filter { it.pinned }.map { item.plugin to it } }
    if (pinned.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.surface)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        items(pinned.size) { index ->
            val (plugin, command) = pinned[index]
            OutlinedButton(
                onClick = { onRun(plugin, command.id) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD4D4D4))
            ) {
                Text(command.title, fontSize = 13.sp)
            }
        }
    }
}
