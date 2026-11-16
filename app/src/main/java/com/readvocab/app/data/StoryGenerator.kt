package com.readvocab.app.data

import com.readvocab.app.data.AppSettings.Companion.STORY_TOPICS
import com.readvocab.core.ai.AiException
import com.readvocab.core.ai.AiTutor
import com.readvocab.core.ai.StoryWord
import com.readvocab.core.text.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class StoryResult(val bookId: Long, val title: String, val words: List<String>, val missing: List<String>)

/**
 * Turns the words on the word list into a short AI-written article. The article is saved as a
 * book, so reading it reviews the words through the normal in-reading review.
 */
class StoryGenerator(
    private val words: WordRepository,
    private val books: BookRepository,
    private val dictionary: DictionaryRepository,
    private val settings: SettingsStore,
    private val tutor: () -> AiTutor?,
) {
    private val mutex = Mutex()

    suspend fun generate(topic: String = settings.current.storyTopic): StoryResult = mutex.withLock {
        val ai = tutor() ?: throw AiException("请先在「设置」里填写 AI 的 API Key")
        dictionary.ensureLoaded()
        val picked = words.soonestDue(MAX_WORDS)
        if (picked.size < MIN_WORDS) throw AiException("生词表里至少要有 $MIN_WORDS 个学习中的词才能生成复习短文（现在 ${picked.size} 个）")
        val targets = picked.map { w ->
            val meaning = words.encounters(w.lemma).firstNotNullOfOrNull { it.aiMeaning }
                ?: dictionary.lookup(w.lemma)?.brief?.take(40).orEmpty()
            StoryWord(w.lemma, meaning)
        }
        val s = settings.current
        val vocab = if (s.vocabSize > 0) s.vocabSize else s.knownBoundary
        val chosenTopic = if (topic == STORY_TOPICS.first()) STORY_TOPICS.drop(1).random() else topic
        val story = withContext(Dispatchers.IO) { ai.reviewStory(targets, vocab, chosenTopic) }

        val used = Tokenizer.tokenize(story.body).map { dictionary.lemma(it.text) }.toSet()
        val missing = targets.map { it.lemma }.filter { it !in used }
        val title = story.title.ifBlank { "Review Story" }
        val id = books.importText("复习：$title", "$title\n\n${story.body.trim()}", author = "AI 复习短文 · ${targets.size} 个目标词")
        StoryResult(id, title, targets.map { it.lemma }, missing)
    }

    /** Called when the app opens: one automatic story per day once enough words are due. */
    suspend fun maybeAutoGenerate(): StoryResult? {
        val s = settings.current
        val today = LocalDate.now().toEpochDay()
        if (!s.autoStory || s.lastAutoStoryDay == today || tutor() == null) return null
        if (words.countDue() < AUTO_THRESHOLD) return null
        settings.update { it.copy(lastAutoStoryDay = today) }
        return runCatching { generate() }.getOrNull()
    }

    companion object {
        const val MIN_WORDS = 3
        const val MAX_WORDS = 12
        const val AUTO_THRESHOLD = 8
    }
}
