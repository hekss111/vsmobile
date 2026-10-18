package com.vsmobile.editor.net

import com.vsmobile.editor.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Ошибка от сервера VS Mobile. [code] — машиночитаемый код ("rate_limited",
 * "daily_limit_reached" и т.д.), [message] — уже готовый текст для пользователя.
 */
class ApiException(val code: String, message: String, val status: Int) : Exception(message)

/** Тонкий HTTP-клиент к server/app (см. server/README.md). Только синхронные вызовы —
 *  вызывающий код сам уходит в Dispatchers.IO. */
object ApiClient {

    var baseUrl: String = BuildConfig.SERVER_BASE_URL

    // Бесплатные хостинги (Render и т.п.) просыпаются до ~50 с — короткий таймаут давал ложное «нет соединения».
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Throws(ApiException::class)
    private fun execute(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json: JSONObject? = try {
                    if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
                } catch (_: JSONException) {
                    null
                }
                if (!response.isSuccessful) {
                    // Тело может быть не JSON (например, HTML-страница 502 от прокси) — тогда берём код статуса.
                    val body = json ?: JSONObject()
                    val code = body.optString("error", "http_${response.code}")
                    val message = localize(
                        code,
                        body.optString("message", "Сервер временно недоступен (${response.code})")
                    )
                    throw ApiException(code, message, response.code)
                }
                return json ?: throw ApiException("bad_response", "Сервер вернул неожиданный ответ", response.code)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network_error", "Нет соединения с сервером", 0)
        }
    }

    /** Серверные сообщения на английском — подменяем на русские для известных кодов. */
    private fun localize(code: String, fallback: String): String = when (code) {
        "invalid_email" -> "Введите корректный email"
        "invalid_code" -> "Неверный или просроченный код"
        "rate_limited" -> "Слишком много попыток, подождите немного и повторите"
        "invalid_token" -> "Сессия истекла, войдите заново"
        "google_auth_failed" -> "Не удалось войти через Google"
        "google_unavailable" -> "Вход через Google временно недоступен"
        "account_conflict" -> "Этот email уже привязан к другому аккаунту Google"
        "invalid_nickname" -> "Ник: 3–20 символов — буквы, цифры, точка, дефис, подчёркивание (некоторые ники зарезервированы)"
        "nickname_taken" -> "Этот ник уже занят"
        "invalid_password" -> "Пароль: от 8 до 128 символов, не должен совпадать с email или ником"
        "email_taken" -> "Этот email уже зарегистрирован — войдите"
        "invalid_credentials" -> "Неверный логин или пароль"
        "invalid_avatar" -> "Аватарка должна быть изображением JPEG, PNG или WebP небольшого размера"
        "mail_failed" -> "Не удалось отправить письмо, попробуйте позже"
        "not_configured" -> "Эта функция не настроена на сервере"
        "subscription_required" -> "Нужна подписка Pro или Max (или свой API-ключ в настройках)"
        "daily_limit_reached" -> "Дневной лимит запросов исчерпан, попробуйте завтра"
        "ai_budget_exhausted" -> "Сервис ИИ достиг дневного бюджета, попробуйте завтра"
        "ai_unavailable" -> "ИИ временно недоступен, попробуйте позже"
        "payload_too_large" -> "Слишком большой запрос"
        "not_authenticated" -> "Войдите в аккаунт"
        else -> fallback
    }

    fun get(path: String, token: String? = null): JSONObject {
        val builder = Request.Builder().url(baseUrl + path).get()
        token?.let { builder.addHeader("Authorization", "Bearer $it") }
        return execute(builder.build())
    }

    fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody(jsonMedia))
        token?.let { builder.addHeader("Authorization", "Bearer $it") }
        return execute(builder.build())
    }

    fun delete(path: String, token: String? = null): JSONObject {
        val builder = Request.Builder().url(baseUrl + path).delete()
        token?.let { builder.addHeader("Authorization", "Bearer $it") }
        return execute(builder.build())
    }
}
