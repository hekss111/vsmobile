package com.vsmobile.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.net.AccountRepository
import com.vsmobile.editor.net.AiReply
import com.vsmobile.editor.net.AiRepository
import com.vsmobile.editor.net.ApiException
import com.vsmobile.editor.net.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MUTED = 0xFF9DA5B4
private const val MAX_HISTORY_SENT = 12

private data class UiMessage(val role: String, val text: String, val isError: Boolean = false)

/**
 * Чат с ИИ-помощником. [buildContext] вызывается заново перед каждой отправкой, чтобы
 * подтянуть актуальный открытый файл и дерево проекта (см. MainActivity.buildAiContext).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    account: AccountRepository,
    projectName: String?,
    buildContext: () -> JSONObject?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val aiRepo = remember { AiRepository(account) }
    val listState = rememberLazyListState()

    val messages = remember { mutableStateListOf<UiMessage>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var usageHint by remember { mutableStateOf<String?>(null) }

    val ready = account.hasOwnApiKey || account.isLoggedIn

    fun send() {
        val text = input.trim()
        if (text.isBlank() || sending) return
        input = ""
        messages.add(UiMessage("user", text))
        sending = true
        scope.launch {
            try {
                val history = messages
                    .filter { !it.isError }
                    .takeLast(MAX_HISTORY_SENT)
                    .map { ChatMessage(it.role, it.text) }
                val reply: AiReply = withContext(Dispatchers.IO) {
                    aiRepo.chat(history, buildContext())
                }
                messages.add(UiMessage("assistant", reply.text))
                usageHint = if (!reply.usedOwnKey && reply.used != null && reply.limit != null) {
                    "Подписка ${reply.plan ?: ""}: ${reply.used}/${reply.limit} запросов сегодня".trim()
                } else null
            } catch (e: ApiException) {
                messages.add(UiMessage("assistant", e.message ?: "Ошибка", isError = true))
            } finally {
                sending = false
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ИИ-помощник", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        if (projectName != null) {
                            Text(projectName, color = Color(MUTED), fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!ready) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.SmartToy, null, tint = Color(MUTED), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Чтобы пользоваться ИИ-помощником, укажите свой API-ключ в настройках.",
                        color = Color(MUTED),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSettings) { Text("Открыть настройки") }
                }
                return@Scaffold
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Спросите про открытый файл или проект — помощник видит дерево файлов и текущий открытый файл.",
                            color = Color(MUTED),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(messages) { msg -> MessageBubble(msg) }
                if (sending) {
                    item { TypingBubble() }
                }
            }

            usageHint?.let {
                Text(
                    it,
                    color = Color(MUTED),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Спросите про код...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { send() },
                    enabled = !sending && input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = AppTheme.accent)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        msg.isError -> Color(0xFF4B1F1F)
                        isUser -> AppTheme.accent.copy(alpha = 0.22f)
                        else -> AppTheme.surface
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                msg.text,
                color = if (msg.isError) Color(0xFFF48771) else Color(0xFFD4D4D4),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AppTheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text("Печатает…", color = Color(MUTED), fontSize = 13.sp)
        }
    }
}
