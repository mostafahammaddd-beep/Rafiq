package com.rafiq.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * محرك تدقيق صوتي للأحكام الزمنية والطيفية (مدّ، غنّة، قلقلة).
 *
 * حدود صريحة (تظهر أيضاً في تقرير JSON):
 *  1) المرجع المبرمج حالياً: آية الكرسي (2:255) فقط. أي سورة/آية أخرى تُرجِع NO_REFERENCE_DATA
 *     ولا تُمرَّر بنجاح مزيَّف. جدول الأحكام يحتاج مراجعة مُقرئ/متخصص قبل الاعتماد عليه.
 *  2) لا يوجد محاذاة قسرية (forced alignment) للصوت مع الحروف، ولا نموذج صوتي للحركات والمخارج؛
 *     لذلك لا يُحكم هنا على التشكيل ولا مخارج الحروف (تُترك لمحرك التعرف + المطابقة الحرفية الحالية).
 *  3) زمن الحركة يُقدَّر من إيقاع القارئ نفسه (معايرة)، لا من قيمة مطلقة بالملي ثانية،
 *     لأن سرعة التلاوة (تحقيق/تدوير/حدر) تغيّر الزمن المطلق.
 *  4) إذا لم تتطابق الأحداث المكتشفة مع المتوقعة، النتيجة INCONCLUSIVE وليست نجاحاً.
 */
object StrictAuditEngine {

    const val SAMPLE_RATE = 16000
    private const val FRAME = 400      // 25ms
    private const val HOP = 160        // 10ms
    private const val HOP_MS = 10

    enum class RuleType { MADD_TABII, MADD_MUTTASIL, MADD_MUNFASIL, MADD_SILAH, GHUNNA }

    class Expected(
        val word: Int,          // فهرس الكلمة في WORDS
        val type: RuleType,
        val harakat: Int,       // عدد الحركات المتوقع (أدنى)
        val harakatMax: Int,    // عدد الحركات (أعلى)
        val nameAr: String
    )

    // مراجعة المختص مطلوبة. الترتيب = ترتيب الورود في التلاوة.
    private val KURSI: List<Expected> = listOf(
        Expected(1, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(7, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(10, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(15, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(19, RuleType.GHUNNA, 2, 2, "إخفاء (من ذا)"),
        Expected(21, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(23, RuleType.GHUNNA, 2, 2, "إخفاء (عندَه)"),
        Expected(23, RuleType.MADD_MUNFASIL, 4, 5, "مد منفصل"),
        Expected(29, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(33, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(34, RuleType.GHUNNA, 2, 2, "إدغام بغنة (شيءٍ من)"),
        Expected(39, RuleType.MADD_MUTTASIL, 4, 5, "مد متصل"),
        Expected(45, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(46, RuleType.MADD_TABII, 2, 2, "مد طبيعي"),
        Expected(49, RuleType.MADD_TABII, 2, 2, "مد طبيعي")
    )

    fun reference(surah: Int, ayah: Int): List<Expected>? =
        if (surah == 2 && ayah == 255) KURSI else null

    // ---------------------------------------------------------------- DSP

    class Frames(val rms: FloatArray, val nasal: FloatArray, val flux: FloatArray, val voiced: BooleanArray)

    private fun goertzel(x: FloatArray, off: Int, n: Int, freq: Float): Float {
        val k = 2f * cos(2.0 * PI * freq / SAMPLE_RATE).toFloat()
        var s1 = 0f; var s2 = 0f
        for (i in 0 until n) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat()
            val s = x[off + i] * w + k * s1 - s2
            s2 = s1; s1 = s
        }
        return sqrt(max(0f, s1 * s1 + s2 * s2 - k * s1 * s2)) / n
    }

    fun analyze(pcm: ShortArray): Frames {
        val x = FloatArray(pcm.size) { pcm[it] / 32768f }
        val n = if (x.size < FRAME) 0 else (x.size - FRAME) / HOP + 1
        val rms = FloatArray(n); val nasal = FloatArray(n); val flux = FloatArray(n)
        val low = floatArrayOf(250f, 300f, 350f)           // طاقة المرنان الأنفي التقريبية
        val mid = floatArrayOf(1000f, 1500f, 2000f, 2500f) // طاقة الصوت الفموي
        var prevMid = 0f
        for (f in 0 until n) {
            val off = f * HOP
            var e = 0f
            for (i in 0 until FRAME) e += x[off + i] * x[off + i]
            rms[f] = sqrt(e / FRAME)
            var lo = 0f; for (fr in low) lo += goertzel(x, off, FRAME, fr)
            var mi = 0f; for (fr in mid) mi += goertzel(x, off, FRAME, fr)
            nasal[f] = lo / (lo + mi + 1e-9f)               // نسبة الطاقة المنخفضة إلى الكلية
            flux[f] = max(0f, mi - prevMid)                  // قفزة في الطاقة العليا = انفجار
            prevMid = mi
        }
        val sorted = rms.sortedArray()
        val floor = if (n > 0) sorted[(n * 0.1f).toInt()] else 0f
        val thr = max(floor * 3f, 0.01f)
        return Frames(rms, nasal, flux, BooleanArray(n) { rms[it] > thr })
    }

    class Segment(val startFrame: Int, val endFrame: Int, val nasalRatio: Float) {
        val ms get() = (endFrame - startFrame) * HOP_MS
    }

    /** مقاطع صوتية مستمرة (تسامح فجوة قصيرة) */
    fun voicedSegments(fr: Frames, minMs: Int = 60): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        val n = fr.voiced.size
        while (i < n) {
            if (!fr.voiced[i]) { i++; continue }
            var j = i; var gap = 0
            while (j < n && gap < 3) { if (fr.voiced[j]) gap = 0 else gap++; j++ }
            val end = j - gap
            if ((end - i) * HOP_MS >= minMs) {
                var s = 0f; for (k in i until end) s += fr.nasal[k]
                out.add(Segment(i, end, s / (end - i)))
            }
            i = j
        }
        return out
    }

    /** وجود قفزة طاقة (قلقلة) داخل نافذة زمنية */
    fun hasBurst(fr: Frames, fromFrame: Int, toFrame: Int): Boolean {
        var mean = 0f; var c = 0
        for (k in fr.flux.indices) { mean += fr.flux[k]; c++ }
        if (c == 0) return false
        mean /= c
        for (k in max(0, fromFrame) until minOf(toFrame, fr.flux.size)) if (fr.flux[k] > mean * 4f) return true
        return false
    }

    // ---------------------------------------------------------------- Report

    private fun err(word: Int, kind: String, expected: String, actual: String, ms: Int, msg: String) =
        JSONObject().apply {
            put("word_position", word + 1)
            put("exact_word", if (word in WORDS.indices) WORDS[word] else JSONObject.NULL)
            put("error_type", kind)
            put("expected_audio_property", expected)
            put("user_audio_property", actual)
            put("timestamp_ms", ms)
            put("ruling_message", msg)
        }

    fun audit(pcm: ShortArray, surah: Int, ayah: Int): JSONObject {
        val limits = JSONArray()
            .put("لا يُحكم على التشكيل ومخارج الحروف هنا (لا يوجد نموذج صوتي/محاذاة قسرية).")
            .put("زمن الحركة يُعاير من إيقاع القارئ؛ ليس قيمة مطلقة بالملي ثانية.")
            .put("جدول الأحكام يحتاج مراجعة مُقرئ متخصص قبل الاعتماد عليه.")
            .put("هذه النتيجة أداة مساعدة للمراجعة وليست حكماً شرعياً نهائياً.")

        val ref = reference(surah, ayah) ?: return JSONObject().apply {
            put("audit_status", "NO_REFERENCE_DATA")
            put("audit_passed", false)
            put("message", "لا توجد بيانات مرجعية مبرمجة للسورة $surah الآية $ayah.")
            put("limitations", limits)
        }

        val fr = analyze(pcm)
        val segs = voicedSegments(fr)
        val madds = ref.filter { it.type != RuleType.GHUNNA }
        val ghunnas = ref.filter { it.type == RuleType.GHUNNA }

        if (segs.size < 5 || pcm.size < SAMPLE_RATE * 5) {
            return JSONObject().apply {
                put("audit_status", "INCONCLUSIVE")
                put("audit_passed", false)
                put("message", "التسجيل قصير أو هادئ جداً للتحليل.")
                put("limitations", limits)
            }
        }

        // معايرة: وحدة الحركة = وسيط مدد المقاطع القصيرة الأقصر 50% (تقريب لنبض القارئ)
        val sortedMs = segs.map { it.ms }.sorted()
        val unitMs = max(40, sortedMs[sortedMs.size / 4]) / 1   // ربع أدنى = مقاطع قصيرة تقريباً
        val harakahMs = unitMs.toFloat()

        // المقاطع الأطول من 1.6 حركة مرشَّحة لأن تكون مدوداً
        val long = segs.filter { it.ms >= harakahMs * 1.6f }
        val errors = JSONArray()
        var checked = 0
        var aligned = true

        if (long.size != madds.size) {
            aligned = false     // لا نخمّن المطابقة
        } else {
            for (k in madds.indices) {
                val e = madds[k]; val s = long[k]
                checked++
                val h = s.ms / harakahMs
                val tol = 0.5f
                if (h < e.harakat - tol || h > e.harakatMax + tol) {
                    errors.put(err(
                        e.word, "Tajweed_Duration",
                        "${e.harakat}-${e.harakatMax} حركات (≈${(e.harakat * harakahMs).toInt()}–${(e.harakatMax * harakahMs).toInt()} ms)",
                        "%.1f حركات (%d ms)".format(h, s.ms),
                        s.startFrame * HOP_MS,
                        "تنبيه: زمن ${e.nameAr} خارج النطاق المتوقع"
                    ))
                }
            }
        }

        // الغنة: لا نتحقق إلا إذا انتهت المحاذاة بنجاح، ونفحص أعلى نسبة أنفية بين المقاطع
        val nasalMean = if (segs.isNotEmpty()) segs.map { it.nasalRatio }.average().toFloat() else 0f
        val nasalHits = segs.count { it.nasalRatio > nasalMean * 1.15f && it.ms >= harakahMs * 1.4f }
        var ghunnaChecked = false
        if (aligned && nasalHits < ghunnas.size) {
            ghunnaChecked = true
            errors.put(err(
                ghunnas.first().word, "Tajweed_Ghunna",
                "${ghunnas.size} مواضع غنة بطاقة أنفية مستمرة ≈ حركتين",
                "$nasalHits مواضع فقط",
                0, "تنبيه: قد تكون الغنة ناقصة في بعض المواضع — راجع التلاوة"
            ))
        } else if (aligned) ghunnaChecked = true

        val status = when {
            !aligned -> "INCONCLUSIVE"
            errors.length() > 0 -> "ERRORS_FOUND"
            else -> "NO_ERRORS_DETECTED"
        }

        return JSONObject().apply {
            put("audit_status", status)
            put("audit_passed", status == "NO_ERRORS_DETECTED")
            put("scope", "madd_duration, ghunna_presence (heuristic)")
            put("rules_checked", checked + (if (ghunnaChecked) ghunnas.size else 0))
            put("calibrated_harakah_ms", harakahMs.toInt())
            put("detected_long_segments", long.size)
            put("expected_madd_events", madds.size)
            put("detailed_errors", errors)
            if (!aligned) put("message",
                "عدد المدود المكتشفة (${long.size}) لا يطابق المتوقع (${madds.size}); لا يمكن الحكم بثقة.")
            put("limitations", limits)
        }
    }
}
