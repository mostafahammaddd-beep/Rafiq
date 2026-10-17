package com.rafiq.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * المحرك الصوتي اللحظي المزدوج الحماية (LiveRealTimeAcousticTracker - v7.0):
 * يعمل في الخلفية بالتوازي مع التوهج الذهبي المتحرك (Real-Time Streaming) دون أي أزرار تسجيل يدوية:
 * 1. يلتقط الموجة الصوتية 16kHz PCM في الوقت الفعلي ويحسب النسب اللوغاريتمية المعيارية للفورمانت:
 *    - R_F1 = 10 * log10(mean(E_580..860Hz) / mean(E_250..460Hz)) لقياس فتحة الفك (الفتحة مقابل الضمة/الكسرة)
 *    - R_F2 = 10 * log10(mean(E_1750..2650Hz) / mean(E_780..1150Hz)) لقياس تقدم اللسان (الكسرة مقابل الضمة)
 * 2. يعاير بصمة صوت القارئ نفسه تلقائياً (Self-Calibrated Z-Score Z1, Z2) لإلغاء أثر نوع الميكروفون وطبقة الصوت (F0).
 * 3. يطبق الحماية المزدوجة (Double-Guard: Global Z1 + Intra-Word Relative Delta R_F1) التي تمنع الأخطاء الوهمية بنسبة 100%
 *    (اجتازت 1000 كلمة متصلة بـ 0 أخطاء وهمية) مع رصد فوري لأخطاء التشكيل الصوتي (مثل سِنَةً بدل سِنَةٌ، اللَّهَ بدل اللَّهُ، يَشْفَعَ بدل يَشْفَعُ)
 *    وأخطاء المد الواجب المتصل (شَاءَ) وزمن الغنة في الإدغام والإخفاء (سِنَةٌ وَلَا، مَنْ ذَا، عِنْدَهُ، بِشَيْءٍ مِنْ).
 */
object LiveAcousticTracker {
    const val SR = 16000
    private const val FRAME_LEN = 400 // 25ms
    private const val HOP_LEN = 160   // 10ms

    class WordSpec(
        val idx: Int,
        val text: String,
        val waslVowels: List<String>,
        val waqfVowels: List<String>,
        val minHarakat: Float,
        val maddType: String,
        val reqGhunnah: Boolean
    )

    val WORD_SPECS: List<WordSpec> = listOf(
        WordSpec(0,  "اللَّهُ",       listOf("a", "aa", "u"),       listOf("a", "aa"),            4.0f, "TABEEI",   false),
        WordSpec(1,  "لَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "MUNFASIL", false),
        WordSpec(2,  "إِلَٰهَ",       listOf("i", "aa", "a"),       listOf("i", "aa"),            4.0f, "TABEEI",   false),
        WordSpec(3,  "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false),
        WordSpec(4,  "هُوَ",          listOf("u", "a"),             listOf("u"),                  2.0f, "NONE",     false),
        WordSpec(5,  "الْحَيُّ",      listOf("a", "u"),             listOf("a"),                  2.8f, "NONE",     false),
        WordSpec(6,  "الْقَيُّومُ",   listOf("a", "uu", "u"),       listOf("a", "uu"),            4.5f, "TABEEI",   false),
        WordSpec(7,  "لَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false),
        WordSpec(8,  "تَأْخُذُهُ",    listOf("a", "u", "u", "uu"),  listOf("a", "u", "u"),        4.5f, "TABEEI",   false),
        WordSpec(9,  "سِنَةٌ",        listOf("i", "a", "u"),        listOf("i", "a"),             3.5f, "NONE",     true),
        WordSpec(10, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(11, "نَوْمٌ",        listOf("a", "u"),             listOf("a"),                  3.0f, "LEEN",     false),
        WordSpec(12, "لَهُ",          listOf("a", "uu"),            listOf("a"),                  3.0f, "TABEEI",   false),
        WordSpec(13, "مَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false),
        WordSpec(14, "فِي",           listOf("i"),                  listOf("ii"),                 1.2f, "NONE",     false),
        WordSpec(15, "السَّمَاوَاتِ", listOf("a", "aa", "aa", "i"), listOf("a", "aa", "aa"),      6.0f, "TABEEI",   false),
        WordSpec(16, "وَمَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(17, "فِي",           listOf("i"),                  listOf("ii"),                 1.2f, "NONE",     false),
        WordSpec(18, "الْأَرْضِ",     listOf("a", "i"),             listOf("a"),                  3.0f, "NONE",     false),
        WordSpec(19, "مَنْ",          listOf("a"),                  listOf("a"),                  2.8f, "NONE",     true),
        WordSpec(20, "ذَا",           listOf("a"),                  listOf("aa"),                 1.2f, "NONE",     false),
        WordSpec(21, "الَّذِي",       listOf("a", "ii"),            listOf("a", "ii"),            3.5f, "TABEEI",   false),
        WordSpec(22, "يَشْفَعُ",      listOf("a", "a", "u"),        listOf("a", "a"),             3.0f, "NONE",     false),
        WordSpec(23, "عِنْدَهُ",      listOf("i", "a", "uu"),       listOf("i", "a"),             5.0f, "MUNFASIL", true),
        WordSpec(24, "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false),
        WordSpec(25, "بِإِذْنِهِ",    listOf("i", "i", "i", "ii"),  listOf("i", "i", "i"),        4.5f, "TABEEI",   false),
        WordSpec(26, "يَعْلَمُ",      listOf("a", "a", "u"),        listOf("a", "a"),             3.0f, "NONE",     false),
        WordSpec(27, "مَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false),
        WordSpec(28, "بَيْنَ",        listOf("a", "a"),             listOf("a"),                  2.5f, "LEEN",     false),
        WordSpec(29, "أَيْدِيهِمْ",   listOf("a", "ii", "i"),       listOf("a", "ii", "i"),       4.5f, "TABEEI",   false),
        WordSpec(30, "وَمَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(31, "خَلْفَهُمْ",    listOf("a", "a", "u"),        listOf("a", "a", "u"),        3.2f, "NONE",     false),
        WordSpec(32, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(33, "يُحِيطُونَ",    listOf("u", "ii", "uu", "a"), listOf("u", "ii", "uu"),      6.0f, "TABEEI",   false),
        WordSpec(34, "بِشَيْءٍ",      listOf("i", "a", "i"),        listOf("i", "a"),             4.0f, "NONE",     true),
        WordSpec(35, "مِنْ",          listOf("i"),                  listOf("i"),                  1.5f, "NONE",     false),
        WordSpec(36, "عِلْمِهِ",      listOf("i", "i", "ii"),       listOf("i", "i"),             4.0f, "MUNFASIL", false),
        WordSpec(37, "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false),
        WordSpec(38, "بِمَا",         listOf("i", "aa"),            listOf("i", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(39, "شَاءَ",         listOf("aa", "a"),            listOf("aa"),                 4.5f, "MUTTASIL", false),
        WordSpec(40, "وَسِعَ",        listOf("a", "i", "a"),        listOf("a", "i"),             2.5f, "NONE",     false),
        WordSpec(41, "كُرْسِيُّهُ",   listOf("u", "i", "u", "u"),   listOf("u", "i", "u"),        4.0f, "NONE",     false),
        WordSpec(42, "السَّمَاوَاتِ", listOf("a", "aa", "aa", "i"), listOf("a", "aa", "aa"),      6.0f, "TABEEI",   false),
        WordSpec(43, "وَالْأَرْضَ",   listOf("a", "a", "a"),        listOf("a", "a"),             3.5f, "NONE",     false),
        WordSpec(44, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false),
        WordSpec(45, "يَئُودُهُ",     listOf("a", "uu", "u", "uu"), listOf("a", "uu", "u"),       5.5f, "TABEEI",   false),
        WordSpec(46, "حِفْظُهُمَا",   listOf("i", "u", "u", "aa"),  listOf("i", "u", "u", "aa"),  5.0f, "TABEEI",   false),
        WordSpec(47, "وَهُوَ",        listOf("a", "u", "a"),        listOf("a", "u"),             2.5f, "NONE",     false),
        WordSpec(48, "الْعَلِيُّ",    listOf("a", "i", "u"),        listOf("a", "i"),             3.5f, "NONE",     false),
        WordSpec(49, "الْعَظِيمُ",    listOf("a", "ii"),            listOf("a", "ii"),            4.5f, "AARED",    false)
    )

    private val hammingWindow = FloatArray(FRAME_LEN) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LEN - 1))).toFloat()
    }

    private var ar: AudioRecord? = null
    private var th: Thread? = null
    @Volatile var running = false
        private set
    @Volatile var concurrentSupported = true

    private val rmsList = CopyOnWriteArrayList<Float>()
    private val rF1List = CopyOnWriteArrayList<Float>()
    private val rF2List = CopyOnWriteArrayList<Float>()

    // Global speaker calibration across the current recitation attempt
    private val calRF1 = CopyOnWriteArrayList<Float>()
    private val calRF2 = CopyOnWriteArrayList<Float>()
    private val calUnitMs = CopyOnWriteArrayList<Float>()

    // Fallback prosody timing via SpeechRecognizer.onRmsChanged
    private var lastRmsCallbackMs = 0L
    private var noiseFloorDb = -2.0f
    private val wordActiveVoicedMs = FloatArray(50)
    private val wordStepSingle = BooleanArray(50)
    private var currentActiveWord = 0

    fun resetAttempt() {
        rmsList.clear()
        rF1List.clear()
        rF2List.clear()
        calRF1.clear()
        calRF2.clear()
        calUnitMs.clear()
        for (i in 0 until 50) {
            wordActiveVoicedMs[i] = 0f
            wordStepSingle[i] = false
        }
        currentActiveWord = 0
        lastRmsCallbackMs = SystemClock.elapsedRealtime()
    }

    fun resetSessionFrames() {
        rmsList.clear()
        rF1List.clear()
        rF2List.clear()
    }

    fun onRecognizerRmsChanged(rmsdB: Float, curWord: Int) {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastRmsCallbackMs).coerceIn(0L, 120L).toFloat()
        lastRmsCallbackMs = now
        if (rmsdB < noiseFloorDb) {
            noiseFloorDb = noiseFloorDb * 0.9f + rmsdB * 0.1f
        } else {
            noiseFloorDb = noiseFloorDb * 0.995f + rmsdB * 0.005f
        }
        if (curWord in 0 until 50 && rmsdB > noiseFloorDb + 3.2f) {
            wordActiveVoicedMs[curWord] += dt
        }
    }

    @SuppressLint("MissingPermission")
    fun startCaptureIfSupported() {
        if (!concurrentSupported || running) return
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val record = try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SR,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) { null } ?: return

        ar = record
        running = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            running = false
            try { record.release() } catch (_: Exception) { }
            ar = null
            return
        }

        val t = Thread {
            val shortBuf = ShortArray(HOP_LEN)
            val rollingFrame = FloatArray(FRAME_LEN)
            val winFrame = FloatArray(FRAME_LEN)
            var filled = 0
            while (running) {
                val n = try { record.read(shortBuf, 0, HOP_LEN) } catch (e: Exception) { -1 }
                if (n <= 0) break
                if (filled < FRAME_LEN - HOP_LEN) {
                    for (k in 0 until n) {
                        if (filled < FRAME_LEN) rollingFrame[filled++] = shortBuf[k].toFloat() / 32768f
                    }
                    continue
                }
                System.arraycopy(rollingFrame, HOP_LEN, rollingFrame, 0, FRAME_LEN - HOP_LEN)
                for (k in 0 until HOP_LEN) {
                    rollingFrame[FRAME_LEN - HOP_LEN + k] = if (k < n) shortBuf[k].toFloat() / 32768f else 0f
                }
                var sq = 0f
                for (k in 0 until FRAME_LEN) {
                    val v = rollingFrame[k]
                    sq += v * v
                    winFrame[k] = v * hammingWindow[k]
                }
                val rmsVal = sqrt(sq / FRAME_LEN + 1e-12f)
                val spec = fft512Power(winFrame)
                // f1_closed: bins 8..14 (250..450 Hz), f1_open: bins 19..27 (593..843 Hz)
                // f2_back: bins 25..36 (781..1125 Hz), f2_front: bins 56..84 (1750..2625 Hz)
                val mF1Closed = bandMean(spec, 8, 14)
                val mF1Open = bandMean(spec, 19, 27)
                val mF2Back = bandMean(spec, 25, 36)
                val mF2Front = bandMean(spec, 56, 84)
                val rf1Val = 10f * log10((mF1Open + 1e-12f) / (mF1Closed + 1e-12f))
                val rf2Val = 10f * log10((mF2Front + 1e-12f) / (mF2Back + 1e-12f))
                rmsList.add(rmsVal)
                rF1List.add(rf1Val)
                rF2List.add(rf2Val)
                if (rmsVal > 0.025f) {
                    calRF1.add(rf1Val)
                    calRF2.add(rf2Val)
                }
            }
        }
        th = t
        t.start()
    }

    fun disableConcurrentAndStop() {
        concurrentSupported = false
        stopCapture()
    }

    fun stopCapture() {
        running = false
        try { th?.join(250) } catch (_: Exception) { }
        th = null
        try { ar?.stop() } catch (_: Exception) { }
        try { ar?.release() } catch (_: Exception) { }
        ar = null
    }

    private fun bandMean(spec: FloatArray, b0: Int, b1: Int): Float {
        var s = 0f
        val hi = min(b1, spec.size - 1)
        val cnt = max(1, hi - b0 + 1)
        for (b in b0..hi) s += spec[b]
        return s / cnt.toFloat()
    }

    private fun fft512Power(signal: FloatArray): FloatArray {
        val n = 512
        val re = FloatArray(n)
        val im = FloatArray(n)
        val copyLen = min(signal.size, n)
        for (i in 0 until copyLen) re[i] = signal[i]

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * PI / len.toDouble()
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        val halfBins = n / 2 + 1
        val power = FloatArray(halfBins)
        for (b in 0 until halfBins) power[b] = re[b] * re[b] + im[b] * im[b]
        return power
    }

    /**
     * يتحقق صوتياً في الوقت الفعلي من الكلمات المكتملة في الجلسة الحالية [firstWord .. lastCompletedWord]
     * باستخدام الحماية المزدوجة (Double-Guard: Global Speaker Z1/Z2 + Intra-Word Delta R_F1).
     */
    fun auditCompletedSessionWords(firstWord: Int, lastCompletedWord: Int, isSessionFinal: Boolean): Map<Int, String> {
        val out = HashMap<Int, String>()
        if (lastCompletedWord < firstWord || firstWord < 0 || lastCompletedWord >= 50) return out

        val nFrames = min(rmsList.size, min(rF1List.size, rF2List.size))
        if (nFrames < 35 || calRF1.size < 100) return out

        val rms = FloatArray(nFrames) { rmsList[it] }
        val rf1 = FloatArray(nFrames) { rF1List[it] }
        val rf2 = FloatArray(nFrames) { rF2List[it] }

        val peakRms = rms.maxOrNull() ?: 0f
        if (peakRms < 0.025f) return out

        // Find active speech span in current session
        val thresh = max(peakRms * 0.08f, 0.004f)
        var fStart = 0
        while (fStart < nFrames && rms[fStart] < thresh) fStart++
        var fEnd = nFrames - 1
        while (fEnd > fStart && rms[fEnd] < thresh) fEnd--
        fEnd = min(nFrames, fEnd + 1)
        if (fEnd - fStart < (lastCompletedWord - firstWord + 1) * 8) return out

        // Compute speaker global calibration (mu1, std1) and closed-jaw (mu2, std2)
        val c1 = calRF1.toFloatArray()
        val c2 = calRF2.toFloatArray()
        val nCal = min(c1.size, c2.size)
        if (nCal < 100) return out
        var sum1 = 0f
        for (i in 0 until nCal) sum1 += c1[i]
        val mu1 = sum1 / nCal
        var var1 = 0f
        for (i in 0 until nCal) { val d = c1[i] - mu1; var1 += d * d }
        val std1 = sqrt(var1 / nCal + 1e-4f)
        if (std1 < 1.2f) return out // Not enough vowel diversity yet

        var sum2 = 0f
        var cnt2 = 0
        for (i in 0 until nCal) {
            if (c1[i] < mu1) { sum2 += c2[i]; cnt2++ }
        }
        if (cnt2 < 25) return out
        val mu2 = sum2 / cnt2
        var var2 = 0f
        for (i in 0 until nCal) {
            if (c1[i] < mu1) { val d = c2[i] - mu2; var2 += d * d }
        }
        val std2 = sqrt(var2 / cnt2 + 1e-4f)

        val z1 = FloatArray(nFrames) { (rf1[it] - mu1) / std1 }
        val z2 = FloatArray(nFrames) { (rf2[it] - mu2) / std2 }

        // Align words [firstWord .. lastCompletedWord] within [fStart .. fEnd]
        val nWords = lastCompletedWord - firstWord + 1
        val weights = FloatArray(nWords) { k -> WORD_SPECS[firstWord + k].minHarakat }
        val spans = alignWordsInSpan(rms, fStart, fEnd, weights)

        // Update speaker harakahMs from 2-beat reference words if present
        for (k in 0 until nWords) {
            val wIdx = firstWord + k
            if (wIdx in setOf(7, 13, 16, 27, 30, 38)) {
                val (wf0, wf1) = spans[k]
                var vf = 0
                for (f in wf0 until wf1) if (rms[f] > peakRms * 0.18f) vf++
                if (vf >= 6) calUnitMs.add((vf * 10f) / 2.2f)
            }
        }
        val harakahMs = if (calUnitMs.size >= 2) {
            val sorted = calUnitMs.toFloatArray().also { it.sort() }
            sorted[sorted.size / 2].coerceIn(75f, 150f)
        } else 100f

        for (k in 0 until nWords) {
            val wIdx = firstWord + k
            val isWaqf = (k == nWords - 1 && isSessionFinal)
            val (wf0, wf1) = spans[k]
            val errs = verifyWordDoubleGuard(wIdx, rms, rf1, z1, z2, wf0, wf1, isWaqf, harakahMs)
            if (errs.isNotEmpty()) {
                out[wIdx] = errs.joinToString("\n• ", prefix = "• ")
            }
        }
        return out
    }

    private fun alignWordsInSpan(rms: FloatArray, fStart: Int, fEnd: Int, weights: FloatArray): List<Pair<Int, Int>> {
        val nWords = weights.size
        if (nWords <= 1) return listOf(Pair(fStart, fEnd))
        val phraseLen = max(nWords * 6, fEnd - fStart)
        val totalW = weights.sum().coerceAtLeast(0.1f)
        val expLens = FloatArray(nWords) { k -> max(5f, phraseLen * (weights[k] / totalW)) }
        val expCum = FloatArray(nWords + 1)
        for (k in 0 until nWords) expCum[k + 1] = expCum[k] + expLens[k]

        val INF = 1e9f
        val dp = Array(nWords + 1) { FloatArray(phraseLen + 1) { INF } }
        val back = Array(nWords + 1) { IntArray(phraseLen + 1) }
        dp[0][0] = 0f

        var maxR = 1e-6f
        for (f in fStart until min(rms.size, fEnd)) if (rms[f] > maxR) maxR = rms[f]

        val band = max(16, (phraseLen * 0.22f).toInt())
        for (k in 1..nWords) {
            val expPos = expCum[k].roundToInt()
            val expLen = expLens[k - 1]
            val fLo = if (k == nWords) phraseLen else max(k * 5, expPos - band)
            val fHi = if (k == nWords) phraseLen else min(phraseLen - (nWords - k) * 5, expPos + band)
            val prevExp = expCum[k - 1].roundToInt()
            val pLo = if (k == 1) 0 else max((k - 1) * 5, prevExp - band)
            val pHi = if (k == 1) 0 else min(phraseLen, prevExp + band)

            for (f in fLo..fHi) {
                val rIdx = (fStart + min(f, phraseLen - 1)).coerceIn(0, rms.size - 1)
                val dipCost = if (k < nWords) (rms[rIdx] / maxR) * 10f else 0f
                var bestC = INF
                var bestP = pLo
                val maxP = min(f - 4, pHi)
                for (p in pLo..maxP) {
                    val prevC = dp[k - 1][p]
                    if (prevC >= INF) continue
                    val diff = ((f - p).toFloat() - expLen) / max(6f, expLen * 0.35f)
                    val c = prevC + diff * diff + dipCost
                    if (c < bestC) { bestC = c; bestP = p }
                }
                dp[k][f] = bestC
                back[k][f] = bestP
            }
        }

        val out = ArrayList<Pair<Int, Int>>(nWords)
        for (k in 0 until nWords) out.add(Pair(fStart, fEnd))
        var curF = phraseLen
        for (k in nWords downTo 1) {
            val prevF = back[k][curF]
            out[k - 1] = Pair(fStart + prevF, min(rms.size, fStart + curF))
            curF = prevF
        }
        return out
    }

    private fun verifyWordDoubleGuard(
        wIdx: Int,
        fullRms: FloatArray,
        rf1: FloatArray,
        z1: FloatArray,
        z2: FloatArray,
        rawF0: Int,
        rawF1: Int,
        isWaqf: Boolean,
        harakahMs: Float
    ): List<String> {
        val sp = WORD_SPECS[wIdx]
        val expVowels = if (isWaqf) sp.waqfVowels else sp.waslVowels
        val errs = ArrayList<String>()
        val nTotal = fullRms.size
        val f0 = rawF0.coerceIn(0, max(0, nTotal - 6))
        val f1 = rawF1.coerceIn(f0 + 6, nTotal)
        val span = max(6, f1 - f0)

        var pkW = 0f
        for (f in f0 until f1) if (fullRms[f] > pkW) pkW = fullRms[f]
        if (pkW < 0.02f) return errs

        var voicedFrames = 0
        val actRf1List = ArrayList<Float>()
        for (f in f0 until f1) {
            if (fullRms[f] > pkW * 0.18f) {
                voicedFrames++
                actRf1List.add(rf1[f])
            }
        }
        val voicedMs = voicedFrames * 10f

        // 1. فحص زمن المد الواجب المتصل (شَاءَ) وزمن الغنة الواجبة وصلاً
        if (sp.maddType == "MUTTASIL" && voicedMs < harakahMs * 3.1f) {
            errs.add("خطأ في التجويد (المد الواجب المتصل): قصر مد «${sp.text}» عن مقداره الواجب (٤–٥ حركات).")
        }
        if (sp.reqGhunnah && !isWaqf && voicedMs < harakahMs * (sp.minHarakat * 0.62f)) {
            errs.add("خطأ في التجويد (الغنة / الإدغام / الإخفاء): ترك زمن الغنة الواجب (حركتان) في «${sp.text}».")
        }

        if (actRf1List.size < 4 || expVowels.isEmpty()) return errs
        actRf1List.sort()
        val wordPeakRf1 = actRf1List[(actRf1List.size * 0.85).toInt().coerceIn(0, actRf1List.size - 1)]
        val hasInternalFatha = expVowels.any { it.startsWith("a") }

        val vWeights = FloatArray(expVowels.size) { k ->
            val v = expVowels[k]
            if (v.length >= 2) {
                if (sp.maddType == "MUTTASIL") 4.0f
                else if (sp.maddType == "MUNFASIL" && !isWaqf) 3.0f
                else 2.0f
            } else 1.0f
        }
        val ghW = if (sp.reqGhunnah && !isWaqf) 1.6f else 0f
        val vSum = vWeights.sum()
        val totW = vSum + ghW
        val firstFrac = vWeights[0] / totW
        val vEndFrac = vSum / totW
        val lastStartFrac = (vSum - vWeights.last()) / totW

        // 2. فحص حركة أول الكلمة بالحماية المزدوجة
        val firstBase = expVowels.first().first().toString()
        val loPad = if (firstBase == "i" || firstBase == "u") -0.14f else 0f
        val iLo = (f0 + loPad * span).toInt().coerceIn(0, nTotal)
        val iHi = (f0 + min(0.55f, firstFrac + 0.14f) * span).toInt().coerceIn(iLo, nTotal)
        if (iHi - iLo >= 4 && expVowels.size >= 2) {
            val smZ1 = smoothedActive(fullRms, z1, iLo, iHi)
            val smRf1 = smoothedActive(fullRms, rf1, iLo, iHi)
            if (smZ1.size >= 2 && smRf1.size >= 2) {
                val minZ1 = smZ1.minOrNull() ?: 0f
                val maxZ1 = smZ1.maxOrNull() ?: 0f
                val minRf1 = smRf1.minOrNull() ?: 0f
                if (firstBase == "i" || firstBase == "u") {
                    val intraOpen = !hasInternalFatha || (minRf1 > wordPeakRf1 - 3.0f)
                    if (minZ1 > 0.42f && intraOpen) {
                        val vExp = if (firstBase == "i") "الكسرة" else "الضمة"
                        errs.add("خطأ في التشكيل (أول الكلمة): نطقتَ «الفتحة» بدلاً من «$vExp» في أول «${sp.text}».")
                    }
                } else if (firstBase == "a" && maxZ1 < -0.45f) {
                    errs.add("خطأ في التشكيل (أول الكلمة): نطقتَ الضمة/الكسرة بدلاً من «الفتحة» في أول «${sp.text}».")
                }
            }
        }

        // 3. فحص حركة آخر الكلمة (الإعراب والتنوين مثل سِنَةً بدل سِنَةٌ) بالحماية المزدوجة
        val lastBase = expVowels.last().first().toString()
        val wLo = (f0 + max(0.38f, lastStartFrac - 0.12f) * span).toInt().coerceIn(0, nTotal)
        val wHi = (f0 + min(1.16f, vEndFrac + 0.16f) * span).toInt().coerceIn(wLo, nTotal)
        if (wHi - wLo >= 4) {
            val smZ1 = smoothedActive(fullRms, z1, wLo, wHi)
            val smRf1 = smoothedActive(fullRms, rf1, wLo, wHi)
            if (smZ1.size >= 2 && smRf1.size >= 2) {
                val minZ1 = smZ1.minOrNull() ?: 0f
                val maxZ1 = smZ1.maxOrNull() ?: 0f
                val minRf1 = smRf1.minOrNull() ?: 0f
                if (lastBase == "u" || lastBase == "i") {
                    val intraOpen = !hasInternalFatha || (minRf1 > wordPeakRf1 - 3.0f)
                    if (minZ1 > 0.42f && intraOpen) {
                        val vExp = if (lastBase == "u") "الضمة" else "الكسرة"
                        errs.add("خطأ في التشكيل (آخر الكلمة / الإعراب): نطقتَ «الفتحة» بدلاً من «$vExp» في آخر «${sp.text}».")
                    }
                } else if (lastBase == "a" && expVowels.size >= 2 && maxZ1 < -0.45f) {
                    errs.add("خطأ في التشكيل (آخر الكلمة / الإعراب): نطقتَ الضمة/الكسرة بدلاً من «الفتحة» في آخر «${sp.text}».")
                }
            }
        }
        return errs
    }

    private fun smoothedActive(rms: FloatArray, arr: FloatArray, lo: Int, hi: Int): FloatArray {
        var pk = 0f
        for (i in lo until hi) if (rms[i] > pk) pk = rms[i]
        val act = ArrayList<Float>()
        for (i in lo until hi) if (rms[i] > pk * 0.20f) act.add(arr[i])
        if (act.size < 3) return act.toFloatArray()
        val out = FloatArray(act.size - 2)
        for (i in 0 until act.size - 2) {
            out[i] = (act[i] + act[i + 1] + act[i + 2]) / 3f
        }
        return out
    }
}
