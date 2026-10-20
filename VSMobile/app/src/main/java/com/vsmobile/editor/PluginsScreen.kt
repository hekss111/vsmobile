package com.vsmobile.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.plugins.InstalledPlugin
import com.vsmobile.editor.plugins.Permission
import com.vsmobile.editor.plugins.PermissionText
import com.vsmobile.editor.plugins.PluginLanguages
import com.vsmobile.editor.plugins.PluginManager
import com.vsmobile.editor.plugins.PluginStore
import com.vsmobile.editor.plugins.VsmFormat
import com.vsmobile.editor.plugins.VsmPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MUTED = 0xFF9DA5B4
private const val DANGER = 0xFFF48771

private class PendingInstall(val raw: String, val plugin: VsmPlugin, val previous: InstalledPlugin?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit, manager: PluginManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore.forContext(context) }
    var refresh by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<PendingInstall?>(null) }
    val installed by produceState<List<InstalledPlugin>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { store.list() }
    }

    fun change(id: String, block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                block()
                PluginLanguages.rebuild(store.list())
                manager.restart(id)
            }
            refresh++
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                PluginStore.readText(context, uri).mapCatching { raw ->
                    val plugin = VsmFormat.parse(raw).getOrThrow()
                    PendingInstall(raw, plugin, store.list().firstOrNull { it.plugin.id == plugin.id })
                }
            }
            prepared.fold(
                onSuccess = { pending = it },
                onFailure = {
                    Toast.makeText(context, it.message ?: "Не удалось прочитать плагин", Toast.LENGTH_LONG).show()
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
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Добавить плагин") }
            )
        }
    ) { padding ->
        val list = installed
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Плагинов пока нет. Нажмите «Добавить плагин» и выберите файл .vsm",
                    color = Color(MUTED),
                    fontSize = 14.sp
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.plugin.id }) { item ->
                    PluginCard(
                        item = item,
                        onEnabled = { enabled -> change(item.plugin.id) { store.setEnabled(item.plugin.id, enabled) } },
                        onGrant = { permission, on ->
                            val next = if (on) item.granted + permission else item.granted - permission
                            change(item.plugin.id) { store.setGranted(item.plugin.id, next) }
                        },
                        onRemove = { change(item.plugin.id) { store.remove(item.plugin.id) } }
                    )
                }
            }
        }
    }

    pending?.let { request ->
        InstallDialog(
            request = request,
            onConfirm = { granted ->
                pending = null
                change(request.plugin.id) { store.install(request.raw, granted) }
            },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun InstallDialog(
    request: PendingInstall,
    onConfirm: (Set<Permission>) -> Unit,
    onDismiss: () -> Unit
) {
    val plugin = request.plugin
    val previous = request.previous
    val selected = remember(request) {
        mutableStateListOf<Permission>().apply {
            addAll(if (previous != null) previous.granted.intersect(plugin.permissions) else plugin.permissions)
        }
    }
    val ordered = plugin.permissions.sortedByDescending { it.risk }
    val warnings = PermissionText.warnings(selected.toSet())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                (if (previous != null) "Обновить" else "Установить") + " «${plugin.name}» v${plugin.pluginVersion}?"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val about = listOf(plugin.author, plugin.description).filter { it.isNotBlank() }.joinToString(". ")
                if (about.isNotEmpty()) Text(about, color = Color(MUTED), fontSize = 13.sp)
                if (ordered.isEmpty()) {
                    Text("Плагин не просит разрешений")
                } else {
                    Text("Плагин хочет:", fontWeight = FontWeight.Medium)
                    ordered.forEach { permission ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = permission in selected,
                                onCheckedChange = { on ->
                                    if (on) selected.add(permission) else selected.remove(permission)
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    PermissionText.describe(permission),
                                    color = if (permission.risk >= 3) Color(DANGER) else Color.Unspecified,
                                    fontSize = 14.sp
                                )
                                if (previous != null && permission !in previous.plugin.permissions) {
                                    Text("новое", color = AppTheme.accent, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                warnings.forEach { Text(it, color = Color(DANGER), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toSet()) }) {
                Text(if (previous != null) "Обновить" else "Установить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun PluginCard(
    item: InstalledPlugin,
    onEnabled: (Boolean) -> Unit,
    onGrant: (Permission, Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val plugin = item.plugin
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${plugin.name}  v${plugin.pluginVersion}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (plugin.description.isNotBlank()) {
                        Text(plugin.description, color = Color(MUTED), fontSize = 13.sp)
                    }
                    val parts = buildList {
                        if (plugin.commands.isNotEmpty()) add("Команды: " + plugin.commands.joinToString { it.title })
                        if (plugin.panels.isNotEmpty()) add("Панели: " + plugin.panels.joinToString { it.title })
                        if (plugin.languages.isNotEmpty()) {
                            add("Языки: " + plugin.languages.flatMap { it.extensions }.joinToString { ".$it" })
                        }
                    }
                    parts.forEach { Text(it, color = Color(MUTED), fontSize = 12.sp) }
                }
                Switch(checked = item.enabled, onCheckedChange = onEnabled)
            }
            if (plugin.permissions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Права: ${item.granted.size} из ${plugin.permissions.size}" + if (expanded) " (скрыть)" else " (показать)",
                    color = AppTheme.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
                if (expanded) {
                    plugin.permissions.sortedByDescending { it.risk }.forEach { permission ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = permission in item.granted,
                                onCheckedChange = { on -> onGrant(permission, on) }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                PermissionText.describe(permission),
                                color = if (permission.risk >= 3) Color(DANGER) else Color.Unspecified,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, "Удалить", tint = Color(DANGER))
                }
            }
        }
    }
}
