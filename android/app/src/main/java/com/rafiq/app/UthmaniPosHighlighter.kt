package com.rafiq.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max
import kotlin.math.min

/**
 * ألوان التمييز البصري الدقيق على مستوى الحرف والحركة والكلمة (`UthmaniPosHighlighter`):
 * - Green  (#4ADE80): حروف وصفات وتشكيل صحيح 100%
 * - Yellow (#FACC15): `error_type == "tashkeel"` (خطأ في الحركة أو التنوين)
 * - Orange (#FF922B): `error_type == "tajweed"` (خطأ في المد أو الغنة أو القلقلة أو التفخيم/الصفات)
 * - Red    (#EF4444): `error_type == "normal"` (إبدال حرف أو حذفه أو زيادته)
 */
val ColorCorrectGreen = Color(0xFF4ADE80)
val ColorTashkeelYellow = Color(0xFFFACC15)
val ColorTajweedOrange = Color(0xFFFF922B)
val ColorNormalRed = Color(0xFFEF4444)
val ColorPendingCream = Color(0xFFF5EBD0)
val ColorActiveGold = Color(0xFFD4AF37)

enum class WordVisualStatus {
    PENDING,
    CORRECT,
    TASHKEEL_ERROR,
    TAJWEED_ERROR,
    NORMAL_ERROR
}

val UTHMANI_VERSE_TEXT: String = WORDS.joinToString(" ")

/**
 * يحسب مواقع بداية ونهاية كل كلمة قرآنية داخل `UTHMANI_VERSE_TEXT`،
 * ويولّد `AnnotatedString` يلوّن الحرف والحركة الدقيقة التي يشير إليها `uthmani_pos: [start_char, end_char]`.
 */
object UthmaniPosHighlighter {

    private val WORD_CHAR_SPANS: List<IntRange> = run {
        val list = ArrayList<IntRange>(WORDS.size)
        var cursor = 0
        for (w in WORDS) {
            list.add(cursor until (cursor + w.length))
            cursor += w.length + 1 // +1 for space separator
        }
        list
    }

    fun getWordCharSpan(wordIndex: Int): IntRange {
        return WORD_CHAR_SPANS.getOrElse(wordIndex) { 0 until 0 }
    }

    fun findWordIndexForCharSpan(startChar: Int, endChar: Int): Int {
        for (i in WORD_CHAR_SPANS.indices) {
            val span = WORD_CHAR_SPANS[i]
            if (startChar <= span.last && endChar >= span.first) {
                return i
            }
        }
        return 0
    }

    fun getErrorsForWord(wordIndex: Int, allErrors: List<MuaalemErrorItem>): List<MuaalemErrorItem> {
        if (wordIndex !in WORDS.indices) return emptyList()
        val wSpan = WORD_CHAR_SPANS[wordIndex]
        return allErrors.filter { err ->
            err.wordIndex == wordIndex ||
                (err.startChar <= wSpan.last && err.endChar > wSpan.first)
        }
    }

    fun getWordVisualStatus(
        wordIndex: Int,
        completedWords: Int,
        allErrors: List<MuaalemErrorItem>
    ): WordVisualStatus {
        val wordErrs = getErrorsForWord(wordIndex, allErrors)
        if (wordErrs.isNotEmpty()) {
            // الأولوية: خطأ حروف (أحمر) ثم تشكيل (أصفر) ثم تجويد (برتقالي)
            return when {
                wordErrs.any { it.errorType == "normal" } -> WordVisualStatus.NORMAL_ERROR
                wordErrs.any { it.errorType == "tashkeel" } -> WordVisualStatus.TASHKEEL_ERROR
                else -> WordVisualStatus.TAJWEED_ERROR
            }
        }
        return if (wordIndex < completedWords) {
            WordVisualStatus.CORRECT
        } else {
            WordVisualStatus.PENDING
        }
    }

    fun colorForErrorType(errorType: String): Color {
        return when (errorType.lowercase().trim()) {
            "tashkeel" -> ColorTashkeelYellow
            "tajweed" -> ColorTajweedOrange
            else -> ColorNormalRed
        }
    }

    fun arabicBadgeForErrorType(errorType: String, speechErrorType: String): String {
        return when (errorType.lowercase().trim()) {
            "tashkeel" -> "خطأ تشكيل (حركة/إعراب)"
            "tajweed" -> "خطأ تجويد وصفات"
            else -> when (speechErrorType.lowercase().trim()) {
                "delete" -> "خطأ حروف (حذف/إسقاط)"
                "insert" -> "خطأ حروف (زيادة)"
                else -> "خطأ حروف (إبدال مخرج)"
            }
        }
    }

    private fun isCombiningMark(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x064B..0x065F) || code == 0x0670 || (code in 0x06D6..0x06ED)
    }

    /**
     * يوسّع نطاق `[localStart, localEnd]` داخل الكلمة ليشمل الحرف الأساسي مع جميع الحركات والتشكيل
     * الملتصق به حتى لا ينكسر اتصال الحروف العربية في محرك HarfBuzz.
     */
    private fun snapToGraphemeCluster(word: String, rawStart: Int, rawEnd: Int): IntRange {
        if (word.isEmpty()) return 0 until 0
        var s = rawStart.coerceIn(0, word.length - 1)
        var e = rawEnd.coerceIn(s + 1, word.length)
        while (s > 0 && isCombiningMark(word[s])) {
            s--
        }
        while (e < word.length && isCombiningMark(word[e])) {
            e++
        }
        return s until e
    }

    /**
     * يبني نصاً عثمانياً ملوّناً على مستوى الحرف والحركة (`AnnotatedString`) لكلمة `WORDS[wordIndex]`:
     * - الحروف السليمة داخل الكلمة المقروءة تُلوّن بالأخضر (#4ADE80).
     * - الحرف أو الحركة التي وقع فيها الخطأ (`uthmani_pos: [start_char, end_char]`) تُلوّن بدقة:
     *   - بالأصفر (#FACC15) إذا كان `error_type == "tashkeel"`
     *   - بالبرتقالي (#FF922B) إذا كان `error_type == "tajweed"`
     *   - بالأحمر (#EF4444) إذا كان `error_type == "normal"`
     */
    fun buildWordAnnotatedString(
        wordIndex: Int,
        completedWords: Int,
        isCurrentActive: Boolean,
        allErrors: List<MuaalemErrorItem>,
        hiddenMask: Boolean = false
    ): AnnotatedString {
        val word = WORDS.getOrElse(wordIndex) { "" }
        if (hiddenMask && wordIndex >= completedWords && !isCurrentActive) {
            return buildAnnotatedString {
                pushStyle(SpanStyle(color = ColorPendingCream.copy(alpha = 0.55f)))
                append("• • •")
                pop()
            }
        }

        val wSpan = getWordCharSpan(wordIndex)
        val wordErrs = getErrorsForWord(wordIndex, allErrors)
        val baseWordColor = when {
            wordIndex < completedWords -> ColorCorrectGreen
            isCurrentActive -> ColorActiveGold
            else -> ColorPendingCream.copy(alpha = 0.85f)
        }

        if (wordErrs.isEmpty() || word.isEmpty()) {
            return buildAnnotatedString {
                pushStyle(SpanStyle(color = baseWordColor))
                append(word)
                pop()
            }
        }

        // مصفوفة لون لكل محرف داخل الكلمة (الافتراضي أخضر للحروف السليمة في الكلمة، ويلوّن موضع الخطأ بدقة)
        val charColors = Array(word.length) { ColorCorrectGreen }
        val charBold = BooleanArray(word.length) { false }

        for (err in wordErrs) {
            val errColor = colorForErrorType(err.errorType)
            val overlapStart = max(wSpan.first, err.startChar) - wSpan.first
            val overlapEnd = min(wSpan.last + 1, err.endChar) - wSpan.first
            val clusterRange = if (overlapEnd > overlapStart) {
                snapToGraphemeCluster(word, overlapStart, overlapEnd)
            } else {
                0 until word.length
            }
            for (cIdx in clusterRange) {
                if (cIdx in charColors.indices) {
                    charColors[cIdx] = errColor
                    charBold[cIdx] = true
                }
            }
        }

        return buildAnnotatedString {
            var idx = 0
            while (idx < word.length) {
                val col = charColors[idx]
                val bold = charBold[idx]
                var next = idx + 1
                while (next < word.length && charColors[next] == col && charBold[next] == bold) {
                    next++
                }
                pushStyle(
                    SpanStyle(
                        color = col,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                    )
                )
                append(word.substring(idx, next))
                pop()
                idx = next
            }
        }
    }
}
