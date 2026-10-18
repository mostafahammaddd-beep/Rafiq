package com.rafiq.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.max

enum class Phase { SETUP, GUIDED, RECITE, RESULT }

// آية الكرسي كاملة (البقرة 255) — 50 كلمة بالرسم العثماني والتشكيل الكامل
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

// أحكام التجويد والتشكيل المرجعية لجميع كلمات آية الكرسي (0..49) وصلاً ووقفاً
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

class Engine(private val ctx: Context) {
    var phase by mutableStateOf(Phase.SETUP)
    var target by mutableStateOf(3)
    var counter by mutableStateOf(3)
    var playing by mutableStateOf(false)
    var status by mutableStateOf("جاهز للبث الصوتي اللحظي إلى محرك Quran-Muaalem v0.2.2")
    var showAll by mutableStateOf(true)

    // حالة البث الصوتي اللحظي ومخرجات Quran-Muaalem QPS
    var isStreaming by mutableStateOf(false)
    var wsConnected by mutableStateOf(false)
    var liveRms by mutableStateOf(0f)
    var elapsedSeconds by mutableStateOf(0)
    var completedWords by mutableStateOf(0)
    var activeWordIndex by mutableStateOf(0)
    var predictedPhonemes by mutableStateOf("")
    var referencePhonemes by mutableStateOf("")
    val liveErrors = mutableStateListOf<MuaalemErrorItem>()
    var showServerUrlModal by mutableStateOf(false)
    var selectedWordIndex by mutableStateOf<Int?>(null)

    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session = 0
    private var prevErrorCount = 0
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    } catch (_: Exception) {
        null
    }

    fun isPerfectRecitation(): Boolean {
        return completedWords >= WORDS.size && liveErrors.isEmpty()
    }

    fun startCycle() {
        counter = target
        goGuided()
    }

    private fun resetSessionState() {
        completedWords = 0
        activeWordIndex = 0
        predictedPhonemes = ""
        referencePhonemes = ""
        liveErrors.clear()
        prevErrorCount = 0
        liveRms = 0f
        elapsedSeconds = 0
        selectedWordIndex = null
    }

    fun goGuided() {
        stopAllStreamingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetSessionState()
        phase = Phase.GUIDED
        status = "اضغط «ابدأ التلاوة الحية» واقرأ الآية؛ سيظهر التدقيق الحرفي والصوتي لحظياً أثناء قراءتك"
    }

    fun goRecite() {
        stopAllStreamingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetSessionState()
        phase = Phase.RECITE
        status = "اضغط «ابدأ التسميع الحي» واقرأ الآية غيباً؛ التدقيق والتوهج يعملان لحظياً مع كل وقفة أو كلمة"
    }

    fun restartAttempt() {
        stopAllStreamingAndAudio()
        h.removeCallbacksAndMessages(null)
        resetSessionState()
        status = "تمت إعادة الضبط — اضغط زر الميكروفون لبدء البث اللحظي"
    }

    fun toggleLiveRecitation() {
        if (isStreaming) {
            finishLiveRecitation()
        } else {
            startLiveRecitation()
        }
    }

    private fun startLiveRecitation() {
        stopAudio()
        if (!RecitationConfig.isNetworkAvailable(ctx)) {
            status = "عذراً، يتطلب الاتصال بمحرك Quran-Muaalem GPU اتصالاً بالإنترنت."
            return
        }
        resetSessionState()
        val mySession = ++session

        val started = LiveAudioStreamer.startLiveStreaming(
            ctx = ctx,
            suraIdx = RecitationConfig.DEFAULT_SURA_IDX,
            ayaIdx = RecitationConfig.DEFAULT_AYA_IDX,
            onAudioProgress = { rms, elapsedMs, localEstWord ->
                if (mySession == session && isStreaming) {
                    liveRms = rms
                    elapsedSeconds = (elapsedMs / 1000L).toInt()
                    activeWordIndex = max(completedWords, localEstWord).coerceIn(0, WORDS.size - 1)
                }
            },
            onLiveDiff = { diff ->
                if (mySession == session) {
                    applyLiveDiff(diff)
                }
            },
            onConnectionState = { connected, msg ->
                if (mySession == session) {
                    wsConnected = connected
                    status = msg
                }
            },
            onError = { errMsg ->
                if (mySession == session) {
                    isStreaming = false
                    status = errMsg
                }
            }
        )

        if (started) {
            isStreaming = true
            status = "🎙 البث الحي نشط — اقرأ الآية وستتلون الحروف والكلمات لحظياً مع كل وقفة أو كلمة"
        }
    }

    private fun finishLiveRecitation() {
        if (!isStreaming) return
        isStreaming = false
        liveRms = 0f
        status = "تم إرسال المقطع الختامي لتحديث التقرير الصوتي النهائي..."
        val mySession = session
        LiveAudioStreamer.stopLiveStreamingAndFlush(
            ctx = ctx,
            suraIdx = RecitationConfig.DEFAULT_SURA_IDX,
            ayaIdx = RecitationConfig.DEFAULT_AYA_IDX,
            onFinalDiff = { diff ->
                if (mySession == session) {
                    applyLiveDiff(diff.copy(isFinal = true))
                }
            }
        )
    }

    private fun applyLiveDiff(diff: LiveRecitationDiff) {
        if (diff.predictedPhonemes.isNotEmpty()) {
            predictedPhonemes = diff.predictedPhonemes
        }
        if (diff.referencePhonemes.isNotEmpty()) {
            referencePhonemes = diff.referencePhonemes
        }
        if (diff.completedWords > completedWords || diff.isFinal) {
            completedWords = if (diff.isFinal && diff.completedWords == 0 && diff.predictedPhonemes.isNotEmpty()) {
                WORDS.size
            } else {
                max(completedWords, diff.completedWords)
            }
        }
        activeWordIndex = completedWords.coerceIn(0, WORDS.size - 1)

        liveErrors.clear()
        liveErrors.addAll(diff.errors)

        if (liveErrors.size > prevErrorCount && phase == Phase.RECITE) {
            try {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
            } catch (_: Exception) {}
        }
        prevErrorCount = liveErrors.size

        if (diff.isFinal || completedWords >= WORDS.size) {
            if (isStreaming) {
                LiveAudioStreamer.cancelStreaming()
                isStreaming = false
            }
            if (phase == Phase.GUIDED) {
                if (liveErrors.isEmpty() && completedWords >= WORDS.size) {
                    counter = (counter - 1).coerceAtLeast(0)
                    if (counter == 0) {
                        status = "ما شاء الله! تلاوة متقنة 100% — ننتقل الآن إلى التسميع…"
                        val my = session
                        h.postDelayed({ if (my == session && phase == Phase.GUIDED) goRecite() }, 1300L)
                    } else {
                        status = "قراءة صحيحة متقنة ✓ — المتبقي $counter"
                    }
                } else {
                    status = "اكتمل التدقيق: رُصدت ${liveErrors.size} ملاحظة في التشكيل/التجويد/الحروف بالأسفل."
                }
            } else if (phase == Phase.RECITE && diff.isFinal) {
                phase = Phase.RESULT
                status = if (liveErrors.isEmpty()) {
                    "تسميع متقن 100% وفق مخرجات Quran-Muaalem QPS ✓"
                } else {
                    "اكتمل التسميع — راجع التقرير الصوتي وبطاقات الأخطاء بالأسفل"
                }
            }
        } else {
            status = "تم تدقيق $completedWords / ${WORDS.size} كلمة لحظياً (${liveErrors.size} ملاحظة)"
        }
    }

    fun toggleAudio() {
        if (playing) {
            stopAudio()
            return
        }
        if (isStreaming) {
            LiveAudioStreamer.cancelStreaming()
            isStreaming = false
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

    private fun stopAllStreamingAndAudio() {
        session++
        LiveAudioStreamer.cancelStreaming()
        isStreaming = false
        wsConnected = false
        stopAudio()
    }

    fun release() {
        h.removeCallbacksAndMessages(null)
        stopAllStreamingAndAudio()
        tone?.release()
    }
}

val Bg = Color(0xFF0F2B2A)
val CardC = Color(0xFF173E3B)
val Gold = Color(0xFFD4AF37)
val Cream = Color(0xFFF5EBD0)
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

/**
 * بطاقة الآية الكريمة مع التلوين الدقيق على مستوى الحرف والحركة (`UthmaniPosHighlighter`)
 * والتوهج الذهبي المتحرك للكلمة النشطة أثناء التلاوة.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerseCard(e: Engine, result: Boolean, onWordTap: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val ys = remember { IntArray(WORDS.size) }
    val tr = rememberInfiniteTransition(label = "glow")
    val glowAlpha by tr.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "glowAlpha"
    )
    val cur = e.activeWordIndex
    LaunchedEffect(cur, e.isStreaming) {
        if (e.isStreaming && cur in WORDS.indices) {
            scroll.animateScrollTo((ys[cur] - 80).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CardC)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(16.dp)
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WORDS.forEachIndexed { i, _ ->
                        val isCur = e.isStreaming && i == cur && !result
                        val hiddenMask = !result && !e.showAll && e.phase == Phase.RECITE
                        val wordStatus = UthmaniPosHighlighter.getWordVisualStatus(
                            wordIndex = i,
                            completedWords = e.completedWords,
                            allErrors = e.liveErrors
                        )

                        val annotatedWord = UthmaniPosHighlighter.buildWordAnnotatedString(
                            wordIndex = i,
                            completedWords = e.completedWords,
                            isCurrentActive = isCur,
                            allErrors = e.liveErrors,
                            hiddenMask = hiddenMask
                        )

                        val borderCol = when (wordStatus) {
                            WordVisualStatus.NORMAL_ERROR -> ColorNormalRed
                            WordVisualStatus.TASHKEEL_ERROR -> ColorTashkeelYellow
                            WordVisualStatus.TAJWEED_ERROR -> ColorTajweedOrange
                            else -> Color.Transparent
                        }

                        val boxMod = when {
                            borderCol != Color.Transparent -> Modifier
                                .background(borderCol.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                                .border(1.dp, borderCol.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            isCur -> Modifier.background(
                                Gold.copy(alpha = 0.14f + 0.22f * glowAlpha),
                                RoundedCornerShape(10.dp)
                            )
                            else -> Modifier
                        }

                        Box(
                            Modifier
                                .onGloballyPositioned { ys[i] = it.positionInParent().y.toInt() }
                                .then(boxMod)
                                .clickable { onWordTap(i) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = annotatedWord,
                                fontSize = 25.sp,
                                lineHeight = 38.sp,
                                fontFamily = Cairo,
                                style = TextStyle(
                                    shadow = if (isCur) Shadow(Gold, Offset.Zero, 8f + 22f * glowAlpha) else null
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardC.copy(alpha = 0.75f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorLegendDot(ColorCorrectGreen, "أخضر: متقن")
            ColorLegendDot(ColorTashkeelYellow, "أصفر: تشكيل")
            ColorLegendDot(ColorTajweedOrange, "برتقالي: تجويد/صفات")
            ColorLegendDot(ColorNormalRed, "أحمر: حروف")
        }
    }
}

@Composable
fun ColorLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, color = Cream, fontSize = 11.sp)
    }
}

/**
 * التقرير التشخيصي اللحظي (Live Diagnostic Report) في أسفل الشاشة أثناء التلاوة:
 * يعرض دائماً:
 * 1) شريط المقارنة الصوتية اللحظي (QPS Phonetic Comparison Bar: `reference_phonemes` مقابل `predicted_phonemes`).
 * 2) بطاقات الأخطاء العربية التفاعلية (`errors`) مع الموضع الدقيق `uthmani_pos` ومقارنة الحركات (`expected_len` مقابل `predicted_len`).
 */
@Composable
fun LiveDiagnosticReportSection(
    e: Engine,
    onSelectErrorWord: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardC)
            .border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📊 التقرير الصوتي اللحظي (Quran Phonetic Script - QPS)",
                color = Gold,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${e.completedWords}/${WORDS.size} كلمة",
                color = ColorCorrectGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. شريط المقارنة الصوتية اللحظي (Live Phonetic Comparison Bar)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Bg.copy(alpha = 0.8f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "التمثيل الصوتي المرجعي (Reference QPS):",
                color = ColorCorrectGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (e.referencePhonemes.isNotEmpty()) e.referencePhonemes
                else "ءَللَاااهُ لَاااا ءِلَاااهَ ءِللَااا هُوَ لحَييُ لقَييُۥۥمُ ...",
                color = Cream,
                fontSize = 13.sp,
                fontFamily = Cairo
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "النطق الفعلي المرصود لحظياً (Predicted QPS):",
                color = Gold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (e.predictedPhonemes.isNotEmpty()) e.predictedPhonemes
                else "سيظهر التمثيل الصوتي الدقيق لتلاوتك هنا فور نطقك...",
                color = if (e.predictedPhonemes.isNotEmpty()) Cream else Cream.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontFamily = Cairo
            )
        }

        // 2. بطاقات التشخيص التفاعلية اللحظية (Interactive Arabic Error Cards)
        if (e.liveErrors.isEmpty()) {
            val msg = if (e.completedWords > 0) {
                "✔ جميع الحروف والحركات والصفات العشر في الكلمات المقروءة (${e.completedWords}) سليمة ومطابقة."
            } else {
                "لا توجد أخطاء مرصودة — ابدأ التلاوة ليتم فحص الحروف والتشكيل والصفات العشر لحظياً."
            }
            Text(
                msg,
                color = ColorCorrectGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                "الملاحظات المرصودة (${e.liveErrors.size}) — اضغط على البطاقة لتفاصيل الموضع:",
                color = ColorTashkeelYellow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            e.liveErrors.forEach { err ->
                val accent = UthmaniPosHighlighter.colorForErrorType(err.errorType)
                val badge = UthmaniPosHighlighter.arabicBadgeForErrorType(err.errorType, err.speechErrorType)
                val highlightedWord = UthmaniPosHighlighter.buildWordAnnotatedString(
                    wordIndex = err.wordIndex,
                    completedWords = WORDS.size,
                    isCurrentActive = false,
                    allErrors = listOf(err),
                    hiddenMask = false
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg.copy(alpha = 0.85f))
                        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .clickable { onSelectErrorWord(err.wordIndex) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = highlightedWord,
                            fontSize = 22.sp,
                            fontFamily = Cairo,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.copy(alpha = 0.2f))
                                .border(1.dp, accent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(badge, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        "الموضع العثماني: [${err.startChar}..${err.endChar}] | المتوقع صوتياً: «${err.expectedPh}» ← المرصود: «${err.predictedPh}»",
                        color = Cream.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )

                    if (err.errorType == "tajweed" || err.expectedLen != err.predictedLen) {
                        Text(
                            "الميزان الزمني (بالحركات): المتوقع ${err.expectedLen} حركات | المرصود ${err.predictedLen} حركات",
                            color = ColorTajweedOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        err.correctionTip,
                        color = Cream,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

/**
 * نافذة إدخال عنوان خادم Modal (🌐 Server URL) بدون أي مفاتيح API.
 */
@Composable
fun ServerUrlDialog(
    ctx: Context,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf(RecitationConfig.getServerBaseUrl(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardC,
        titleContentColor = Gold,
        textContentColor = Cream,
        title = {
            Text(
                "🌐 رابط خادم Quran-Muaalem (Modal URL)",
                color = Gold,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "الصق رابط `.modal.run` الخاص بخادم `quran-muaalem` (v0.2.2) مرة واحدة فقط. لا حاجة لأي مفاتيح API.",
                    color = Cream.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Server Base URL (.modal.run)", color = Gold) },
                    placeholder = { Text(RecitationConfig.BACKEND_BASE_URL, color = Cream.copy(alpha = 0.45f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Cream,
                        unfocusedTextColor = Cream,
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Cream.copy(alpha = 0.5f),
                        cursorColor = Gold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { urlInput = RecitationConfig.BACKEND_BASE_URL }) {
                    Text("استعادة الرابط الافتراضي", color = Gold, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    RecitationConfig.saveServerBaseUrl(ctx, urlInput)
                    onSaved(RecitationConfig.getServerBaseUrl(ctx))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
            ) {
                Text("حفظ الرابط", fontWeight = FontWeight.Bold)
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
fun WordDetailModal(
    wordIndex: Int,
    errors: List<MuaalemErrorItem>,
    onDismiss: () -> Unit
) {
    val wordErrs = UthmaniPosHighlighter.getErrorsForWord(wordIndex, errors)
    val highlighted = UthmaniPosHighlighter.buildWordAnnotatedString(
        wordIndex = wordIndex,
        completedWords = WORDS.size,
        isCurrentActive = false,
        allErrors = wordErrs,
        hiddenMask = false
    )
    val tip = TIPS[wordIndex] ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardC,
        titleContentColor = Gold,
        textContentColor = Cream,
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = highlighted, fontSize = 32.sp, fontFamily = Cairo, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "الكلمة رقم ${wordIndex + 1} من آية الكرسي",
                    color = Cream.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (wordErrs.isEmpty()) {
                    Text(
                        "✔ لم يُرصد أي خطأ في حروف هذه الكلمة أو تشكيلها أو صفاتها التجويدية.",
                        color = ColorCorrectGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    wordErrs.forEach { err ->
                        val col = UthmaniPosHighlighter.colorForErrorType(err.errorType)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Bg.copy(alpha = 0.8f))
                                .border(1.dp, col, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                UthmaniPosHighlighter.arabicBadgeForErrorType(err.errorType, err.speechErrorType),
                                color = col,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "المتوقع (QPS): «${err.expectedPh}» (${err.expectedLen} حركات)",
                                color = ColorCorrectGreen,
                                fontSize = 13.sp
                            )
                            Text(
                                "المرصود (QPS): «${err.predictedPh}» (${err.predictedLen} حركات)",
                                color = col,
                                fontSize = 13.sp
                            )
                            Text(err.correctionTip, color = Cream, fontSize = 13.sp)
                        }
                    }
                }
                if (tip.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Bg.copy(alpha = 0.6f))
                            .padding(10.dp)
                    ) {
                        Text("الحكم التجويدي والتشكيلي المرجعي:", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(tip, color = Cream, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)
            ) {
                Text("حسناً", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val e = remember { Engine(ctx) }
    DisposableEffect(Unit) { onDispose { e.release() } }

    var pendingStartCycle by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingStartCycle) {
                pendingStartCycle = false
                e.startCycle()
            } else {
                e.toggleLiveRecitation()
            }
        } else {
            e.status = "صلاحية الميكروفون مطلوبة للبث الصوتي اللحظي."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // الشريط العلوي مع أيقونة (🌐 Server URL) بدون أي شاشات لمفاتيح API
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("رَفِيق", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Cairo)
                Text(
                    "Quran-Muaalem v0.2.2 • Multilevel CTC + QPS",
                    color = Cream.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
            OutlinedButton(
                onClick = { e.showServerUrlModal = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
            ) {
                Text("🌐 Server URL", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))

        when (e.phase) {
            Phase.SETUP -> {
                Text(
                    "عدد مرات التكرار المطلوبة للقراءة الصحيحة",
                    color = Cream,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    RoundBtn("+") { e.target++ }
                    Text("${e.target}", color = Gold, fontSize = 50.sp, fontWeight = FontWeight.Bold)
                    RoundBtn("−") { if (e.target > 1) e.target-- }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(3, 5, 10).forEach { n ->
                        OutlinedButton(onClick = { e.target = n }) { Text("$n", color = Cream) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Switch(checked = e.showAll, onCheckedChange = { e.showAll = it })
                    Text("إظهار الآية كاملة أثناء التسميع", color = Cream, fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            e.startCycle()
                        } else {
                            pendingStartCycle = true
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("ابدأ الدورة", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                VerseCard(e = e, result = false) { wi -> e.selectedWordIndex = wi }
                Spacer(Modifier.height(14.dp))
                LiveDiagnosticReportSection(e = e) { wi -> e.selectedWordIndex = wi }
            }

            Phase.GUIDED, Phase.RECITE, Phase.RESULT -> {
                val isResult = e.phase == Phase.RESULT
                val modeTitle = when (e.phase) {
                    Phase.GUIDED -> "القراءة الموجَّهة الحية (المتبقي: ${e.counter})"
                    Phase.RECITE -> "التسميع الحي المباشر (بدون شاشات انتظار)"
                    else -> if (e.isPerfectRecitation()) "✔ تسميع متقن 100%" else "نتيجة التدقيق الصوتي"
                }
                Text(modeTitle, color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    e.status,
                    color = Cream.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))

                VerseCard(e = e, result = isResult) { wi -> e.selectedWordIndex = wi }
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                e.toggleLiveRecitation()
                            } else {
                                pendingStartCycle = false
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (e.isStreaming) ColorNormalRed else Gold,
                            contentColor = if (e.isStreaming) Cream else Bg
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        val txt = if (e.isStreaming) {
                            "⏹ إيقاف البث الحي (${e.elapsedSeconds} ث)"
                        } else {
                            "🎙 ابدأ التلاوة والبث الحي"
                        }
                        Text(txt, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { e.restartAttempt() },
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text("إعادة", color = Cream)
                    }
                }

                if (e.phase == Phase.GUIDED) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { e.toggleAudio() },
                            colors = ButtonDefaults.buttonColors(containerColor = CardC, contentColor = Gold)
                        ) {
                            Text(if (e.playing) "إيقاف صوت الشيخ" else "🎧 استمع للشيخ المنشاوي", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { e.goRecite() }) {
                            Text("الانتقال للتسميع", color = Gold, fontSize = 13.sp)
                        }
                    }
                } else if (e.phase == Phase.RESULT) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                Spacer(Modifier.height(14.dp))
                // التقرير الصوتي والبطاقات التفاعلية معروضة دائماً أسفل الشاشة أثناء التلاوة
                LiveDiagnosticReportSection(e = e) { wi -> e.selectedWordIndex = wi }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    e.selectedWordIndex?.let { wi ->
        WordDetailModal(
            wordIndex = wi,
            errors = e.liveErrors,
            onDismiss = { e.selectedWordIndex = null }
        )
    }

    if (e.showServerUrlModal) {
        ServerUrlDialog(
            ctx = ctx,
            onDismiss = { e.showServerUrlModal = false },
            onSaved = { savedUrl ->
                e.showServerUrlModal = false
                e.status = "تم حفظ عنوان الخادم: $savedUrl"
            }
        )
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Gold)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Bg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}
