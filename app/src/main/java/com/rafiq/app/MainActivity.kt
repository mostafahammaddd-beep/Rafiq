package com.rafiq.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

enum class Phase { SETUP, GUIDED, RECITE, RESULT }

/**
 * حالات تلوين الكلمات القرآنية في الواجهة:
 * - PENDING: لم تُفحص بعد (كريمي هادئ / ذهبي للكلمة النشطة)
 * - OK: تلاوة متقنة وصحيحة (أخضر Green)
 * - ERROR_LETTER: خطأ في الحروف أو الكلمة (أحمر Red)
 * - ERROR_TASHKEEL: خطأ في التشكيل والحركات (أصفر Yellow)
 * - ERROR_TAJWEED: خطأ في التجويد والمدود والغنة (برتقالي Orange)
 */
enum class WS {
    PENDING,
    OK,
    ERROR_LETTER,
    ERROR_TASHKEEL,
    ERROR_TAJWEED;

    val isError: Boolean
        get() = this == ERROR_LETTER || this == ERROR_TASHKEEL || this == ERROR_TAJWEED
}

// آية الكرسي كاملة (البقرة 255) — 50 كلمة بالتشكيل الكامل
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

// أحكام التجويد والتشكيل لجميع كلمات آية الكرسي (0..49) وصلاً ووقفاً
val TIPS: Map<Int, String> = mapOf(
    0 to "تفخيم اللام في لفظ الجلالة «اللَّهُ» مع تشديدها، ومدّها مدّاً طبيعياً، وضمّ الهاء وصلاً وتسكينها وقفاً.",
    1 to "مدّ منفصل جائز (٢ أو ٤ أو ٥ حركات) وصلاً قبل همزة «إله»، ومدّ طبيعي حركتان وقفاً.",
    2 to "تحقيق الهمزة المكسورة، ومدّ الألف الخنجرية مدّاً طبيعياً (حركتان)، وفتح الهاء وصلاً وتسكينها وقفاً.",
    3 to "تحقيق الهمزة المكسورة، وتشديد اللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    4 to "ضمّ الهاء وفتح الواو دون مدّ وصلاً، وتسكين الواو وقفاً.",
    5 to "إظهار اللام القمرية، وترقيق الحاء، وتشديد الياء مع ضمّها وصلاً والنبر عليها بالسكون وقفاً.",
    6 to "تفخيم القاف، وتشديد الياء مع ضمّها، ومدّ الواو طبيعياً وصلاً أو عارضاً للسكون (٢/٤/٦) وقفاً مع إسكان الميم.",
    7 to "مدّ طبيعي بالألف بمقدار حركتين.",
    8 to "تحقيق الهمزة الساكنة دون قلقلة، وتفخيم الخاء المضمومة، وضمّ الذال مع إخراج طرف اللسان، ومدّ صلة صغرى للهاء (حركتان) وصلاً وحذف الصلة بالسكون وقفاً.",
    9 to "كسر السين، وفتح النون، وتنوين الضمّ في التاء المربوطة يُدغم بغنة كاملة في واو «ولا» وصلاً، وتنقلب التاء هاءً ساكنة وقفاً.",
    10 to "فتح الواو واللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    11 to "واو ليّنة ساكنة بعد فتح؛ وصلاً يُدغم التنوين إدغاماً كاملاً بغير غنة في لام «له»، ووقفاً مدّ لين عارض للسكون (٢/٤/٦) مع إسكان الميم.",
    12 to "فتح اللام، وضمّ الهاء مع مدّ صلة صغرى (حركتان) وصلاً، وإسكان الهاء وقفاً.",
    13 to "فتح الميم ومدّ الألف مدّاً طبيعياً حركتين.",
    14 to "كسر الفاء؛ وصلاً تُحذف ياء المدّ منعاً لالتقاء الساكنين قبل السين المشددة في «السماوات»، ووقفاً تُمدّ حركتين.",
    15 to "إدغام اللام الشمسية في السين المشددة مع صفيرها، ومدّ الألفين مدّاً طبيعياً، وكسر التاء وصلاً وإسكانها بالهمس وقفاً.",
    16 to "فتح الواو والميم، ومدّ الألف مدّاً طبيعياً حركتين.",
    17 to "كسر الفاء؛ وصلاً تُحذف ياء المدّ لالتقاء الساكنين قبل اللام القمرية في «الأرض»، ووقفاً تُمدّ حركتين.",
    18 to "إظهار اللام القمرية، وتحقيق الهمزة المرققة، وتفخيم الراء، وتفخيم الضاد وإطباقها مع كسرها وصلاً واستطالتها ساكنةً وقفاً.",
    19 to "فتح الميم، وإخفاء النون الساكنة عند الذال بغنة مرققة بمقدار حركتين وصلاً.",
    20 to "إخراج طرف اللسان في الذال؛ وصلاً تُحذف الألف لالتقاء الساكنين قبل «الذي»، ووقفاً تُمدّ حركتين.",
    21 to "تشديد اللام وكسر الذال مع إخراج طرف اللسان، ومدّ الياء مدّاً طبيعياً حركتين.",
    22 to "فتح الياء، وهمس الشين الساكنة وتفشّيها، وفتح الفاء، وضمّ العين وصلاً وتوسّطها ساكنةً وقفاً.",
    23 to "كسر العين، وإخفاء النون الساكنة عند الدال بغنة مرققة، ومدّ صلة كبرى للهاء (٢–٥ حركات) وصلاً قبل همزة «إلا»، وإسكان الهاء وقفاً.",
    24 to "تحقيق الهمزة المكسورة، وتشديد اللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    25 to "كسر الباء والهمزة، وإخراج طرف اللسان في الذال الساكنة برخاوة دون قلقلة، وكسر النون والهاء مع صلة صغرى وصلاً وإسكان الهاء دون صلة وقفاً.",
    26 to "فتح الياء، وتوسّط العين الساكنة من غير قلقلة، وفتح اللام، وضمّ الميم وصلاً وإسكانها وقفاً.",
    27 to "فتح الميم ومدّ الألف مدّاً طبيعياً حركتين.",
    28 to "فتح الباء، وياء ليّنة ساكنة، وفتح النون وصلاً أو مدّ لين عارض مع إسكان النون وقفاً.",
    29 to "تحقيق الهمزة المفتوحة، وياء ليّنة ساكنة، ومدّ الياء بعد الدال حركتين، وكسر الهاء، وإظهار الميم الساكنة إظهاراً شفوياً قبل الواو.",
    30 to "فتح الواو والميم، ومدّ الألف مدّاً طبيعياً حركتين.",
    31 to "تفخيم الخاء المفتوحة، وترقيق اللام الساكنة والفاء المفتوحة، وضمّ الهاء، وإظهار الميم الساكنة إظهاراً شفوياً.",
    32 to "فتح الواو واللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    33 to "ضمّ الياء، وكسر الحاء وترقيقها مع مدّ الياء، وتفخيم الطاء المضمومة وإطباقها مع مدّ الواو، وفتح النون وصلاً وإسكانها وقفاً.",
    34 to "كسر الباء، وفتح الشين، وياء ليّنة ساكنة، وهمزة منوّنة بالكسر تُدغم بغنة كاملة في ميم «من» وصلاً وتُسكّن بالنبر وقفاً.",
    35 to "كسر الميم، وإظهار النون الساكنة إظهاراً حلقياً عند العين من غير غنة زائدة.",
    36 to "كسر العين واللام الساكنة المظهرة، وكسر الميم والهاء مع مدّ صلة كبرى بالياء (٢–٥ حركات) وصلاً قبل همزة «إلا»، وإسكان الهاء وقفاً.",
    37 to "تحقيق الهمزة المكسورة، وتشديد اللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    38 to "كسر الباء، وفتح الميم، ومدّ الألف مدّاً طبيعياً حركتين.",
    39 to "مدّ واجب متصل (٤–٥ حركات وصلاً، و٤–٦ حركات وقفاً)، وفتح الهمزة وصلاً وإسكانها محققةً وقفاً.",
    40 to "فتح الواو، وكسر السين وترقيقها، وفتح العين وصلاً وإسكانها وقفاً.",
    41 to "ضمّ الكاف، وتفخيم الراء الساكنة، وكسر السين، وتشديد الياء مع ضمّها، وضمّ الهاء دون صلة وصلاً (لالتقاء الساكنين مع السماوات) وإسكانها وقفاً.",
    42 to "إدغام اللام الشمسية في السين المشددة، ومدّ الألفين مدّاً طبيعياً، وكسر التاء وصلاً وإسكانها بالهمس وقفاً.",
    43 to "فتح الواو، وإظهار اللام القمرية، وتحقيق الهمزة المرققة، وتفخيم الراء والضاد مع فتح الضاد وصلاً وإسكانها بالاستطالة وقفاً.",
    44 to "فتح الواو واللام، ومدّ الألف مدّاً طبيعياً حركتين.",
    45 to "فتح الياء، وضمّ الهمزة ومدّها بالواو مدّ بدل طبيعياً (حركتان)، وضمّ الدال، ومدّ صلة صغرى للهاء وصلاً وإسكانها وقفاً.",
    46 to "كسر الحاء، وهمس الفاء الساكنة ورخاوتها دون قلقلة، وتفخيم الظاء المضمومة وإطباقها مع إخراج طرف اللسان، وضمّ الهاء، ومدّ الألف حركتين.",
    47 to "فتح الواو، وضمّ الهاء، وفتح الواو الثانية دون مدّ وصلاً وإسكانها وقفاً.",
    48 to "إظهار اللام القمرية، وفتح العين وكسر اللام، وتشديد الياء مع ضمّها وصلاً والنبر عليها بالسكون وقفاً.",
    49 to "إظهار اللام القمرية، وفتح العين وترقيقها، وتفخيم الظاء المكسورة وإطباقها مع إخراج اللسان، ومدّ عارض للسكون (٢ أو ٤ أو ٦ حركات) مع إسكان الميم وقفاً."
)

fun normRawWord(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        val c = ch.code
        if (c in 0x064B..0x065F || c == 0x0670 || c == 0x0640 || c in 0x06D6..0x06ED) continue
        when (ch) {
            'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
            'ى', 'ی', 'ئ' -> sb.append('ي')
            'ؤ' -> sb.append('و')
            'ة' -> sb.append('ه')
            'ک' -> sb.append('ك')
            'ء' -> {}
            else -> if (c in 0x0621..0x064A) sb.append(ch)
        }
    }
    return when (val res = sb.toString()) {
        "السموات" -> "السماوات"
        "الاه" -> "اله"
        "يووده", "ييوده", "ياوده", "يوده" -> "يوده"
        "بشيي" -> "بشي"
        "شاا" -> "شا"
        else -> res
    }
}

fun norm(s: String): String {
    val tokens = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return normRawWord(s)
    return tokens.joinToString("") { normRawWord(it) }
}

val NW: List<String> = WORDS.map { norm(it) }

class Engine(private val ctx: Context) {
    var phase by mutableStateOf(Phase.SETUP)
    var target by mutableStateOf(3)
    var counter by mutableStateOf(3)
    var playing by mutableStateOf(false)
    var status by mutableStateOf("")
    var showAll by mutableStateOf(true)
    var current by mutableStateOf(0)

    // حالات التسجيل المباشر والتحليل عبر Gemini API
    var isRecording by mutableStateOf(false)
    var isAnalyzing by mutableStateOf(false)
    var isOffline by mutableStateOf(false)
    var liveRms by mutableStateOf(0f)
    var recordedSeconds by mutableStateOf(0)
    var overallAccuracy by mutableStateOf<Int?>(null)
    var generalFeedback by mutableStateOf("")
    var showSettingsModal by mutableStateOf(false)

    val states = mutableStateListOf<WS>().apply { repeat(WORDS.size) { add(WS.PENDING) } }
    val wordErrors = mutableStateMapOf<Int, RecitationError>()

    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session = 0
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    } catch (_: Exception) {
        null
    }

    fun hasApiKey(): Boolean = GeminiSettingsManager.getApiKey(ctx).isNotEmpty()

    fun refreshConnectivity(): Boolean {
        val online = GeminiSettingsManager.isNetworkConnected(ctx)
        isOffline = !online
        if (!online) {
            status = OFFLINE_NOTICE_AR
        }
        return online
    }

    fun success(): Boolean = states.all { it == WS.OK }

    fun startCycle() {
        counter = target
        goGuided()
    }

    private fun resetAttemptState() {
        for (i in states.indices) {
            states[i] = WS.PENDING
        }
        wordErrors.clear()
        current = 0
        liveRms = 0f
        recordedSeconds = 0
        overallAccuracy = null
        generalFeedback = ""
        isAnalyzing = false
        isRecording = false
        refreshConnectivity()
    }

    fun goGuided() {
        stopRecordingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetAttemptState()
        phase = Phase.GUIDED
        if (!isOffline) {
            status = "اضغط «ابدأ التسجيل والتلاوة» واقرأ الآية بصوتك ليتم تدقيق الحروف والتشكيل والتجويد عبر Gemini"
        }
    }

    fun goRecite() {
        stopRecordingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetAttemptState()
        phase = Phase.RECITE
        if (!isOffline) {
            status = "اضغط «ابدأ التسجيل والتسميع» واقرأ الآية غيباً، ثم اضغط «إيقاف وتدقيق التلاوة»"
        }
    }

    fun restartAttempt() {
        stopRecordingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetAttemptState()
        if (!isOffline) {
            status = "جاهز للتلاوة — اضغط زر التسجيل للبدء"
        }
    }

    fun toggleRecording() {
        if (isAnalyzing) return
        if (isRecording) {
            stopAndSubmitToGemini()
        } else {
            startRawRecording()
        }
    }

    private fun startRawRecording() {
        if (isAnalyzing) return
        stopAudio()
        if (!refreshConnectivity()) {
            status = OFFLINE_NOTICE_AR
            return
        }
        if (!hasApiKey()) {
            status = "يرجى إدخال مفتاح Gemini API في الإعدادات أولاً."
            showSettingsModal = true
            return
        }

        for (i in states.indices) {
            states[i] = WS.PENDING
        }
        wordErrors.clear()
        overallAccuracy = null
        generalFeedback = ""
        current = 0
        recordedSeconds = 0

        val mySession = ++session
        val autoStop = GeminiSettingsManager.getAutoStopOnSilence(ctx)
        val started = RawAudioRecorder.startRecording(
            autoStopOnSilence = autoStop,
            onProgress = { rms, durationMs, estWordIdx ->
                if (mySession == session && isRecording) {
                    liveRms = rms
                    recordedSeconds = (durationMs / 1000L).toInt()
                    if (estWordIdx > current) {
                        current = estWordIdx.coerceIn(0, WORDS.size - 1)
                    }
                }
            },
            onAutoSilenceStop = {
                if (mySession == session && isRecording) {
                    stopAndSubmitToGemini()
                }
            },
            onError = { errMsg ->
                isRecording = false
                status = errMsg
            }
        )

        if (started) {
            isRecording = true
            status = "🎙 جاري التسجيل المباشر... اقرأ الآية بوضوح ثم اضغط «إيقاف وتدقيق التلاوة»"
        }
    }

    fun stopAndSubmitToGemini() {
        if (!isRecording || isAnalyzing) return
        isRecording = false
        liveRms = 0f

        val base64Wav = RawAudioRecorder.stopAndBuildBase64Wav()
        if (base64Wav == null) {
            status = "التسجيل قصير جداً. يرجى قراءة الآية بصوت واضح ثم الضغط على إيقاف وتدقيق."
            return
        }

        if (!refreshConnectivity()) {
            status = OFFLINE_NOTICE_AR
            return
        }

        isAnalyzing = true
        status = LOADING_NOTICE_AR
        val mySession = session

        GeminiRecitationAuditor.auditAudioAsync(
            ctx = ctx,
            base64WavAudio = base64Wav,
            onSuccess = { report ->
                if (mySession != session) return@auditAudioAsync
                isAnalyzing = false
                applyAuditReport(report)
            },
            onFailure = { errMsg ->
                if (mySession != session) return@auditAudioAsync
                isAnalyzing = false
                status = errMsg
                if (errMsg.contains("API", ignoreCase = true)) {
                    showSettingsModal = true
                }
            }
        )
    }

    private fun applyAuditReport(report: RecitationAuditReport) {
        wordErrors.clear()
        for ((wi, err) in report.errorByWordIndex) {
            if (wi in WORDS.indices) {
                wordErrors[wi] = err
            }
        }

        for (i in WORDS.indices) {
            val err = wordErrors[i]
            states[i] = when (err?.errorType) {
                null -> WS.OK
                AuditErrorType.LETTER -> WS.ERROR_LETTER
                AuditErrorType.TASHKEEL -> WS.ERROR_TASHKEEL
                AuditErrorType.TAJWEED -> WS.ERROR_TAJWEED
            }
        }

        current = WORDS.size
        overallAccuracy = report.overallAccuracyPercentage
        generalFeedback = report.generalFeedback

        if (wordErrors.isNotEmpty()) {
            try {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            } catch (_: Exception) {}
        }

        if (phase == Phase.GUIDED) {
            if (report.isPerfect && wordErrors.isEmpty()) {
                counter = (counter - 1).coerceAtLeast(0)
                if (counter == 0) {
                    status = "${report.generalFeedback} — اكتمل التكرار بنجاح! ننتقل إلى التسميع…"
                    val my = session
                    h.postDelayed({ if (my == session && phase == Phase.GUIDED) goRecite() }, 1400L)
                } else {
                    status = "${report.generalFeedback} (الدقة ${report.overallAccuracyPercentage}%) — المتبقي $counter"
                }
            } else {
                val errCount = wordErrors.size
                status = "${report.generalFeedback} — وُجدت ملاحظات في $errCount كلمة (الدقة ${report.overallAccuracyPercentage}%). اضغط على أي كلمة ملوّنة للتفاصيل."
            }
        } else if (phase == Phase.RECITE) {
            status = report.generalFeedback
            phase = Phase.RESULT
        }
    }

    fun toggleAudio() {
        if (playing) {
            stopAudio()
            return
        }
        if (isRecording) {
            RawAudioRecorder.cancelRecording()
            isRecording = false
        }
        playing = true
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(AUDIO_URL)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopAudio() }
            setOnErrorListener { _, _, _ ->
                stopAudio()
                status = "تعذّر تشغيل التلاوة الصوتية (تحقق من الاتصال بالإنترنت)"
                true
            }
            prepareAsync()
        }
    }

    private fun stopAudio() {
        player?.release()
        player = null
        playing = false
    }

    private fun stopRecordingAndAudio() {
        session++
        RawAudioRecorder.cancelRecording()
        isRecording = false
        isAnalyzing = false
        stopAudio()
    }

    fun release() {
        h.removeCallbacksAndMessages(null)
        stopRecordingAndAudio()
        tone?.release()
    }
}

// ألوان التصميم والتمييز البصري الدقيق للكلمات القرآنية
val Bg = Color(0xFF0F2B2A)
val CardC = Color(0xFF173E3B)
val Gold = Color(0xFFD4AF37)
val Cream = Color(0xFFF5EBD0)
val RedLetterError = Color(0xFFEF4444)      // Red: Letter/Word error
val YellowTashkeelError = Color(0xFFFACC15) // Yellow: Tashkeel/Vowel error
val OrangeTajweedError = Color(0xFFFF922B)  // Orange: Tajweed/Timing error
val GreenPerfect = Color(0xFF4ADE80)        // Green: Perfect recitation
val Cairo: FontFamily = FontFamily.Serif

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                App()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerseCard(e: Engine, result: Boolean, onWordClick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val ys = remember { IntArray(WORDS.size) }
    val tr = rememberInfiniteTransition(label = "glow")
    val a by tr.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "glowA"
    )
    val cur = e.current
    LaunchedEffect(cur, result, e.isRecording) {
        if (!result && e.isRecording && cur in 0 until WORDS.size) {
            scroll.animateScrollTo((ys[cur] - 80).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 370.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardC)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(18.dp)
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WORDS.forEachIndexed { i, w ->
                        val s = e.states[i]
                        val isCur = e.isRecording && i == cur && !result
                        val hidden = !result && !e.showAll && e.phase == Phase.RECITE && s == WS.PENDING

                        val txt = if (hidden && !isCur) "• • •" else w
                        val textColor = when (s) {
                            WS.ERROR_LETTER -> RedLetterError
                            WS.ERROR_TASHKEEL -> YellowTashkeelError
                            WS.ERROR_TAJWEED -> OrangeTajweedError
                            WS.OK -> GreenPerfect
                            WS.PENDING -> if (isCur) Gold else Cream.copy(alpha = 0.85f)
                        }

                        val pillModifier = when {
                            s == WS.ERROR_LETTER -> Modifier
                                .background(RedLetterError.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                .border(1.dp, RedLetterError.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                            s == WS.ERROR_TASHKEEL -> Modifier
                                .background(YellowTashkeelError.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                .border(1.dp, YellowTashkeelError.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                            s == WS.ERROR_TAJWEED -> Modifier
                                .background(OrangeTajweedError.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                .border(1.dp, OrangeTajweedError.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                            isCur -> Modifier.background(
                                Gold.copy(alpha = 0.14f + 0.22f * a),
                                RoundedCornerShape(12.dp)
                            )
                            else -> Modifier
                        }

                        Box(
                            Modifier
                                .onGloballyPositioned { ys[i] = it.positionInParent().y.toInt() }
                                .then(pillModifier)
                                .clickable { onWordClick(i) }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                txt,
                                color = textColor,
                                fontSize = 26.sp,
                                lineHeight = 40.sp,
                                fontFamily = Cairo,
                                style = TextStyle(
                                    shadow = if (isCur) Shadow(Gold, Offset.Zero, 8f + 24f * a) else null
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // دليل الألوان التوضيحي للكلمات القرآنية
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardC.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = RedLetterError, label = "أحمر: حروف")
            LegendItem(color = YellowTashkeelError, label = "أصفر: تشكيل")
            LegendItem(color = OrangeTajweedError, label = "برتقالي: تجويد")
            LegendItem(color = GreenPerfect, label = "أخضر: متقن")
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, color = Cream, fontSize = 12.sp)
    }
}

@Composable
fun LoadingAuditCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardC)
            .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
        ) {
            CircularProgressIndicator(
                color = Gold,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
            Text(
                LOADING_NOTICE_AR,
                color = Gold,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun OfflineNoticeBanner(onRetryCheck: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RedLetterError.copy(alpha = 0.18f))
            .border(1.dp, RedLetterError.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                OFFLINE_NOTICE_AR,
                color = Cream,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "يمكنك الاستمرار في قراءة الآية ومراجعة أحكام التجويد بالضغط على أي كلمة دون إنترنت.",
                color = Cream.copy(alpha = 0.85f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRetryCheck) {
                Text("إعادة فحص الاتصال", color = Gold, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WordFeedbackModal(
    wordIndex: Int,
    error: RecitationError?,
    onDismiss: () -> Unit
) {
    val wordText = WORDS.getOrElse(wordIndex) { "" }
    val tipText = TIPS[wordIndex] ?: ""
    val badgeColor = when (error?.errorType) {
        AuditErrorType.LETTER -> RedLetterError
        AuditErrorType.TASHKEEL -> YellowTashkeelError
        AuditErrorType.TAJWEED -> OrangeTajweedError
        null -> GreenPerfect
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
            ) {
                Text("حسناً", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardC,
        titleContentColor = Gold,
        textContentColor = Cream,
        title = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "« $wordText »",
                    color = badgeColor,
                    fontSize = 30.sp,
                    fontFamily = Cairo,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                val label = if (error != null) {
                    "${error.errorType.arabicLabel} (${(error.confidence * 100).roundToInt()}%)"
                } else {
                    "تلاوة صحيحة وحكم الكلمة"
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .border(1.dp, badgeColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(label, color = badgeColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (error != null) {
                    FeedbackInfoBox(
                        title = "النطق الصحيح المتوقع (Expected):",
                        value = error.expected,
                        accent = GreenPerfect
                    )
                    FeedbackInfoBox(
                        title = "ما تم رصده في تلاوتك (Detected):",
                        value = error.detected,
                        accent = badgeColor
                    )
                    FeedbackInfoBox(
                        title = "نصيحة التصحيح (Correction Tip):",
                        value = error.correctionTip,
                        accent = Gold
                    )
                } else {
                    Text(
                        "لم يُرصد أي خطأ في هذه الكلمة.",
                        color = GreenPerfect,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (tipText.isNotEmpty()) {
                    FeedbackInfoBox(
                        title = "القاعدة التجويدية والتشكيلية للكلمة:",
                        value = tipText,
                        accent = Cream.copy(alpha = 0.9f)
                    )
                }
            }
        }
    )
}

@Composable
fun FeedbackInfoBox(title: String, value: String, accent: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Bg.copy(alpha = 0.65f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Cream, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
fun SettingsModal(
    ctx: Context,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf(GeminiSettingsManager.getApiKey(ctx)) }
    var modelInput by remember { mutableStateOf(GeminiSettingsManager.getModelName(ctx)) }
    var autoStop by remember { mutableStateOf(GeminiSettingsManager.getAutoStopOnSilence(ctx)) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardC,
        titleContentColor = Gold,
        textContentColor = Cream,
        title = {
            Text(
                "⚙️ إعدادات Gemini API",
                color = Gold,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "يعتمد التطبيق على إرسال صوت تلاوتك الخام مباشرةً إلى نموذج Gemini لتحليل الحروف والتشكيل وأحكام التجويد بدقة عالية.",
                    color = Cream.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("مفتاح Gemini API Key", color = Gold) },
                    placeholder = { Text("AIzaSy...", color = Cream.copy(alpha = 0.45f)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream,
                        unfocusedTextColor = Cream,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Cream.copy(alpha = 0.5f),
                        cursorColor = Gold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "إخفاء المفتاح" else "إظهار المفتاح", color = Gold, fontSize = 13.sp)
                    }
                    if (apiKeyInput.isNotEmpty()) {
                        TextButton(onClick = { apiKeyInput = "" }) {
                            Text("مسح", color = RedLetterError, fontSize = 13.sp)
                        }
                    }
                }

                // رابط مباشر إلى Google AI Studio للحصول على مفتاح مجاني
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg.copy(alpha = 0.8f))
                        .border(1.dp, Gold.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_AI_STUDIO_URL))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                        .padding(12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "🔑 احصل على مفتاح Gemini API مجاناً من Google AI Studio",
                            color = Gold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            GOOGLE_AI_STUDIO_URL,
                            color = GreenPerfect,
                            fontSize = 12.sp,
                            textDecoration = TextDecoration.Underline,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Text("النموذج المفضل:", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("gemini-2.5-flash", "gemini-2.0-flash").forEach { m ->
                        val selected = modelInput == m
                        OutlinedButton(
                            onClick = { modelInput = m },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) Gold.copy(alpha = 0.22f) else Color.Transparent,
                                contentColor = if (selected) Gold else Cream
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(m.removePrefix("gemini-"), fontSize = 12.sp)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "إيقاف التسجيل تلقائياً بعد سكون ختام الآية",
                        color = Cream,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = autoStop, onCheckedChange = { autoStop = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    GeminiSettingsManager.saveApiKey(ctx, apiKeyInput)
                    GeminiSettingsManager.saveModelName(ctx, modelInput)
                    GeminiSettingsManager.saveAutoStopOnSilence(ctx, autoStop)
                    onSaved()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
            ) {
                Text("حفظ الإعدادات", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Cream)
            }
        }
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val e = remember { Engine(ctx) }
    DisposableEffect(Unit) {
        e.refreshConnectivity()
        onDispose { e.release() }
    }

    var pendingStartCycleAfterPerm by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingStartCycleAfterPerm) {
                pendingStartCycleAfterPerm = false
                e.startCycle()
            } else {
                e.toggleRecording()
            }
        } else {
            e.status = "صلاحية الميكروفون مطلوبة لتسجيل التلاوة وتدقيقها."
        }
    }

    var selectedWordForModal by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // الشريط العلوي مع زر الإعدادات ومفتاح Gemini API
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("رَفِيق", color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Cairo)
                Text("Rafiq • Gemini Multimodal Quran Auditor", color = Cream.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { e.showSettingsModal = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
            ) {
                val keyReady = e.hasApiKey()
                Text(
                    if (keyReady) "⚙️ الإعدادات ✓" else "⚙️ مفتاح API",
                    color = if (keyReady) GreenPerfect else Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (e.isOffline) {
            OfflineNoticeBanner(onRetryCheck = { e.refreshConnectivity() })
            Spacer(Modifier.height(12.dp))
        }

        when (e.phase) {
            Phase.SETUP -> {
                Text(
                    "عدد مرات التكرار المطلوبة للقراءة الصحيحة",
                    color = Cream,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    RoundBtn("+") { e.target++ }
                    Text("${e.target}", color = Gold, fontSize = 54.sp, fontWeight = FontWeight.Bold)
                    RoundBtn("−") { if (e.target > 1) e.target-- }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(3, 5, 10).forEach { n ->
                        OutlinedButton(onClick = { e.target = n }) { Text("$n", color = Cream) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Switch(checked = e.showAll, onCheckedChange = { e.showAll = it })
                    Text("إظهار الآية كاملة أثناء التسميع", color = Cream, fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))

                // معاينة الآية الكريمة مع إمكانية القراءة والضغط على أي كلمة حتى بدون إنترنت
                VerseCard(e = e, result = false) { wi -> selectedWordForModal = wi }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            e.startCycle()
                        } else {
                            pendingStartCycleAfterPerm = true
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text("ابدأ الدورة", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            Phase.GUIDED -> {
                Text("القراءة الموجَّهة والتدقيق الصوتي المباشر", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                VerseCard(e = e, result = false) { wi -> selectedWordForModal = wi }
                Spacer(Modifier.height(12.dp))

                if (e.isAnalyzing) {
                    LoadingAuditCard()
                    Spacer(Modifier.height(12.dp))
                }

                // زر التسجيل والإيقاف الرئيسي (يُعطَّل تلقائياً أثناء طلب Gemini API)
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            e.toggleRecording()
                        } else {
                            pendingStartCycleAfterPerm = false
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !e.isAnalyzing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (e.isRecording) RedLetterError else Gold,
                        contentColor = if (e.isRecording) Cream else Bg,
                        disabledContainerColor = CardC,
                        disabledContentColor = Cream.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    val btnLabel = when {
                        e.isAnalyzing -> LOADING_NOTICE_AR
                        e.isRecording -> "⏹ إيقاف التسجيل وتدقيق التلاوة (${e.recordedSeconds} ث)"
                        else -> "🎙 ابدأ التسجيل والتلاوة"
                    }
                    Text(btnLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { e.toggleAudio() },
                        enabled = !e.isAnalyzing,
                        colors = ButtonDefaults.buttonColors(containerColor = CardC, contentColor = Gold)
                    ) {
                        Text(if (e.playing) "إيقاف صوت الشيخ" else "🎧 استمع للشيخ المنشاوي")
                    }
                    OutlinedButton(
                        onClick = { e.restartAttempt() },
                        enabled = !e.isAnalyzing
                    ) {
                        Text("إعادة المحاولة", color = Cream)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Gold),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${e.counter}", color = Bg, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("المتبقي من القراءات الصحيحة", color = Cream, fontSize = 13.sp)
                        e.overallAccuracy?.let { acc ->
                            Text(
                                "دقة التلاوة الأخيرة: $acc%",
                                color = if (acc == 100) GreenPerfect else YellowTashkeelError,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    e.status,
                    color = Cream.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Phase.RECITE, Phase.RESULT -> {
                val result = e.phase == Phase.RESULT
                val wrongCount = e.wordErrors.size

                if (!result) {
                    Text(
                        "التسميع الذكي المباشر عبر Gemini",
                        color = Gold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        e.status,
                        color = Cream.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                } else if (e.success() && wrongCount == 0) {
                    Box(
                        Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(GreenPerfect),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✔", color = Bg, fontSize = 50.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "أحسنت وبارك الله فيك! تسميع متقن (${e.overallAccuracy ?: 100}%)",
                        color = GreenPerfect,
                        fontSize = 19.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    if (e.generalFeedback.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(e.generalFeedback, color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(
                        "نتيجة التدقيق: $wrongCount كلمة بها ملاحظات (الدقة ${e.overallAccuracy ?: 0}%)",
                        color = RedLetterError,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    if (e.generalFeedback.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(e.generalFeedback, color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "اضغط على أي كلمة ملوّنة (أحمر / أصفر / برتقالي) لعرض المقارنة والتصحيح",
                        color = YellowTashkeelError,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(12.dp))
                VerseCard(e = e, result = result) { wi -> selectedWordForModal = wi }
                Spacer(Modifier.height(14.dp))

                if (e.isAnalyzing) {
                    LoadingAuditCard()
                    Spacer(Modifier.height(14.dp))
                }

                if (!result) {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                e.toggleRecording()
                            } else {
                                pendingStartCycleAfterPerm = false
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !e.isAnalyzing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (e.isRecording) RedLetterError else Gold,
                            contentColor = if (e.isRecording) Cream else Bg,
                            disabledContainerColor = CardC,
                            disabledContentColor = Cream.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        val btnLabel = when {
                            e.isAnalyzing -> LOADING_NOTICE_AR
                            e.isRecording -> "⏹ إيقاف التسجيل وتدقيق التسميع (${e.recordedSeconds} ث)"
                            else -> "🎙 ابدأ تسجيل التسميع"
                        }
                        Text(btnLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { e.restartAttempt() },
                        enabled = !e.isAnalyzing
                    ) {
                        Text("إعادة من الأول", color = Cream)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { e.goRecite() },
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
                        ) {
                            Text("إعادة التسميع", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { e.phase = Phase.SETUP }) {
                            Text("دورة جديدة", color = Cream)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    selectedWordForModal?.let { wi ->
        WordFeedbackModal(
            wordIndex = wi,
            error = e.wordErrors[wi],
            onDismiss = { selectedWordForModal = null }
        )
    }

    if (e.showSettingsModal) {
        SettingsModal(
            ctx = ctx,
            onDismiss = { e.showSettingsModal = false },
            onSaved = {
                e.showSettingsModal = false
                e.refreshConnectivity()
                if (!e.isOffline) {
                    e.status = "تم حفظ مفتاح Gemini API والإعدادات محلياً بنجاح ✓"
                }
            }
        )
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Gold)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Bg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}
