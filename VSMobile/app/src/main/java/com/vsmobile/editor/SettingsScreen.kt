package com.vsmobile.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import com.vsmobile.editor.net.ApiException
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vsmobile.editor.net.AccountRepository

private const val AUTHOR_NAME = "k1llcoder"
private val GITHUB_URL = AppLinks.GITHUB
private val TELEGRAM_URL = AppLinks.TELEGRAM
private const val TIKTOK_URL = ""
private const val MUTED = 0xFF9DA5B4

private data class LanguageInfo(
    val name: String,
    val extensions: String,
    val runnable: Boolean
)

private val supportedLanguages = listOf(
    LanguageInfo("Kotlin", ".kt, .kts", false),
    LanguageInfo("Python", ".py", true),
    LanguageInfo("JavaScript", ".js, .jsx", false),
    LanguageInfo("TypeScript", ".ts, .tsx", false),
    LanguageInfo("Java", ".java", false),
    LanguageInfo("C / C++", ".c, .cpp, .h, .hpp", false)
)

@Composable
fun SettingsScreen(
    repo: FileRepository,
    account: AccountRepository,
    onBack: () -> Unit,
    onRecentCleared: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenSubscription: () -> Unit
) {
    val context = LocalContext.current
    remember { AppTheme.load(context) }
    remember { EditorSettings.load(context) }
    MaterialTheme(colorScheme = AppTheme.colorScheme()) {
        SettingsContent(repo, account, onBack, onRecentCleared, onOpenAuth, onOpenSubscription)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    repo: FileRepository,
    account: AccountRepository,
    onBack: () -> Unit,
    onRecentCleared: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenSubscription: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var recentCount by remember { mutableStateOf(repo.recentFolders().size) }
    val rootLabel = repo.rootUri?.lastPathSegment ?: "не выбрана"
    var hexInput by remember(AppTheme.accent) { mutableStateOf(AppTheme.hexOf(AppTheme.accent)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("Аккаунт и подписка")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    if (!AppLinks.ACCOUNT_AVAILABLE) {
                        Text(
                            "Вход и оплата подписки пока недоступны. Скоро появятся в следующих обновлениях. Сейчас используйте свой API-ключ ниже.",
                            color = Color(MUTED),
                            fontSize = 13.sp
                        )
                    } else if (account.isLoggedIn) {
                        ProfileSection(account)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth()) {
                            Text("Подписка и использование ИИ")
                        }
                    } else {
                        Text(
                            "Войдите, чтобы оформить подписку на встроенного ИИ-помощника (DeepSeek). Без входа можно пользоваться редактором и своим API-ключом ниже.",
                            color = Color(MUTED),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenAuth, modifier = Modifier.fillMaxWidth()) {
                            Text("Войти")
                        }
                    }
                }
            }

            SectionLabel("ИИ-помощник")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    var apiKey by remember { mutableStateOf(account.apiKey ?: "") }
                    var showKey by remember { mutableStateOf(false) }
                    var baseUrl by remember { mutableStateOf(account.apiKeyBaseUrl) }
                    var model by remember { mutableStateOf(account.apiKeyModel) }

                    Text(
                        "Свой API-ключ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Вставьте сюда ключ своей нейросети (OpenAI-совместимый API). Ключ хранится только на телефоне и уходит напрямую провайдеру, минуя наш сервер.",
                        color = Color(MUTED),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            account.apiKey = it
                        },
                        label = { Text("API-ключ") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (showKey) "Скрыть" else "Показать"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            account.apiKeyBaseUrl = it
                        },
                        label = { Text("Адрес API (base URL)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            account.apiKeyModel = it
                        },
                        label = { Text("Модель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (apiKey.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Используется ваш ключ — подписка и дневные лимиты сервера не применяются.",
                            color = AppTheme.accent,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            SectionLabel("О приложении")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "K",
                                color = Color(0xFF1E1E1E),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Автор: $AUTHOR_NAME",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Разработчик VS Mobile",
                                color = Color(MUTED),
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    InfoRow("Версия", "v1.0 release")
                }
            }

            SectionLabel("Наши соцсети")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SocialButton("Telegram", Icons.AutoMirrored.Filled.Send, TELEGRAM_URL) {
                        uriHandler.openUri(it)
                    }
                    SocialButton("TikTok", Icons.Default.MusicNote, TIKTOK_URL) {
                        uriHandler.openUri(it)
                    }
                    SocialButton("GitHub", Icons.Default.Code, GITHUB_URL) {
                        uriHandler.openUri(it)
                    }
                }
            }

            SectionLabel("Оформление")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Цвет акцента", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppTheme.accents.forEach { option ->
                            ColorDot(
                                color = option.color,
                                selected = option.color == AppTheme.accent,
                                description = option.name
                            ) { AppTheme.setAccent(context, option.color) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            val cleaned = raw
                                .removePrefix("#")
                                .filter { it in "0123456789abcdefABCDEF" }
                                .take(6)
                                .uppercase()
                            hexInput = cleaned
                            AppTheme.parseHex(cleaned)?.let { AppTheme.setAccent(context, it) }
                        },
                        label = { Text("Свой цвет (HEX)") },
                        prefix = { Text("#") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    AccentSliders()
                    Spacer(Modifier.height(16.dp))
                    Text("Фон", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppTheme.backgrounds.forEachIndexed { index, option ->
                            BackgroundSwatch(
                                option = option,
                                selected = index == AppTheme.backgroundIndex
                            ) { AppTheme.setBackground(context, index) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { AppTheme.reset(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сбросить оформление")
                    }
                }
            }

            SectionLabel("Редактор")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Автодополнение скобок и кавычек",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "При вводе открывающей скобки или кавычки сразу ставится парный закрывающий символ",
                                color = Color(MUTED),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = EditorSettings.autoCloseEnabled,
                            onCheckedChange = { EditorSettings.setAutoCloseEnabled(context, it) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    var extText by remember { mutableStateOf(EditorSettings.disabledExtensionsText()) }
                    OutlinedTextField(
                        value = extText,
                        onValueChange = {
                            extText = it
                            EditorSettings.setDisabledExtensions(context, it)
                        },
                        label = { Text("Не применять для расширений") },
                        placeholder = { Text("txt, md, json") },
                        supportingText = { Text("Через запятую или пробел, без точки") },
                        singleLine = true,
                        enabled = EditorSettings.autoCloseEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SectionLabel("Поддерживаемые языки")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    supportedLanguages.forEach { LanguageRow(it) }
                    Text(
                        "Остальные файлы открываются с базовой подсветкой строк, чисел и комментариев. Запуск кода пока доступен для Python.",
                        color = Color(MUTED),
                        fontSize = 12.sp
                    )
                }
            }

            SectionLabel("Хранилище")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppTheme.surface)
            ) {
                Column(Modifier.padding(20.dp)) {
                    InfoRow("Папка проектов", rootLabel)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Недавние папки", recentCount.toString())
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            repo.clearRecent()
                            recentCount = 0
                            onRecentCleared()
                        },
                        enabled = recentCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Очистить недавние папки")
                    }
                }
            }
        }
    }
}

private val NICK_REGEX = Regex("[\\w.\\-]{3,20}")

/** Декодирует аватарку из data-URI или голого base64. */
private fun decodeAvatar(data: String?): ImageBitmap? {
    if (data.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/** Центр-кроп до 256×256, JPEG, base64 — укладывается в лимит сервера (120 тыс. символов). */
private fun encodeAvatar(context: android.content.Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        val src = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}

/** Профиль: аватарка (выбор из галереи), ник, email. */
@Composable
private fun ProfileSection(account: AccountRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var avatarData by remember { mutableStateOf(account.avatar) }
    var savedNick by remember { mutableStateOf(account.nickname) }
    var nickInput by remember { mutableStateOf(account.nickname ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val bitmap = remember(avatarData) { decodeAvatar(avatarData) }

    // Подтягиваем актуальный профиль (если сервер недоступен — остаёмся на кэше).
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { account.refreshProfile() }
            avatarData = account.avatar
            savedNick = account.nickname
            nickInput = account.nickname ?: ""
        } catch (_: ApiException) {
        }
    }

    suspend fun doSave(nick: String?, avatar: String?) {
        busy = true
        message = null
        try {
            withContext(Dispatchers.IO) { account.updateProfile(nick, avatar) }
            avatarData = account.avatar
            savedNick = account.nickname
            nickInput = account.nickname ?: ""
            isError = false
            message = "Сохранено"
        } catch (e: ApiException) {
            isError = true
            message = e.message
        }
        busy = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val encoded = withContext(Dispatchers.IO) { encodeAvatar(context, uri) }
            if (encoded == null) {
                busy = false
                isError = true
                message = "Не удалось обработать изображение"
            } else {
                doSave(null, encoded)
            }
        }
    }

    val shownName = savedNick ?: account.displayName ?: "?"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = !busy) { picker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Аватарка",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    shownName.take(1).uppercase(),
                    color = Color(0xFF1E1E1E),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(shownName, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            account.userEmail?.let { Text(it, color = Color(MUTED), fontSize = 13.sp, maxLines = 1) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
            Text(if (bitmap == null) "Выбрать аватарку" else "Сменить аватарку")
        }
        if (bitmap != null) {
            TextButton(onClick = { scope.launch { doSave(null, "") } }, enabled = !busy) {
                Text("Убрать")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val nickTrimmed = nickInput.trim()
    val nickValid = NICK_REGEX.matches(nickTrimmed)
    OutlinedTextField(
        value = nickInput,
        onValueChange = { nickInput = it.filter { c -> !c.isWhitespace() }.take(20) },
        label = { Text("Ник") },
        supportingText = { Text("3–20 символов: буквы, цифры, . - _") },
        isError = nickInput.isNotEmpty() && !nickValid,
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { scope.launch { doSave(nickTrimmed, null) } },
        enabled = !busy && nickValid && nickTrimmed != savedNick,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Сохранить ник") }
    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = if (isError) Color(0xFFF48771) else AppTheme.accent, fontSize = 12.sp)
    }
}

@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                description,
                tint = Color(0xFF111111),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BackgroundSwatch(
    option: BackgroundOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(76.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 76.dp, height = 52.dp)
                .clip(shape)
                .background(option.background)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) AppTheme.accent else option.divider,
                    shape = shape
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(option.surface)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(option.name, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun LanguageRow(info: LanguageInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(info.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(info.extensions, color = Color(MUTED), fontSize = 12.sp)
        }
        if (info.runnable) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppTheme.accent.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Запуск", color = AppTheme.accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SocialButton(
    label: String,
    icon: ImageVector,
    url: String,
    onOpen: (String) -> Unit
) {
    OutlinedButton(
        onClick = { onOpen(url) },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (url.isBlank()) "$label (скоро)" else label)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color(MUTED),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Ручной подбор акцента: ползунки R/G/B + предпросмотр итогового цвета. */
@Composable
private fun AccentSliders() {
    val context = LocalContext.current
    val argb = AppTheme.accent.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val onPreview = if (AppTheme.accent.luminance() > 0.5f) Color(0xFF111111) else Color.White

    fun withChannel(shift: Int, value: Int): Color {
        val cur = AppTheme.accent.toArgb()
        val mask = (0xFF shl shift).inv()
        return Color((cur and mask) or (value.coerceIn(0, 255) shl shift) or 0xFF000000.toInt())
    }

    val save = { AppTheme.setAccent(context, AppTheme.accent) }

    Text("Подобрать цвет ползунками", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.accent),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "#${AppTheme.hexOf(AppTheme.accent)}",
                color = onPreview,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text("RGB($r, $g, $b)", color = onPreview, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
    ChannelSlider("R", r, Color(0xFFE06C6C), { AppTheme.previewAccent(withChannel(16, it)) }, save)
    ChannelSlider("G", g, Color(0xFF7EC27E), { AppTheme.previewAccent(withChannel(8, it)) }, save)
    ChannelSlider("B", b, Color(0xFF569CD6), { AppTheme.previewAccent(withChannel(0, it)) }, save)
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Int,
    tint: Color,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onFinished,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = tint.copy(alpha = 0.25f)
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString(),
            fontSize = 13.sp,
            color = Color(MUTED),
            modifier = Modifier.width(34.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(MUTED), fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, maxLines = 1)
    }
}
