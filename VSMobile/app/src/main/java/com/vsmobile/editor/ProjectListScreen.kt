package com.vsmobile.editor

import androidx.compose.material.icons.filled.Extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Главный экран: недавние папки и проекты из корневой папки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(vm: EditorViewModel, onOpenSettings: () -> Unit, onOpenPlugins: () -> Unit) {
    val repo = vm.repo

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var projectsRefresh by remember { mutableStateOf(0) }
    var recentRefresh by remember { mutableStateOf(0) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = OpenTreeContract()
    ) { uri ->
        uri?.let {
            repo.setRootUri(it)
            projectsRefresh++
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = OpenTreeContract()
    ) { uri ->
        if (uri != null) vm.openFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VS Mobile", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { openFolderLauncher.launch(Unit) }) {
                        Icon(Icons.Default.FolderOpen, "Открыть папку")
                    }
                    IconButton(onClick = { folderPickerLauncher.launch(Unit) }) {
                        Icon(Icons.Default.Storage, "Выбрать папку проектов")
                    }
                    IconButton(onClick = onOpenPlugins) {
                        Icon(Icons.Default.Extension, "Плагины")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewProjectDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Новый проект") }
            )
        }
    ) { padding ->
        val listing by produceState<Pair<List<ProjectItem>, List<RecentFolder>>?>(null, projectsRefresh, recentRefresh) {
            value = withContext(Dispatchers.IO) { repo.listProjects() to repo.recentFolders() }
        }
        val projects = listing?.first ?: emptyList()
        val recent = listing?.second ?: emptyList()
        if (listing != null && projects.isEmpty() && recent.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FolderOpen, null,
                        tint = Color(0xFF6E7681),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Откройте любую папку с кодом на телефоне или создайте новый проект",
                        color = Color(0xFF9DA5B4),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { openFolderLauncher.launch(Unit) }) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть папку")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (recent.isNotEmpty()) {
                    item {
                        Text(
                            "Недавние папки",
                            color = Color(0xFF9DA5B4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(recent.size) { i ->
                        val entry = recent[i]
                        ListItem(
                            headlineContent = {
                                Text(entry.doc.name ?: "?", fontWeight = FontWeight.Medium)
                            },
                            supportingContent = {
                                Text(entry.treeUri.lastPathSegment ?: "", maxLines = 1)
                            },
                            leadingContent = {
                                Icon(Icons.Default.FolderOpen, null, tint = Color(0xFFDCB67A))
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    repo.removeRecent(entry.treeUri)
                                    recentRefresh++
                                }) {
                                    Icon(Icons.Default.Close, "Убрать из списка")
                                }
                            },
                            modifier = Modifier.clickable {
                                val doc = repo.openFolder(entry.treeUri)
                                if (doc != null) vm.openProject(doc) else recentRefresh++
                            }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.divider))
                    }
                    if (projects.isNotEmpty()) {
                        item {
                            Text(
                                "Проекты",
                                color = Color(0xFF9DA5B4),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
                items(projects.size) { i ->
                    val proj = projects[i]
                    ListItem(
                        headlineContent = { Text(proj.name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("${proj.count} элементов") },
                        leadingContent = {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFDCB67A))
                        },
                        modifier = Modifier.clickable { vm.openProject(proj.doc) }
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.divider))
                }
            }
        }
    }

    if (showNewProjectDialog) {
        TextInputDialog(
            title = "Новый проект",
            label = "Название проекта",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    val p = repo.createProject(name)
                    projectsRefresh++
                    if (p != null) vm.openProject(p)
                }
                showNewProjectDialog = false
            },
            onDismiss = { showNewProjectDialog = false }
        )
    }
}
