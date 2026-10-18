package com.rafiq.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * إعدادات الاتصال بالخادم السحابي (Modal Serverless GPU Backend - `quran-muaalem` v0.2.2)
 * بدون أي مفاتيح API من المستخدم (Zero API Keys Required).
 */
object RecitationConfig {
    /**
     * العنوان الافتراضي لخادم Modal GPU (`obadx/muaalem-model-v3_2`).
     * يمكن تعديله هنا مباشرةً أو لصق رابط `.modal.run` مرة واحدة من أيقونة (🌐 Server URL) في أعلى التطبيق.
     */
    const val BACKEND_BASE_URL: String =
"https://mostafahammad480--rafiq-quran-muaalem-backend-fastapi-asgi-app.us-east.modal.run"
    const val DEFAULT_SURA_IDX: Int = 2
    const val DEFAULT_AYA_IDX: Int = 255
    const val SAMPLE_RATE: Int = 16000

    private const val PREFS_NAME = "rafiq_muaalem_server_prefs"
    private const val KEY_SERVER_URL = "modal_backend_base_url"

    fun getServerBaseUrl(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SERVER_URL, "")?.trim() ?: ""
        return normalizeBaseUrl(if (saved.isNotEmpty()) saved else BACKEND_BASE_URL)
    }

    fun saveServerBaseUrl(ctx: Context, rawUrl: String) {
        val normalized = normalizeBaseUrl(rawUrl.ifBlank { BACKEND_BASE_URL })
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normalized)
            .apply()
    }

    fun normalizeBaseUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return BACKEND_BASE_URL
        if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
        if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        u = u.removeSuffix("/ws/live-recitation")
            .removeSuffix("/correct-recitation")
            .trimEnd('/')
        return u
    }

    fun buildWebSocketUrl(
        ctx: Context,
        suraIdx: Int = DEFAULT_SURA_IDX,
        ayaIdx: Int = DEFAULT_AYA_IDX
    ): String {
        val base = getServerBaseUrl(ctx)
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$wsBase/ws/live-recitation?sura_idx=$suraIdx&aya_idx=$ayaIdx"
    }

    fun buildCorrectRecitationUrl(ctx: Context): String {
        return "${getServerBaseUrl(ctx)}/correct-recitation"
    }

    fun buildHealthUrl(ctx: Context): String {
        return "${getServerBaseUrl(ctx)}/health"
    }

    fun isNetworkAvailable(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * عنصر خطأ مفرد ناتج عن `quran-muaalem` و `quran-transcript`:
 * - `uthmaniPos`: `[start_char, end_char]` داخل النص العثماني الكامل للآية (`uthmani_text`).
 * - `errorType`: `"tajweed" | "tashkeel" | "normal"`
 * - `speechErrorType`: `"replace" | "insert" | "delete"`
 * - `expectedPh` / `predictedPh`: التمثيل الصوتي القرآني (QPS) المتوقع والمرصود.
 * - `expectedLen` / `predictedLen`: المقدار الزمني بالحركات (للمدود والغنة والحركات).
 * - `correctionTip`: النصيحة التصحيحية بالعربية.
 */
data class MuaalemErrorItem(
    val startChar: Int,
    val endChar: Int,
    val wordIndex: Int,
    val uthmaniWord: String,
    val errorType: String,
    val speechErrorType: String,
    val expectedPh: String,
    val predictedPh: String,
    val expectedLen: Int,
    val predictedLen: Int,
    val correctionTip: String
) {
    val uthmaniPos: IntRange
        get() = startChar until endChar
}

data class LiveRecitationDiff(
    val uthmaniText: String,
    val predictedPhonemes: String,
    val referencePhonemes: String,
    val completedWords: Int,
    val uthmaniCharProgress: Int,
    val errors: List<MuaalemErrorItem>,
    val isFinal: Boolean
)

object MuaalemJsonParser {
    fun parseDiffJson(rawJson: String): LiveRecitationDiff? {
        return try {
            val obj = JSONObject(rawJson)
            if (obj.optString("type") == "error") return null

            val uthmaniText = obj.optString("uthmani_text", UTHMANI_VERSE_TEXT).ifEmpty { UTHMANI_VERSE_TEXT }
            val predictedPh = obj.optString("predicted_phonemes", "")
            val referencePh = obj.optString("reference_phonemes", "")
            val isFinal = obj.optBoolean("is_final", false)

            val errorsArr = obj.optJSONArray("errors") ?: JSONArray()
            val parsedErrors = ArrayList<MuaalemErrorItem>(errorsArr.length())

            for (i in 0 until errorsArr.length()) {
                val eObj = errorsArr.optJSONObject(i) ?: continue
                val posArr = eObj.optJSONArray("uthmani_pos")
                val rawStart = posArr?.optInt(0, 0) ?: 0
                val rawEnd = posArr?.optInt(1, rawStart + 1) ?: (rawStart + 1)
                val startChar = rawStart.coerceIn(0, uthmaniText.length)
                val endChar = rawEnd.coerceIn(startChar, uthmaniText.length)

                val reportedWordIdx = eObj.optInt("word_index", -1)
                val resolvedWordIdx = if (reportedWordIdx in WORDS.indices) {
                    reportedWordIdx
                } else {
                    UthmaniPosHighlighter.findWordIndexForCharSpan(startChar, endChar)
                }

                val uWord = eObj.optString(
                    "uthmani_word",
                    WORDS.getOrElse(resolvedWordIdx) { "" }
                )
                val errorType = eObj.optString("error_type", "normal").lowercase().trim()
                val speechErrorType = eObj.optString("speech_error_type", "replace").lowercase().trim()
                val expectedPh = eObj.optString("expected_ph", "")
                val predictedPhItem = eObj.optString("predicted_ph", eObj.optString("preditected_ph", ""))
                val expectedLen = eObj.optInt("expected_len", expectedPh.length.coerceAtLeast(1))
                val predictedLen = eObj.optInt("predicted_len", predictedPhItem.length)
                val tip = eObj.optString("correction_tip", eObj.optString("note", "")).ifBlank {
                    buildFallbackArabicTip(uWord, errorType, speechErrorType, expectedPh, predictedPhItem, expectedLen, predictedLen)
                }

                parsedErrors.add(
                    MuaalemErrorItem(
                        startChar = startChar,
                        endChar = endChar,
                        wordIndex = resolvedWordIdx,
                        uthmaniWord = uWord,
                        errorType = errorType,
                        speechErrorType = speechErrorType,
                        expectedPh = expectedPh,
                        predictedPh = predictedPhItem,
                        expectedLen = expectedLen,
                        predictedLen = predictedLen,
                        correctionTip = tip
                    )
                )
            }

            val wordsAuditArr = obj.optJSONArray("words_audit")
            val completedFromAudit = wordsAuditArr?.length() ?: 0
            val explicitCompleted = obj.optInt(
                "completed_words",
                if (completedFromAudit > 0) completedFromAudit
                else if (predictedPh.isNotEmpty() && isFinal) WORDS.size
                else estimateCompletedWordsFromPhonemes(predictedPh, parsedErrors)
            ).coerceIn(0, WORDS.size)

            val charProgress = obj.optInt(
                "uthmani_char_progress",
                if (explicitCompleted > 0) UthmaniPosHighlighter.getWordCharSpan(explicitCompleted - 1).last + 1 else 0
            ).coerceIn(0, uthmaniText.length)

            LiveRecitationDiff(
                uthmaniText = uthmaniText,
                predictedPhonemes = predictedPh,
                referencePhonemes = referencePh,
                completedWords = explicitCompleted,
                uthmaniCharProgress = charProgress,
                errors = parsedErrors,
                isFinal = isFinal
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun estimateCompletedWordsFromPhonemes(
        predictedPh: String,
        errors: List<MuaalemErrorItem>
    ): Int {
        if (predictedPh.isEmpty()) return 0
        val maxErrWord = errors.maxOfOrNull { it.wordIndex + 1 } ?: 0
        val approxByLen = ((predictedPh.length.toFloat() / 245.0f) * WORDS.size).toInt().coerceIn(1, WORDS.size)
        return maxOf(maxErrWord, approxByLen)
    }

    private fun buildFallbackArabicTip(
        word: String,
        errorType: String,
        speechErrorType: String,
        expectedPh: String,
        predictedPh: String,
        expectedLen: Int,
        predictedLen: Int
    ): String {
        return when (errorType) {
            "tashkeel" -> "خطأ في التشكيل في «$word»: المتوقع صوتياً «$expectedPh» والمرصود «$predictedPh»."
            "tajweed" -> "خطأ في التجويد أو الصفات في «$word»: المقدار المتوقع $expectedLen حركات والمرصود $predictedLen حركات (المتوقع: «$expectedPh»، المرصود: «$predictedPh»)."
            else -> when (speechErrorType) {
                "delete" -> "خطأ في الحروف في «$word»: إسقاط صوتي (المتوقع: «$expectedPh»)."
                "insert" -> "خطأ في الحروف في «$word»: زيادة حرف (المرصود: «$predictedPh»)."
                else -> "خطأ في مخرج الحرف في «$word»: نطقتَ «$predictedPh» والصواب «$expectedPh»."
            }
        }
    }
}
