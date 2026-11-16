package com.readvocab.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContextualMeaning(
    /** 词性，如 "v." */
    val pos: String = "",
    /** 该词在本句中的中文意思（简短） */
    val meaning: String = "",
    /** 为什么是这个意思、和常见义项的区别 */
    val explanation: String = "",
    /** 本句的中文翻译 */
    val sentenceTranslation: String = "",
    /** 常见搭配或同根词 */
    val collocations: List<String> = emptyList(),
)

@Serializable
data class SentenceExplanation(
    val translation: String = "",
    /** 句子主干与结构分析 */
    val structure: String = "",
    /** 值得注意的词组 / 语法点 */
    val notes: List<String> = emptyList(),
)

/**
 * Reading-tutor features built on any [AiProvider]. Prompts ask for JSON so the UI can render
 * fields; parsing is lenient because not every provider supports structured outputs.
 */
class AiTutor(private val provider: AiProvider) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun contextualMeaning(word: String, lemma: String, sentence: String, dictionary: String?): ContextualMeaning {
        val user = buildString {
            appendLine("句子：$sentence")
            appendLine("要解释的词：$word（原形：$lemma）")
            if (!dictionary.isNullOrBlank()) appendLine("词典释义（供参考，可能包含多个义项）：${dictionary.take(600)}")
            appendLine()
            appendLine("请只输出一个 JSON 对象，字段：")
            appendLine("""{"pos": "该词在本句中的词性，如 v./n./adj.", "meaning": "在本句中的中文意思，10 字以内", "explanation": "用中文简要说明为什么是这个意思，若与最常见义项不同请指出，60 字以内", "sentenceTranslation": "整句自然的中文翻译", "collocations": ["1 到 3 个相关常用搭配，英文加中文"]}""")
        }
        return parse(provider.complete(SYSTEM_WORD, user, 4000))
    }

    fun explainSentence(sentence: String, context: String? = null): SentenceExplanation {
        val user = buildString {
            if (!context.isNullOrBlank()) appendLine("上下文：${context.take(1200)}")
            appendLine("要解析的句子：$sentence")
            appendLine()
            appendLine("请只输出一个 JSON 对象，字段：")
            appendLine("""{"translation": "自然流畅的中文翻译", "structure": "用中文说明句子主干和从句/修饰结构，100 字以内", "notes": ["2 到 4 条值得学习的短语或语法点，每条一句话"]}""")
        }
        return parse(provider.complete(SYSTEM_SENTENCE, user, 4000))
    }

    /** Quick round trip used by the settings screen. */
    fun ping(): String = provider.complete("You are a test endpoint.", "Reply with the single word: OK", 2000).trim()

    private inline fun <reified T> parse(raw: String): T {
        val obj = extractJsonObject(raw) ?: throw AiException("无法解析 AI 返回的内容：${raw.take(200)}")
        return try {
            json.decodeFromString<T>(obj)
        } catch (e: Exception) {
            throw AiException("无法解析 AI 返回的内容：${raw.take(200)}", e)
        }
    }

    companion object {
        private const val SYSTEM_WORD =
            "你是面向中文母语者的英语阅读老师。学习者已有一定词汇基础，正在阅读原版材料。" +
                "根据句子语境判断单词在这里的确切含义，区分多义词，简洁准确。只输出 JSON，不要输出其他文字。"
        private const val SYSTEM_SENTENCE =
            "你是面向中文母语者的英语阅读老师。帮助学习者理解难句：给出自然的中文翻译，并讲清楚句子结构。" +
                "只输出 JSON，不要输出其他文字。"

        /** Pulls the first balanced {...} block out of a reply that may include code fences or prose. */
        fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escape = false
            for (i in start until text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
            return null
        }
    }
}
