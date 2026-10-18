package com.vsmobile.editor

import android.app.Activity.RESULT_OK
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException as GoogleApiException
import com.vsmobile.editor.net.AccountRepository
import com.vsmobile.editor.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AuthMode { LOGIN, REGISTER, CODE_LOGIN, RESET }
private enum class CodeStep { FORM, CODE }

private val NICK_REGEX = Regex("[\\w.\\-]{3,20}")
private const val MUTED = 0xFF9DA5B4
private const val MIN_PASSWORD = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    account: AccountRepository,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var step by remember { mutableStateOf(CodeStep.FORM) }

    var login by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun switchMode(newMode: AuthMode) {
        mode = newMode
        step = CodeStep.FORM
        code = ""
        error = null
        if (newMode == AuthMode.RESET) {
            password = ""
            password2 = ""
        }
    }

    /** Общий запуск сетевого действия: лоадер, перехват ошибок, переход при успехе. */
    fun launchAction(block: suspend () -> Unit, onSuccess: () -> Unit) {
        loading = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                loading = false
                onSuccess()
            } catch (e: ApiException) {
                loading = false
                error = e.message
            }
        }
    }

    val googleEnabled = BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()
    val googleClient = remember {
        if (!googleEnabled) null else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(context, gso)
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val idToken = task.getResult(GoogleApiException::class.java).idToken
            if (idToken == null) {
                error = "Google не вернул токен"
                return@rememberLauncherForActivityResult
            }
            launchAction({ account.loginWithGoogle(idToken) }, onLoggedIn)
        } catch (e: GoogleApiException) {
            error = "Не удалось войти через Google (${e.statusCode})"
        }
    }

    // --- Валидация на клиенте (сервер всё равно проверяет повторно) ---------------

    fun registerFormError(): String? = when {
        !NICK_REGEX.matches(nickname.trim()) -> "Ник: 3–20 символов — буквы, цифры, точка, дефис, подчёркивание"
        !email.trim().contains('@') -> "Введите корректный email"
        password.length < MIN_PASSWORD -> "Пароль должен быть не короче $MIN_PASSWORD символов"
        password != password2 -> "Пароли не совпадают"
        else -> null
    }

    fun submitLogin() {
        if (login.isBlank() || password.isEmpty()) return
        launchAction({ account.loginWithPassword(login.trim(), password) }, onLoggedIn)
    }

    fun submitRegisterForm() {
        val problem = registerFormError()
        if (problem != null) {
            error = problem
            return
        }
        launchAction(
            { account.requestRegistration(nickname.trim(), email.trim(), password) },
            { step = CodeStep.CODE }
        )
    }

    fun submitRegisterCode() {
        if (code.length != 6) return
        launchAction({ account.verifyRegistration(email.trim(), code.trim()) }, onLoggedIn)
    }

    fun requestLoginCode() {
        if (email.isBlank()) return
        launchAction({ account.requestEmailCode(email.trim()) }, { step = CodeStep.CODE })
    }

    fun submitLoginCode() {
        if (code.length != 6) return
        launchAction({ account.verifyEmailCode(email.trim(), code.trim()) }, onLoggedIn)
    }

    /** Сброс пароля: код запрашивается тем же requestLoginCode(), здесь — подтверждение и новый пароль. */
    fun submitReset() {
        if (code.length != 6) return
        val problem = when {
            password.length < MIN_PASSWORD -> "Пароль должен быть не короче $MIN_PASSWORD символов"
            password != password2 -> "Пароли не совпадают"
            else -> null
        }
        if (problem != null) {
            error = problem
            return
        }
        launchAction({ account.resetPassword(email.trim(), code.trim(), password) }, onLoggedIn)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (mode == AuthMode.REGISTER) "Регистрация"
                        else if (mode == AuthMode.RESET) "Сброс пароля"
                        else "Вход в аккаунт",
                        fontWeight = FontWeight.Bold
                    )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Аккаунт нужен только для подписки на встроенного ИИ-помощника. Можно пользоваться редактором и своим API-ключом без входа.",
                color = Color(MUTED),
                fontSize = 13.sp
            )

            if (googleEnabled && googleClient != null && step == CodeStep.FORM) {
                OutlinedButton(
                    onClick = { googleLauncher.launch(googleClient.signInIntent) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Войти через Google") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("  или  ", color = Color(MUTED), fontSize = 12.sp)
                    HorizontalDivider(Modifier.weight(1f))
                }
            }

            val passwordTransform =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
            val eyeButton: @Composable () -> Unit = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (showPassword) "Скрыть пароль" else "Показать пароль"
                    )
                }
            }

            when {
                // ---- Вход по паролю ----
                mode == AuthMode.LOGIN -> {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text("Email или ник") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = passwordTransform,
                        trailingIcon = eyeButton,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { submitLogin() },
                        enabled = !loading && login.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Войти") }
                    TextButton(
                        onClick = { switchMode(AuthMode.REGISTER) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Нет аккаунта? Зарегистрироваться") }
                    TextButton(
                        onClick = { switchMode(AuthMode.CODE_LOGIN) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Войти по коду из письма") }
                    TextButton(
                        onClick = { switchMode(AuthMode.RESET) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забыли пароль?") }
                }

                // ---- Регистрация: форма ----
                mode == AuthMode.REGISTER && step == CodeStep.FORM -> {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.filter { c -> !c.isWhitespace() }.take(20) },
                        label = { Text("Ник") },
                        supportingText = { Text("3–20 символов: буквы, цифры, . - _") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль") },
                        supportingText = { Text("Не короче $MIN_PASSWORD символов") },
                        singleLine = true,
                        visualTransformation = passwordTransform,
                        trailingIcon = eyeButton,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = password2,
                        onValueChange = { password2 = it },
                        label = { Text("Повторите пароль") },
                        singleLine = true,
                        visualTransformation = passwordTransform,
                        isError = password2.isNotEmpty() && password2 != password,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitRegisterForm() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { submitRegisterForm() },
                        enabled = !loading && nickname.isNotBlank() && email.isNotBlank() &&
                                password.isNotEmpty() && password2.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Получить код на почту") }
                    TextButton(
                        onClick = { switchMode(AuthMode.LOGIN) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Уже есть аккаунт? Войти") }
                }

                // ---- Сброс пароля: email ----
                mode == AuthMode.RESET && step == CodeStep.FORM -> {
                    Text(
                        "Введите email аккаунта — отправим код для сброса пароля.",
                        color = Color(MUTED),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { requestLoginCode() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { requestLoginCode() },
                        enabled = !loading && email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Получить код") }
                    TextButton(
                        onClick = { switchMode(AuthMode.LOGIN) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Назад ко входу") }
                }

                // ---- Сброс пароля: код и новый пароль ----
                mode == AuthMode.RESET -> {
                    Text("Код отправлен на ${email.trim()}", color = Color(MUTED), fontSize = 13.sp)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Код из письма") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Новый пароль") },
                        supportingText = { Text("Не короче $MIN_PASSWORD символов") },
                        singleLine = true,
                        visualTransformation = passwordTransform,
                        trailingIcon = eyeButton,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    OutlinedTextField(
                        value = password2,
                        onValueChange = { password2 = it },
                        label = { Text("Повторите пароль") },
                        singleLine = true,
                        visualTransformation = passwordTransform,
                        isError = password2.isNotEmpty() && password2 != password,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitReset() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { submitReset() },
                        enabled = !loading && code.length == 6 && password.isNotEmpty() && password2.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сменить пароль и войти") }
                    TextButton(
                        onClick = { step = CodeStep.FORM; code = ""; error = null },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Назад") }
                }

                // ---- Регистрация и вход по коду: ввод кода ----
                step == CodeStep.CODE -> {
                    Text("Код отправлен на ${email.trim()}", color = Color(MUTED), fontSize = 13.sp)
                    val verify = { if (mode == AuthMode.REGISTER) submitRegisterCode() else submitLoginCode() }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Код из письма") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { verify() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { verify() },
                        enabled = !loading && code.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Подтвердить") }
                    TextButton(
                        onClick = { step = CodeStep.FORM; code = ""; error = null },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Назад") }
                }

                // ---- Вход по коду: email ----
                else -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { requestLoginCode() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading
                    )
                    Button(
                        onClick = { requestLoginCode() },
                        enabled = !loading && email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Получить код") }
                    TextButton(
                        onClick = { switchMode(AuthMode.LOGIN) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Войти по паролю") }
                }
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Text(it, color = Color(0xFFF48771), fontSize = 13.sp)
            }
        }
    }
}
