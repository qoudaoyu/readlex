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

/** A word the review story must use, with the meaning the learner met it in. */
data class StoryWord(val lemma: String, val meaning: String)

/** A multi-word expression in an article whose meaning does not follow from its words. */
@Serializable
data class ArticlePhrase(
    /** Exactly as it appears in the article (caught up with). */
    val text: String = "",
    /** Dictionary form (catch up with). */
    val phrase: String = "",
    /** Chinese meaning in this article. */
    val meaning: String = "",
    /** What kind of expression the AI says this is: phrasal_verb, idiom, fixed or term. */
    val type: String = "",
)

@Serializable
data class ArticlePhrases(val phrases: List<ArticlePhrase> = emptyList())

@Serializable
data class ReviewStory(val title: String = "", val body: String = "")

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
            appendLine("要翻译和解析的内容（一句或连续几句）：$sentence")
            appendLine()
            appendLine("请只输出一个 JSON 对象，字段：")
            appendLine("""{"translation": "自然流畅的中文翻译（多句时按原文顺序完整翻译）", "structure": "用中文说明句子主干和从句/修饰结构；多句时只分析其中最难的一两句，150 字以内", "notes": ["2 到 4 条值得学习的短语或语法点，每条一句话"]}""")
        }
        return parse(provider.complete(SYSTEM_SENTENCE, user, 4000))
    }

    /**
     * Writes a short article that naturally uses every target word, keeping all other vocabulary
     * within the learner's level, so the targets are the only hard words on the page.
     */
    fun reviewStory(words: List<StoryWord>, vocabSize: Int, topic: String): ReviewStory {
        val user = buildString {
            appendLine("读者是中文母语的英语学习者，词汇量约 ${vocabSize} 词（大致认识英语最常用的前 ${vocabSize} 个词）。")
            appendLine("请写一篇 250 到 400 词的英文短文，题材：$topic。")
            appendLine("必须自然地用到下面每一个目标词（可以用屈折形式，如复数、过去式），并且用的是括号里的意思：")
            words.forEach { appendLine("- ${it.lemma}（${it.meaning}）") }
            appendLine()
            appendLine("要求：")
            appendLine("1. 除目标词外，其他用词尽量不超过读者的词汇量，句子清楚易读。")
            appendLine("2. 目标词出现的语境要能帮读者推断或回忆词义；重要的词可以出现两次。")
            appendLine("3. 内容要有趣、连贯，像一篇真实的文章，而不是罗列单词的练习。")
            appendLine("4. 不要加粗或标注目标词，不要附词汇表或翻译。")
            appendLine("5. 段落之间用空行分隔。")
            appendLine()
            appendLine("""请只输出一个 JSON 对象：{"title": "英文标题", "body": "正文"}""")
        }
        val story = parse<ReviewStory>(provider.complete(SYSTEM_STORY, user, 8000))
        if (story.body.isBlank()) throw AiException("AI 没有返回文章内容")
        return story
    }

    /**
     * Picks the phrasal verbs, idioms, fixed expressions and terms in an article that a learner
     * who knows each word may still not understand, with their meaning in this article.
     */
    fun articlePhrases(article: String, vocabSize: Int): List<ArticlePhrase> {
        val user = buildString {
            appendLine("读者是中文母语的英语学习者，词汇量约 ${vocabSize} 词。下面是一篇英文文章。")
            appendLine("请找出文中真正的“固定表达”：读者即使认识每个单词，按字面直译也会理解错或理解不了的多词组合。只收这四类：")
            appendLine("- phrasal_verb：短语动词或动词/形容词与介词的固定搭配，如 catch up with、engage with、keen on、carry out")
            appendLine("- idiom：习语，整体意思不是字面意思，如 on their doorstep（近在身边）、stepping stone（垫脚石）、at stake")
            appendLine("- fixed：固定的介词短语或连接表达，如 in general、by and large、as a result of")
            appendLine("- term：专业术语，如 market capitalization、quantitative easing")
            appendLine("一律不要列：")
            appendLine("- 动词加宾语的普通组合（creating opportunities、getting people involved、raise money）")
            appendLine("- 分词或时态结构（having already run、has been building）")
            appendLine("- 形容词或名词修饰名词、意思一看就懂的组合（thriving habitats、local residents、wildlife corridors）")
            appendLine("- 人名、地名、机构名、项目名（The National Lottery Heritage Fund）")
            appendLine("- 意思能从单词直接推出的组合（agree with、very good、the government）")
            appendLine("判断标准：把每个词按最常见的意思直译成中文，如果读者能正确理解，就不要列。拿不准就不列，宁缺毋滥。")
            appendLine("text 只取固定的那部分，不要带上前后的普通词（写 stepping stones，不写 act as stepping stones）。最多 12 个，按在文中出现的顺序。")
            appendLine()
            appendLine("文章：")
            appendLine(article.take(12_000))
            appendLine()
            appendLine("""请只输出一个 JSON 对象：{"phrases": [{"text": "与原文完全一致的片段（保留原文的时态和单复数）", "phrase": "词典形式", "type": "phrasal_verb、idiom、fixed 或 term", "meaning": "在本文中的中文意思，12 字以内"}]}""")
        }
        return parse<ArticlePhrases>(provider.complete(SYSTEM_PHRASES, user, 6000)).phrases
            .filter { it.text.trim().contains(' ') && it.meaning.isNotBlank() }
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

        private const val SYSTEM_PHRASES =
            "你是帮助中国学生读英文报刊的老师，擅长发现学生容易误解的固定短语和术语，挑选标准严格，宁缺毋滥。只输出 JSON，不要输出其他文字。"

        private const val SYSTEM_STORY =
            "你是为英语学习者写分级读物的作者。你写的文章自然、有趣，难度严格控制在读者水平，" +
                "并且把指定的目标词放进能帮助理解的语境里。只输出 JSON，不要输出其他文字。"

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
