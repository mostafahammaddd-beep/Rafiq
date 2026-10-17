package com.rafiq.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

enum class Phase { SETUP, GUIDED, RECITE, RESULT }
enum class WS { PENDING, OK, WRONG, HINT }

// آية الكرسي كاملة (البقرة 255) — 50 كلمة
val WORDS: List<String> = listOf(
    "اللَّهُ", "لَا", "إِلَٰهَ", "إِلَّا", "هُوَ", "الْحَيُّ", "الْقَيُّومُ",
    "لَا", "تَأْخُذُهُ", "سِنَةٌ", "وَلَا", "نَوْمٌ",
    "لَهُ", "مَا", "فِي", "السَّمَاوَاتِ", "وَمَا", "فِي", "الْأَرْضِ",
    "مَنْ", "ذَا", "الَّذِي", "يَشْفَعُ", "عِنْدَهُ", "إِلَّا", "بِإِذْنِهِ",
    "يَعْلَمُ", "مَا", "بَيْنَ", "أَيْدِيهِمْ", "وَمَا", "خَلْفَهُمْ",
    "وَلَا", "يُحِيطُونَ", "بِشَيْءٍ", "مِنْ", "عِلْمِهِ", "إِلَّا", "بِمَا", "شَاءَ",
    "وَسِعَ", "كُرْسِيُّهُ", "السَّمَاوَاتِ", "وَالْأَرْضَ",
    "وَلَا", "يَئُودُهُ", "حِفْظُهُمَا", "وَهُوَ", "الْعَلِيُّ", "الْعَظِيمُ"
)

// الشيخ المنشاوي - آية الكرسي (2:255)
const val AUDIO_URL = "https://everyayah.com/data/Minshawy_Murattal_128kbps/002255.mp3"

// في القراءة الموجَّهة: أقصى عدد كلمات يُسمح بها خطأ تعرُّف الصوت قبل رفض القراءة
const val GUIDED_TOLERANCE = 2

val TIPS: Map<Int, String> = mapOf(
    0 to "تفخيم اللام في لفظ الجلالة، مع شدّ اللام وضمّ الهاء.",
    1 to "مدّ طبيعي (حركتان) في «لا».",
    2 to "الألف الخنجرية مدّ طبيعي، وفتح الهاء.",
    3 to "شدّ اللام في «إلّا».",
    4 to "ضمّ الهاء وفتح الواو دون مدّ.",
    5 to "شدّ الياء مع ضمّها.",
    6 to "شدّ الياء، ثم مدّ الواو مدّاً طبيعياً، وضمّ الميم.",
    8 to "همزة ساكنة، وضمّ الذال، وصلة الهاء بمدّ حركتين.",
    9 to "كسر السين وتنوين الضمّ.",
    11 to "الواو لينة بعد الفتحة، دون مدّ.",
    12 to "صلة هاء الضمير بمدّ حركتين.",
    15 to "شدّ السين، ومدّ الألفين مدّاً طبيعياً.",
    21 to "شدّ الذال، ومدّ الياء مدّاً طبيعياً.",
    22 to "سكون الشين، وفتح الفاء، وضمّ العين.",
    23 to "صلة الهاء مع مدّ منفصل قبل همزة «إلّا».",
    25 to "كسر الهاء وصلتها بالياء.",
    29 to "مدّ الياء بعد الدال، وكسر الهاء، وضمّ الميم.",
    33 to "مدّ الياء ومدّ الواو (طبيعيان).",
    34 to "نطق الهمزة المكسورة بعد الياء مع تنوين الكسر.",
    39 to "مدّ متصل (٤–٥ حركات) لوجود الهمزة بعد الألف.",
    41 to "شدّ الياء مع ضمّها.",
    45 to "نطق الهمزة المضمومة ثم مدّ الواو مدّاً طبيعياً.",
    46 to "سكون الفاء، وضمّ الظاء، ومدّ الألف في «هما».",
    48 to "شدّ الياء مع ضمّها.",
    49 to "تفخيم الظاء، ومدّ الياء مدّاً طبيعياً."
)

// توحيد الكتابة: حذف التشكيل والمدّ، توحيد أشكال الألف والياء والتاء المربوطة، وحذف الهمزات المنفردة
fun norm(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        val c = ch.code
        if (c in 0x064B..0x065F || c == 0x0670 || c == 0x0640 || c in 0x06D6..0x06ED) continue
        when (ch) {
            'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
            'ى', 'ی' -> sb.append('ي')
            'ة' -> sb.append('ه')
            'ک' -> sb.append('ك')
            'ء', 'ؤ', 'ئ' -> { }
            else -> if (c in 0x0621..0x064A) sb.append(ch)
        }
    }
    return sb.toString()
}

val NW: List<String> = WORDS.map { norm(it) }
val REF: String = NW.joinToString("")
val W_START: IntArray = IntArray(WORDS.size).also { arr ->
    var p = 0
    for (i in NW.indices) { arr[i] = p; p += NW[i].length }
}
val W_END: IntArray = IntArray(WORDS.size) { W_START[it] + NW[it].length }
val OWNER: IntArray = IntArray(REF.length).also { arr ->
    for (i in NW.indices) for (c in W_START[i] until W_END[i]) arr[c] = i
}
val IS_START: BooleanArray = BooleanArray(REF.length + 1).also { arr -> for (s in W_START) arr[s] = true }

class AlignResult(
    val st: Array<WS?>,        // OK / WRONG للكلمات المحسومة، و null لغير المحسومة
    val heard: Array<String>,  // ما سُمع فعلاً لكل كلمة (بحروف مجرّدة)
    val last: Int,             // آخر كلمة وصلت إليها المطابقة
    val bad: Int,
    val score: Float
)

/**
 * مطابقة على مستوى الحروف بين ما سمعه المعرِّف وبين نص الآية:
 *  - لا تعتمد على ترتيب/عدد الكلمات التي يُرجعها المعرِّف (يصحّ مع الدمج والتقسيم).
 *  - الكلمة المتروكة تُكتشف وتُلوَّن بالأحمر.
 *  - الكلمة المستبدلة بأخرى تُلوَّن بالأحمر.
 */
object Aligner {
    private const val INF = 1e9f
    private const val SKIP = 0.5f

    private fun allowed(len: Int, strict: Boolean): Float =
        if (strict) {
            if (len <= 2) 0f else if (len <= 4) 0.35f else if (len <= 7) 1.0f else 1.5f
        } else {
            if (len <= 2) 0.3f else if (len <= 4) 1.0f else if (len <= 7) 1.3f else 2.0f
        }

    private fun gap(c: Char): Float = if (c == 'ا') 0.3f else 1.0f

    fun align(h: String, base: Int, isFinal: Boolean, strict: Boolean): AlignResult? {
        val m = h.length
        if (m == 0) return null
        val n = WORDS.size
        val s0 = W_START[base]
        val rl = REF.length - s0
        val w = rl + 1
        val d = FloatArray((m + 1) * w) { INF }
        val bp = ByteArray((m + 1) * w)

        d[0] = 0f
        for (j in 1..rl) if (IS_START[s0 + j]) d[j] = SKIP * j

        for (i in 1..m) {
            val hi = h[i - 1]
            val row = i * w
            val prev = (i - 1) * w
            for (j in 0..rl) {
                var best = INF
                var b = 0
                if (j > 0) {
                    val v0 = d[prev + j - 1]
                    if (v0 < INF) {
                        val v = v0 + (if (hi == REF[s0 + j - 1]) 0f else 1f)
                        if (v < best) { best = v; b = 1 }
                    }
                }
                val v1 = d[prev + j]
                if (v1 < INF) {
                    val v = v1 + gap(hi)
                    if (v < best) { best = v; b = 2 }
                }
                if (j > 0) {
                    val v2 = d[row + j - 1]
                    if (v2 < INF) {
                        val v = v2 + gap(REF[s0 + j - 1])
                        if (v < best) { best = v; b = 3 }
                    }
                    val c = s0 + j - 1
                    val wi = OWNER[c]
                    if (c + 1 == W_END[wi]) {
                        val js = W_START[wi] - s0
                        if (js >= 0) {
                            val v3 = d[row + js]
                            if (v3 < INF) {
                                val v = v3 + SKIP * NW[wi].length
                                if (v < best) { best = v; b = 4 }
                            }
                        }
                    }
                }
                d[row + j] = best
                bp[row + j] = b.toByte()
            }
        }

        var je = 0
        var bc = INF
        for (j in 0..rl) {
            val v = d[m * w + j]
            if (v < bc - 1e-6f) { bc = v; je = j }
        }
        if (bc >= INF) return null

        val cost = FloatArray(n)
        val heardRev = Array(n) { StringBuilder() }
        val skipped = BooleanArray(n)
        val insJ = ArrayList<Int>()
        val insC = ArrayList<Char>()
        var i = m
        var j = je
        var matched = 0
        while (true) {
            val b = bp[i * w + j].toInt()
            if (i == 0 || b == 0) break
            when (b) {
                1 -> {
                    val col = s0 + j - 1
                    val wi = OWNER[col]
                    if (h[i - 1] == REF[col]) matched++ else cost[wi] += 1f
                    heardRev[wi].append(h[i - 1])
                    i--; j--
                }
                2 -> { insJ.add(j); insC.add(h[i - 1]); i-- }
                3 -> {
                    val col = s0 + j - 1
                    cost[OWNER[col]] += gap(REF[col])
                    j--
                }
                else -> {
                    val wi = OWNER[s0 + j - 1]
                    skipped[wi] = true
                    j = W_START[wi] - s0
                }
            }
        }
        val startCol = s0 + j
        val endCol = s0 + je
        // تجاهل الضوضاء: لازم يتطابق نصف ما سُمع على الأقل
        if (endCol <= startCol || matched == 0 || matched * 2 < m) return null

        // الحروف الزائدة داخل الكلمة تُحتسب خطأً
        val penIn = if (strict) 1f else 0.5f
        val groups = HashMap<Int, StringBuilder>()
        for (k in insJ.indices) groups.getOrPut(insJ[k]) { StringBuilder() }.append(insC[k])
        for ((jj, chs) in groups) {
            if (jj <= 0) continue
            val left = s0 + jj - 1
            val lw = OWNER[left]
            if (s0 + jj < REF.length && OWNER[s0 + jj] == lw && left + 1 < W_END[lw]) {
                var sum = 0f
                for (c in chs) sum += gap(c)
                cost[lw] += penIn * sum
            } else if (strict && chs.length == 1 && chs[0] != 'ا' && NW[lw].length >= 4) {
                cost[lw] += 1f
            }
        }

        val st = arrayOfNulls<WS>(n)
        val heard = Array(n) { "" }
        val first = OWNER[startCol]
        val last = OWNER[endCol - 1]
        for (wi in base until first) st[wi] = WS.WRONG
        for (wi in first..last) {
            heard[wi] = heardRev[wi].reverse().toString()
            if (skipped[wi]) { st[wi] = WS.WRONG; continue }
            val a = allowed(NW[wi].length, strict)
            if (wi < last) {
                st[wi] = if (cost[wi] <= a + 1e-4f) WS.OK else WS.WRONG
            } else if (endCol >= W_END[wi]) {
                if (cost[wi] <= a + 1e-4f) st[wi] = WS.OK
                else if (isFinal) st[wi] = WS.WRONG
            } else {
                val missing = W_END[wi] - endCol
                if (cost[wi] > a + 1e-4f) {
                    if (isFinal) st[wi] = WS.WRONG
                } else if (isFinal && missing <= 1) {
                    st[wi] = WS.OK
                }
            }
        }
        var bad = 0
        for (wi in base until n) if (st[wi] == WS.WRONG) bad++
        return AlignResult(st, heard, last, bad, matched.toFloat() - bc)
    }
}

class Engine(private val ctx: Context) {
    var phase by mutableStateOf(Phase.SETUP)
    var target by mutableStateOf(3)
    var counter by mutableStateOf(3)
    var playing by mutableStateOf(false)
    var status by mutableStateOf("")
    var showAll by mutableStateOf(true)
    var current by mutableStateOf(0)
    val states = mutableStateListOf<WS>().apply { repeat(WORDS.size) { add(WS.PENDING) } }
    val heard = Array(WORDS.size) { "" }

    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var session = 0
    private var base = 0          // أول كلمة لم تُحسم بعد
    private var sBase = 0         // قيمة base عند بداية الجلسة الحالية
    private var lastR: AlignResult? = null
    private var stopping = false
    private var lastActivity = 0L
    private val beeped = BooleanArray(WORDS.size)
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    } catch (e: Exception) { null }

    fun success() = states.all { it == WS.OK }

    fun startCycle() { counter = target; goGuided() }

    private fun resetAttempt() {
        for (i in states.indices) { states[i] = WS.PENDING; heard[i] = ""; beeped[i] = false }
        base = 0; sBase = 0; lastR = null; current = 0
        lastActivity = SystemClock.elapsedRealtime()
    }

    fun goGuided() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt(); phase = Phase.GUIDED
        status = "اقرأ الآية بصوتك…"; startRec()
    }

    fun goRecite() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt(); phase = Phase.RECITE
        status = "سَمِّع الآية غيباً…"; startRec(); tick()
    }

    fun restartAttempt() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt()
        status = "اقرأ الآية بصوتك…"
        if (phase == Phase.GUIDED) startRec()
    }

    fun finish() {
        stopRec(); h.removeCallbacksAndMessages(null)
        for (i in states.indices) if (states[i] == WS.PENDING) states[i] = WS.WRONG
        current = WORDS.size
        phase = Phase.RESULT
    }

    fun explain(i: Int): String {
        val sb = StringBuilder()
        if (states[i] == WS.HINT) {
            sb.append("نسيتَ هذه الكلمة فلُقِّنتَها. أعد حفظ موضعها جيداً.")
        } else if (heard[i].isEmpty()) {
            sb.append("لم أسمع هذه الكلمة منك (قد تكون تركتَها، أو لم يلتقطها الميكروفون).")
        } else {
            sb.append("سمعتُ منك: «").append(heard[i]).append("»\nوالصواب: ").append(WORDS[i])
        }
        TIPS[i]?.let { sb.append("\n\nتذكير بحكم الكلمة: ").append(it) }
        return sb.toString()
    }

    private fun refreshCurrent() {
        var c = states.indexOfFirst { it == WS.PENDING }
        if (c < 0) c = WORDS.size
        if (c != current) { current = c; lastActivity = SystemClock.elapsedRealtime() }
    }

    private fun allDecided(): Boolean = states.none { it == WS.PENDING }

    private fun tick() {
        h.postDelayed({
            if (phase != Phase.RECITE) return@postDelayed
            val now = SystemClock.elapsedRealtime()
            val i = current
            if (now - lastActivity >= 7000 && i < WORDS.size && states[i] == WS.PENDING) {
                states[i] = WS.HINT; lastActivity = now; refreshCurrent()
            }
            tick()
        }, 500)
    }

    private fun startRec() {
        stopRec()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { status = "خدمة التعرف على الصوت غير متوفرة"; return }
        val id = ++session
        sBase = base
        lastR = null
        stopping = false
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) {
                if (id != session) return
                lastR?.let { commit(it) }
                if (id != session) return
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { status = "صلاحية الميكروفون غير ممنوحة"; return }
                val wait = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) 500L else 120L
                restart(id, wait)
            }
            override fun onResults(b: Bundle?) {
                if (id != session) return
                val c = candidates(b)
                if (c != null) onHyp(c, true) else lastR?.let { commit(it) }
                restart(id, 0L)
            }
            override fun onPartialResults(b: Bundle?) {
                if (id != session) return
                candidates(b)?.let { onHyp(it, false) }
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // جلسات أطول وصبر أكثر على التوقف بين الكلمات (قد لا تلتزم بها بعض الأجهزة)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        })
    }

    private fun restart(id: Int, delay: Long) {
        if (id != session) return
        if ((phase == Phase.GUIDED && !playing) || phase == Phase.RECITE) {
            h.postDelayed({ if (id == session) startRec() }, delay)
        }
    }

    private fun candidates(b: Bundle?): List<String>? {
        val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        val out = l.filter { norm(it).isNotEmpty() }
        return if (out.isEmpty()) null else out
    }

    private fun onHyp(raw: List<String>, isFinal: Boolean) {
        if (phase != Phase.GUIDED && phase != Phase.RECITE) return
        val strict = phase == Phase.RECITE
        // التسميع: أول فرضية فقط (صارم). القراءة الموجَّهة: أفضل فرضية بين البدائل (متسامح مع أخطاء التعرف).
        val pool = if (strict) raw.take(1) else raw
        var best: AlignResult? = null
        for (c in pool) {
            val r = Aligner.align(norm(c), sBase, isFinal, strict) ?: continue
            if (best == null || r.score > best.score) best = r
        }
        val r = best
        if (r == null) {
            if (isFinal) lastR?.let { commit(it) }
            return
        }
        var beep = false
        for (i in sBase until WORDS.size) {
            if (states[i] == WS.HINT) continue
            val s = r.st[i]
            if (s == WS.WRONG && !beeped[i] && phase == Phase.RECITE) { beeped[i] = true; beep = true }
            states[i] = s ?: WS.PENDING
            if (s != null) heard[i] = r.heard[i]
        }
        if (beep) { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (e: Exception) { } }
        refreshCurrent()
        if (isFinal) {
            commit(r)
        } else {
            lastR = r
            // وصلت للكلمة الأخيرة: أنهِ الجلسة ليصل الحكم النهائي بسرعة
            if (allDecided() && !stopping) { stopping = true; rec?.stopListening() }
        }
    }

    private fun commit(r: AlignResult) {
        val nb = if (r.st[r.last] != null) r.last + 1 else r.last
        if (nb > base) base = nb
        lastR = null
        if (allDecided()) {
            if (phase == Phase.RECITE) finish() else if (phase == Phase.GUIDED) attemptDone()
        }
    }

    private fun attemptDone() {
        stopRec()
        val bad = states.count { it == WS.WRONG }
        val my = session
        if (bad <= GUIDED_TOLERANCE) {
            counter = (counter - 1).coerceAtLeast(0)
            if (counter == 0) {
                status = "أحسنت! ننتقل إلى التسميع…"
                h.postDelayed({ if (my == session && phase == Phase.GUIDED) goRecite() }, 900)
            } else {
                status = "قراءة صحيحة ✓ — المتبقي $counter"
                again(1300L)
            }
        } else {
            status = "لم تُحتسب القراءة: $bad كلمة بها خطأ (بالأحمر). أعد القراءة من البداية."
            again(3200L)
        }
    }

    private fun again(ms: Long) {
        val my = session
        h.postDelayed({
            if (my == session && phase == Phase.GUIDED) {
                resetAttempt(); status = "اقرأ الآية بصوتك…"; startRec()
            }
        }, ms)
    }

    fun toggleAudio() {
        if (playing) { stopAudio(); if (phase == Phase.GUIDED) startRec(); return }
        stopRec(); playing = true
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(AUDIO_URL)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopAudio(); if (phase == Phase.GUIDED) startRec() }
            setOnErrorListener { _, _, _ ->
                stopAudio(); status = "تعذّر تشغيل الصوت (تحقق من الإنترنت)"
                if (phase == Phase.GUIDED) startRec()
                true
            }
            prepareAsync()
        }
    }

    private fun stopAudio() { player?.release(); player = null; playing = false }
    private fun stopRec() { session++; rec?.destroy(); rec = null }
    fun release() { h.removeCallbacksAndMessages(null); stopRec(); stopAudio(); tone?.release() }
}

val Bg = Color(0xFF0F2B2A); val CardC = Color(0xFF173E3B); val Gold = Color(0xFFD4AF37)
val Cream = Color(0xFFF5EBD0); val Red = Color(0xFFEF4444); val Green = Color(0xFF4ADE80); val Orange = Color(0xFFFFA94D)
// لاستخدام خط Cairo: ضع Cairo-Regular.ttf في res/font ثم استبدل بـ FontFamily(Font(R.font.cairo_regular))
val Cairo: FontFamily = FontFamily.Serif

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerseCard(e: Engine, result: Boolean, onWord: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val ys = remember { IntArray(WORDS.size) }
    val tr = rememberInfiniteTransition(label = "glow")
    val a by tr.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "glowA"
    )
    val cur = e.current
    LaunchedEffect(cur, result) {
        if (!result && cur >= 0 && cur < WORDS.size) {
            scroll.animateScrollTo((ys[cur] - 80).coerceAtLeast(0))
        }
    }
    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(24.dp)).background(CardC)) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(18.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WORDS.forEachIndexed { i, w ->
                    val s = e.states[i]
                    val isCur = i == cur && !result
                    val hidden = !result && !e.showAll && e.phase == Phase.RECITE
                    if (!hidden || s != WS.PENDING || isCur) {
                        val txt = if (hidden && s == WS.PENDING) "• • •" else w
                        val col = when {
                            s == WS.WRONG -> Red
                            s == WS.HINT -> Orange
                            s == WS.OK -> Green
                            isCur -> Gold
                            else -> Cream.copy(alpha = 0.6f)
                        }
                        val glow = if (isCur) {
                            Modifier.background(Gold.copy(alpha = 0.12f + 0.25f * a), RoundedCornerShape(12.dp))
                        } else Modifier
                        val tap = if (result && (s == WS.WRONG || s == WS.HINT)) {
                            Modifier.clickable { onWord(i) }
                        } else Modifier
                        Box(
                            Modifier
                                .onGloballyPositioned { ys[i] = it.positionInParent().y.toInt() }
                                .then(glow)
                                .then(tap)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                txt,
                                color = col,
                                fontSize = 26.sp,
                                lineHeight = 40.sp,
                                fontFamily = Cairo,
                                style = TextStyle(
                                    shadow = if (isCur) Shadow(Gold, Offset.Zero, 8f + 28f * a) else null
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val e = remember { Engine(ctx) }
    DisposableEffect(Unit) { onDispose { e.release() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) e.startCycle() }
    var dialog by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("رَفِيق", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.Bold, fontFamily = Cairo)
        Text("Rafiq", color = Cream, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        when (e.phase) {
            Phase.SETUP -> {
                Text("عدد مرات التكرار المطلوبة للقراءة الصحيحة", color = Cream, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RoundBtn("+") { e.target++ }
                    Text("${e.target}", color = Gold, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    RoundBtn("−") { if (e.target > 1) e.target-- }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(3, 5, 10).forEach { n ->
                        OutlinedButton(onClick = { e.target = n }) { Text("$n", color = Cream) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = e.showAll, onCheckedChange = { e.showAll = it })
                    Text("إظهار الآية كاملة أثناء التسميع", color = Cream, fontSize = 15.sp)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) e.startCycle()
                        else launcher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text("ابدأ الدورة", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }

            Phase.GUIDED -> {
                Text("القراءة الموجَّهة", color = Gold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                VerseCard(e, false) { }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { e.toggleAudio() }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) {
                        Text(if (e.playing) "إيقاف الصوت" else "استمع للشيخ المنشاوي")
                    }
                    OutlinedButton(onClick = { e.restartAttempt() }) { Text("ابدأ من أول الآية", color = Cream) }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.size(84.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
                    Text("${e.counter}", color = Bg, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("المتبقي من القراءات الصحيحة", color = Cream, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(e.status, color = Cream.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            Phase.RECITE, Phase.RESULT -> {
                val result = e.phase == Phase.RESULT
                val wrongCount = e.states.count { it == WS.WRONG || it == WS.HINT }
                if (!result) {
                    Box(Modifier.size(70.dp).clip(CircleShape).background(Green.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                        Text("🎙", fontSize = 32.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("التسميع الذكي — بدون لمس الهاتف", color = Cream, fontSize = 14.sp)
                } else if (e.success()) {
                    Box(Modifier.size(100.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                        Text("✔", color = Bg, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("أحسنت وبارك الله فيك! تسميع متقن", color = Green, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                } else {
                    Text("عدد الكلمات التي بها خطأ: $wrongCount", color = Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("اضغط على الكلمات الملوّنة لمعرفة الخطأ", color = Orange, fontSize = 15.sp)
                }
                Spacer(Modifier.height(14.dp))
                VerseCard(e, result) { dialog = e.explain(it) }
                Spacer(Modifier.height(18.dp))
                if (!result) {
                    Button(onClick = { e.finish() }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("إيقاف التسجيل") }
                } else if (e.success()) {
                    Button(onClick = { e.phase = Phase.SETUP }, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) { Text("دورة جديدة") }
                } else {
                    Button(
                        onClick = { e.counter = 1; e.goGuided() },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
                    ) { Text("إعادة الآية (مرة واحدة)") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    dialog?.let {
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("حسناً") } },
            title = { Text("تنبيه") }, text = { Text(it) }
        )
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Gold).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = Bg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}
