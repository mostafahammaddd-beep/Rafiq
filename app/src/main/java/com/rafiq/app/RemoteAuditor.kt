package com.rafiq.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** تسجيل صوت خام 16kHz أحادي (PCM 16-bit) — هذا هو الصوت الفعلي الذي ينطقه المستخدم، بلا أي تخمين. */
class PcmRecorder {
    private var ar: AudioRecord? = null
    private var th: Thread? = null
    private val buf = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(
            RemoteAuditor.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        val r: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RemoteAuditor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
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

    /** يوقف التسجيل ويعيد الصوت الخام. آمن للاستدعاء حتى لو لم يبدأ التسجيل. */
    fun stop(): ByteArray {
        running = false
        try { th?.join(800) } catch (e: Exception) { }
        th = null
        try { ar?.stop() } catch (e: Exception) { }
        try { ar?.release() } catch (e: Exception) { }
        ar = null
        return synchronized(buf) { buf.toByteArray() }
    }

    fun cancel() { stop() }
}

/**
 * الفحص الدقيق: يرسل الصوت إلى خادم quran-muaalem (نموذج CTC على مستوى الفونيمات، بلا نموذج لغوي)
 * فيقارن ما نُطق فعلاً بالنطق المرجعي للآية، ويعيد أخطاء الحروف والتشكيل والتجويد.
 */
object RemoteAuditor {
    const val SAMPLE_RATE = 16000

    class WordResult(val ok: Boolean, val note: String, val heard: String)
    class AuditResult(val words: List<WordResult>, val raw: String, val error: String?)

    private fun failure(n: Int, raw: String, msg: String): AuditResult =
        AuditResult(List(n) { WordResult(false, "", "") }, raw, msg)

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
        val endpoint = "$base/correct-recitation"
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
                // نسبة الخطأ هنا تخص "البحث عن موضع الآية" فقط، وليست تسامحاً في الحكم على النطق
                field("error_ratio", "0.2")
                field("sura_idx", sura.toString())
                field("aya_idx", aya.toString())
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
                val why = if (code == 404) "لم يجد الخادم الآية في الصوت (صوت غير واضح أو أخطاء كثيرة جداً)"
                          else "ردّ الخادم برمز $code"
                return failure(nWords, raw, why)
            }
            return parse(raw, sura, aya, nWords, lenient)
        } catch (e: Exception) {
            return failure(nWords, raw, "تعذّر الاتصال بالخادم: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun wordAt(text: String, charPos: Int): Int {
        var w = 0
        val lim = minOf(charPos, text.length)
        for (k in 0 until lim) if (text[k] == ' ') w++
        return w
    }

    internal fun parse(raw: String, sura: Int, aya: Int, n: Int, lenient: Boolean): AuditResult {
        val j: JSONObject = try { JSONObject(raw) } catch (e: Exception) {
            return failure(n, raw, "ردّ غير مفهوم من الخادم")
        }
        val st = j.optJSONObject("start")
        val text = j.optString("uthmani_text", "")
        if (st == null || text.isEmpty()) return failure(n, raw, "لم يُتعرَّف على الآية في التسجيل")
        val gotSura = st.optInt("sura_idx", -1)
        val gotAya = st.optInt("aya_idx", -1)
        if (gotSura != sura || gotAya != aya) {
            return failure(n, raw, "المطابقة وقعت على موضع آخر ($gotSura:$gotAya) وليس الآية المطلوبة")
        }

        val base = maxOf(0, st.optInt("uthmani_word_idx", 0))
        val covered = text.count { it == ' ' } + 1
        val ok = BooleanArray(n) { false }
        val notes = Array(n) { StringBuilder() }
        val last = minOf(n, base + covered)
        for (w in base until last) ok[w] = true
        for (w in 0 until n) if (!ok[w]) notes[w].append("لم تُقرأ هذه الكلمة (أو لم يلتقطها الفحص).")

        val errs = j.optJSONArray("errors")
        if (errs != null) {
            for (k in 0 until errs.length()) {
                val e = errs.optJSONObject(k) ?: continue
                if (lenient && acceptableLength(e)) continue
                val pos = e.optJSONArray("uthmani_pos")
                val p0 = if (pos != null && pos.length() > 0) pos.optInt(0, 0) else 0
                val p1 = if (pos != null && pos.length() > 1) pos.optInt(1, p0) else p0
                var w0 = base + wordAt(text, p0)
                var w1 = base + wordAt(text, maxOf(p0, p1 - 1))
                if (w1 - w0 > 4) w1 = w0
                if (w0 < 0) w0 = 0
                if (w0 >= n) w0 = n - 1
                if (w1 >= n) w1 = n - 1
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

    /**
     * أطوال المدود الفرعية المعتبرة في رواية حفص (حسب خصائص المصحف في المحرك):
     * المنفصل 2–5، المتصل 4–6، العارض واللين 2–6. الخادم يقارن بطول واحد افتراضي،
     * فيُحسب صحيحٌ معتبر خطأً. هذا الاستثناء لا يشمل أي خطأ آخر.
     */
    private fun acceptableLength(e: JSONObject): Boolean {
        if (e.optString("error_type") != "tajweed") return false
        val pl = e.optInt("predicted_len", -1)
        if (pl < 0) return false
        val rules = e.optJSONArray("ref_tajweed_rules") ?: return false
        if (rules.length() == 0) return false
        for (i in 0 until rules.length()) {
            val ar = rules.optJSONObject(i)?.optJSONObject("name")?.optString("ar", "") ?: return false
            val fine = when {
                ar.contains("المنفصل") -> pl in 2..5
                ar.contains("المتصل") -> pl in 4..6
                ar.contains("العارض") || ar.contains("اللين") -> pl in 2..6
                else -> false
            }
            if (!fine) return false
        }
        return true
    }

    private fun describe(e: JSONObject): String {
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
}
