package com.readvocab.core.ai

/** Which API dialect the user's key speaks. */
enum class ProviderType(val label: String) {
    ANTHROPIC("Claude (Anthropic)"),
    OPENAI_COMPATIBLE("OpenAI 兼容 (OpenAI / DeepSeek / 通义 / Kimi 等)"),
}

data class AiConfig(
    val type: ProviderType,
    val apiKey: String,
    val model: String,
    /** Empty means the provider's default endpoint. */
    val baseUrl: String = "",
) {
    val isUsable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        fun defaultModel(type: ProviderType) = when (type) {
            ProviderType.ANTHROPIC -> "claude-opus-5-5"
            ProviderType.OPENAI_COMPATIBLE -> "gpt-4o-mini"
        }
        fun defaultBaseUrl(type: ProviderType) = when (type) {
            ProviderType.ANTHROPIC -> "https://api.anthropic.com"
            ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
        }
    }
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal chat interface every AI feature is built on. New providers (Gemini, local models…)
 * only need to implement this.
 */
interface AiProvider {
    /** Sends one system + user turn and returns the model's text. Blocking; call off the main thread. */
    fun complete(system: String, user: String, maxTokens: Int = 4000): String
}

object AiProviders {
    fun create(config: AiConfig): AiProvider = when (config.type) {
        ProviderType.ANTHROPIC -> AnthropicProvider(config)
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(config)
    }
}
