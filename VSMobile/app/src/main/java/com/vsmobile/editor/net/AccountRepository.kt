package com.vsmobile.editor.net

import android.content.Context
import org.json.JSONObject

/**
 * Аккаунт пользователя: токен, email/имя (кэш), собственный API-ключ нейросети.
 *
 * Важно: если задан [apiKey], приложение всегда использует его напрямую у провайдера
 * (см. AiRepository) — наш сервер в этом случае вообще не участвует, подписка не нужна.
 * Если ключа нет, используется подписка Pro/Max через /ai/chat на сервере.
 */
class AccountRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vsmobile_account", Context.MODE_PRIVATE)

    private fun readSecret(key: String): String? =
        prefs.getString(key, null)?.let { SecretStore.decrypt(it) }?.takeIf { it.isNotBlank() }

    private fun writeSecret(key: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, SecretStore.encrypt(value))
        editor.apply()
    }

    init {
        // Старые версии хранили токен и ключ открытым текстом — шифруем при первом запуске.
        for (name in listOf("token", "api_key")) {
            val stored = prefs.getString(name, null)
            if (!stored.isNullOrBlank() && !SecretStore.isEncrypted(stored)) writeSecret(name, stored.trim())
        }
    }

    var token: String?
        get() = readSecret("token")
        private set(value) { writeSecret("token", value) }

    var userEmail: String?
        get() = prefs.getString("user_email", null)
        private set(value) { prefs.edit().putString("user_email", value).apply() }

    var userName: String?
        get() = prefs.getString("user_name", null)
        private set(value) { prefs.edit().putString("user_name", value).apply() }

    var nickname: String?
        get() = prefs.getString("user_nickname", null)?.takeIf { it.isNotBlank() }
        private set(value) { prefs.edit().putString("user_nickname", value).apply() }

    /** Аватарка как data-URI (data:image/...;base64,...), как отдаёт сервер. */
    var avatar: String?
        get() = prefs.getString("user_avatar", null)?.takeIf { it.isNotBlank() }
        private set(value) { prefs.edit().putString("user_avatar", value).apply() }

    /** Имя для отображения: ник → имя → email. */
    val displayName: String?
        get() = nickname ?: userName ?: userEmail

    /** Собственный API-ключ пользователя (OpenAI/DeepSeek/др.) — хранится только на устройстве,
     *  никогда не отправляется на наш сервер. */
    var apiKey: String?
        get() = readSecret("api_key")
        set(value) { writeSecret("api_key", value?.trim()) }

    /** Базовый URL провайдера для собственного ключа (по умолчанию — OpenAI-совместимый). */
    var apiKeyBaseUrl: String
        get() = prefs.getString("api_key_base_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_OWN_KEY_BASE_URL
        set(value) { prefs.edit().putString("api_key_base_url", value.trim()).apply() }

    var apiKeyModel: String
        get() = prefs.getString("api_key_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_OWN_KEY_MODEL
        set(value) { prefs.edit().putString("api_key_model", value.trim()).apply() }

    val isLoggedIn: Boolean get() = !token.isNullOrBlank()
    val hasOwnApiKey: Boolean get() = !apiKey.isNullOrBlank()

    // --- Email login -------------------------------------------------------

    /** Запрашивает код на почту. Бросает [ApiException] при ошибке. Возвращает TTL кода в секундах. */
    fun requestEmailCode(email: String): Int {
        val res = ApiClient.post("/auth/email/request", JSONObject().put("email", email))
        return res.optInt("expires_in", 600)
    }

    fun verifyEmailCode(email: String, code: String) {
        val res = ApiClient.post(
            "/auth/email/verify",
            JSONObject().put("email", email).put("code", code)
        )
        applySession(res)
    }

    // --- Регистрация и вход по паролю ----------------------------------------------

    /** Шаг 1 регистрации: ник + почта + пароль, на почту уходит код. Возвращает TTL кода в секундах. */
    fun requestRegistration(nickname: String, email: String, password: String): Int {
        val res = ApiClient.post(
            "/auth/register/request",
            JSONObject().put("nickname", nickname).put("email", email).put("password", password)
        )
        return res.optInt("expires_in", 600)
    }

    /** Шаг 2: подтверждение кода создаёт аккаунт и входит в него. */
    fun verifyRegistration(email: String, code: String) {
        val res = ApiClient.post(
            "/auth/register/verify",
            JSONObject().put("email", email).put("code", code)
        )
        applySession(res)
    }

    /** Вход по email или нику + пароль. */
    fun loginWithPassword(login: String, password: String) {
        val res = ApiClient.post(
            "/auth/password/login",
            JSONObject().put("login", login).put("password", password)
        )
        applySession(res)
    }

    /** Забыли пароль, шаг 2: код из requestEmailCode + новый пароль; в ответ сервер сразу отдаёт сессию. */
    fun resetPassword(email: String, code: String, newPassword: String) {
        val res = ApiClient.post(
            "/auth/password/reset",
            JSONObject().put("email", email).put("code", code).put("new_password", newPassword)
        )
        applySession(res)
    }

    /** Обновляет кэш профиля с сервера. */
    fun refreshProfile() {
        val res = ApiClient.get("/me", requireToken())
        applyUser(res.optJSONObject("user"))
    }

    /** nickname/avatar = null — не менять; avatar = "" — удалить. avatar — base64 или data-URI. */
    fun updateProfile(nickname: String? = null, avatar: String? = null) {
        val body = JSONObject()
        nickname?.let { body.put("nickname", it) }
        avatar?.let { body.put("avatar", it) }
        val res = ApiClient.post("/me/profile", body, requireToken())
        applyUser(res.optJSONObject("user"))
    }

    // --- Google login --------------------------------------------------------

    fun loginWithGoogle(idToken: String) {
        val res = ApiClient.post("/auth/google", JSONObject().put("id_token", idToken))
        applySession(res)
    }

    private fun applySession(res: JSONObject) {
        token = res.optString("token", "").takeIf { it.isNotBlank() }
        applyUser(res.optJSONObject("user"))
    }

    private fun applyUser(user: JSONObject?) {
        if (user == null) return
        // В org.json optString для JSON null возвращает строку "null" — проверяем явно.
        fun str(key: String): String? =
            if (user.isNull(key)) null else user.optString(key, "").takeIf { it.isNotBlank() }
        userEmail = str("email")
        userName = str("name")
        nickname = str("nickname")
        avatar = str("avatar")
    }

    fun logout() {
        token = null
        userEmail = null
        userName = null
        nickname = null
        avatar = null
    }

    fun deleteAccount() {
        val t = token ?: return
        ApiClient.delete("/me", t)
        logout()
    }

    // --- Subscription / usage ----------------------------------------------

    fun fetchSubscription(): JSONObject = ApiClient.get("/billing/subscription", requireToken())

    fun fetchUsage(): JSONObject = ApiClient.get("/ai/usage", requireToken())

    private fun requireToken(): String = token ?: throw ApiException("not_authenticated", "Нужно войти в аккаунт", 401)

    companion object {
        const val DEFAULT_OWN_KEY_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OWN_KEY_MODEL = "gpt-4o-mini"
    }
}
