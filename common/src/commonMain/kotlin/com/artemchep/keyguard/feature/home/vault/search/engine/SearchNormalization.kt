package com.artemchep.keyguard.feature.home.vault.search.engine

import java.text.Normalizer
import java.util.Locale

private const val NON_SPACING_MARK = Character.NON_SPACING_MARK.toInt()
private const val COMBINING_SPACING_MARK = Character.COMBINING_SPACING_MARK.toInt()
private const val ENCLOSING_MARK = Character.ENCLOSING_MARK.toInt()

private val foldedCharacterReplacements =
    mapOf(
        'ß' to "ss",
        'ẞ' to "ss",
        'æ' to "ae",
        'Æ' to "ae",
        'œ' to "oe",
        'Œ' to "oe",
        'ø' to "o",
        'Ø' to "o",
        'ł' to "l",
        'Ł' to "l",
        'đ' to "d",
        'Đ' to "d",
        'ð' to "d",
        'Ð' to "d",
        'þ' to "th",
        'Þ' to "th",
        'ħ' to "h",
        'Ħ' to "h",
        'ı' to "i",
    )

internal fun SearchTokenizerProfile.hasFoldedAliases(): Boolean =
    when (this) {
        SearchTokenizerProfile.TEXT -> true

        SearchTokenizerProfile.URL,
        SearchTokenizerProfile.HOST,
        SearchTokenizerProfile.EMAIL,
        SearchTokenizerProfile.IDENTIFIER,
        SearchTokenizerProfile.SENSITIVE,
        -> false
    }

internal fun normalizeSearchValue(
    value: String,
    foldAliases: Boolean,
): SearchNormalizedValue {
    val exact =
        value
            .compatibilityNormalize()
            .lowercase(Locale.ROOT)
    val folded =
        if (foldAliases) {
            exact.foldSearchAliases()
        } else {
            exact
        }
    return SearchNormalizedValue(
        exact = exact,
        folded = folded,
    )
}

internal data class SearchNormalizedValue(
    val exact: String,
    val folded: String,
)

private fun String.compatibilityNormalize(): String =
    Normalizer.normalize(
        this,
        Normalizer.Form.NFKC,
    )

private fun String.foldSearchAliases(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFKD)
    return buildString(decomposed.length) {
        decomposed.forEach { char ->
            val replacement = foldedCharacterReplacements[char]
            if (replacement != null) {
                append(replacement)
                return@forEach
            }
            when (Character.getType(char)) {
                NON_SPACING_MARK,
                COMBINING_SPACING_MARK,
                ENCLOSING_MARK,
                -> Unit

                else -> append(char)
            }
        }
    }
}
