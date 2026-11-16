package com.readvocab.app.data

import android.content.Context
import com.readvocab.core.ai.AiConfig
import com.readvocab.core.ai.ProviderType
import com.readvocab.core.vocab.HighlightMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val aiType: ProviderType = ProviderType.ANTHROPIC,
    val apiKey: String = "",
    val model: String = AiConfig.defaultModel(ProviderType.ANTHROPIC),
    val baseUrl: String = "",
    /** Fetch the AI contextual meaning automatically when a word is tapped. */
    val autoAi: Boolean = false,
    val highlightMode: HighlightMode = HighlightMode.ALL,
    /** Estimated vocabulary size from the test; 0 = not tested yet. */
    val vocabSize: Int = 0,
    /** Words at or above this frequency rank are assumed known. */
    val knownBoundary: Int = DEFAULT_BOUNDARY,
    /** Read past a highlighted new word this many times without tapping and it is marked known. 0 = off. */
    val autoKnownAfter: Int = 3,
    val fontSizeSp: Int = 18,
    /** Generate a review story automatically on app open when enough words are due. */
    val autoStory: Boolean = false,
    val storyTopic: String = STORY_TOPICS.first(),
    /** Day (epoch day) the last automatic story was generated. */
    val lastAutoStoryDay: Long = 0,
) {
    val aiConfig: AiConfig get() = AiConfig(aiType, apiKey, model, baseUrl)

    companion object {
        /** Before the test, assume a typical learner who passed CET-4. */
        const val DEFAULT_BOUNDARY = 3000
        val STORY_TOPICS = listOf("随机", "短篇故事", "新闻报道", "科普", "日常生活", "历史", "商业职场")
    }
}

/**
 * Settings in app-private SharedPreferences. The API key never leaves the device except in
 * requests to the provider the user configured.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings
    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings {
        val d = AppSettings()
        val type = runCatching { ProviderType.valueOf(prefs.getString("aiType", d.aiType.name)!!) }.getOrDefault(d.aiType)
        return AppSettings(
            aiType = type,
            apiKey = prefs.getString("apiKey", "")!!,
            model = prefs.getString("model", AiConfig.defaultModel(type))!!,
            baseUrl = prefs.getString("baseUrl", "")!!,
            autoAi = prefs.getBoolean("autoAi", d.autoAi),
            highlightMode = runCatching { HighlightMode.valueOf(prefs.getString("highlightMode", d.highlightMode.name)!!) }.getOrDefault(d.highlightMode),
            vocabSize = prefs.getInt("vocabSize", 0),
            knownBoundary = prefs.getInt("knownBoundary", d.knownBoundary),
            autoKnownAfter = prefs.getInt("autoKnownAfter", d.autoKnownAfter),
            fontSizeSp = prefs.getInt("fontSizeSp", d.fontSizeSp),
            autoStory = prefs.getBoolean("autoStory", d.autoStory),
            storyTopic = prefs.getString("storyTopic", d.storyTopic)!!,
            lastAutoStoryDay = prefs.getLong("lastAutoStoryDay", 0),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("aiType", s.aiType.name)
            .putString("apiKey", s.apiKey)
            .putString("model", s.model)
            .putString("baseUrl", s.baseUrl)
            .putBoolean("autoAi", s.autoAi)
            .putString("highlightMode", s.highlightMode.name)
            .putInt("vocabSize", s.vocabSize)
            .putInt("knownBoundary", s.knownBoundary)
            .putInt("autoKnownAfter", s.autoKnownAfter)
            .putInt("fontSizeSp", s.fontSizeSp)
            .putBoolean("autoStory", s.autoStory)
            .putString("storyTopic", s.storyTopic)
            .putLong("lastAutoStoryDay", s.lastAutoStoryDay)
            .apply()
        _settings.value = s
    }
}
