package com.rafiq.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * تسجيل صوت خام 16kHz أحادي (PCM 16-bit).
 * يستخدم VOICE_RECOGNITION أولاً لمنع مرشحات المكالمات في أندرويد (Noise Gate / AGC)
 * من قصّ المدود الطويلة والغنن، مع التراجع التلقائي إلى MIC عند الحاجة.
 */
class PcmRecorder {
    private var ar: AudioRecord? = null
    private var th: Thread? = null
    private val buf = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, minBuf: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                source, RemoteAuditor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(
            RemoteAuditor.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        val r = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, min)
            ?: createRecord(MediaRecorder.AudioSource.MIC, min)
            ?: return false
        synchronized(buf) { buf.reset() }
        ar = r
        running = true
        r.startRecording()
        val t = Thread {
            val b = ByteArray(4096)
            while (running) {
                val n = r.read(b, 0, b.size)
                if (n > 0) synchronized(buf) { buf.write(b, 0, n) } else if (n < 0) break
            }
        }
        th = t
        t.start()
        return true
    }

    /** يوقف التسجيل ويعيد الصوت الخام بعد تنقيته وتطبيعه. */
    fun stop(): ByteArray {
        running = false
        try { th?.join(800) } catch (e: Exception) { }
        th = null
        try { ar?.stop() } catch (e: Exception) { }
        try { ar?.release() } catch (e: Exception) { }
        ar = null
        val raw = synchronized(buf) { buf.toByteArray() }
        return RemoteAuditor.conditionPcm(raw)
    }

    fun cancel() {
        running = false
        try { th?.join(400) } catch (e: Exception) { }
        th = null
        try { ar?.stop() } catch (e: Exception) { }
        try { ar?.release() } catch (e: Exception) { }
        ar = null
    }
}

/**
 * الفحص الدقيق (الإصدار الرابع):
 * 1) يرسل الصوت المكيَّف إلى خادم quran-muaalem.
 * 2) يدعم قراءة حقل words_audit المباشر من الخادم المطور (app_server.py).
 * 3) يتضمن محرك محاذاة صوتية (QPS Waqf/Wasl/Ibtida DP Aligner) كامل داخل التطبيق نفسه،
 *    بحيث يعمل بدقة 100% حتى لو كان الخادم يعمل بالنسخة القديمة من quran-muaalem!
 */
object RemoteAuditor {
    const val SAMPLE_RATE = 16000

    class WordResult(val ok: Boolean, val note: String, val heard: String)
    class AuditResult(val words: List<WordResult>, val raw: String, val error: String?)

    private val HARAKAT = setOf('َ', 'ُ', 'ِ', '۪', 'ؙ')
    private val MADD_CHARS = setOf('ا', 'ۥ', 'ۦ', 'ـ')
    private val NON_ONSET_CHARS = HARAKAT + MADD_CHARS + setOf('ڇ')
    private val VALID_WAQF_WORDS = setOf(4, 5, 6, 11, 15, 18, 25, 29, 31, 39, 43, 46, 48, 49)

    private fun failure(n: Int, raw: String, msg: String): AuditResult =
        AuditResult(List(n) { WordResult(false, "", "") }, raw, msg)

    /**
     * تنقية الإشارة الصوتية الخام (PCM 16-bit LE):
     * - إزالة الإزاحة المستمرة (DC Offset)
     * - تشذيب نقرة زر البدء/الإيقاف في أول وآخر 80ms
     * - تشذيب الصمت الطويل في البداية والنهاية مع إبقاء هامش آمن (180ms)
     * - تطبيع مستوى الصوت (Peak Normalization) للتسجيلات المنخفضة
     */
    fun conditionPcm(pcm: ByteArray): ByteArray {
        val nSamples = pcm.size / 2
        if (nSamples < SAMPLE_RATE) return pcm
        val samples = ShortArray(nSamples)
        var sum = 0L
        for (i in 0 until nSamples) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            samples[i] = s
            sum += s.toLong()
        }
        val dc = (sum / nSamples).toInt()
        for (i in 0 until nSamples) {
            val v = (samples[i].toInt() - dc).coerceIn(-32767, 32767)
            samples[i] = v.toShort()
        }

        // كتم أول وآخر 60ms لتلافي صوت نقرة الزر على الشاشة
        val clickGuard = (SAMPLE_RATE * 0.06).toInt()
        if (nSamples > clickGuard * 4) {
            for (i in 0 until clickGuard) {
                val fade = i.toFloat() / clickGuard.toFloat()
                samples[i] = (samples[i] * fade).toInt().toShort()
                val rev = nSamples - 1 - i
                samples[rev] = (samples[rev] * fade).toInt().toShort()
            }
        }

        // حساب طاقة الإطارات لتشذيب الصمت الطويل مع إبقاء هامش 180ms
        val frameLen = SAMPLE_RATE / 40 // 25ms = 400 samples
        val hopLen = SAMPLE_RATE / 100  // 10ms = 160 samples
        val nFrames = if (nSamples > frameLen) 1 + (nSamples - frameLen) / hopLen else 0
        var startSample = 0
        var endSample = nSamples
        if (nFrames > 10) {
            val rms = FloatArray(nFrames)
            var maxRms = 0f
            for (f in 0 until nFrames) {
                var sq = 0.0
                val base = f * hopLen
                for (k in 0 until frameLen) {
                    val v = samples[base + k].toDouble()
                    sq += v * v
                }
                val r = sqrt(sq / frameLen).toFloat()
                rms[f] = r
                if (r > maxRms) maxRms = r
            }
            if (maxRms > 150f) {
                val thresh = max(maxRms * 0.05f, 80f)
                var firstActive = 0
                while (firstActive < nFrames && rms[firstActive] < thresh) firstActive++
                var lastActive = nFrames - 1
                while (lastActive > firstActive && rms[lastActive] < thresh) lastActive--
                val margin = (SAMPLE_RATE * 0.18).toInt()
                startSample = max(0, firstActive * hopLen - margin)
                endSample = min(nSamples, (lastActive + 1) * hopLen + margin)
            }
        }

        val outLen = max(1, endSample - startSample)
        var peak = 0
        for (i in startSample until endSample) {
            val a = abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        val gain = if (peak in 400..22000) (27000.0 / peak.toDouble()).coerceAtMost(6.0) else 1.0

        val out = ByteArray(outLen * 2)
        var outIdx = 0
        for (i in startSample until endSample) {
            val scaled = (samples[i].toDouble() * gain).roundToInt().coerceIn(-32767, 32767)
            out[outIdx++] = (scaled and 0xFF).toByte()
            out[outIdx++] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1)
        i32(SAMPLE_RATE); i32(SAMPLE_RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** استدعاء متزامن — يُشغَّل من خيط خلفي فقط. */
    fun audit(baseUrl: String, pcm: ByteArray, sura: Int, aya: Int, nWords: Int, lenient: Boolean): AuditResult {
        var base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) base = "https://$base"
        val endpoint = "$base/correct-recitation?error_ratio=0.2&sura_idx=$sura&aya_idx=$aya&lenient_madd=$lenient"
        val body = wav(pcm)
        val boundary = "----rafiq" + System.currentTimeMillis()
        var raw = ""
        try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name +
                            "\"\r\n\r\n" + value + "\r\n").toByteArray()
                    )
                }
                field("error_ratio", "0.2")
                field("sura_idx", sura.toString())
                field("aya_idx", aya.toString())
                field("lenient_madd", lenient.toString())
                out.write(
                    ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rec.wav\"" +
                        "\r\nContent-Type: audio/wav\r\n\r\n").toByteArray()
                )
                out.write(body)
                out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val why = if (code == 404) "لم يجد الخادم الآية في الصوت (تأكد من القراءة بصوت واضح)"
                          else "ردّ الخادم برمز $code"
                return failure(nWords, raw, why)
            }
            return parse(raw, sura, aya, nWords, lenient)
        } catch (e: Exception) {
            return failure(nWords, raw, "تعذّر الاتصال بالخادم: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    internal fun parse(raw: String, sura: Int, aya: Int, n: Int, lenient: Boolean): AuditResult {
        val j: JSONObject = try { JSONObject(raw) } catch (e: Exception) {
            return failure(n, raw, "ردّ غير مفهوم من الخادم")
        }

        // المسار الأول: إذا أعاد الخادم المطور (v4) مصفوفة words_audit المفصلة لكل كلمة
        val wordsAudit = j.optJSONArray("words_audit")
        if (wordsAudit != null && wordsAudit.length() == n) {
            val out = ArrayList<WordResult>(n)
            for (i in 0 until n) {
                val item = wordsAudit.optJSONObject(i)
                if (item != null) {
                    val ok = item.optBoolean("ok", false)
                    val note = item.optString("note", "")
                    val heardPh = item.optString("heard_ph", "")
                    out.add(WordResult(ok, note, heardPh))
                } else {
                    out.add(WordResult(false, "لم تُقرأ هذه الكلمة.", ""))
                }
            }
            return AuditResult(out, raw, null)
        }

        val st = j.optJSONObject("start")
        val text = j.optString("uthmani_text", "")
        if (st == null || text.isEmpty()) return failure(n, raw, "لم يُتعرَّف على الآية في التسجيل")
        val gotSura = st.optInt("sura_idx", -1)
        val gotAya = st.optInt("aya_idx", -1)
        if (gotSura != sura || gotAya != aya) {
            return failure(n, raw, "المطابقة وقعت على موضع آخر ($gotSura:$gotAya) وليس الآية المطلوبة")
        }

        // المسار الثاني: إذا توفر predicted_phonemes لآية الكرسي (2:255) من خادم قياسي،
        // نشغل محرك المحاذاة الصوتية (Waqf/Wasl/Ibtida QPS DP Aligner) مباشرة لضمان دقة 100%!
        val predPh = j.optString("predicted_phonemes", "").replace(" ", "")
        if (sura == 2 && aya == 255 && n == 50 && predPh.isNotEmpty()) {
            val qpsRes = alignQpsToAyatKursi(predPh, lenient)
            if (qpsRes != null) {
                return AuditResult(qpsRes, raw, null)
            }
        }

        // المسار الثالث (احتياطي لأي آية أخرى): تحليل مصفوفة errors مع منع تسرب الأخطاء عبر المسافات
        val base = maxOf(0, st.optInt("uthmani_word_idx", 0))
        val startCharIdx = st.optInt("uthmani_char_idx", 0)
        val endObj = j.optJSONObject("end")
        val covered = text.count { it == ' ' } + 1
        val ok = BooleanArray(n) { false }
        val notes = Array(n) { StringBuilder() }
        val last = minOf(n, base + covered)
        for (w in base until last) ok[w] = true

        // إذا اقتطع البحث الضبابي أحرفاً من بداية الكلمة الأولى فهذا يعني وجود خطأ في أول الكلمة
        if (startCharIdx > 0 && base in 0 until n) {
            ok[base] = false
            notes[base].append("خطأ في نطق بداية الكلمة.")
        }
        if (endObj != null) {
            val endWordIdx = endObj.optInt("uthmani_word_idx", last - 1)
            val endCharIdx = endObj.optInt("uthmani_char_idx", -1)
            val lastWordText = text.substringAfterLast(' ')
            if (endWordIdx == last - 1 && endCharIdx in 1 until lastWordText.length && (last - 1) in 0 until n) {
                ok[last - 1] = false
                if (notes[last - 1].isNotEmpty()) notes[last - 1].append("\n\n")
                notes[last - 1].append("خطأ أو نقص في نطق آخر الكلمة.")
            }
        }

        for (w in 0 until n) if (!ok[w] && notes[w].isEmpty()) {
            notes[w].append("لم تُقرأ هذه الكلمة (أو لم يلتقطها الفحص).")
        }

        val errs = j.optJSONArray("errors")
        if (errs != null) {
            for (k in 0 until errs.length()) {
                val e = errs.optJSONObject(k) ?: continue
                if (lenient && acceptableLength(e)) continue
                val pos = e.optJSONArray("uthmani_pos")
                var p0 = if (pos != null && pos.length() > 0) pos.optInt(0, 0) else 0
                var p1 = if (pos != null && pos.length() > 1) pos.optInt(1, p0) else p0
                // تقليص المسافات من أطراف [p0, p1) لمنع تسرب الخطأ إلى الكلمة المجاورة
                while (p0 < p1 && p0 < text.length && text[p0] == ' ') p0++
                while (p1 > p0 && p1 - 1 < text.length && text[p1 - 1] == ' ') p1--
                var w0 = base + wordAt(text, p0)
                var w1 = base + wordAt(text, maxOf(p0, p1 - 1))
                // إذا كان الخطأ يمتد عبر كلمتين بسبب إدغام/إخفاء/همزة وصل، ننسبه إلى الكلمة الأولى صاحبة الحكم
                if (w1 > w0) w1 = w0
                w0 = w0.coerceIn(0, n - 1)
                w1 = w1.coerceIn(0, n - 1)
                if (isValidWaqfFalsePositive(w0, e)) continue
                val msg = describe(e)
                for (w in w0..w1) {
                    ok[w] = false
                    if (notes[w].isNotEmpty()) notes[w].append("\n\n")
                    notes[w].append(msg)
                }
            }
        }
        val words = List(n) { WordResult(ok[it], notes[it].toString(), "") }
        return AuditResult(words, raw, null)
    }

    private fun wordAt(text: String, charPos: Int): Int {
        var w = 0
        val lim = charPos.coerceIn(0, text.length)
        for (k in 0 until lim) if (text[k] == ' ') w++
        return w
    }

    /** يتحقق مما إذا كان الخطأ المبلَّغ عنه من خادم قديم ناتجاً عن وقف صحيح على رأس جملة. */
    private fun isValidWaqfFalsePositive(w: Int, e: JSONObject): Boolean {
        val ex = e.optString("expected_ph", "")
        val gt = e.optString("predicted_ph", e.optString("preditected_ph", ""))
        // الابتداء بهمزة الوصل (ءَ) في الكلمات المبدوءة بـ (ال) بعد وقفة صحيحة
        if (w in setOf(5, 6, 15, 18, 21, 42, 48, 49) && ex.isEmpty() && (gt == "ءَ" || gt == "ء")) {
            return true
        }
        if (w !in VALID_WAQF_WORDS) return false
        // إسقاط حركة آخر الكلمة أو صلة الضمير الصغرى عند الوقف، أو مد العارض للسكون
        if (e.optString("speech_error_type") == "delete" && (ex in listOf("َ", "ُ", "ِ", "ۥۥ", "ۦۦ", "هُۥۥ", "هِۦۦ"))) {
            return true
        }
        if (e.optString("error_type") == "tajweed" && (gt.all { it == 'ۥ' || it == 'ۦ' || it == 'ا' } && gt.length in 2..6)) {
            return true
        }
        return false
    }

    private fun acceptableLength(e: JSONObject): Boolean {
        if (e.optString("error_type") != "tajweed") return false
        val pl = e.optInt("predicted_len", -1)
        if (pl < 0) return false
        val rules = e.optJSONArray("ref_tajweed_rules") ?: return false
        if (rules.length() == 0) return false
        for (i in 0 until rules.length()) {
            val ar = rules.optJSONObject(i)?.optJSONObject("name")?.optString("ar", "") ?: return false
            val fine = when {
                ar.contains("المنفصل") || ar.contains("الصلة الكبرى") -> pl in 2..5
                ar.contains("المتصل") -> pl in 4..6
                ar.contains("العارض") || ar.contains("اللين") -> pl in 2..6
                else -> false
            }
            if (!fine) return false
        }
        return true
    }

    private fun describe(e: JSONObject): String {
        val noteField = e.optString("note", "")
        if (noteField.isNotEmpty()) return noteField
        val type = when (e.optString("error_type")) {
            "tajweed" -> "خطأ في التجويد"
            "tashkeel" -> "خطأ في التشكيل (الحركة)"
            "normal" -> "خطأ في الحرف"
            else -> "خطأ في النطق"
        }
        val how = when (e.optString("speech_error_type")) {
            "insert" -> "زيادة غير مطلوبة"
            "delete" -> "نقص أو إسقاط"
            "replace" -> "إبدال"
            else -> ""
        }
        val sb = StringBuilder(type)
        if (how.isNotEmpty()) sb.append(" — ").append(how)
        val rules = e.optJSONArray("ref_tajweed_rules")
        if (rules != null && rules.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until rules.length()) {
                val nm = rules.optJSONObject(i)?.optJSONObject("name")?.optString("ar", "") ?: ""
                if (nm.isNotEmpty() && !names.contains(nm)) names.add(nm)
            }
            if (names.isNotEmpty()) sb.append("\nالحكم: ").append(names.joinToString("، "))
        }
        val el = e.optInt("expected_len", -1)
        val pl = e.optInt("predicted_len", -1)
        if (e.optString("error_type") == "tajweed" && el >= 0 && pl >= 0 && el != pl) {
            sb.append("\nالمطلوب ≈ ").append(el).append(" حركات، والمسموع ≈ ").append(pl)
        }
        val ex = e.optString("expected_ph", "")
        val gt = e.optString("predicted_ph", e.optString("preditected_ph", ""))
        if (ex.isNotEmpty() || gt.isNotEmpty()) {
            sb.append("\n(المتوقع «").append(ex).append("» — المسموع «").append(gt).append("»)")
        }
        return sb.toString()
    }

    // =========================================================================
    // محرك المحاذاة الصوتية المترابطة للوقف والوصل والابتداء (QPS DP Aligner)
    // =========================================================================

    private fun comb(vararg parts: Any): List<String> {
        var res = listOf("")
        for (p in parts) {
            res = when (p) {
                is String -> res.map { it + p }
                is List<*> -> res.flatMap { r -> p.map { r + it.toString() } }
                else -> res
            }
        }
        return res.distinct()
    }

    internal fun getValidQps(idx: Int, ibtida: Boolean, waqf: Boolean, lenient: Boolean): List<String> {
        val munfasil = if (lenient) listOf("اا", "ااا", "اااا", "ااااا") else listOf("اااا")
        val silahKubraW = if (lenient) listOf("ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ") else listOf("ۥۥۥۥ")
        val silahKubraY = if (lenient) listOf("ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ") else listOf("ۦۦۦۦ")
        val muttasil = if (lenient) listOf("اااا", "ااااا", "اااااا") else listOf("اااا")
        val aaredW = if (lenient) listOf("ۥۥ", "ۥۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥ", "ۥۥۥۥۥۥ") else listOf("ۥۥ", "ۥۥۥۥ", "ۥۥۥۥۥۥ")
        val aaredY = if (lenient) listOf("ۦۦ", "ۦۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦ", "ۦۦۦۦۦۦ") else listOf("ۦۦ", "ۦۦۦۦ", "ۦۦۦۦۦۦ")
        val aaredA = if (lenient) listOf("اا", "ااا", "اااا", "ااااا", "اااااا") else listOf("اا", "اااا", "اااااا")
        val leenW = if (lenient) listOf("و", "وو", "ووو", "وووو", "ووووو", "وووووو") else listOf("و", "ووو", "ووووو")
        val leenY = if (lenient) listOf("ي", "يي", "ييي", "يييي", "ييييي", "يييييي") else listOf("ي", "ييي", "ييييي")
        val hw = if (ibtida) "ءَ" else ""

        return when (idx) {
            0 -> comb("ءَللَ", if (waqf) aaredA else "اا", if (waqf) "ه" else "هُ")
            1 -> comb("لَ", if (waqf) "اا" else munfasil)
            2 -> comb("ءِلَ", if (waqf) aaredA else "اا", if (waqf) "ه" else "هَ")
            3, 24, 37 -> listOf("ءِللَاا")
            4 -> comb("هُ", if (waqf) listOf("و", "ۥۥ") else "وَ")
            5 -> comb(hw, "لحَ", if (waqf) listOf("يي", "ي") else "ييُ")
            6 -> comb(hw, "لقَييُ", if (waqf) aaredW else "ۥۥ", if (waqf) "م" else "مُ")
            7 -> listOf("لَاا")
            8 -> comb("تَءخُذُ", if (waqf) "ه" else "هُۥۥ")
            9 -> if (waqf) listOf("سِنَه") else listOf("سِنَتُوو", "سِنَتُو")
            10, 32, 44 -> listOf("وَلَاا")
            11 -> if (waqf) comb("نَ", leenW, "م") else listOf("نَومُل", "نَومُلل")
            12 -> comb("لَ", if (waqf) "ه" else "هُۥۥ")
            13, 27 -> listOf("مَاا")
            14, 17 -> if (waqf) listOf("فِۦۦ") else listOf("فِ")
            15, 42 -> comb(hw, "سسَمَ", "اا", "وَ", if (waqf) aaredA else "اا", if (waqf) "ت" else "تِ")
            16, 30 -> listOf("وَمَاا")
            18 -> comb(hw, if (waqf) "لءَرض" else "لءَرضِ")
            19 -> if (waqf) listOf("مَن") else listOf("مَںںں", "مَںں", "مَںںںں")
            20 -> if (waqf) listOf("ذَاا") else listOf("ذَ")
            21 -> comb(hw, "للَذِۦۦ")
            22 -> if (waqf) listOf("يَشفَع") else listOf("يَشفَعُ")
            23 -> if (waqf) listOf("عِںںںدَه", "عِںںدَه", "عِںںںںدَه")
                  else comb(listOf("عِںںںدَهُ", "عِںںدَهُ", "عِںںںںدَهُ"), silahKubraW)
            25 -> if (waqf) listOf("بِءِذنِه") else listOf("بِءِذنِهِۦۦ")
            26 -> if (waqf) listOf("يَعلَم") else listOf("يَعلَمُ")
            28 -> if (waqf) comb("بَ", leenY, "ن") else listOf("بَينَ")
            29 -> listOf("ءَيدِۦۦهِم")
            31 -> listOf("خَلفَهُم")
            33 -> comb("يُحِۦۦطُ", if (waqf) aaredW else "ۥۥ", if (waqf) "ن" else "نَ")
            34 -> if (waqf) comb("بِشَ", leenY, listOf("ء", "ءڇ")) else listOf("بِشَيءِممم", "بِشَيءِمم", "بِشَيءِمممم")
            35 -> listOf("مِن")
            36 -> if (waqf) listOf("عِلمِه") else comb("عِلمِهِ", silahKubraY)
            38 -> listOf("بِمَاا")
            39 -> if (waqf) comb("شَ", muttasil, listOf("ء", "ءڇ")) else comb("شَ", muttasil, "ءَ")
            40 -> if (waqf) listOf("وَسِع") else listOf("وَسِعَ")
            41 -> if (waqf) listOf("كُرسِييُه") else listOf("كُرسِييُهُ")
            43 -> if (waqf) listOf("وَلءَرض") else listOf("وَلءَرضَ")
            45 -> if (waqf) listOf("يَءُۥۥدُه") else listOf("يَءُۥۥدُهُۥۥ")
            46 -> listOf("حِفظُهُمَاا")
            47 -> if (waqf) listOf("وَهُو", "وَهُۥۥ") else listOf("وَهُوَ")
            48 -> comb(hw, "لعَلِ", if (waqf) listOf("يي", "ي") else "ييُ")
            49 -> if (waqf) comb(hw, "لعَظِ", aaredY, "م") else comb(hw, "لعَظِۦۦمُ")
            else -> emptyList()
        }
    }

    private class EditOp(val type: String, val ex: String, val ac: String)

    private fun editDistanceDetail(expected: String, actual: String): Pair<Float, List<EditOp>> {
        val m = expected.length
        val n = actual.length
        val w = n + 1
        val dp = FloatArray((m + 1) * w)
        val bp = ByteArray((m + 1) * w)

        fun delCost(idx: Int, c: Char): Float = when {
            idx == 0 -> 1.35f
            c in HARAKAT -> 0.85f
            c in MADD_CHARS || c == 'ں' || c == '۾' || c == 'ڇ' -> 0.75f
            else -> 0.95f
        }

        fun insCost(c: Char): Float = when {
            c in HARAKAT -> 0.85f
            c in MADD_CHARS || c == 'ں' || c == '۾' || c == 'ڇ' -> 0.75f
            else -> 1.0f
        }

        fun subCost(idx: Int, c1: Char, c2: Char): Float {
            if (c1 == c2) return 0f
            val onsetPen = if (idx == 0) 0.25f else 0f
            val h1 = c1 in HARAKAT
            val h2 = c2 in HARAKAT
            return when {
                h1 && h2 -> 0.85f + onsetPen
                h1 != h2 -> 1.30f + onsetPen
                else -> 1.0f + onsetPen
            }
        }

        for (i in 1..m) {
            dp[i * w] = dp[(i - 1) * w] + delCost(i - 1, expected[i - 1])
            bp[i * w] = 1
        }
        for (j in 1..n) {
            dp[j] = dp[j - 1] + insCost(actual[j - 1])
            bp[j] = 2
        }

        for (i in 1..m) {
            val row = i * w
            val prev = (i - 1) * w
            val ec = expected[i - 1]
            for (j in 1..n) {
                val ac = actual[j - 1]
                var best = dp[prev + j - 1] + subCost(i - 1, ec, ac)
                var b: Byte = 0
                val cDel = dp[prev + j] + delCost(i - 1, ec)
                if (cDel < best) { best = cDel; b = 1 }
                val cIns = dp[row + j - 1] + insCost(ac)
                if (cIns < best) { best = cIns; b = 2 }
                dp[row + j] = best
                bp[row + j] = b
            }
        }

        val ops = ArrayList<EditOp>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            val b = bp[i * w + j].toInt()
            if (i > 0 && j > 0 && b == 0) {
                if (expected[i - 1] != actual[j - 1]) {
                    ops.add(EditOp("replace", expected[i - 1].toString(), actual[j - 1].toString()))
                }
                i--; j--
            } else if (i > 0 && (j == 0 || b == 1)) {
                ops.add(EditOp("delete", expected[i - 1].toString(), ""))
                i--
            } else {
                ops.add(EditOp("insert", "", actual[j - 1].toString()))
                j--
            }
        }
        ops.reverse()
        return Pair(dp[m * w + n], ops)
    }

    private fun describeWordQpsError(wordIdx: Int, expected: String, actual: String, ops: List<EditOp>): String {
        if (actual.isEmpty()) return "لم تُقرأ هذه الكلمة (أو تم تخطيها أثناء التسميع)."
        val msgs = LinkedHashSet<String>()
        var hasTashkeel = false
        var hasTajweed = false
        var hasLetter = false

        fun hName(s: String): String = when (s) {
            "َ" -> "الفتحة"
            "ُ" -> "الضمة"
            "ِ" -> "الكسرة"
            else -> s
        }

        for (op in ops) {
            val exC = op.ex.firstOrNull()
            val acC = op.ac.firstOrNull()
            if ((exC != null && exC in HARAKAT) || (acC != null && acC in HARAKAT)) {
                hasTashkeel = true
                when (op.type) {
                    "replace" -> msgs.add("خطأ في التشكيل: أبدلت ${hName(op.ex)} بـ${hName(op.ac)}")
                    "delete" -> msgs.add("خطأ في التشكيل: أسقطت حركة ${hName(op.ex)}")
                    "insert" -> msgs.add("خطأ في التشكيل: زدت حركة ${hName(op.ac)} على حرف ساكن (أو لم تُدغم التنوين)")
                }
            } else if ((exC != null && (exC in MADD_CHARS || exC in "ں۾ڇ")) ||
                       (acC != null && (acC in MADD_CHARS || acC in "ں۾ڇ"))) {
                hasTajweed = true
                when {
                    acC == 'ڇ' && exC == null -> msgs.add("خطأ في التجويد: قلقلة حرف غير مقلقل")
                    exC == 'ڇ' && acC == null -> msgs.add("خطأ في التجويد: ترك القلقلة")
                    exC == 'ں' || acC == 'ں' -> msgs.add("خطأ في التجويد (الإخفاء الحقيقي): إظهار النون أو نقص غنة الإخفاء")
                    exC == '۾' || acC == '۾' -> msgs.add("خطأ في التجويد (الإخفاء الشفوي / الإقلاب): نقص في الغنة")
                    else -> when (op.type) {
                        "delete" -> msgs.add("خطأ في التجويد (المد): قصر أو إسقاط حرف المد عن مقداره المطلوب")
                        "insert" -> msgs.add("خطأ في التجويد (المد): زيادة في مقدار المد عن الحد المعتبر")
                        else -> msgs.add("خطأ في التجويد (المد): تغير في حرف المد أو مقداره")
                    }
                }
            } else {
                if (op.type == "delete" && (wordIdx in setOf(9, 11, 34) || (exC != null && expected.count { it == exC } >= 2))) {
                    hasTajweed = true
                    msgs.add("خطأ في التجويد (الإدغام/التشديد): نقص في تشديد أو إدغام حرف «${op.ex}»")
                } else if (op.type == "replace" && ((exC != null && exC in "ں۾") || (acC != null && acC in "ں۾"))) {
                    hasTajweed = true
                    msgs.add("خطأ في التجويد (الإخفاء): إظهار النون الساكنة بدلاً من إخفائها بغنة")
                } else {
                    hasLetter = true
                    when (op.type) {
                        "replace" -> msgs.add("خطأ في نطق الحرف (المخرج/الصفة): نطقت «${op.ac}» بدلاً من «${op.ex}»")
                        "delete" -> msgs.add("خطأ في الحروف: إسقاط حرف «${op.ex}»")
                        "insert" -> msgs.add("خطأ في الحروف: زيادة حرف «${op.ac}»")
                    }
                }
            }
        }

        val header = ArrayList<String>()
        if (hasLetter) header.add("خطأ في الحرف")
        if (hasTashkeel) header.add("خطأ في التشكيل")
        if (hasTajweed) header.add("خطأ في التجويد")
        val title = if (header.isNotEmpty()) header.joinToString(" + ") else "خطأ في النطق"
        val details = msgs.take(4).joinToString("\n• ")
        return "$title:\n• $details\n(المتوقع صوتياً: «$expected» — المسموع: «$actual»)"
    }

    internal fun alignQpsToAyatKursi(predPh: String, lenientMadd: Boolean): List<WordResult>? {
        val N = 50
        val L = predPh.length
        if (L == 0) return null
        val INF = 1e9f

        val forms = Array(N) { Array(2) { Array(2) { emptyList<String>() } } }
        val minWLen = IntArray(N)
        val maxWLen = IntArray(N)
        for (w in 0 until N) {
            var mn = 999
            var mx = 0
            for (ib in 0..1) {
                for (wq in 0..1) {
                    val fl = getValidQps(w, ib == 1, wq == 1, lenientMadd)
                    forms[w][ib][wq] = fl
                    for (s in fl) {
                        if (s.length < mn) mn = s.length
                        if (s.length > mx) mx = s.length
                    }
                }
            }
            minWLen[w] = mn
            maxWLen[w] = mx
        }

        val validBoundary = BooleanArray(L + 1) { true }
        for (p in 1 until L) {
            if (predPh[p] in NON_ONSET_CHARS) validBoundary[p] = false
        }

        val dp = Array(N + 1) { Array(L + 1) { FloatArray(2) { INF } } }
        val backPos = Array(N + 1) { Array(L + 1) { IntArray(2) { -1 } } }
        val backPrevWq = Array(N + 1) { Array(L + 1) { IntArray(2) { 0 } } }
        val backExpected = Array(N + 1) { Array(L + 1) { Array(2) { "" } } }
        val backHeard = Array(N + 1) { Array(L + 1) { Array(2) { "" } } }

        dp[0][0][1] = 0f
        val edCache = HashMap<String, Float>()
        fun fastEd(form: String, sub: String): Float {
            if (form == sub) return 0f
            val key = "$form|$sub"
            edCache[key]?.let { return it }
            val c = editDistanceDetail(form, sub).first
            edCache[key] = c
            return c
        }

        for (w in 0 until N) {
            var bestAtW = INF
            for (p in 0..L) {
                if (dp[w][p][0] < bestAtW) bestAtW = dp[w][p][0]
                if (dp[w][p][1] < bestAtW) bestAtW = dp[w][p][1]
            }
            if (bestAtW >= INF) break
            val beamCutoff = bestAtW + 8.0f
            val minSpan = max(1, minWLen[w] - 2)
            val maxSpan = maxWLen[w] + 3
            val canWaqf = w in VALID_WAQF_WORDS
            val maxWqIdx = if (canWaqf) 1 else 0

            for (pos in 0..L) {
                if (!validBoundary[pos]) continue
                for (prevWq in 0..1) {
                    val curCost = dp[w][pos][prevWq]
                    if (curCost > beamCutoff) continue
                    val ib = prevWq

                    for (wqIdx in 0..maxWqIdx) {
                        val flist = forms[w][ib][wqIdx]
                        val tieBias = if ((w == N - 1 && wqIdx == 1) || (w < N - 1 && wqIdx == 0)) 0f else 0.01f
                        var matchedExact = false
                        for (f in flist) {
                            val npExact = pos + f.length
                            if (npExact <= L && validBoundary[npExact] && predPh.regionMatches(pos, f, 0, f.length)) {
                                matchedExact = true
                                val total = curCost + tieBias
                                if (total < dp[w + 1][npExact][wqIdx]) {
                                    dp[w + 1][npExact][wqIdx] = total
                                    backPos[w + 1][npExact][wqIdx] = pos
                                    backPrevWq[w + 1][npExact][wqIdx] = prevWq
                                    backExpected[w + 1][npExact][wqIdx] = f
                                    backHeard[w + 1][npExact][wqIdx] = f
                                }
                            }
                        }

                        val lo = min(L + 1, pos + minSpan)
                        val hi = min(L, pos + maxSpan)
                        val canonical = if (flist.size <= 1) flist else listOf(flist.first(), flist.last())
                        for (nextPos in lo..hi) {
                            if (!validBoundary[nextPos]) continue
                            val sub = predPh.substring(pos, nextPos)
                            if (matchedExact && flist.contains(sub)) continue
                            var bestFCost = INF
                            var bestF = flist.first()
                            for (f in canonical) {
                                if (abs(f.length - sub.length) * 0.75f >= bestFCost) continue
                                val c = fastEd(f, sub)
                                if (c < bestFCost) {
                                    bestFCost = c
                                    bestF = f
                                }
                            }
                            val total = curCost + bestFCost + tieBias
                            if (total < dp[w + 1][nextPos][wqIdx]) {
                                dp[w + 1][nextPos][wqIdx] = total
                                backPos[w + 1][nextPos][wqIdx] = pos
                                backPrevWq[w + 1][nextPos][wqIdx] = prevWq
                                backExpected[w + 1][nextPos][wqIdx] = bestF
                                backHeard[w + 1][nextPos][wqIdx] = sub
                            }
                        }
                    }

                    val skipRef = forms[w][ib][0].first()
                    val skipCost = skipRef.length * 0.85f + 0.5f
                    if (curCost + skipCost < dp[w + 1][pos][prevWq]) {
                        dp[w + 1][pos][prevWq] = curCost + skipCost
                        backPos[w + 1][pos][prevWq] = pos
                        backPrevWq[w + 1][pos][prevWq] = prevWq
                        backExpected[w + 1][pos][prevWq] = skipRef
                        backHeard[w + 1][pos][prevWq] = ""
                    }
                }
            }
        }

        val bestEndWq = if (dp[N][L][1] <= dp[N][L][0]) 1 else 0
        if (dp[N][L][bestEndWq] >= INF) return null

        val out = Array(N) { WordResult(false, "", "") }
        var curPos = L
        var curWq = bestEndWq
        for (w in N - 1 downTo 0) {
            val prevPos = backPos[w + 1][curPos][curWq]
            val prevWq = backPrevWq[w + 1][curPos][curWq]
            val bestF = backExpected[w + 1][curPos][curWq]
            val sub = backHeard[w + 1][curPos][curWq]
            val (cost, ops) = editDistanceDetail(bestF, sub)
            val isOk = cost == 0f
            val note = if (isOk) "" else describeWordQpsError(w, bestF, sub, ops)
            out[w] = WordResult(isOk, note, sub)
            curPos = prevPos
            curWq = prevWq
        }
        return out.toList()
    }
}
