package com.artemchep.keyguard.feature.home.vault.search.engine

import java.text.BreakIterator
import java.util.Locale

class DefaultSearchTokenizer(
    private val defaultConfig: SearchTokenizerConfig = SearchTokenizerConfig(),
) : SearchTokenizer {
    override fun tokenize(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig,
    ): SearchTokenization {
        val actualConfig = if (config == SearchTokenizerConfig()) defaultConfig else config
        val normalized =
            normalizeSearchValue(
                value = value,
                foldAliases = profile.hasFoldedAliases(),
            )
        val exactTokens =
            tokenizeInternal(
                value = normalized.exact,
                profile = profile,
                config = actualConfig,
            )
        val foldedTokens =
            tokenizeInternal(
                value = normalized.folded,
                profile = profile,
                config = actualConfig,
            )
        return SearchTokenization(
            normalizedText = foldedTokens.joinToString(separator = " "),
            terms = foldedTokens,
            exactNormalizedText = exactTokens.joinToString(separator = " "),
            exactTerms = exactTokens,
        )
    }

    private fun tokenizeInternal(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig,
    ): List<String> {
        val normalized = value.lowercase(Locale.ROOT)
        if (profile == SearchTokenizerProfile.TEXT && normalized.requiresWordSegmentation()) {
            return tokenizeTextWithWordSegmentation(
                value = normalized,
                config = config,
            ).takeIf { it.isNotEmpty() }
                ?: tokenizeByCharacterClass(
                    value = normalized,
                    profile = profile,
                    config = config,
                )
        }
        return tokenizeByCharacterClass(
            value = normalized,
            profile = profile,
            config = config,
        )
    }

    private fun tokenizeByCharacterClass(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig,
    ): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isEmpty()) {
                return
            }
            val token = current.toString()
            current.clear()
            pushToken(
                tokens = tokens,
                token = token,
                config = config,
            )
        }

        value.forEach { char ->
            val keep =
                when (profile) {
                    SearchTokenizerProfile.TEXT,
                    SearchTokenizerProfile.SENSITIVE,
                    SearchTokenizerProfile.HOST,
                    SearchTokenizerProfile.URL,
                    SearchTokenizerProfile.EMAIL,
                    SearchTokenizerProfile.IDENTIFIER,
                    -> char.isLetterOrDigit()
                }
            if (keep) {
                current.append(char)
            } else {
                flush()
            }
        }
        flush()
        return tokens
    }

    private fun tokenizeTextWithWordSegmentation(
        value: String,
        config: SearchTokenizerConfig,
    ): List<String> {
        val tokens = mutableListOf<String>()
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(value)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = value.substring(start, end)
            if (token.any(Char::isLetterOrDigit)) {
                pushToken(
                    tokens = tokens,
                    token = token,
                    config = config,
                )
            }
            start = end
            end = iterator.next()
        }
        return tokens
    }

    private fun pushToken(
        tokens: MutableList<String>,
        token: String,
        config: SearchTokenizerConfig,
    ) {
        if (token.length < config.minimumTokenLength) {
            return
        }
        if (config.dropStopWords && token in config.stopWords) {
            return
        }
        tokens += token
    }
}

private fun String.requiresWordSegmentation(): Boolean =
    codePoints().anyMatch { codePoint ->
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.THAI,
            -> true

            else -> false
        }
    }
