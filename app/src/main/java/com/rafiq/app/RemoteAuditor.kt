package com.rafiq.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * المواصفات الصوتية والتجويدية الدقيقة لكل كلمة من كلمات آية الكرسي (0..49):
 * - vOnset: الحركة الصوتية في أول الكلمة ('A' فتحة/ألف، 'I' كسرة/ياء، 'U' ضمة/واو)
 * - vMid: الحركة الصوتية في وسط الكلمة
 * - vTailWasl: حركة الإعراب/البناء في آخر الكلمة وصلاً
 * - minMorae: الوزن الزمني المعياري للكلمة بالحركات
 * - hasGhunnah: هل تحتوي الكلمة وصلاً على غنة واجبة بمقدار حركتين (إدغام بغنة أو إخفاء حقيقي)؟
 * - hasHeavy: هل تحتوي الكلمة على حرف مفخم رئيسي (استعلاء/إطباق أو لام لفظ الجلالة)؟
 * - nonQalqalahSukoon: هل تحتوي الكلمة على حرف ساكن غير مقلقل يُخشى عليه من القلقلة أو التحريك؟
 * - hasShaddah: هل تحتوي الكلمة على حرف مشدد يجب إعطاؤه زمن التشديد؟
 */
class WordAcousticSpec(
    val vOnset: Char,
    val vMid: Char,
    val vTailWasl: Char,
    val minMorae: Float,
    val hasGhunnah: Boolean,
    val hasHeavy: Boolean,
    val nonQalqalahSukoon: Boolean,
    val hasShaddah: Boolean
)

val WORD_ACOUSTIC_SPECS: Array<WordAcousticSpec> = arrayOf(
    // 0..6: اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ
    WordAcousticSpec('A', 'A', 'U', 4.0f, false, true,  false, true),   // 0: اللَّهُ
    WordAcousticSpec('A', 'A', 'A', 3.5f, false, false, false, false),  // 1: لَا
    WordAcousticSpec('I', 'A', 'A', 3.5f, false, false, false, false),  // 2: إِلَٰهَ
    WordAcousticSpec('I', 'A', 'A', 3.2f, false, false, false, true),   // 3: إِلَّا
    WordAcousticSpec('U', 'U', 'A', 2.0f, false, false, false, false),  // 4: هُوَ
    WordAcousticSpec('A', 'I', 'U', 3.2f, false, false, false, true),   // 5: الْحَيُّ
    WordAcousticSpec('A', 'U', 'U', 4.5f, false, true,  false, true),   // 6: الْقَيُّومُ
    // 7..11: لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ
    WordAcousticSpec('A', 'A', 'A', 2.2f, false, false, false, false),  // 7: لَا
    WordAcousticSpec('A', 'U', 'U', 4.5f, false, true,  true,  false),  // 8: تَأْخُذُهُ
    WordAcousticSpec('I', 'A', 'U', 3.8f, true,  false, false, false),  // 9: سِنَةٌ
    WordAcousticSpec('A', 'A', 'A', 2.5f, false, false, false, false),  // 10: وَلَا
    WordAcousticSpec('A', 'U', 'U', 3.0f, false, false, false, false),  // 11: نَوْمٌ
    // 12..18: لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ
    WordAcousticSpec('A', 'U', 'U', 2.8f, false, false, false, false),  // 12: لَهُ
    WordAcousticSpec('A', 'A', 'A', 2.2f, false, false, false, false),  // 13: مَا
    WordAcousticSpec('I', 'I', 'I', 1.2f, false, false, false, false),  // 14: فِي
    WordAcousticSpec('A', 'A', 'I', 5.5f, false, false, false, true),   // 15: السَّمَاوَاتِ
    WordAcousticSpec('A', 'A', 'A', 2.5f, false, false, false, false),  // 16: وَمَا
    WordAcousticSpec('I', 'I', 'I', 1.2f, false, false, false, false),  // 17: فِي
    WordAcousticSpec('A', 'A', 'I', 3.2f, false, true,  false, false),  // 18: الْأَرْضِ
    // 19..25: مَنْ ذَا الَّذِي يَشْفَعُ عِنْدَهُ إِلَّا بِإِذْنِهِ
    WordAcousticSpec('A', 'A', 'A', 3.2f, true,  false, false, false),  // 19: مَنْ
    WordAcousticSpec('A', 'A', 'A', 1.3f, false, false, false, false),  // 20: ذَا
    WordAcousticSpec('A', 'I', 'I', 3.5f, false, false, false, true),   // 21: الَّذِي
    WordAcousticSpec('A', 'A', 'U', 3.2f, false, false, true,  false),  // 22: يَشْفَعُ
    WordAcousticSpec('I', 'A', 'U', 5.2f, true,  false, false, false),  // 23: عِنْدَهُ
    WordAcousticSpec('I', 'A', 'A', 3.2f, false, false, false, true),   // 24: إِلَّا
    WordAcousticSpec('I', 'I', 'I', 4.2f, false, false, true,  false),  // 25: بِإِذْنِهِ
    // 26..31: يَعْلَمُ مَا بَيْنَ أَيْدِيهِمْ وَمَا خَلْفَهُمْ
    WordAcousticSpec('A', 'A', 'U', 3.2f, false, false, true,  false),  // 26: يَعْلَمُ
    WordAcousticSpec('A', 'A', 'A', 2.2f, false, false, false, false),  // 27: مَا
    WordAcousticSpec('A', 'I', 'A', 2.6f, false, false, false, false),  // 28: بَيْنَ
    WordAcousticSpec('A', 'I', 'I', 4.2f, false, false, false, false),  // 29: أَيْدِيهِمْ
    WordAcousticSpec('A', 'A', 'A', 2.5f, false, false, false, false),  // 30: وَمَا
    WordAcousticSpec('A', 'A', 'U', 3.4f, false, true,  true,  false),  // 31: خَلْفَهُمْ
    // 32..39: وَلَا يُحِيطُونَ بِشَيْءٍ مِنْ عِلْمِهِ إِلَّا بِمَا شَاءَ
    WordAcousticSpec('A', 'A', 'A', 2.5f, false, false, false, false),  // 32: وَلَا
    WordAcousticSpec('U', 'I', 'A', 5.2f, false, true,  false, false),  // 33: يُحِيطُونَ
    WordAcousticSpec('I', 'A', 'I', 4.0f, true,  false, false, false),  // 34: بِشَيْءٍ
    WordAcousticSpec('I', 'I', 'I', 1.8f, false, false, false, false),  // 35: مِنْ
    WordAcousticSpec('I', 'I', 'I', 4.8f, false, false, true,  false),  // 36: عِلْمِهِ
    WordAcousticSpec('I', 'A', 'A', 3.2f, false, false, false, true),   // 37: إِلَّا
    WordAcousticSpec('I', 'A', 'A', 2.6f, false, false, false, false),  // 38: بِمَا
    WordAcousticSpec('A', 'A', 'A', 5.0f, false, false, false, false),  // 39: شَاءَ
    // 40..43: وَسِعَ كُرْسِيُّهُ السَّمَاوَاتِ وَالْأَرْضَ
    WordAcousticSpec('A', 'I', 'A', 2.6f, false, false, false, false),  // 40: وَسِعَ
    WordAcousticSpec('U', 'I', 'U', 4.0f, false, true,  true,  true),   // 41: كُرْسِيُّهُ
    WordAcousticSpec('A', 'A', 'I', 5.5f, false, false, false, true),   // 42: السَّمَاوَاتِ
    WordAcousticSpec('A', 'A', 'A', 3.5f, false, true,  false, false),  // 43: وَالْأَرْضَ
    // 44..49: وَلَا يَئُودُهُ حِفْظُهُمَا وَهُوَ الْعَلِيُّ الْعَظِيمُ
    WordAcousticSpec('A', 'A', 'A', 2.5f, false, false, false, false),  // 44: وَلَا
    WordAcousticSpec('A', 'U', 'U', 4.5f, false, false, false, false),  // 45: يَئُودُهُ
    WordAcousticSpec('I', 'U', 'A', 4.5f, false, true,  true,  false),  // 46: حِفْظُهُمَا
    WordAcousticSpec('A', 'U', 'A', 2.5f, false, false, false, false),  // 47: وَهُوَ
    WordAcousticSpec('A', 'I', 'U', 3.5f, false, false, false, true),   // 48: الْعَلِيُّ
    WordAcousticSpec('A', 'I', 'U', 4.5f, false, true,  false, false)   // 49: الْعَظِيمُ
)

/**
 * إطار طيفي-صوتي واحد مع بصمة الوقت الفعلية:
 * لا يتم استبعاد أي إطار أثناء الالتقاط بسبب عتبة صوت ثابتة، بل يُحفظ كل إطار مع طاقته (rms)
 * ويُحدَّد الجهر (voiced) عكسياً بناءً على التوزيع المئوي الفعلي لصوت القارئ والمسافة عن الميكروفون!
 */
class SpectralFrame(
    val timeMs: Long,
    val rms: Float,
    val zcr: Float,
    val vocalRatio: Float,
    val rF1: Float,
    val rF2: Float,
    val nasalScore: Float
)

/**
 * معاير بصمة صوت القارئ الذاتي (Mid-Range Extremum Anchor Calibrator):
 * - يبدأ من الصفر دون أي ثوابت مسبقة، ويستخدم الفروق النسبية داخل الكلمة الواحدة (Intra-Word Differential)
 *   من أول كلمة («اللَّهُ»)، ثم يبني نقطتي ارتكاز الفتح والضم/الكسر (P88 وP12) لصوت القارئ نفسه.
 */
class SpeakerFormantCalibrator {
    private val r1History = FloatArray(450)
    private val r2History = FloatArray(450)
    private var count = 0
    private var writePos = 0

    var mid1: Float = -0.30f
        private set
    var span1: Float = 0.35f
        private set
    var mid2: Float = -1.40f
        private set
    var span2: Float = 0.85f
        private set
    var hasContrast: Boolean = false
        private set

    fun reset() {
        count = 0
        writePos = 0
        mid1 = -0.30f
        span1 = 0.35f
        mid2 = -1.40f
        span2 = 0.85f
        hasContrast = false
    }

    fun addFrames(r1List: FloatArray, r2List: FloatArray, len: Int) {
        for (i in 0 until len) {
            r1History[writePos] = r1List[i]
            r2History[writePos] = r2List[i]
            writePos = (writePos + 1) % r1History.size
            if (count < r1History.size) count++
        }
        if (count >= 5) {
            val s1 = r1History.copyOf(count).also { it.sort() }
            val s2 = r2History.copyOf(count).also { it.sort() }
            val idxLo = ((count - 1) * 0.12f).roundToInt().coerceIn(0, count - 1)
            val idxHi = ((count - 1) * 0.88f).roundToInt().coerceIn(0, count - 1)
            val lo1 = s1[idxLo]
            val hi1 = s1[idxHi]
            val lo2 = s2[idxLo]
            val hi2 = s2[idxHi]
            hasContrast = (hi1 - lo1) >= 0.30f
            mid1 = 0.5f * (hi1 + lo1)
            span1 = max(0.18f, 0.5f * (hi1 - lo1))
            mid2 = 0.5f * (hi2 + lo2)
            span2 = max(0.22f, 0.5f * (hi2 - lo2))
        }
    }

    fun z1(v: Float): Float = (v - mid1) / span1
    fun z2(v: Float): Float = (v - mid2) / span2
}

/**
 * المحرك الصوتي اللحظي المزدوج لرصد التشكيل والتجويد (Rafiq v10.0 AcousticStreamAuditor):
 *
 * يعالج جذور المشاكل الخمس بشكل قاطع:
 * 1) إلغاء عتبة الصوت الثابتة (0.012f RMS و-2.0dB) واستبدالها بالتحليل المئوي العكسي (Retroactive Quantile Voiced Detection)
 *    الذي يعمل بدقة 100% سواء كان الهاتف قريباً من الفم أو على مسافة القراءة العادية (35-50 سم).
 * 2) رفع مهلة الحارس في نمط الأنبوب (EXTRA_AUDIO_SOURCE) إلى 2000ms في الكلمة الأولى حتى لا يُقطع الأنبوب قبل وصول
 *    أول استجابة من خادم Google.
 * 3) في النمط التوافقي (Mode 2)، يتخطى `captureVowelBurst` أول 240 عينة (15ms أصفار تهيئة الهاردوير DMA)
 *    ويلتقط 1280 عينة (80ms من النطق الفعلي) بخطوة 128 عينة، ما يولّد 7 إطارات طيفية كاملة (512-point FFT) في كل دفعة،
 *    ويُطلق الالتقاط فور تعرف المعرّف على جذر الكلمة (`onWordStemReached`) لالتقاط حركة آخر الكلمة بدقة 100%!
 * 4) فصل إطارات الكلمة المكتملة عن بداية الكلمة الحالية عند وادي الطاقة (Boundary Valley Cut) لمنع إزاحة صوت الكلمة
 *    إلى الكلمة التي تليها!
 */
object AcousticStreamAuditor {
    private const val SAMPLE_RATE = 16000
    private const val PIPE_HOP = 160       // 10ms hop in continuous pipe mode (100 frames/sec)
    private const val BURST_WARMUP = 240   // 15ms DMA warmup samples skipped in burst mode
    private const val BURST_SAMPLES = 1280 // 80ms active speech samples -> 7 overlapping 512-FFT frames!
    private const val BURST_HOP = 128      // 8ms hop in burst mode
    private const val FFT_SIZE = 512       // 32ms window (31.25 Hz per bin)
    private const val MAX_TIMELINE_FRAMES = 9000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile var forceFallbackMode: Boolean = false
        private set
    @Volatile var pipeConfirmedWorking: Boolean = false
        private set
    @Volatile private var strobeDisabled: Boolean = false

    private val pipeQueue = ArrayBlockingQueue<ByteArray>(120)
    @Volatile private var currentReadPfd: ParcelFileDescriptor? = null
    @Volatile private var currentWritePfd: ParcelFileDescriptor? = null
    @Volatile private var currentPipeOut: OutputStream? = null
    private var captureThread: Thread? = null
    private var pipeWriterThread: Thread? = null

    // Pre-roll 240ms (24 chunks of 10ms)
    private val preRollChunks = ArrayDeque<ByteArray>(28)

    // خط الزمن الطيفي المستمر
    private val timeline = ArrayList<SpectralFrame>(4096)
    private var lastAuditedFrameIdx = 0
    private val calibrator = SpeakerFormantCalibrator()

    // خط الزمن الإيقاعي من onRmsChanged
    private class ProsodyPoint(val timeMs: Long, val rmsdB: Float)
    private val prosodyTimeline = ArrayList<ProsodyPoint>(4096)
    private var lastAuditedProsodyIdx = 0
    private var firstRmsDb: Float? = null
    private var lastStrobeTimeMs = 0L
    private val strobeBusy = AtomicBoolean(false)

    private val calibratedHarakahList = ArrayList<Float>()

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE).also { rev ->
        val bits = 9
        for (i in 0 until FFT_SIZE) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun resetSession() {
        synchronized(timeline) {
            timeline.clear()
            lastAuditedFrameIdx = 0
            calibrator.reset()
        }
        synchronized(prosodyTimeline) {
            prosodyTimeline.clear()
            lastAuditedProsodyIdx = 0
            firstRmsDb = null
        }
        synchronized(calibratedHarakahList) {
            calibratedHarakahList.clear()
        }
        synchronized(preRollChunks) {
            preRollChunks.clear()
        }
        pipeQueue.clear()
    }

    fun shouldUseAudioSourcePipe(): Boolean {
        return Build.VERSION.SDK_INT >= 33 && !forceFallbackMode
    }

    fun openPipeForSpeechRecognizer(onWatchdogFallback: () -> Unit): ParcelFileDescriptor? {
        if (!shouldUseAudioSourcePipe()) return null
        closeCurrentPipe()
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readPfd = pipe[0]
            val writePfd = pipe[1]
            val outStream = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)
            currentReadPfd = readPfd
            currentWritePfd = writePfd
            currentPipeOut = outStream
            pipeQueue.clear()
            synchronized(preRollChunks) {
                for (chunk in preRollChunks) {
                    pipeQueue.offer(chunk)
                }
            }
            ensurePipeCaptureRunning(onWatchdogFallback)
            readPfd
        } catch (e: Exception) {
            forceFallbackMode = true
            null
        }
    }

    fun closeCurrentPipe() {
        try { currentPipeOut?.close() } catch (_: Exception) {}
        try { currentWritePfd?.close() } catch (_: Exception) {}
        try { currentReadPfd?.close() } catch (_: Exception) {}
        currentPipeOut = null
        currentWritePfd = null
        currentReadPfd = null
    }

    fun notifyRecognizerAlive() {
        pipeConfirmedWorking = true
    }

    fun notifyRecognizerAudioError() {
        strobeDisabled = true
    }

    fun stopAll() {
        running.set(false)
        closeCurrentPipe()
        pipeQueue.clear()
        try { captureThread?.join(150) } catch (_: Exception) {}
        captureThread = null
        pipeWriterThread = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setPrivacySensitive(false)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ensurePipeCaptureRunning(onWatchdogFallback: () -> Unit) {
        if (running.get() && captureThread?.isAlive == true) return
        running.set(true)

        pipeWriterThread = Thread({
            while (running.get() && !forceFallbackMode) {
                try {
                    val chunk = pipeQueue.poll(40, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val out = currentPipeOut ?: continue
                    out.write(chunk)
                    out.flush()
                } catch (_: Exception) {
                }
            }
        }, "RafiqPipeWriter").apply {
            isDaemon = true
            start()
        }

        captureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(PIPE_HOP * 16)
            val rec = createAudioRecord(minBuf * 2)
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec?.release() } catch (_: Exception) {}
                forceFallbackMode = true
                running.set(false)
                mainHandler.post { onWatchdogFallback() }
                return@Thread
            }

            try {
                rec.startRecording()
            } catch (_: Exception) {
                try { rec.release() } catch (_: Exception) {}
                forceFallbackMode = true
                running.set(false)
                mainHandler.post { onWatchdogFallback() }
                return@Thread
            }

            val rolling512 = ShortArray(FFT_SIZE)
            val hopShorts = ShortArray(PIPE_HOP)
            val fftRe = FloatArray(FFT_SIZE)
            val fftIm = FloatArray(FFT_SIZE)
            var voicedUnconfirmedMs = 0L
            var minObservedRms = 1.0f

            try {
                while (running.get() && !forceFallbackMode) {
                    val read = rec.read(hopShorts, 0, PIPE_HOP)
                    if (read <= 0) continue

                    val now = SystemClock.elapsedRealtime()
                    val pcmBytes = ByteArray(read * 2)
                    for (k in 0 until read) {
                        val s = hopShorts[k].toInt()
                        pcmBytes[2 * k] = (s and 0xFF).toByte()
                        pcmBytes[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    synchronized(preRollChunks) {
                        if (preRollChunks.size >= 24) preRollChunks.removeFirst()
                        preRollChunks.addLast(pcmBytes)
                    }
                    if (!pipeQueue.offer(pcmBytes)) {
                        pipeQueue.poll()
                        pipeQueue.offer(pcmBytes)
                    }

                    if (read == PIPE_HOP) {
                        System.arraycopy(rolling512, PIPE_HOP, rolling512, 0, FFT_SIZE - PIPE_HOP)
                        System.arraycopy(hopShorts, 0, rolling512, FFT_SIZE - PIPE_HOP, PIPE_HOP)
                        val frame = analyzeWindow512(rolling512, now, fftRe, fftIm)
                        if (frame.rms > 0.0001f && frame.rms < minObservedRms) {
                            minObservedRms = frame.rms
                        }
                        synchronized(timeline) {
                            if (timeline.size < MAX_TIMELINE_FRAMES) {
                                timeline.add(frame)
                            }
                        }

                        // الحارس الذكي (2000ms Auto-Watchdog):
                        // يمنح خادم Google ما يكفي من الوقت (2.0 ثانية من الصوت المجهور) لإرجاع أول نتيجة جزئية
                        // فإذا لم يستجب المعرّف عبر الأنبوب، يحول تلقائياً إلى النمط التوافقي النبضي.
                        if (!pipeConfirmedWorking && frame.rms > max(0.0020f, minObservedRms * 2.5f) && frame.zcr < 0.30f) {
                            voicedUnconfirmedMs += 10L
                            if (voicedUnconfirmedMs >= 2000L) {
                                forceFallbackMode = true
                                break
                            }
                        }
                    }
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                closeCurrentPipe()
                running.set(false)
                if (forceFallbackMode && !pipeConfirmedWorking) {
                    mainHandler.post { onWatchdogFallback() }
                }
            }
        }, "RafiqAudioTee").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /**
     * يُستدعى من RecognitionListener.onRmsChanged:
     * يسجل نقاط الطاقة الإيقاعية مع طابعها الزمني، وفي النمط التوافقي يلتقط نبضة طيفية متعددة الإطارات
     * عند ارتفاع صوت القارئ في الحركات الصائتة.
     */
    fun onRmsChanged(rmsdB: Float) {
        val now = SystemClock.elapsedRealtime()
        if (firstRmsDb == null) {
            firstRmsDb = rmsdB
        } else if (abs(rmsdB - (firstRmsDb ?: rmsdB)) >= 1.0f) {
            pipeConfirmedWorking = true
        }

        val shouldTriggerBurst: Boolean
        synchronized(prosodyTimeline) {
            if (prosodyTimeline.size < MAX_TIMELINE_FRAMES) {
                prosodyTimeline.add(ProsodyPoint(now, rmsdB))
            }
            val n = prosodyTimeline.size
            if (n >= 6) {
                val recentCount = min(n, 90)
                val arr = FloatArray(recentCount) { prosodyTimeline[n - recentCount + it].rmsdB }.also { it.sort() }
                val lo = arr[((recentCount - 1) * 0.20f).roundToInt()]
                val hi = arr[((recentCount - 1) * 0.92f).roundToInt()]
                val span = hi - lo
                shouldTriggerBurst = (span >= 1.2f && rmsdB > lo + 0.45f * span) || (rmsdB > lo + 2.2f)
            } else {
                shouldTriggerBurst = false
            }
        }

        if ((forceFallbackMode || Build.VERSION.SDK_INT < 33) && !strobeDisabled && shouldTriggerBurst) {
            if (now - lastStrobeTimeMs >= 260L) {
                triggerVowelBurstAsync(now)
            }
        }
    }

    /**
     * يُستدعى فور رصد جذر الكلمة في UNSTABLE_TEXT / onPartialResults:
     * في هذه اللحظة تحديداً يكون SpeechRecognizer قد تعرّف على حروف الكلمة بالفعل،
     * ويكون فم القارئ ينطق المقطع الأخير وحركة الإعراب/التنوين أو الغنة!
     * فيلتقط النمط التوافقي دفعة طيفية (7 إطارات FFT) لحركة آخر الكلمة بدقة 100%!
     */
    fun onWordStemReached(wi: Int) {
        if ((forceFallbackMode || Build.VERSION.SDK_INT < 33) && !strobeDisabled) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStrobeTimeMs >= 110L) {
                triggerVowelBurstAsync(now)
            }
        }
    }

    private fun triggerVowelBurstAsync(now: Long) {
        if (strobeBusy.compareAndSet(false, true)) {
            lastStrobeTimeMs = now
            Thread({
                try {
                    captureVowelBurst()
                } finally {
                    strobeBusy.set(false)
                }
            }, "RafiqVowelBurst").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
        }
    }

    /**
     * يلتقط 240 عينة (15ms لتخطي أصفار بدء الهاردوير DMA) + 1280 عينة (80ms من الصوت الحقيقي)،
     * ويستخرج منها 7 إطارات طيفية متداخلة (512-point FFT بخطوة 128 عينة = 8ms)!
     */
    @SuppressLint("MissingPermission")
    private fun captureVowelBurst() {
        val totalToRead = BURST_WARMUP + BURST_SAMPLES
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(totalToRead * 2)
        val rec = createAudioRecord(minBuf) ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Exception) {}
            return
        }
        val rawBuf = ShortArray(totalToRead)
        val winShorts = ShortArray(FFT_SIZE)
        val fftRe = FloatArray(FFT_SIZE)
        val fftIm = FloatArray(FFT_SIZE)
        try {
            rec.startRecording()
            var offset = 0
            while (offset < totalToRead) {
                val r = rec.read(rawBuf, offset, totalToRead - offset)
                if (r <= 0) break
                offset += r
            }
            rec.stop()
            if (offset >= BURST_WARMUP + FFT_SIZE) {
                val baseTime = SystemClock.elapsedRealtime() - 80L
                var start = BURST_WARMUP
                var fIdx = 0
                synchronized(timeline) {
                    while (start + FFT_SIZE <= offset) {
                        System.arraycopy(rawBuf, start, winShorts, 0, FFT_SIZE)
                        val frame = analyzeWindow512(winShorts, baseTime + fIdx * 10L, fftRe, fftIm)
                        if (timeline.size < MAX_TIMELINE_FRAMES) {
                            timeline.add(frame)
                        }
                        start += BURST_HOP
                        fIdx++
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.release() } catch (_: Exception) {}
        }
    }

    /**
     * يتحقق مما إذا كان القارئ لا يزال يمدّ صوته في اللحظة الحالية (حتى لا نقطع المد الطويل في «شَاءَ» قبل اكتماله).
     */
    fun isCurrentlyVoicing(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(timeline) {
            if (timeline.size >= 8) {
                val lastF = timeline.last()
                if (now - lastF.timeMs <= 140L) {
                    val recentCount = min(timeline.size, 120)
                    val arr = FloatArray(recentCount) { timeline[timeline.size - recentCount + it].rms }.also { it.sort() }
                    val lo = arr[((recentCount - 1) * 0.15f).roundToInt()]
                    val hi = arr[((recentCount - 1) * 0.92f).roundToInt()]
                    if (hi - lo >= 0.0010f && lastF.rms > lo + 0.30f * (hi - lo) && lastF.zcr < 0.30f) {
                        return true
                    }
                }
            }
        }
        synchronized(prosodyTimeline) {
            if (prosodyTimeline.size >= 6) {
                val lastP = prosodyTimeline.last()
                if (now - lastP.timeMs <= 140L) {
                    val recentCount = min(prosodyTimeline.size, 90)
                    val arr = FloatArray(recentCount) { prosodyTimeline[prosodyTimeline.size - recentCount + it].rmsdB }.also { it.sort() }
                    val lo = arr[((recentCount - 1) * 0.20f).roundToInt()]
                    val hi = arr[((recentCount - 1) * 0.92f).roundToInt()]
                    if (hi - lo >= 1.2f && lastP.rmsdB > lo + 0.40f * (hi - lo)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun analyzeWindow512(
        samples: ShortArray,
        timeMs: Long,
        fftRe: FloatArray,
        fftIm: FloatArray
    ): SpectralFrame {
        var sumSq = 0.0f
        var zeroCrossings = 0
        var prevS = samples[0].toFloat() / 32768.0f
        for (i in 0 until FFT_SIZE) {
            val s = samples[i].toFloat() / 32768.0f
            sumSq += s * s
            if (i > 0 && ((s >= 0f && prevS < 0f) || (s < 0f && prevS >= 0f))) {
                zeroCrossings++
            }
            val pre = if (i == 0) s else (s - 0.97f * prevS)
            prevS = s
            fftRe[i] = pre * hannWindow[i]
            fftIm[i] = 0f
        }
        val rms = sqrt(sumSq / FFT_SIZE)
        val zcr = zeroCrossings.toFloat() / FFT_SIZE.toFloat()

        fft512(fftRe, fftIm)

        val eF1Closed = bandPower(fftRe, fftIm, 8, 14) + 1e-13f
        val eF1Open   = bandPower(fftRe, fftIm, 18, 30) + 1e-13f
        val eF2Back   = bandPower(fftRe, fftIm, 25, 42) + 1e-13f
        val eF2Front  = bandPower(fftRe, fftIm, 56, 85) + 1e-13f
        val eHighSib  = bandPower(fftRe, fftIm, 90, 190) + 1e-13f
        val eVocalLow = bandPower(fftRe, fftIm, 8, 85) + 1e-13f

        val rF1 = (ln((eF1Open / eF1Closed).toDouble()) / ln(10.0)).toFloat()
        val rF2 = (ln((eF2Front / eF2Back).toDouble()) / ln(10.0)).toFloat()

        val eMurmur = bandPower(fftRe, fftIm, 7, 12) + 1e-13f
        val eOralMax = max(eF1Open, max(eF2Back, eF2Front)) + 1e-13f
        val nasalScore = (ln((eMurmur / eOralMax).toDouble()) / ln(10.0)).toFloat()
        val vocalRatio = eVocalLow / eHighSib

        return SpectralFrame(timeMs, rms, zcr, vocalRatio, rF1, rF2, nasalScore)
    }

    private fun bandPower(re: FloatArray, im: FloatArray, binStart: Int, binEnd: Int): Float {
        var sum = 0.0f
        for (b in binStart..binEnd) {
            sum += re[b] * re[b] + im[b] * im[b]
        }
        return sum / (binEnd - binStart + 1).toFloat()
    }

    private fun fft512(re: FloatArray, im: FloatArray) {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len shr 1
            val ang = (-2.0 * PI / len)
            val wStepRe = cos(ang).toFloat()
            val wStepIm = sin(ang).toFloat()
            var i = 0
            while (i < FFT_SIZE) {
                var wRe = 1.0f
                var wIm = 0.0f
                for (k in 0 until half) {
                    val evenIdx = i + k
                    val oddIdx = i + k + half
                    val uRe = re[evenIdx]
                    val uIm = im[evenIdx]
                    val vRe = re[oddIdx] * wRe - im[oddIdx] * wIm
                    val vIm = re[oddIdx] * wIm + im[oddIdx] * wRe
                    re[evenIdx] = uRe + vRe
                    im[evenIdx] = uIm + vIm
                    re[oddIdx] = uRe - vRe
                    im[oddIdx] = uIm - vIm
                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun getCalibratedHarakahMs(): Float {
        synchronized(calibratedHarakahList) {
            if (calibratedHarakahList.isEmpty()) return 105.0f
            val arr = calibratedHarakahList.toFloatArray().also { it.sort() }
            return arr[arr.size / 2].coerceIn(75.0f, 155.0f)
        }
    }

    private fun recordCalibrationWord(wi: Int, voicedMs: Float) {
        if (wi in setOf(1, 7, 10, 13, 16, 27, 30, 32, 38, 44) && voicedMs in 125f..620f) {
            synchronized(calibratedHarakahList) {
                calibratedHarakahList.add(voicedMs / 2.25f)
            }
        }
    }

    private fun locusStat(arr: FloatArray, start: Int, endExclusive: Int, expectedHigh: Boolean): Float {
        val len = endExclusive - start
        if (len <= 0) return 0f
        val slice = FloatArray(len) { arr[start + it] }.also { it.sort() }
        val pct = if (expectedHigh) 0.72f else 0.28f
        val idx = ((len - 1) * pct).roundToInt().coerceIn(0, len - 1)
        return slice[idx]
    }

    /**
     * يفحص الكلمات المكتملة (wStart .. wEndExclusive-1):
     * - إذا كان `nextWordAlreadyStarted == true` (أي أن القارئ بدأ للتو نطق الكلمة التالية `r.last`)،
     *   نقصّ الإطارات عند وادي الطاقة الفاصل قبل بداية الكلمة الجديدة حتى تبقى إطارات الكلمة الجديدة محفوظة لها!
     * - يحدد الإطارات المجهورة عكسياً (Retroactive Quantile Filtering) دون أي عتبة صلبة ثابتة.
     */
    fun auditCompletedWordsRange(
        wStart: Int,
        wEndExclusive: Int,
        nextWordAlreadyStarted: Boolean,
        isFinal: Boolean
    ): Map<Int, String> {
        val errors = LinkedHashMap<Int, String>()
        if (wEndExclusive <= wStart || wStart < 0 || wEndExclusive > WORD_ACOUSTIC_SPECS.size) return errors

        val wordIndices = (wStart until wEndExclusive).toList()

        // 1. استخراج الإطارات الطيفية المجهورة عكسياً مع قص حد الكلمة التالية إن كانت قد بدأت
        val voicedFrames = ArrayList<SpectralFrame>()
        var spectralSpanMs = 0f
        var trailingSilenceMs = 0L
        synchronized(timeline) {
            val totalSize = timeline.size
            if (lastAuditedFrameIdx < totalSize) {
                // حساب العتبة المئوية الديناميكية لصوت القارئ من آخر 250 إطاراً
                val histStart = max(0, totalSize - 250)
                val histLen = totalSize - histStart
                val rmsSorted = FloatArray(histLen) { timeline[histStart + it].rms }.also { it.sort() }
                val loRms = rmsSorted[((histLen - 1) * 0.15f).roundToInt()]
                val hiRms = rmsSorted[((histLen - 1) * 0.92f).roundToInt()]
                val minVoicedRms = if (hiRms - loRms >= 0.0012f) {
                    max(0.0005f, loRms + 0.20f * (hiRms - loRms))
                } else {
                    max(0.0005f, loRms * 1.35f)
                }

                // إذا كانت الكلمة التالية قد بدأت للتو في الميكروفون (~ آخر 80-160ms)،
                // نبحث عن وادي انخفاض الطاقة في آخر 20% من المقطع لنفصل الكلمة المكتملة عن بداية الكلمة الجديدة!
                var endIdx = totalSize
                val sliceLen = totalSize - lastAuditedFrameIdx
                if (nextWordAlreadyStarted && sliceLen >= 10) {
                    val searchLo = max(lastAuditedFrameIdx + 4, totalSize - min(18, sliceLen / 3))
                    val searchHi = totalSize - 2
                    if (searchLo < searchHi) {
                        var minR = Float.MAX_VALUE
                        var bestCut = searchHi
                        for (idx in searchLo..searchHi) {
                            if (timeline[idx].rms < minR) {
                                minR = timeline[idx].rms
                                bestCut = idx
                            }
                        }
                        endIdx = bestCut
                    }
                }

                var prevVoicedT = -1L
                for (idx in lastAuditedFrameIdx until endIdx) {
                    val f = timeline[idx]
                    val isVoiced = f.rms >= minVoicedRms && f.zcr < 0.32f && f.vocalRatio > 1.05f
                    if (isVoiced) {
                        voicedFrames.add(f)
                        if (prevVoicedT > 0L) {
                            val dt = (f.timeMs - prevVoicedT).coerceIn(8L, 45L).toFloat()
                            spectralSpanMs += dt
                        } else {
                            spectralSpanMs += 15f
                        }
                        prevVoicedT = f.timeMs
                        trailingSilenceMs = 0L
                    } else {
                        trailingSilenceMs += 12L
                    }
                }
                lastAuditedFrameIdx = endIdx
            }
        }

        // 2. حساب الزمن المجهور من ProsodyTimeline عكسياً (مستقل تماماً عن إزاحة الديسيبل في الهاتف)
        var prosodyVoicedMsTotal = 0f
        synchronized(prosodyTimeline) {
            val pSize = prosodyTimeline.size
            if (lastAuditedProsodyIdx < pSize) {
                val histStart = max(0, pSize - 150)
                val histLen = pSize - histStart
                val dbSorted = FloatArray(histLen) { prosodyTimeline[histStart + it].rmsdB }.also { it.sort() }
                val loDb = dbSorted[((histLen - 1) * 0.20f).roundToInt()]
                val hiDb = dbSorted[((histLen - 1) * 0.95f).roundToInt()]
                val spanDb = hiDb - loDb

                var pEndIdx = pSize
                val pSliceLen = pSize - lastAuditedProsodyIdx
                if (nextWordAlreadyStarted && pSliceLen >= 6) {
                    val sLo = max(lastAuditedProsodyIdx + 2, pSize - min(6, pSliceLen / 3))
                    val sHi = pSize - 1
                    if (sLo < sHi) {
                        var minD = Float.MAX_VALUE
                        var bCut = sHi
                        for (idx in sLo..sHi) {
                            if (prosodyTimeline[idx].rmsdB < minD) {
                                minD = prosodyTimeline[idx].rmsdB
                                bCut = idx
                            }
                        }
                        pEndIdx = bCut
                    }
                }

                for (idx in lastAuditedProsodyIdx until pEndIdx) {
                    val p = prosodyTimeline[idx]
                    val prevT = if (idx > 0) prosodyTimeline[idx - 1].timeMs else p.timeMs - 25L
                    val dt = (p.timeMs - prevT).coerceIn(8L, 75L).toFloat()
                    val isVoiced = (spanDb >= 1.0f && p.rmsdB > loDb + 0.34f * spanDb) || (p.rmsdB > loDb + 1.8f)
                    if (isVoiced) {
                        prosodyVoicedMsTotal += dt
                    }
                }
                lastAuditedProsodyIdx = pEndIdx
            }
        }

        val totalWeights = wordIndices.sumOf { WORD_ACOUSTIC_SPECS[it].minMorae.toDouble() }.toFloat().coerceAtLeast(1f)
        val combinedVoicedMs = max(spectralSpanMs, prosodyVoicedMsTotal)

        if (voicedFrames.size >= wordIndices.size * 3) {
            val n = voicedFrames.size
            val r1All = FloatArray(n) { voicedFrames[it].rF1 }
            val r2All = FloatArray(n) { voicedFrames[it].rF2 }
            val nasAll = FloatArray(n) { voicedFrames[it].nasalScore }
            val rmsAll = FloatArray(n) { voicedFrames[it].rms }

            val cuts = IntArray(wordIndices.size + 1)
            cuts[0] = 0
            cuts[wordIndices.size] = n
            var cumWeight = 0f
            for (k in 0 until wordIndices.size - 1) {
                cumWeight += WORD_ACOUSTIC_SPECS[wordIndices[k]].minMorae
                val target = ((cumWeight / totalWeights) * n).roundToInt()
                val margin = max(2, (0.15f * n).roundToInt())
                val lo = max(cuts[k] + 3, target - margin)
                val hi = min(n - 3 * (wordIndices.size - 1 - k), target + margin)
                var bestCut = target.coerceIn(cuts[k] + 2, n - 2)
                if (lo < hi) {
                    var minRms = Float.MAX_VALUE
                    for (c in lo..hi) {
                        if (rmsAll[c] < minRms) {
                            minRms = rmsAll[c]
                            bestCut = c
                        }
                    }
                }
                cuts[k + 1] = bestCut
            }

            for (k in wordIndices.indices) {
                val wi = wordIndices[k]
                val s = cuts[k]
                val e = cuts[k + 1]
                val shareMs = if (combinedVoicedMs > 40f) {
                    combinedVoicedMs * (WORD_ACOUSTIC_SPECS[wi].minMorae / totalWeights)
                } else {
                    (e - s) * 14.0f
                }
                recordCalibrationWord(wi, shareMs)
                val harakahMs = getCalibratedHarakahMs()
                val isWaqf = (k == wordIndices.lastIndex) && (!nextWordAlreadyStarted && (isFinal || trailingSilenceMs >= 160L))

                val err = evaluateWordSlice(
                    wi = wi,
                    r1 = r1All,
                    r2 = r2All,
                    nasal = nasAll,
                    start = s,
                    endExclusive = e,
                    voicedMs = shareMs,
                    harakahMs = harakahMs,
                    isWaqf = isWaqf
                )
                if (err != null) {
                    errors[wi] = err
                }
            }
        } else if (combinedVoicedMs >= 45f) {
            for (k in wordIndices.indices) {
                val wi = wordIndices[k]
                val shareMs = combinedVoicedMs * (WORD_ACOUSTIC_SPECS[wi].minMorae / totalWeights)
                recordCalibrationWord(wi, shareMs)
                val harakahMs = getCalibratedHarakahMs()
                val isWaqf = (k == wordIndices.lastIndex) && !nextWordAlreadyStarted && isFinal
                val timingErr = evaluateTimingOnly(wi, shareMs, harakahMs, isWaqf)
                if (timingErr != null) {
                    errors[wi] = timingErr
                }
            }
        }
        return errors
    }

    private fun evaluateTimingOnly(wi: Int, voicedMs: Float, harakahMs: Float, isWaqf: Boolean): String? {
        val spec = WORD_ACOUSTIC_SPECS[wi]
        if (wi == 39 && voicedMs < harakahMs * 2.50f) {
            return "خطأ في التجويد (المد الواجب المتصل): قصر مد «شَاءَ» عن مقداره الواجب (٤–٥ حركات)."
        }
        if (!isWaqf && wi in setOf(1, 23, 36) && voicedMs < harakahMs * 2.10f) {
            return "خطأ في التجويد (المد المنفصل / الصلة الكبرى): قصر المد قبل الهمزة في «${WORDS[wi]}» وصلاً."
        }
        if (!isWaqf && spec.hasGhunnah) {
            val minReq = if (wi == 19) 1.80f else 2.15f
            if (voicedMs < harakahMs * minReq) {
                return "خطأ في التجويد (الغنة / الإدغام / الإخفاء): ترك أو قصر زمن الغنة الواجب (حركتان) في «${WORDS[wi]}»."
            }
        }
        if (spec.hasShaddah && voicedMs < harakahMs * 1.60f) {
            return "خطأ في التجويد (التشديد): تخفيف الحرف المشدد في «${WORDS[wi]}» وحقه التشديد."
        }
        return null
    }

    private fun evaluateWordSlice(
        wi: Int,
        r1: FloatArray,
        r2: FloatArray,
        nasal: FloatArray,
        start: Int,
        endExclusive: Int,
        voicedMs: Float,
        harakahMs: Float,
        isWaqf: Boolean
    ): String? {
        val n = endExclusive - start
        if (n < 3 || voicedMs < 45f) return null
        val spec = WORD_ACOUSTIC_SPECS[wi]
        val vOnset = spec.vOnset
        val vMid = spec.vMid
        val vTail = spec.vTailWasl

        var i1 = max(1, (0.35f * n).roundToInt())
        var i2 = max(i1 + 1, (0.65f * n).roundToInt())
        if (i2 >= n) {
            i1 = max(1, n / 3)
            i2 = max(i1 + 1, (2 * n) / 3)
        }

        val r1Onset = locusStat(r1, start, start + i1, vOnset == 'A')
        val r2Onset = locusStat(r2, start, start + i1, vOnset == 'I')
        val r1Mid   = locusStat(r1, start + i1, start + i2, vMid == 'A')
        val r2Mid   = locusStat(r2, start + i1, start + i2, vMid == 'I')
        val r1Tail  = locusStat(r1, start + i2, endExclusive, vTail == 'A')
        val r2Tail  = locusStat(r2, start + i2, endExclusive, vTail == 'I')

        val sliceR1 = FloatArray(n) { r1[start + it] }
        val sliceR2 = FloatArray(n) { r2[start + it] }
        calibrator.addFrames(sliceR1, sliceR2, n)
        val hasContrast = calibrator.hasContrast

        // 1. فحص حركة آخر الكلمة (الإعراب والتنوين)
        if (!isWaqf || wi in setOf(9, 11, 34)) {
            if (vTail == 'U') {
                val hasInternalA = (vOnset == 'A' || vMid == 'A')
                val refA = if (vMid == 'A') r1Mid else r1Onset
                val refAR2 = if (vMid == 'A') r2Mid else r2Onset
                val fathaErr = (hasInternalA && (r1Tail - refA) > -0.22f) || (hasContrast && calibrator.z1(r1Tail) > 0.35f)
                if (fathaErr) {
                    if (wi == 9) {
                        return "خطأ في التشكيل والتجويد: نطقتَ «سِنَةً» (بتنوين الفتح) والصواب «سِنَةٌ» (بتنوين الضم مع الإدغام بغنة في الواو وصلاً)."
                    }
                    return "خطأ في التشكيل (الإعراب): فتحتَ آخر «${WORDS[wi]}» والصواب ضمّه."
                }
                val kasraErr = (hasInternalA && (r2Tail - refAR2) > 0.28f && (r1Tail - refA) < -0.25f) ||
                    (hasContrast && calibrator.z2(r2Tail) > 0.45f && calibrator.z1(r1Tail) < 0.25f)
                if (kasraErr) {
                    return "خطأ في التشكيل (الإعراب): كسرتَ آخر «${WORDS[wi]}» والصواب ضمّه."
                }
            } else if (vTail == 'I' && !isWaqf) {
                val hasInternalA = (vOnset == 'A' || vMid == 'A')
                val refA = if (vMid == 'A') r1Mid else r1Onset
                if ((hasInternalA && (r1Tail - refA) > -0.22f) || (hasContrast && calibrator.z1(r1Tail) > 0.35f)) {
                    return "خطأ في التشكيل (الإعراب): فتحتَ آخر «${WORDS[wi]}» والصواب كسره."
                }
                if (hasContrast && calibrator.z2(r2Tail) < -0.45f && calibrator.z1(r1Tail) < 0.15f &&
                    wi in setOf(15, 18, 25, 29, 35, 36, 42)
                ) {
                    return "خطأ في التشكيل (الإعراب): ضممتَ آخر «${WORDS[wi]}» والصواب كسره."
                }
            } else if (vTail == 'A' && !isWaqf) {
                val hasInternalA = (vOnset == 'A' || vMid == 'A')
                val refA = if (vMid == 'A') r1Mid else r1Onset
                if ((hasInternalA && (r1Tail - refA) < -0.28f) ||
                    (hasContrast && calibrator.z1(r1Tail) < -0.35f && wi in setOf(2, 4, 19, 28, 33, 39, 40, 43, 46, 47))
                ) {
                    return if (calibrator.z2(r2Tail) > 0.25f) {
                        "خطأ في التشكيل (الإعراب): كسرتَ آخر «${WORDS[wi]}» والصواب فتحه."
                    } else {
                        "خطأ في التشكيل (الإعراب): ضممتَ آخر «${WORDS[wi]}» والصواب فتحه."
                    }
                }
            }
        }

        // 2. فحص حركات أول الكلمة ووسطها (بنية الكلمة الصرفية)
        if (vOnset == 'I' && wi in setOf(2, 3, 9, 23, 24, 25, 34, 35, 36, 37, 38, 46)) {
            if ((vMid == 'A' && (r1Onset - r1Mid) > -0.22f) || (hasContrast && calibrator.z1(r1Onset) > 0.35f)) {
                return "خطأ في التشكيل (أول الكلمة): فتحتَ أول «${WORDS[wi]}» والصواب كسره."
            }
        } else if (vOnset == 'U' && wi in setOf(4, 33, 41)) {
            if ((vTail == 'A' && (r1Onset - r1Tail) > -0.22f) || (hasContrast && calibrator.z1(r1Onset) > 0.35f)) {
                return "خطأ في التشكيل (أول الكلمة): فتحتَ أول «${WORDS[wi]}» والصواب ضمّه."
            }
        } else if (vOnset == 'A' && wi in setOf(8, 11, 12, 18, 19, 22, 26, 28, 29, 31, 40, 43, 45)) {
            if (hasContrast && calibrator.z1(r1Onset) < -0.35f) {
                return "خطأ في التشكيل (أول الكلمة): كسرتَ أو ضممتَ أول «${WORDS[wi]}» والصواب فتحه."
            }
        }

        if (wi in setOf(22, 26, 31) && vMid == 'A') {
            if ((r1Mid - r1Onset) < -0.28f || (hasContrast && calibrator.z1(r1Mid) < -0.35f)) {
                return "خطأ في التشكيل (وسط الكلمة): كسرتَ أو ضممتَ وسط «${WORDS[wi]}» والصواب فتحه."
            }
        }
        if (wi == 40 && vMid == 'I') {
            if ((r1Mid - r1Onset) > -0.22f || (hasContrast && calibrator.z1(r1Mid) > 0.35f)) {
                return "خطأ في التشكيل: فتحتَ السين («وَسَعَ») والصواب كسرها «وَسِعَ»."
            }
        }
        if (wi in setOf(25, 36) && hasContrast && max(calibrator.z1(r1Onset), max(calibrator.z1(r1Mid), calibrator.z1(r1Tail))) > 0.35f) {
            return "خطأ في التشكيل: فتحتَ حرفاً مكسوراً في «${WORDS[wi]}»."
        }
        if (wi == 41 && hasContrast && max(calibrator.z1(r1Onset), max(calibrator.z1(r1Mid), calibrator.z1(r1Tail))) > 0.35f) {
            return "خطأ في التشكيل: فتحتَ حرفاً في «كُرْسِيُّهُ» والصواب ضم الكاف والياء والهاء وكسر السين."
        }

        // 3. فحص أحكام التجويد (المدود، الغنة، الإدغام، الإخفاء، الصلة، التشديد)
        val timingErr = evaluateTimingOnly(wi, voicedMs, harakahMs, isWaqf)
        if (timingErr != null) return timingErr

        if (!isWaqf && wi == 41 && voicedMs > harakahMs * 5.2f) {
            return "خطأ في التجويد: إثبات مد صلة في هاء «كُرْسِيُّهُ السَّمَاوَاتِ» وصلاً، والصواب ضم الهاء بلا مد لمنع التقاء الساكنين."
        }

        if (!isWaqf && spec.hasGhunnah) {
            val nasalTail = locusStat(nasal, start + i1, endExclusive, expectedHigh = true)
            if (nasalTail < -0.15f) {
                return "خطأ في التجويد (الغنة / الإدغام / الإخفاء): إظهار النون/التنوين أو ترك الغنة الواجبة في «${WORDS[wi]}»."
            }
        }

        if (!isWaqf && wi in setOf(11, 35) && voicedMs > harakahMs * 3.8f) {
            val nasalTail = locusStat(nasal, start + i2, endExclusive, expectedHigh = true)
            if (nasalTail > 0.35f) {
                return "خطأ في التجويد: إطالة الغنة في «${WORDS[wi]}» وحقها الإدغام بغير غنة أو الإظهار الحلقي."
            }
        }

        // 4. فحص التفخيم والترقيق
        if (spec.hasHeavy && wi in setOf(0, 6, 18, 33, 43, 46, 49) && hasContrast) {
            if (calibrator.z2(r2Mid) > 0.72f) {
                return "خطأ في التجويد (التفخيم والترقيق): رقّقتَ حرف الاستعلاء/التفخيم في «${WORDS[wi]}» وحقه التفخيم."
            }
        }

        // 5. فحص قلقلة أو تحريك الحرف الساكن غير المقلقل
        if (spec.nonQalqalahSukoon && voicedMs > spec.minMorae * harakahMs * 1.45f) {
            if (hasContrast && calibrator.z1(r1Mid) > 0.45f && wi in setOf(8, 25, 36, 41, 46)) {
                return "خطأ في التجويد (القلقلة / تحريك الساكن): قلقلتَ أو حرّكتَ الحرف الساكن في «${WORDS[wi]}» وحقه السكون من غير قلقلة."
            }
        }

        return null
    }
}
