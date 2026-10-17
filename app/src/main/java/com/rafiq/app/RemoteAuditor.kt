package com.rafiq.app

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * مراقب المدود والغنن اللحظي عبر تدفق الطاقة الصوتية الأصلي (SpeechRecognizer.onRmsChanged):
 * - يعمل 100% من داخل SpeechRecognizer نفسه بدون فتح أي AudioRecord إضافي،
 *   وبذلك لا يتعارض إطلاقاً مع الميكروفون أو التوهج الذهبي المتحرك على أي هاتف أندرويد!
 * - يعاير زمن الحركة الواحدة (harakahMs) من سرعة قراءة المستخدم نفسه، ويرصد قصر المد الواجب المتصل
 *   في «شَاءَ» وترك زمن الغنة الواجب في «سِنَةٌ وَلَا» و«مَنْ ذَا» و«عِنْدَهُ» و«بِشَيْءٍ مِنْ».
 */
object RmsProsodyMonitor {
    private var lastRmsTimeMs = 0L
    private var noiseFloorDb = -2.0f
    private val wordVoicedMs = FloatArray(50)
    private val wordStepCount = IntArray(50)
    private val calibratedTwoBeatMs = ArrayList<Float>()
    private var lastTrackedWord = -1

    fun reset() {
        lastRmsTimeMs = SystemClock.elapsedRealtime()
        noiseFloorDb = -2.0f
        for (i in 0 until 50) {
            wordVoicedMs[i] = 0f
            wordStepCount[i] = 0
        }
        calibratedTwoBeatMs.clear()
        lastTrackedWord = -1
    }

    fun onRmsChanged(rmsdB: Float, currentWord: Int) {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastRmsTimeMs).coerceIn(0L, 100L).toFloat()
        lastRmsTimeMs = now

        if (rmsdB < noiseFloorDb) {
            noiseFloorDb = noiseFloorDb * 0.90f + rmsdB * 0.10f
        } else {
            noiseFloorDb = noiseFloorDb * 0.995f + rmsdB * 0.005f
        }

        if (currentWord in 0 until 50) {
            if (currentWord != lastTrackedWord) {
                val jump = if (lastTrackedWord >= 0) currentWord - lastTrackedWord else 1
                wordStepCount[currentWord] = jump
                lastTrackedWord = currentWord
            }
            if (rmsdB > noiseFloorDb + 2.8f) {
                wordVoicedMs[currentWord] += dt
            }
        }
    }

    /**
     * يُستدعى عند اكتمال الكلمة wi وانتقال القارئ إلى الكلمة التالية (wi < last).
     * لا يُطلق تنبيهاً إلا إذا كانت الكلمة قد رُصدت كخطوة منفردة غير مدمجة (wordStepCount == 1)
     * وكان زمنها المجهور المسجل أقل بكثير من الحد الأدنى المعاير لسرعة القارئ نفسه.
     */
    fun checkCompletedWordTajweedTiming(wi: Int, isWaqf: Boolean): String? {
        if (wi !in 0 until 50) return null
        val vMs = wordVoicedMs[wi]
        // إذا قفز المعرّف عدة كلمات دفعة واحدة بسبب الشبكة، لا نحكم على الزمن الفردي منعاً للأخطاء الوهمية
        if (wordStepCount[wi] != 1 || vMs < 50f) return null

        // معايرة سرعة القارئ من كلمات المد الطبيعي القصيرة (حركتان: لا، ما، وما، بما)
        if (wi in setOf(1, 7, 10, 13, 16, 27, 30, 32, 38, 44) && vMs in 120f..650f) {
            calibratedTwoBeatMs.add(vMs / 2.2f)
        }

        if (calibratedTwoBeatMs.size < 2) return null
        val sorted = calibratedTwoBeatMs.toFloatArray().also { it.sort() }
        val harakahMs = sorted[sorted.size / 2].coerceIn(70f, 160f)

        // 1. فحص المد الواجب المتصل (٤–٥ حركات) في «شَاءَ» (الكلمة 39)
        if (wi == 39 && vMs < harakahMs * 2.3f) {
            return "خطأ في التجويد (المد الواجب المتصل): قصر مد «شَاءَ» عن مقداره الواجب (٤–٥ حركات)."
        }

        // 2. فحص زمن الغنة الواجبة وصلاً في «سِنَةٌ وَلَا» (9)، «مَنْ ذَا» (19)، «عِنْدَهُ» (23)، «بِشَيْءٍ مِنْ» (34)
        if (!isWaqf && wi in setOf(9, 19, 23, 34)) {
            val minReqHarakat = when (wi) {
                19 -> 1.65f // مَنْ (حركة الميم + غنة الإخفاء حركتان)
                9  -> 2.0f  // سِنَةٌ (سِ + نَ + غنة الإدغام)
                34 -> 2.1f  // بِشَيْءٍ
                23 -> 2.3f  // عِنْدَهُ
                else -> 1.8f
            }
            if (vMs < harakahMs * minReqHarakat) {
                return "خطأ في التجويد (الغنة / الإدغام / الإخفاء): قصر زمن الغنة الواجب (حركتان) في «${WORDS[wi]}»."
            }
        }
        return null
    }
}
