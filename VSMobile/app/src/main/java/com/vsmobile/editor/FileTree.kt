package com.vsmobile.editor

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile

@OptIn(ExperimentalFoundationApi::class)
fun androidx.compose.foundation.lazy.LazyListScope.renderTree(
    dir: DocumentFile,
    depth: Int,
    nodes: Map<String, List<FileNode>>,
    expandedDirs: Set<String>,
    onToggleDir: (DocumentFile) -> Unit,
    onOpenFile: (DocumentFile) -> Unit,
    onNewFile: (DocumentFile) -> Unit,
    onNewFolder: (DocumentFile) -> Unit,
    onRename: (DocumentFile) -> Unit,
    onDelete: (DocumentFile) -> Unit,
    drag: DragDropState,
    rootDir: DocumentFile,
    onMove: (DocumentFile, DocumentFile, DocumentFile) -> Unit
) {
    val children = nodes[dir.uri.toString()] ?: emptyList()
    for (node in children) {
        val child = node.file
        item(key = child.uri.toString()) {
            var showMenu by remember { mutableStateOf(false) }
            val key = child.uri.toString()
            val isDir = node.isDir
            val haptic = LocalHapticFeedback.current
            DisposableEffect(key) { onDispose { drag.rows.remove(key) } }
            val hovered = drag.hoverTarget?.uri?.toString() == key
            val beingDragged = drag.dragged?.uri?.toString() == key
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { drag.rows[key] = RowInfo(it.boundsInRoot(), child, dir, isDir) }
                        .background(
                            when {
                                hovered -> AppTheme.accent.copy(alpha = 0.25f)
                                beingDragged -> AppTheme.divider
                                else -> Color.Transparent
                            }
                        )
                        .clickable {
                            if (SystemClock.uptimeMillis() < drag.blockClickUntil) return@clickable
                            if (isDir) onToggleDir(child) else onOpenFile(child)
                        }
                        // Зажатие и перетаскивание — перенос в другую папку; меню — по кнопке «⋮»
                        .pointerInput(key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    drag.start(child, dir, key, offset, rootDir)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.moveBy(amount, rootDir)
                                },
                                onDragEnd = { drag.finish(onMove) },
                                onDragCancel = { drag.cancel() }
                            )
                        }
                        .padding(start = (12 + depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isExpanded = child.uri.toString() in expandedDirs
                    Icon(
                        imageVector = when {
                            isDir && isExpanded -> Icons.Default.FolderOpen
                            isDir -> Icons.Default.Folder
                            else -> iconForFile(node.name)
                        },
                        contentDescription = null,
                        tint = if (isDir) Color(0xFFDCB67A) else Color(0xFF9CDCFE),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        node.name,
                        color = Color(0xFFD4D4D4),
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            "Меню",
                            tint = Color(0xFF6E7681),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (isDir) {
                        DropdownMenuItem(
                            text = { Text("Новый файл") },
                            onClick = { showMenu = false; onNewFile(child) }
                        )
                        DropdownMenuItem(
                            text = { Text("Новая папка") },
                            onClick = { showMenu = false; onNewFolder(child) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        onClick = { showMenu = false; onRename(child) }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = { showMenu = false; onDelete(child) }
                    )
                }
            }
        }
        if (node.isDir && child.uri.toString() in expandedDirs) {
            renderTree(
                child, depth + 1, nodes, expandedDirs,
                onToggleDir, onOpenFile, onNewFile, onNewFolder, onRename, onDelete,
                drag, rootDir, onMove
            )
        }
    }
}

/** Где на экране находится строка дерева (координаты root) — для поиска цели при перетаскивании. */
class RowInfo(val rect: Rect, val file: DocumentFile, val parent: DocumentFile, val isDir: Boolean)

/** Состояние перетаскивания файлов в дереве проекта. */
class DragDropState {
    val rows = HashMap<String, RowInfo>()
    var listBounds: Rect = Rect.Zero
    var containerOrigin: Offset = Offset.Zero
    var blockClickUntil: Long = 0L

    var dragged by mutableStateOf<DocumentFile?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var hoverTarget by mutableStateOf<DocumentFile?>(null)
    private var draggedParent: DocumentFile? = null

    fun start(file: DocumentFile, parent: DocumentFile, key: String, offset: Offset, root: DocumentFile) {
        dragged = file
        draggedParent = parent
        pointer = (rows[key]?.rect?.topLeft ?: Offset.Zero) + offset
        blockClickUntil = Long.MAX_VALUE
        updateHover(root)
    }

    fun moveBy(delta: Offset, root: DocumentFile) {
        pointer += delta
        updateHover(root)
    }

    private fun updateHover(root: DocumentFile) {
        val src = dragged
        val srcParent = draggedParent
        if (src == null || srcParent == null) {
            hoverTarget = null
            return
        }
        val hit = rows.values.firstOrNull { it.rect.contains(pointer) }
        val candidate = when {
            hit != null -> if (hit.isDir) hit.file else hit.parent
            listBounds.contains(pointer) -> root
            else -> null
        }
        hoverTarget = candidate?.takeIf { isValidTarget(src, srcParent, it) }
    }

    /** Нельзя бросать в ту же папку, в саму себя или в своё потомка. */
    private fun isValidTarget(src: DocumentFile, srcParent: DocumentFile, target: DocumentFile): Boolean {
        val s = src.uri.toString()
        val t = target.uri.toString()
        return t != srcParent.uri.toString() && t != s && !t.startsWith("$s%2F")
    }

    fun finish(onMove: (DocumentFile, DocumentFile, DocumentFile) -> Unit) {
        val src = dragged
        val parent = draggedParent
        val target = hoverTarget
        reset()
        if (src != null && parent != null && target != null) onMove(src, parent, target)
    }

    fun cancel() = reset()

    private fun reset() {
        dragged = null
        draggedParent = null
        hoverTarget = null
        // Даём время прогнать «отпускание» пальца, чтобы оно не стало кликом по строке
        blockClickUntil = SystemClock.uptimeMillis() + 400
    }
}

fun iconForFile(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx" -> Icons.Default.Code
    "xml", "html", "json" -> Icons.Default.DataObject
    "md", "txt" -> Icons.Default.Description
    else -> Icons.Filled.Description
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("ОК") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
