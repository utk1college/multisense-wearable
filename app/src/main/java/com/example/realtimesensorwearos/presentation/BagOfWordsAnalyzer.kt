package com.example.realtimesensorwearos.presentation

import android.util.Log

/**
 * CMAI-aligned agitation categories for dementia patient monitoring.
 * Based on Cohen-Mansfield Agitation Inventory behavioral classifications.
 */
enum class AgitationCategory(val weight: Float, val cmaiItems: List<Int>) {
    HELP_DISTRESS(1.0f, listOf(22, 27)),           // Screaming, calling for help
    PAIN_DISCOMFORT(0.9f, listOf(22)),              // Pain expressions
    ANXIETY_DISTRESS(0.85f, listOf(24)),            // Verbal agitation
    VERBAL_AGGRESSION(0.8f, listOf(24)),            // Cursing, verbal hostility
    REPETITIVE_VOCALIZATION(0.6f, listOf(25)),      // Constant vocalization
    CALLING_FOR_HELP(0.85f, listOf(27)),            // Calling names
    NEGATIVE_STATES(0.75f, listOf(26))              // Negativism, refusal
}

/**
 * Represents a detected keyword match.
 */
data class KeywordMatch(
    val keyword: String,
    val category: AgitationCategory,
    val confidence: Float,
    val isExactMatch: Boolean
)

/**
 * Result of bag-of-words analysis on transcribed text.
 */
data class KeywordAnalysisResult(
    val detectedCategories: Map<AgitationCategory, Float>,
    val detectedKeywords: List<KeywordMatch>,
    val topCategory: AgitationCategory?,
    val speechDetectionScore: Float,
    val hasRepetition: Boolean,
    val repetitionDetails: String?
) {
    companion object {
        val EMPTY = KeywordAnalysisResult(
            detectedCategories = emptyMap(),
            detectedKeywords = emptyList(),
            topCategory = null,
            speechDetectionScore = 0f,
            hasRepetition = false,
            repetitionDetails = null
        )
    }
}

/**
 * Bag of Words Analyzer for CMAI-based agitation detection.
 *
 * Analyzes transcribed speech for keywords and phrases associated with
 * dementia patient agitation behaviors according to CMAI classifications.
 */
class BagOfWordsAnalyzer {

    companion object {
        private const val TAG = "BOW_ANALYZER"
        private const val REPETITION_THRESHOLD = 3  // Word repeated 3+ times
    }

    /**
     * Keyword dictionaries for each agitation category.
     * Organized as phrases (multi-word) and single words for different matching.
     */
    private val categoryKeywords: Map<AgitationCategory, CategoryKeywords> = mapOf(
        AgitationCategory.HELP_DISTRESS to CategoryKeywords(
            phrases = listOf(
                "help me", "help me please", "somebody help", "call someone",
                "please help", "i need help", "someone help", "help help",
                "please come", "come here", "come help", "need help"
            ),
            words = listOf("help", "please", "somebody", "anyone", "emergency")
        ),

        AgitationCategory.PAIN_DISCOMFORT to CategoryKeywords(
            phrases = listOf(
                "it hurts", "it's painful", "i'm in pain", "hurts so much",
                "so painful", "stop hurting", "that hurts"
            ),
            words = listOf("pain", "hurt", "hurts", "ouch", "ache", "sore", "aching", "painful")
        ),

        AgitationCategory.ANXIETY_DISTRESS to CategoryKeywords(
            phrases = listOf(
                "i'm scared", "i'm afraid", "don't know", "where am i",
                "i'm lost", "i'm confused", "what's happening", "i'm worried"
            ),
            words = listOf("scared", "afraid", "worried", "anxious", "nervous", "confused", "lost", "terrified", "frightened")
        ),

        AgitationCategory.VERBAL_AGGRESSION to CategoryKeywords(
            phrases = listOf(
                "shut up", "leave me alone", "go away", "get out",
                "don't touch me", "get off", "stay away", "fuck you",
                "get the fuck", "leave me the fuck alone"
            ),
            words = listOf(
                "stupid", "idiot", "hate", "angry", "mad", "fuck", "fucking",
                "shit", "bitch", "bastard", "asshole", "damn"
            )
        ),

        AgitationCategory.CALLING_FOR_HELP to CategoryKeywords(
            phrases = listOf(
                "where is", "call the", "get the", "find the",
                "where are you", "come here", "someone come"
            ),
            words = listOf(
                "nurse", "doctor", "caregiver", "mom", "mum", "mother",
                "dad", "father", "son", "daughter", "brother", "sister"
            )
        ),

        AgitationCategory.NEGATIVE_STATES to CategoryKeywords(
            phrases = listOf(
                "don't want", "don't like", "i won't", "no way",
                "leave me", "i refuse", "not doing", "stop it",
                "dont want", "leave me be"
            ),
            words = listOf("no", "stop", "never", "can't", "won't", "refuse")
        )
    )

    // Family names can be added dynamically for personalization
    private val customFamilyNames = mutableListOf<String>()

    /**
     * Analyze transcription text for agitation-related keywords.
     *
     * @param text Transcribed speech text
     * @return KeywordAnalysisResult containing detected categories and keywords
     */
    fun analyzeTranscription(text: String): KeywordAnalysisResult {
        if (text.isBlank()) {
            return KeywordAnalysisResult.EMPTY
        }

        val normalizedText = text.lowercase().trim()
        val words = normalizedText.split(Regex("\\s+"))

        Log.d(TAG, "Analyzing: '$normalizedText'")

        val detectedKeywords = mutableListOf<KeywordMatch>()
        val categoryScores = mutableMapOf<AgitationCategory, Float>()

        // Check each category
        for ((category, keywords) in categoryKeywords) {
            val matches = findMatches(normalizedText, words, keywords, category)
            detectedKeywords.addAll(matches)

            if (matches.isNotEmpty()) {
                // Calculate category score as max confidence of matches
                val maxConfidence = matches.maxOfOrNull { it.confidence } ?: 0f
                categoryScores[category] = maxConfidence
            }
        }

        // Check for repetitive vocalization (any word repeated 3+ times)
        val (hasRepetition, repetitionDetails) = checkRepetition(words)
        if (hasRepetition) {
            categoryScores[AgitationCategory.REPETITIVE_VOCALIZATION] =
                categoryScores.getOrDefault(AgitationCategory.REPETITIVE_VOCALIZATION, 0f)
                    .coerceAtLeast(0.7f)
        }

        // Check for custom family names
        checkFamilyNames(normalizedText, detectedKeywords, categoryScores)

        // Calculate overall speech detection score
        val speechDetectionScore = if (categoryScores.isNotEmpty()) {
            val weightedSum = categoryScores.entries.sumOf { (cat, conf) ->
                (cat.weight * conf).toDouble()
            }
            val totalWeight = categoryScores.keys.sumOf { it.weight.toDouble() }
            (weightedSum / totalWeight).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        // Find top category
        val topCategory = categoryScores.maxByOrNull { it.value }?.key

        val result = KeywordAnalysisResult(
            detectedCategories = categoryScores.toMap(),
            detectedKeywords = detectedKeywords.sortedByDescending { it.confidence },
            topCategory = topCategory,
            speechDetectionScore = speechDetectionScore,
            hasRepetition = hasRepetition,
            repetitionDetails = repetitionDetails
        )

        Log.d(TAG, "Analysis result: ${categoryScores.size} categories, " +
                "score=${"%.2f".format(speechDetectionScore)}, top=$topCategory")

        return result
    }

    /**
     * Find keyword matches in the text for a specific category.
     */
    private fun findMatches(
        text: String,
        words: List<String>,
        keywords: CategoryKeywords,
        category: AgitationCategory
    ): List<KeywordMatch> {
        val matches = mutableListOf<KeywordMatch>()

        // Check phrases first (higher confidence for exact phrase matches)
        for (phrase in keywords.phrases) {
            if (text.contains(phrase)) {
                matches.add(
                    KeywordMatch(
                        keyword = phrase,
                        category = category,
                        confidence = 0.95f,  // High confidence for phrase match
                        isExactMatch = true
                    )
                )
            }
        }

        // Check individual words
        for (keyword in keywords.words) {
            if (words.contains(keyword)) {
                // Check if already matched in a phrase
                val alreadyMatched = matches.any { it.keyword.contains(keyword) }
                if (!alreadyMatched) {
                    // Calculate confidence based on word frequency and position
                    val occurrences = words.count { it == keyword }
                    val confidence = calculateWordConfidence(keyword, occurrences, words)

                    matches.add(
                        KeywordMatch(
                            keyword = keyword,
                            category = category,
                            confidence = confidence,
                            isExactMatch = false
                        )
                    )
                }
            }
        }

        return matches
    }

    /**
     * Calculate confidence score for a word match.
     */
    private fun calculateWordConfidence(
        keyword: String,
        occurrences: Int,
        allWords: List<String>
    ): Float {
        // Base confidence for single word match
        var confidence = 0.75f

        // Boost for repeated occurrences
        if (occurrences > 1) {
            confidence += 0.05f * (occurrences - 1).coerceAtMost(3)
        }

        // Boost for emphasized words (at beginning or end)
        if (allWords.firstOrNull() == keyword || allWords.lastOrNull() == keyword) {
            confidence += 0.05f
        }

        // Boost for short utterances (more focused)
        if (allWords.size <= 5) {
            confidence += 0.05f
        }

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Check for repetitive vocalization patterns.
     * Returns true if any word is repeated 3+ times consecutively.
     */
    private fun checkRepetition(words: List<String>): Pair<Boolean, String?> {
        if (words.size < REPETITION_THRESHOLD) return Pair(false, null)

        // Check for consecutive repetition (e.g., "no no no no")
        var consecutiveCount = 1
        var lastWord = ""
        var repeatedWord = ""

        for (word in words) {
            if (word == lastWord) {
                consecutiveCount++
                if (consecutiveCount >= REPETITION_THRESHOLD) {
                    repeatedWord = word
                }
            } else {
                consecutiveCount = 1
            }
            lastWord = word
        }

        if (repeatedWord.isNotEmpty()) {
            return Pair(true, "'$repeatedWord' repeated ${consecutiveCount}x")
        }

        // Check for overall word frequency
        val wordCounts = words.groupingBy { it }.eachCount()
        val highFreqWord = wordCounts.entries.firstOrNull {
            it.value >= REPETITION_THRESHOLD && it.key.length > 1
        }

        if (highFreqWord != null) {
            return Pair(true, "'${highFreqWord.key}' appears ${highFreqWord.value}x")
        }

        return Pair(false, null)
    }

    /**
     * Check for custom family names in the text.
     */
    private fun checkFamilyNames(
        text: String,
        matches: MutableList<KeywordMatch>,
        scores: MutableMap<AgitationCategory, Float>
    ) {
        for (name in customFamilyNames) {
            if (text.contains(name.lowercase())) {
                matches.add(
                    KeywordMatch(
                        keyword = name,
                        category = AgitationCategory.CALLING_FOR_HELP,
                        confidence = 0.90f,
                        isExactMatch = true
                    )
                )
                scores[AgitationCategory.CALLING_FOR_HELP] =
                    scores.getOrDefault(AgitationCategory.CALLING_FOR_HELP, 0f)
                        .coerceAtLeast(0.90f)
            }
        }
    }

    /**
     * Add custom family names for personalized detection.
     */
    fun addFamilyNames(names: List<String>) {
        customFamilyNames.addAll(names.map { it.lowercase() })
        Log.d(TAG, "Added ${names.size} family names for detection")
    }

    /**
     * Clear custom family names.
     */
    fun clearFamilyNames() {
        customFamilyNames.clear()
    }
}

/**
 * Container for category keywords (phrases and single words).
 */
private data class CategoryKeywords(
    val phrases: List<String>,
    val words: List<String>
)
