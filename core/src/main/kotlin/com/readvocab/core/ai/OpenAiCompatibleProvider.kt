package com.readvocab.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Any endpoint that speaks the OpenAI Chat Completions format (OpenAI, DeepSeek, Qwen, Kimi, local servers…). */
class OpenAiCompatibleProvider(private val config: AiConfig) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }

    fun buildRequestBody(system: String, user: String, maxTokens: Int): String = buildJsonObject {
        put("model", config.model.trim())
        put("max_tokens", maxTokens)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", user) }
        }
    }.toString()

    fun parseResponse(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"] as? JsonArray ?: throw AiException("响应格式不正确：缺少 choices")
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiException("响应格式不正确：缺少 message")
        val content = message["content"]?.jsonPrimitive?.content.orEmpty()
        if (content.isBlank()) throw AiException("模型没有返回内容")
        return content
    }

    override fun complete(system: String, user: String, maxTokens: Int): String {
        val base = config.baseUrl.ifBlank { AiConfig.defaultBaseUrl(ProviderType.OPENAI_COMPATIBLE) }.trim().trimEnd('/')
        val url = URL("$base/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        try {
            conn.outputStream.use { it.write(buildRequestBody(system, user, maxTokens).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 -> throw AiException("API Key 无效或没有权限 ($code)")
                code == 429 -> throw AiException("请求太频繁或额度不足 (429)")
                code !in 200..299 -> throw AiException("接口返回错误 $code：${body.take(300)}")
            }
            return parseResponse(body)
        } catch (e: IOException) {
            throw AiException("网络错误：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
