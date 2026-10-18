package com.vsmobile.editor.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

data class AiReply(
    val text: String,
    val usedOwnKey: Boolean,
    val plan: String?,
    val used: Int?,
    val limit: Int?
)

/**
 * Решает, куда отправить запрос ИИ-помощнику:
 * - если в настройках указан собственный API-ключ — напрямую провайдеру (OpenAI-совместимый
 *   endpoint /chat/completions), наш сервер вообще не видит этот запрос;
 * - иначе — на наш сервер (/ai/chat), который проверяет подписку Pro/Max и дневной лимит,
 *   и сам обращается к DeepSeek.
 */
class AiRepository(private val account: AccountRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val willUseOwnKey: Boolean get() = account.hasOwnApiKey

    fun chat(history: List<ChatMessage>, context: JSONObject?): AiReply {
        val ownKey = account.apiKey
        return if (!ownKey.isNullOrBlank()) {
            chatWithOwnKey(ownKey, history, context)
        } else {
            chatWithServer(history, context)
        }
    }

    private fun mergeContext(history: List<ChatMessage>, context: JSONObject?): List<ChatMessage> {
        if (history.isEmpty()) return history
        val block = context?.let(::buildContextBlock) ?: return history
        val last = history.last()
        return history.dropLast(1) + ChatMessage(last.role, "$block\n\n${last.content}")
    }

    private fun buildContextBlock(context: JSONObject): String? {
        val parts = mutableListOf<String>()
        val projectName = context.optString("project_name", "")
        if (projectName.isNotBlank()) parts.add("Project: $projectName")

        val tree = context.optJSONArray("file_tree")
        if (tree != null && tree.length() > 0) {
            val lines = (0 until tree.length()).joinToString("\n") { tree.optString(it) }
            parts.add("Project files:\n$lines")
        }

        val files = context.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val path = f.optString("path", "")
                val content = f.optString("content", "")
                if (path.isBlank()) continue
                parts.add("File: $path\n```\n$content\n```")
            }
        }
        if (parts.isEmpty()) return null
        return "<project_context>\n" + parts.joinToString("\n\n") + "\n</project_context>"
    }

    private fun chatWithOwnKey(apiKey: String, history: List<ChatMessage>, context: JSONObject?): AiReply {
        val merged = mergeContext(history, context)
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "You are a coding assistant inside a mobile code editor. " +
                        "Reply in the language of the user's latest message. Be concise: " +
                        "prefer short explanations and show only the changed lines, not whole files, unless asked."
                )
        )
        merged.forEach { m -> messages.put(JSONObject().put("role", m.role).put("content", m.content)) }

        val payload = JSONObject()
            .put("model", account.apiKeyModel)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_tokens", 1024)

        val url = (account.apiKeyBaseUrl.trimEnd('/') + "/chat/completions").toHttpUrlOrNull()
            ?: throw ApiException("invalid_url", "Некорректный адрес API в настройках", 0)
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMedia))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    throw ApiException("provider_error", msg ?: "Ошибка провайдера (${response.code})", response.code)
                }
                val json = JSONObject(raw)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                if (content.isNullOrBlank()) {
                    throw ApiException("provider_error", "Пустой ответ от провайдера", 502)
                }
                return AiReply(content.trim(), usedOwnKey = true, plan = null, used = null, limit = null)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network_error", "Не удалось связаться с провайдером нейросети", 0)
        }
    }

    private fun chatWithServer(history: List<ChatMessage>, context: JSONObject?): AiReply {
        if (!account.isLoggedIn) {
            throw ApiException(
                "not_authenticated",
                "Войдите в аккаунт или укажите свой API-ключ в настройках",
                401
            )
        }
        val messages = JSONArray()
        history.forEach { m -> messages.put(JSONObject().put("role", m.role).put("content", m.content)) }
        val body = JSONObject().put("messages", messages)
        if (context != null) body.put("context", context)

        val res = ApiClient.post("/ai/chat", body, account.token)
        return AiReply(
            text = res.optString("reply", ""),
            usedOwnKey = false,
            plan = if (res.has("plan")) res.optString("plan") else null,
            used = if (res.has("used")) res.optInt("used") else null,
            limit = if (res.has("limit")) res.optInt("limit") else null
        )
    }
}
