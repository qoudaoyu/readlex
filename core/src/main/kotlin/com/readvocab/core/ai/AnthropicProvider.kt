package com.readvocab.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig

/** Claude via the official Anthropic Java SDK. */
class AnthropicProvider(private val config: AiConfig) : AiProvider {

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey.trim())
            .apply { if (config.baseUrl.isNotBlank()) baseUrl(config.baseUrl.trim().trimEnd('/')) }
            .build()
    }

    override fun complete(system: String, user: String, maxTokens: Int): String {
        val model = config.model.trim()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .addUserMessage(user)
        // Word lookups are short tasks: keep thinking light on models that support effort.
        if (supportsEffort(model)) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        }
        // Let the server retry on a fallback model if a safety classifier declines the request.
        if (supportsServerFallback(model)) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            throw AiException("API Key 无效或已过期", e)
        } catch (e: RateLimitException) {
            throw AiException("请求太频繁或额度不足，请稍后再试", e)
        } catch (e: AnthropicServiceException) {
            throw AiException("Claude 返回错误 ${e.statusCode()}：${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw AiException("网络错误：${e.message}", e)
        }
        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw AiException("模型拒绝回答这条内容")
        }
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw AiException("模型没有返回内容")
        return text
    }

    companion object {
        private val EFFORT_MODELS = Regex("^claude-(opus|sonnet|haiku|fable|mythos)-(5|4-[678])")
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-fable-5-2")
        fun supportsEffort(model: String) = EFFORT_MODELS.containsMatchIn(model)
        fun supportsServerFallback(model: String) = model in FALLBACK_MODELS

        val SUGGESTED_MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
    }
}
