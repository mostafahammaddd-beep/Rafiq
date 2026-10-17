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

// عدم التسامح مع أي كلمة خاطئة سواء في القراءة الموجهة أو التسميع أو الفحص الدقيق (القرآن أمانة)
const val GUIDED_TOLERANCE = 0
const val PRECISE_TOLERANCE = 0

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

// تطبيع الكلمة المفردة مع الحفاظ على بنية الحروف والهمزات بدقة، وتوحيد الرسم العثماني والإملائي الصحيح فقط
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
            'ء' -> { }
            else -> if (c in 0x0621..0x064A) sb.append(ch)
        }
    }
    return when (val res = sb.toString()) {
        "السموات" -> "السماوات"
        "الاه" -> "اله"
        "يووده", "ييوده", "ياوده" -> "يوده"
        "سنت" -> "سنه"
        else -> res
    }
}

fun norm(s: String): String {
    val tokens = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return normRawWord(s)
    val joined = tokens.joinToString("") { normRawWord(it) }
    return joined
        .replace("السموات", "السماوات")
        .replace("سنتولا", "سنهولا")
        .replace("يووده", "يوده")
        .replace("ييوده", "يوده")
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

/**
 * استخراج بصمة الحركات (التشكيل) إن وُجدت في مخرجات المعرِّف الصوتي،
 * لرصد أخطاء التشكيل فورياً حتى في الوضع المحلي متى ما أعاد المعرِّفُ الحركات.
 */
object TashkeelAndMakhrajChecker {
    private fun extractHarakat(wordWithDiacritics: String): List<Pair<Char, String>> {
        val out = ArrayList<Pair<Char, String>>()
        var curCons: Char? = null
        var curVowel: String? = null
        for (ch in wordWithDiacritics) {
            val v = when (ch) {
                'َ', 'ً' -> "الفتحة"
                'ُ', 'ٌ' -> "الضمة"
                'ِ', 'ٍ' -> "الكسرة"
                'ْ' -> "السكون"
                else -> null
            }
            if (v != null) {
                curVowel = v
            } else {
                val c = ch.code
                if (c in 0x064B..0x065F || c == 0x0670 || c == 0x0640 || c in 0x06D6..0x06ED) continue
                if (curCons != null && curVowel != null) {
                    val nc = normRawWord(curCons.toString()).firstOrNull() ?: curCons
                    out.add(Pair(nc, curVowel))
                }
                curCons = ch
                curVowel = null
            }
        }
        if (curCons != null && curVowel != null) {
            val nc = normRawWord(curCons.toString()).firstOrNull() ?: curCons
            out.add(Pair(nc, curVowel))
        }
        return out
    }

    fun checkDiacriticConflict(rawHeardWord: String, wordIdx: Int): String? {
        if (wordIdx !in WORDS.indices) return null
        val heardSig = extractHarakat(rawHeardWord)
        if (heardSig.isEmpty()) return null
        val refSig = extractHarakat(WORDS[wordIdx])
        if (refSig.isEmpty()) return null
        val m = minOf(heardSig.size, refSig.size)
        for (k in 0 until m) {
            val (hc, hv) = heardSig[k]
            val (rc, rv) = refSig[k]
            if (hc == rc && hv != rv) {
                if (k == refSig.size - 1 && hv == "السكون") continue // الوقف بالسكون جائز
                return "خطأ في التشكيل: نطقت «$hv» على حرف «$hc» والصواب «$rv» في كلمة «${WORDS[wordIdx]}»."
            }
        }
        return null
    }

    /** تشخيص تفصيلي لنوع الخطأ بين الكلمة المتوقعة والكلمة المسموعة (مخرج، صفة، مد، نقص أو زيادة). */
    fun diagnoseOrthographicError(wordIdx: Int, expectedNorm: String, heardNorm: String): String {
        if (heardNorm.isEmpty()) {
            return "لم أسمع هذه الكلمة منك (قد تكون تخطيتَها أثناء التسميع)."
        }
        val msgs = LinkedHashSet<String>()
        val makhrajHints = mapOf(
            Pair('ذ', 'ز') to "إبدال الذال زاياً — احرص على إخراج طرف اللسان في الذال",
            Pair('ذ', 'د') to "إبدال الذال دالاً — احرص على إخراج طرف اللسان ورخاوة الذال",
            Pair('ظ', 'ز') to "إبدال الظاء زاياً — احرص على تفخيم الظاء وإطباقها وإخراج طرف اللسان",
            Pair('ظ', 'ذ') to "ترقيق الظاء فتخرج ذالاً — احرص على تفخيم الظاء وإطباقها",
            Pair('ظ', 'ض') to "خلط مخرج الظاء بالضاد — الظاء يخرج فيها طرف اللسان",
            Pair('ض', 'د') to "إبدال الضاد دالاً — احرص على تفخيم الضاد وإطباقها واستطالتها",
            Pair('ض', 'ز') to "إبدال الضاد زاياً — الضاد تخرج من حافتي اللسان مع الأضراس العليا",
            Pair('ض', 'ظ') to "إبدال الضاد ظاءً — لا تُخرج طرف اللسان في الضاد",
            Pair('ش', 'س') to "إبدال الشين سيناً — احرص على صفة التفشّي في الشين من وسط اللسان",
            Pair('س', 'ص') to "تفخيم السين فتخرج صاداً — السين حرف مستفل مرقّق",
            Pair('ص', 'س') to "ترقيق الصاد فتخرج سيناً — الصاد حرف مستعلٍ مطبق مفخّم",
            Pair('ط', 'ت') to "ترقيق الطاء فتخرج تاءً — احرص على تفخيم الطاء وإطباقها وترك الهمس",
            Pair('ت', 'ط') to "تفخيم التاء فتخرج طاءً — التاء حرف مرقّق مهموس",
            Pair('ق', 'ك') to "إبدال القاف كافاً — القاف مفخّمة من أقصى اللسان مع الحنك اللحمي",
            Pair('ح', 'ه') to "إبدال الحاء هاءً — الحاء من وسط الحلق والهاء من أقصاه",
            Pair('ع', 'ا') to "إبدال العين همزةً — حقّق مخرج العين من وسط الحلق",
            Pair('خ', 'غ') to "خلط الخاء بالغين — الخاء مهموسة والغين مجهورة"
        )

        if (expectedNorm.length == heardNorm.length) {
            for (k in expectedNorm.indices) {
                val ec = expectedNorm[k]
                val hc = heardNorm[k]
                if (ec != hc) {
                    val hint = makhrajHints[Pair(ec, hc)]
                    if (hint != null) {
                        msgs.add("خطأ في مخرج/صفة الحرف: $hint (نطقت «$hc» بدلاً من «$ec»).")
                    } else {
                        msgs.add("خطأ في نطق الحرف: نطقت «$hc» بدلاً من «$ec».")
                    }
                }
            }
        } else if (heardNorm.length < expectedNorm.length) {
            val missingChars = expectedNorm.toMutableList()
            for (c in heardNorm) missingChars.remove(c)
            for (mc in missingChars) {
                if (mc in listOf('ا', 'و', 'ي')) {
                    msgs.add("خطأ في التجويد (المدّ) أو بنية الكلمة: إسقاط أو قصر حرف المدّ «$mc».")
                } else {
                    msgs.add("خطأ في الحروف: إسقاط حرف «$mc» من الكلمة.")
                }
            }
        } else {
            val extraChars = heardNorm.toMutableList()
            for (c in expectedNorm) extraChars.remove(c)
            for (xc in extraChars) {
                msgs.add("خطأ في الحروف: زيادة حرف «$xc» غير موجود في الكلمة.")
            }
        }

        val sb = StringBuilder()
        if (msgs.isNotEmpty()) {
            sb.append(msgs.joinToString("\n• ", prefix = "• ")).append("\n\n")
        }
        sb.append("سمعتُ منك: «").append(heardNorm).append("»\nوالصواب: ").append(WORDS[wordIdx])
        return sb.toString()
    }
}

class AlignResult(
    val st: Array<WS?>,        // OK / WRONG للكلمات المحسومة، و null لغير المحسومة
    val heard: Array<String>,  // ما سُمع فعلاً لكل كلمة (بحروف مجرّدة)
    val notes: Array<String>,  // ملاحظات تشخيصية خاصة بالكلمة (تشكيل / مخرج)
    val last: Int,             // آخر كلمة وصلت إليها المطابقة
    val bad: Int,
    val score: Float
)

/**
 * محرك المطابقة الصارم على مستوى الحروف والكلمات (الإصدار الرابع):
 *  - نسبة التسامح مع الحروف الخاطئة = صفر (allowed = 0f لجميع الكلمات بلا استثناء).
 *  - يدعم نافذة رجوع (Lookback) حتى 4 كلمات سابقة لمنع تلوين الكلمة بالأحمر إذا أعاد القارئ كلمة بعد أخذ النَّفَس.
 *  - يمنع التلوين الأحمر المبكر أثناء الفرضيات الجزئية غير المستقرة (onPartialResults).
 *  - يستخرج الكلمة المسموعة فعلياً بدقة (بما في ذلك الحروف الزائدة أو المبدلة في آخر الكلمة).
 */
object Aligner {
    private const val INF = 1e9f
    private const val SKIP = 0.85f
    private const val MATCH_BONUS = -0.04f
    private const val SUB_COST = 0.98f
    private const val GAP_COST = 1.00f

    fun align(rawText: String, base: Int, isFinal: Boolean, strict: Boolean): AlignResult? {
        val h = norm(rawText)
        val m = h.length
        if (m == 0) return null
        val n = WORDS.size
        val lookbackBase = maxOf(0, base - 4)
        val s0 = W_START[lookbackBase]
        val rl = REF.length - s0
        val w = rl + 1
        val d = FloatArray((m + 1) * w) { INF }
        val bp = ByteArray((m + 1) * w)

        val baseOffset = W_START[base] - s0
        for (wi in lookbackBase until minOf(n, base + 4)) {
            val jStart = W_START[wi] - s0
            d[jStart] = if (wi <= base) (base - wi) * 0.02f else SKIP * (jStart - baseOffset)
        }

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
                        val v = v0 + (if (hi == REF[s0 + j - 1]) MATCH_BONUS else SUB_COST)
                        if (v < best) { best = v; b = 1 }
                    }
                }
                val v1 = d[prev + j]
                if (v1 < INF) {
                    val v = v1 + GAP_COST
                    if (v < best) { best = v; b = 2 }
                }
                if (j > 0) {
                    val v2 = d[row + j - 1]
                    if (v2 < INF) {
                        val v = v2 + GAP_COST
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
            if (v <= bc + 1e-6f) { bc = v; je = j }
        }
        if (bc >= INF) return null

        val cost = FloatArray(n)
        val heardRev = Array(n) { StringBuilder() }
        val skipped = BooleanArray(n)
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
                2 -> {
                    val col = (s0 + j - 1).coerceIn(s0, REF.length - 1)
                    val wi = OWNER[col]
                    cost[wi] += 1f
                    heardRev[wi].append(h[i - 1])
                    i--
                }
                3 -> {
                    val col = s0 + j - 1
                    cost[OWNER[col]] += 1f
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
        if (endCol <= startCol || matched == 0 || matched * 2 < m) return null

        val st = arrayOfNulls<WS>(n)
        val heard = Array(n) { "" }
        val notes = Array(n) { "" }
        val first = OWNER[startCol]
        val last = OWNER[endCol - 1]

        // إذا تخطى القارئ كلمات في بداية الجلسة، لا نوسمها بالخطأ إلا عند الحسم النهائي أو بعد تأكيد كلمتين بعدها
        if (first > base && (isFinal || (last - first >= 1))) {
            for (wi in base until first) st[wi] = WS.WRONG
        }

        // فحص الحركات (التشكيل) في النص الخام إن وُجدت
        val rawTokens = rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val evalStart = maxOf(base, first)
        for (wi in evalStart..last) {
            heard[wi] = heardRev[wi].reverse().toString()
            if (skipped[wi]) {
                if (isFinal || (last - wi >= 2)) st[wi] = WS.WRONG
                continue
            }
            // فحص تعارض التشكيل في التوكن المقابل إن أعاد المعرِّف حركات
            val relIdx = wi - first
            if (relIdx in rawTokens.indices) {
                val tConflict = TashkeelAndMakhrajChecker.checkDiacriticConflict(rawTokens[relIdx], wi)
                if (tConflict != null) {
                    cost[wi] += 1f
                    notes[wi] = tConflict
                }
            }

            // صرامة تامة (allowed = 0f): لا يُتسامح مع أي حرف مبدَّل أو ناقص أو زائد في أي كلمة
            if (wi < last) {
                if (cost[wi] <= 1e-4f) {
                    st[wi] = WS.OK
                } else if (isFinal || (last - wi >= 2)) {
                    st[wi] = WS.WRONG
                }
            } else if (endCol >= W_END[wi]) {
                if (cost[wi] <= 1e-4f) {
                    st[wi] = WS.OK
                } else if (isFinal) {
                    st[wi] = WS.WRONG
                }
            } else {
                // الكلمة الأخيرة لم تكتمل حروفها بعد: عند الحسم النهائي (isFinal) تُعدّ ناقصة/خاطئة
                if (isFinal) st[wi] = WS.WRONG
            }
        }
        var bad = 0
        for (wi in base until n) if (st[wi] == WS.WRONG) bad++
        return AlignResult(st, heard, notes, last, bad, matched.toFloat() - bc)
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

    // ===== الوضع الدقيق: صوت خام ← خادم يحلّل الفونيمات وصفات الحروف (حروف + تشكيل + تجويد) =====
    val notes = Array(WORDS.size) { "" }
    var serverUrl by mutableStateOf(
        ctx.getSharedPreferences("rafiq", Context.MODE_PRIVATE).getString("server", "") ?: ""
    )
    var lenientMadd by mutableStateOf(true)
    var recording by mutableStateOf(false)
    var analyzing by mutableStateOf(false)
    var lastRaw by mutableStateOf("")
    private val recorder = PcmRecorder()
    private var auditId = 0
    val precise: Boolean get() = serverUrl.isNotBlank()

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
        auditId++
        if (recording) { recording = false; recorder.cancel() }
        analyzing = false
        for (i in states.indices) { states[i] = WS.PENDING; heard[i] = ""; notes[i] = ""; beeped[i] = false }
        base = 0; sBase = 0; lastR = null; current = if (precise) -1 else 0
        lastActivity = SystemClock.elapsedRealtime()
    }

    fun goGuided() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt(); phase = Phase.GUIDED
        if (precise) {
            status = "اضغط «ابدأ التسجيل» ثم اقرأ الآية كاملة بصوتك"
        } else {
            status = "اقرأ الآية بصوتك…"; startRec()
        }
    }

    fun goRecite() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt(); phase = Phase.RECITE
        if (precise) {
            status = "اضغط «ابدأ التسجيل» ثم سمِّع الآية غيباً"
        } else {
            status = "سَمِّع الآية غيباً…"; startRec(); tick()
        }
    }

    fun restartAttempt() {
        stopRec(); stopAudio(); h.removeCallbacksAndMessages(null)
        resetAttempt()
        status = if (precise) "اضغط «ابدأ التسجيل» ثم اقرأ الآية كاملة" else "اقرأ الآية بصوتك…"
        if (phase == Phase.GUIDED && !precise) startRec()
    }

    fun finish() {
        stopRec(); h.removeCallbacksAndMessages(null)
        for (i in states.indices) if (states[i] == WS.PENDING) states[i] = WS.WRONG
        current = WORDS.size
        phase = Phase.RESULT
    }

    fun explain(i: Int): String {
        val sb = StringBuilder()
        if (notes[i].isNotEmpty()) {
            sb.append(notes[i])
        } else if (states[i] == WS.HINT) {
            sb.append("نسيتَ هذه الكلمة فلُقِّنتَها. أعد حفظ موضعها جيداً.")
        } else {
            sb.append(TashkeelAndMakhrajChecker.diagnoseOrthographicError(i, NW[i], heard[i]))
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
                val wait = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) 400L else 80L
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L)
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
        // نعتمد الفرضية الصوتية الأولى (الأقرب لما نطقه المستخدم فعلاً) حتى لا يخفي النموذج اللغوي أخطاء النطق
        val primary = Aligner.align(raw[0], sBase, isFinal, strict)
        val r = primary ?: raw.drop(1).firstNotNullOfOrNull { Aligner.align(it, sBase, isFinal, strict) }
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
            if (s != null) {
                heard[i] = r.heard[i]
                notes[i] = r.notes[i]
            }
        }
        if (beep) { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (e: Exception) { } }
        refreshCurrent()
        if (isFinal) {
            commit(r)
        } else {
            lastR = r
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
        if (bad <= (if (precise) PRECISE_TOLERANCE else GUIDED_TOLERANCE)) {
            counter = (counter - 1).coerceAtLeast(0)
            if (counter == 0) {
                status = "أحسنت! ننتقل إلى التسميع…"
                h.postDelayed({ if (my == session && phase == Phase.GUIDED) goRecite() }, 900)
            } else {
                status = "قراءة صحيحة ✓ — المتبقي $counter"
                again(1300L)
            }
        } else {
            if (precise) {
                status = "لم تُحتسب القراءة: $bad كلمة بها خطأ (بالأحمر). اضغط على الكلمة الحمراء لمعرفة الخطأ، ثم أعد التسجيل."
            } else {
                status = "لم تُحتسب القراءة: $bad كلمة بها خطأ (بالأحمر). اضغط على الكلمة الحمراء لمعرفة السبب، أو أعد القراءة."
                again(3600L)
            }
        }
    }

    private fun again(ms: Long) {
        val my = session
        h.postDelayed({
            if (my == session && phase == Phase.GUIDED) {
                resetAttempt()
                if (precise) {
                    status = "اضغط «ابدأ التسجيل» للقراءة التالية"
                } else {
                    status = "اقرأ الآية بصوتك…"; startRec()
                }
            }
        }, ms)
    }

    fun saveServer(u: String) {
        serverUrl = u.trim()
        ctx.getSharedPreferences("rafiq", Context.MODE_PRIVATE).edit().putString("server", serverUrl).apply()
    }

    /** بدء/إنهاء تسجيل الصوت الخام ثم إرساله للفحص الدقيق. */
    fun toggleRecord() {
        if (analyzing || !precise) return
        if (!recording) {
            stopAudio()
            resetAttempt()
            if (recorder.start()) {
                recording = true
                status = "جارٍ التسجيل… اقرأ بصوت واضح ثم اضغط «أنهِ وافحص»"
            } else {
                status = "تعذّر تشغيل الميكروفون"
            }
        } else {
            recording = false
            val pcm = recorder.stop()
            if (pcm.size < RemoteAuditor.SAMPLE_RATE * 2 * 2) {
                status = "التسجيل قصير جداً، أعد المحاولة"
                return
            }
            analyzing = true
            status = "جارٍ الفحص الدقيق… قد يستغرق عدة ثوانٍ"
            val id = ++auditId
            val url = serverUrl
            val len = lenientMadd
            Thread {
                val res = RemoteAuditor.audit(url, pcm, 2, 255, WORDS.size, len)
                h.post { applyAudit(res, id) }
            }.start()
        }
    }

    private fun applyAudit(res: RemoteAuditor.AuditResult, id: Int) {
        if (id != auditId) return
        analyzing = false
        lastRaw = res.raw
        if (res.error != null) {
            status = res.error + " — اضغط «ابدأ التسجيل» للمحاولة من جديد."
            return
        }
        for (i in WORDS.indices) {
            val w = res.words[i]
            states[i] = if (w.ok) WS.OK else WS.WRONG
            notes[i] = w.note
            heard[i] = w.heard
        }
        if (phase == Phase.RECITE) finish() else if (phase == Phase.GUIDED) attemptDone()
    }

    fun toggleAudio() {
        if (playing) { stopAudio(); if (phase == Phase.GUIDED && !precise) startRec(); return }
        stopRec(); playing = true
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(AUDIO_URL)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopAudio(); if (phase == Phase.GUIDED && !precise) startRec() }
            setOnErrorListener { _, _, _ ->
                stopAudio(); status = "تعذّر تشغيل الصوت (تحقق من الإنترنت)"
                if (phase == Phase.GUIDED && !precise) startRec()
                true
            }
            prepareAsync()
        }
    }

    private fun stopAudio() { player?.release(); player = null; playing = false }
    private fun stopRec() { session++; rec?.destroy(); rec = null }
    fun release() { h.removeCallbacksAndMessages(null); stopRec(); stopAudio(); recorder.cancel(); tone?.release() }
}

val Bg = Color(0xFF0F2B2A); val CardC = Color(0xFF173E3B); val Gold = Color(0xFFD4AF37)
val Cream = Color(0xFFF5EBD0); val Red = Color(0xFFEF4444); val Green = Color(0xFF4ADE80); val Orange = Color(0xFFFFA94D)
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
                        val tap = if (s == WS.WRONG || s == WS.HINT) {
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
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = e.serverUrl,
                    onValueChange = { e.saveServer(it) },
                    label = { Text("عنوان خادم الفحص الدقيق", color = Cream) },
                    placeholder = { Text("https://example.hf.space", color = Cream.copy(alpha = 0.4f)) },
                    textStyle = TextStyle(color = Cream, fontSize = 15.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (e.precise) "الوضع: فحص دقيق للحروف والتشكيل والتجويد وصفات الحروف والوقف والابتداء"
                    else "الوضع: فحص نصي صارم للحروف — لرصد التشكيل وأحكام التجويد الدقيقة أدخل عنوان خادم الفحص الدقيق.",
                    color = if (e.precise) Green else Orange,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                if (e.precise) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(checked = e.lenientMadd, onCheckedChange = { e.lenientMadd = it })
                        Text("قبول الأطوال المعتبرة للمدود الفرعية في حفص (منفصل ٢–٥، متصل ٤–٦، عارض ٢–٦)", color = Cream, fontSize = 13.sp)
                    }
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
                VerseCard(e, false) { dialog = e.explain(it) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { e.toggleAudio() }, enabled = !e.recording && !e.analyzing, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Bg)) {
                        Text(if (e.playing) "إيقاف الصوت" else "استمع للشيخ المنشاوي")
                    }
                    OutlinedButton(onClick = { e.restartAttempt() }) { Text("ابدأ من أول الآية", color = Cream) }
                }
                if (e.precise) {
                    Spacer(Modifier.height(10.dp))
                    RecordButton(e)
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
                    Text(if (e.precise) e.status else "التسميع الذكي — بدون لمس الهاتف", color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center)
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
                    if (e.precise) {
                        RecordButton(e)
                    } else {
                        Button(onClick = { e.finish() }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("إيقاف التسجيل") }
                    }
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
        if (e.precise && e.phase == Phase.RESULT) {
            Spacer(Modifier.height(14.dp))
            Text(
                "الفحص آلي وقد يخطئ نادراً، ولا يغني عن العرض على معلّم مُجاز.",
                color = Cream.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center
            )
            if (e.lastRaw.isNotEmpty()) {
                TextButton(onClick = { dialog = e.lastRaw.take(3500) }) {
                    Text("عرض الرد الخام (للتشخيص)", color = Cream.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    dialog?.let {
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("حسناً") } },
            title = { Text("تفصيل الخطأ والحكم التجويدي") },
            text = { Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(it) } }
        )
    }
}

@Composable
fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Gold).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = Bg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RecordButton(e: Engine) {
    Button(
        onClick = { e.toggleRecord() },
        enabled = !e.analyzing,
        colors = ButtonDefaults.buttonColors(containerColor = if (e.recording) Red else Green, contentColor = Bg),
        modifier = Modifier.height(50.dp)
    ) {
        Text(
            if (e.recording) "أنهِ وافحص" else if (e.analyzing) "جارٍ الفحص…" else "ابدأ التسجيل",
            fontSize = 17.sp, fontWeight = FontWeight.Bold
        )
    }
}
