package com.vsmobile.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.plugins.PluginStore
import com.vsmobile.editor.plugins.VsmPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MUTED = 0xFF9DA5B4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore(context) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val plugins by produceState<List<VsmPlugin>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { store.list() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.install(uri) }
            busy = false
            result.fold(
                onSuccess = {
                    Toast.makeText(context, "Плагин «${it.name}» добавлен", Toast.LENGTH_SHORT).show()
                    refresh++
                },
                onFailure = {
                    Toast.makeText(context, it.message ?: "Не удалось добавить плагин", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Плагины", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Добавить плагин") }
            )
        }
    ) { padding ->
        val list = plugins
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Плагины не установлены. Нажмите «Добавить плагин» и выберите файл .vsm",
                    color = Color(MUTED),
                    fontSize = 14.sp
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.id }) { plugin ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${plugin.name}  v${plugin.pluginVersion}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (plugin.description.isNotBlank()) {
                                    Text(plugin.description, color = Color(MUTED), fontSize = 13.sp)
                                }
                                Text(
                                    plugin.commands.joinToString { it.title },
                                    color = Color(MUTED),
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.remove(plugin.id) }
                                    refresh++
                                }
                            }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = Color(0xFFF48771))
                            }
                        }
                    }
                }
            }
        }
    }
}
