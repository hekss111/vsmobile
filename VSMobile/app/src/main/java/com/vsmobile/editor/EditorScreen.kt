package com.vsmobile.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.vsmobile.editor.plugins.ActivePanel
import com.vsmobile.editor.plugins.InstalledPlugin
import com.vsmobile.editor.plugins.Permission
import com.vsmobile.editor.plugins.PluginContext
import com.vsmobile.editor.plugins.VsmPlugin
import kotlin.math.roundToInt

/** Экран открытого проекта: дерево файлов, вкладки, редактор, ИИ-чат и консоль Python. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, project: DocumentFile, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = vm.repo
    val account = vm.account
    val console = vm.console
    val openTabs = vm.openTabs
    var activeTabIndex by vm::activeTabIndex
    var expandedDirs by vm::expandedDirs
    val scope = rememberCoroutineScope()

    var drawerOpen by rememberSaveable { mutableStateOf(true) }
    var showNewFileDialog by remember { mutableStateOf<DocumentFile?>(null) }
    var showNewFolderDialog by remember { mutableStateOf<DocumentFile?>(null) }
    var showRenameDialog by remember { mutableStateOf<DocumentFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<DocumentFile?>(null) }
    var showAiChat by remember { mutableStateOf(false) }
    var showPluginDialog by remember { mutableStateOf(false) }
    val pluginItems by produceState<List<InstalledPlugin>>(emptyList(), project.uri) {
        value = withContext(Dispatchers.IO) { vm.pluginStore.list().filter { it.enabled } }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = OpenTreeContract()
    ) { uri ->
        if (uri != null) vm.openFolder(uri)
    }

    // Объявлен после обработчика «назад» в VSMobileApp, поэтому имеет приоритет, пока открыт ИИ-чат.
    BackHandler(enabled = showAiChat) { showAiChat = false }

    var treeRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(vm.fsVersion) { if (vm.fsVersion > 0) treeRefresh++ }
    val dragState = remember { DragDropState() }

    // Дерево файлов читаем в фоне и кэшируем, чтобы при отрисовке не дёргать провайдер на главном потоке.
    val treeNodes = remember { mutableStateMapOf<String, List<FileNode>>() }
    var treeLoadedRefresh by remember { mutableStateOf(-1) }
    LaunchedEffect(project.uri, treeRefresh, expandedDirs) {
        // После изменений (treeRefresh) перечитываем всё, при раскрытии папки — только недостающее.
        val reloadAll = treeLoadedRefresh != treeRefresh
        val expanded = expandedDirs
        val known: Map<String, List<FileNode>> = if (reloadAll) emptyMap() else HashMap(treeNodes)
        val loaded = withContext(Dispatchers.IO) {
            val out = HashMap<String, List<FileNode>>()
            fun walk(dir: DocumentFile) {
                val nodes = known[dir.uri.toString()] ?: repo.listNodes(dir)
                out[dir.uri.toString()] = nodes
                for (n in nodes) {
                    if (n.isDir && n.file.uri.toString() in expanded) walk(n.file)
                }
            }
            walk(project)
            out
        }
        treeNodes.clear()
        treeNodes.putAll(loaded)
        treeLoadedRefresh = treeRefresh
    }

    /** Перенос файла/папки перетаскиванием. Вкладки переносимого закрываются (с сохранением), т.к. URI меняется. */
    fun moveItem(src: DocumentFile, srcParent: DocumentFile, target: DocumentFile) {
        vm.closeTabsUnder(src, saveFirst = true)
        scope.launch {
            val error = withContext(Dispatchers.IO) { repo.move(src, srcParent, target) }
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            } else {
                expandedDirs = expandedDirs + target.uri.toString()
                Toast.makeText(context, "«${src.name}» перемещено в «${target.name}»", Toast.LENGTH_SHORT).show()
            }
            treeRefresh++
        }
    }

    fun runPluginCommand(plugin: VsmPlugin, commandId: String) {
        val idx = activeTabIndex
        val tab = openTabs.getOrNull(idx)
        val selection = tab?.content?.selection
        val pluginContext = PluginContext(
            fileName = tab?.file?.name ?: "",
            text = tab?.content?.text ?: "",
            selectionStart = selection?.start ?: 0,
            selectionEnd = selection?.end ?: 0
        )
        val tabUri = tab?.file?.uri?.toString()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                vm.plugins.runCommand(plugin.id, commandId, pluginContext, tabUri)
            }
            result.fold(
                onSuccess = { r ->
                    r.text?.let { newText ->
                        val current = openTabs.getOrNull(idx)
                        if (tab != null && current != null && current.file.uri == tab.file.uri) {
                            openTabs[idx] = current.copy(
                                content = TextFieldValue(newText, androidx.compose.ui.text.TextRange(newText.length)),
                                isDirty = true
                            )
                        }
                    }
                    r.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                },
                onFailure = {
                    Toast.makeText(context, "Ошибка плагина: ${it.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun buildAiContext(): JSONObject {
        val tree = JSONArray()
        var count = 0
        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > 6) return
            for (child in repo.listChildren(dir)) {
                if (count >= 200) return
                val name = child.name ?: continue
                if (name.startsWith(".") || name == "build" || name == "node_modules") continue
                val path = prefix + name
                count++
                if (child.isDirectory) {
                    tree.put("$path/")
                    walk(child, "$path/", depth + 1)
                } else {
                    tree.put(path)
                }
            }
        }
        walk(project, "", 0)
        val files = JSONArray()
        openTabs.getOrNull(activeTabIndex)?.let { tab ->
            files.put(
                JSONObject()
                    .put("path", tab.file.name ?: "file")
                    .put("content", tab.content.text.take(12000))
            )
        }
        return JSONObject()
            .put("project_name", project.name ?: "")
            .put("file_tree", tree)
            .put("files", files)
    }

    if (showAiChat) {
        AiChatScreen(
            account = account,
            projectName = project.name,
            buildContext = { buildAiContext() },
            onBack = { showAiChat = false },
            onOpenSettings = {
                vm.closeProject()
                onOpenSettings()
            }
        )
        return
    }

    val currentTabForActions = openTabs.getOrNull(activeTabIndex)
    val isPython = currentTabForActions?.file?.name?.endsWith(".py") == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name ?: "?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeProject() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад к проектам")
                    }
                },
                actions = {
                    IconButton(onClick = { showAiChat = true }) {
                        Icon(Icons.Default.SmartToy, "ИИ-помощник", tint = AppTheme.accent)
                    }
                    IconButton(onClick = {
                        vm.saveAllTabs()
                        openFolderLauncher.launch(Unit)
                    }) {
                        Icon(Icons.Default.FolderOpen, "Открыть другую папку")
                    }
                    IconButton(onClick = { drawerOpen = !drawerOpen }) {
                        Icon(Icons.Default.AccountTree, "Файлы")
                    }

                    IconButton(onClick = { showPluginDialog = true }) {
                        Icon(Icons.Default.Extension, "Команды плагинов", tint = AppTheme.accent)
                    }

                    if (isPython) {
                        if (console.isRunning) {
                            IconButton(onClick = { console.stop() }) {
                                Icon(Icons.Default.Stop, "Остановить", tint = Color(0xFFF48771))
                            }
                        } else {
                            IconButton(onClick = { console.run(currentTabForActions!!.content.text) }) {
                                Icon(Icons.Default.PlayArrow, "Запустить Python", tint = AppTheme.accent)
                            }
                        }
                    }

                    IconButton(onClick = { vm.saveTab(activeTabIndex) }, enabled = activeTabIndex >= 0) {
                        Icon(Icons.Default.Save, "Сохранить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { dragState.containerOrigin = it.positionInRoot() }
        ) {
            Row(Modifier.fillMaxSize()) {
                if (drawerOpen) {
                    Column(
                        Modifier
                            .width(230.dp)
                            .fillMaxHeight()
                            .background(AppTheme.surface)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = { showNewFolderDialog = project },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, "Новая папка", tint = Color(0xFF9DA5B4))
                            }
                            IconButton(
                                onClick = { showNewFileDialog = project },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.NoteAdd, "Новый файл", tint = Color(0xFF9DA5B4))
                            }
                        }
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { dragState.listBounds = it.boundsInRoot() }
                                .then(
                                    if (dragState.hoverTarget?.uri == project.uri)
                                        Modifier.border(2.dp, AppTheme.accent, RoundedCornerShape(4.dp))
                                    else Modifier
                                )
                        ) {
                            item(key = "refresh-$treeRefresh") {}
                            renderTree(
                                dir = project,
                                depth = 0,
                                nodes = treeNodes,
                                expandedDirs = expandedDirs,
                                onToggleDir = { d ->
                                    val k = d.uri.toString()
                                    expandedDirs = if (k in expandedDirs) expandedDirs - k
                                    else expandedDirs + k
                                },
                                onOpenFile = { f -> vm.openFile(f) },
                                onNewFile = { d -> showNewFileDialog = d },
                                onNewFolder = { d -> showNewFolderDialog = d },
                                onRename = { f -> showRenameDialog = f },
                                onDelete = { f -> showDeleteConfirm = f },
                                drag = dragState,
                                rootDir = project,
                                onMove = { s, p, t -> moveItem(s, p, t) }
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxHeight().width(1.dp).background(AppTheme.divider)
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .imePadding()
                ) {
                    if (openTabs.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = activeTabIndex.coerceAtLeast(0),
                            containerColor = AppTheme.surface,
                            edgePadding = 0.dp
                        ) {
                            openTabs.forEachIndexed { idx, tab ->
                                Tab(
                                    selected = idx == activeTabIndex,
                                    onClick = { activeTabIndex = idx },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                (if (tab.isDirty) "\u25CF " else "") + (tab.file.name ?: "?"),
                                                maxLines = 1,
                                                fontSize = 13.sp
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.Close,
                                                "Закрыть",
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { vm.closeTab(idx) }
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        val tab = openTabs.getOrNull(activeTabIndex)
                        if (tab != null) {
                            val language = SyntaxHighlighter.languageFromFileName(tab.file.name ?: "")
                            Box(Modifier.weight(1f)) {
                                CodeEditor(
                                    value = tab.content,
                                    onValueChange = { newVal ->
                                        openTabs[activeTabIndex] = tab.copy(
                                            content = newVal,
                                            isDirty = tab.isDirty || newVal.text != tab.content.text
                                        )
                                        if (newVal.text != tab.content.text) vm.onTextChanged(tab.file.name ?: "")
                                    },
                                    language = language,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            PinnedCommandsBar(pluginItems) { plugin, commandId -> runPluginCommand(plugin, commandId) }
                            QuickSymbolsBar(onInsert = { symbol ->
                                val cur = openTabs[activeTabIndex]
                                val sel = cur.content.selection
                                val newText = cur.content.text.substring(0, sel.start) +
                                        symbol + cur.content.text.substring(sel.end)
                                val newCursor = sel.start + symbol.length
                                openTabs[activeTabIndex] = cur.copy(
                                    content = TextFieldValue(newText, androidx.compose.ui.text.TextRange(newCursor)),
                                    isDirty = true
                                )
                            })
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Code, null,
                                    tint = AppTheme.divider,
                                    modifier = Modifier.size(80.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Откройте файл из дерева проекта", color = Color(0xFF6E7681))
                            }
                        }
                    }
                }
            }
            dragState.dragged?.let { item ->
                Surface(
                    modifier = Modifier.offset {
                        IntOffset(
                            (dragState.pointer.x - dragState.containerOrigin.x).roundToInt() + 12,
                            (dragState.pointer.y - dragState.containerOrigin.y).roundToInt() - 24
                        )
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = AppTheme.accent.copy(alpha = 0.9f),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        item.name ?: "?",
                        color = Color(0xFF111111),
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    showNewFileDialog?.let { parent ->
        TextInputDialog(
            title = "Новый файл",
            label = "Имя файла (напр. Main.kt)",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    val f = repo.createFile(parent, name)
                    treeRefresh++
                    if (f != null) vm.openFile(f)
                }
                showNewFileDialog = null
            },
            onDismiss = { showNewFileDialog = null }
        )
    }

    showNewFolderDialog?.let { parent ->
        TextInputDialog(
            title = "Новая папка",
            label = "Имя папки",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    repo.createFolder(parent, name)
                    expandedDirs = expandedDirs + parent.uri.toString()
                    treeRefresh++
                }
                showNewFolderDialog = null
            },
            onDismiss = { showNewFolderDialog = null }
        )
    }

    showRenameDialog?.let { target ->
        TextInputDialog(
            title = "Переименовать",
            label = "Новое имя",
            initialValue = target.name ?: "",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    vm.closeTabsUnder(target, saveFirst = true)
                    scope.launch {
                        if (!withContext(Dispatchers.IO) { repo.rename(target, name) }) {
                            Toast.makeText(context, "Не удалось переименовать", Toast.LENGTH_SHORT).show()
                        }
                        treeRefresh++
                    }
                }
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    showDeleteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Удалить «${target.name}»?") },
            text = { Text("Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeTabsUnder(target, saveFirst = false)
                    scope.launch {
                        if (!withContext(Dispatchers.IO) { repo.delete(target) }) {
                            Toast.makeText(context, "Не удалось удалить", Toast.LENGTH_SHORT).show()
                        }
                        treeRefresh++
                    }
                    showDeleteConfirm = null
                }) { Text("Удалить", color = Color(0xFFF48771)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Отмена") }
            }
        )
    }

    if (showPluginDialog) {
        AlertDialog(
            onDismissRequest = { showPluginDialog = false },
            title = { Text("Команды плагинов") },
            text = {
                val list = pluginItems
                Column {
                    when {
                        list.isEmpty() -> Text("Плагины не установлены. Добавьте .vsm в разделе «Плагины».")
                        else -> list.forEach { item ->
                            val plugin = item.plugin
                            Text(plugin.name, fontWeight = FontWeight.Bold)
                            plugin.commands.forEach { command ->
                                TextButton(onClick = {
                                    showPluginDialog = false
                                    runPluginCommand(plugin, command.id)
                                }) { Text(command.title) }
                            }
                            if (Permission.UI in item.granted) {
                                plugin.panels.forEach { panel ->
                                    TextButton(onClick = {
                                        showPluginDialog = false
                                        vm.activePanel = ActivePanel(plugin.id, panel.id)
                                    }) { Text(panel.title) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPluginDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (console.visible) {
        val consoleScroll = rememberScrollState()
        LaunchedEffect(console.text) { consoleScroll.scrollTo(Int.MAX_VALUE) }
        AlertDialog(
            onDismissRequest = { if (!console.isRunning) console.visible = false },
            title = { Text(if (console.isRunning) "Выполняется" else "Результат") },
            text = {
                Column {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(consoleScroll)
                    ) {
                        Text(
                            if (console.text.isEmpty() && !console.isRunning) "(нет вывода)" else console.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    if (console.waiting) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = console.inputText,
                            onValueChange = { console.inputText = it },
                            singleLine = true,
                            label = { Text("Ввод") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { console.submitAnswer() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (console.isRunning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (!console.isRunning) {
                    TextButton(onClick = { console.visible = false }) { Text("Закрыть") }
                } else if (console.waiting) {
                    TextButton(onClick = { console.submitAnswer() }) { Text("Отправить") }
                }
            },
            dismissButton = {
                if (console.isRunning) {
                    TextButton(onClick = { console.stop() }) { Text("Остановить") }
                }
            }
        )
    }
}
