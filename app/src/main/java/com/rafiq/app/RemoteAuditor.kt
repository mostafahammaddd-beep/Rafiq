package com.rafiq.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * المحرك الفيزيائي الطيفي والمرجعي المدمج داخل الهاتف (On-Device Physical Acoustic & Reference DTW Auditor):
 * - يحلل الإشارة الصوتية الخام (16kHz PCM) عبر تحويل فورييه السريع (512-point FFT) لاستخراج ترددات الفورمانت (F1/F2)
 *   وطاقة حزم الغنة والصفير والتفشي والتفخيم لكل كلمة من الكلمات الخمسين.
 * - يقارن بصمة كل كلمة طيفياً وزمنياً بالتلاوة المرجعية للشيخ المنشاوي (002255.mp3) عبر خوارزمية DTW.
 * - يكشف أخطاء التشكيل (مثل «سِنَةً» بالفتح بدل «سِنَةٌ»، أو فتح/كسر أواخر الكلمات)، وأخطاء مقادير المدود بالملي ثانية،
 *   وترك الغنة في الإخفاء والإدغام، وإبدال الحروف المتقاربة (ذ/ز، ظ/ز، ش/س)، وترقيق المفخم.
 */
object LocalAcousticAuditor {
    const val SR = 16000

    class WordSpec(
        val idx: Int,
        val text: String,
        val waslVowels: List<String>,
        val waqfVowels: List<String>,
        val minHarakat: Float,
        val maddType: String,
        val reqGhunnah: Boolean,
        val interdental: Boolean,
        val safeer: Boolean,
        val tafashie: Boolean,
        val tafkheem: Boolean,
        val internalSakin: Boolean
    )

    val NATURAL_WAQF_SET = setOf(4, 5, 6, 11, 15, 18, 25, 29, 31, 39, 43, 46, 48, 49)

    val WORD_SPECS: List<WordSpec> = listOf(
        WordSpec(0,  "اللَّهُ",       listOf("a", "aa", "u"),       listOf("a", "aa"),            4.0f, "TABEEI",   false, false, false, false, true,  false),
        WordSpec(1,  "لَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "MUNFASIL", false, false, false, false, false, false),
        WordSpec(2,  "إِلَٰهَ",       listOf("i", "aa", "a"),       listOf("i", "aa"),            4.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(3,  "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(4,  "هُوَ",          listOf("u", "a"),             listOf("u"),                  2.0f, "NONE",     false, false, false, false, false, false),
        WordSpec(5,  "الْحَيُّ",      listOf("a", "u"),             listOf("a"),                  2.8f, "NONE",     false, false, false, false, false, false),
        WordSpec(6,  "الْقَيُّومُ",   listOf("a", "uu", "u"),       listOf("a", "uu"),            4.5f, "TABEEI",   false, false, false, false, true,  false),
        WordSpec(7,  "لَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(8,  "تَأْخُذُهُ",    listOf("a", "u", "u", "uu"),  listOf("a", "u", "u"),        4.5f, "TABEEI",   false, true,  false, false, true,  true),
        WordSpec(9,  "سِنَةٌ",        listOf("i", "a", "u"),        listOf("i", "a"),             3.5f, "NONE",     true,  false, true,  false, false, false),
        WordSpec(10, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(11, "نَوْمٌ",        listOf("a", "u"),             listOf("a"),                  3.0f, "LEEN",     false, false, false, false, false, false),
        WordSpec(12, "لَهُ",          listOf("a", "uu"),            listOf("a"),                  3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(13, "مَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(14, "فِي",           listOf("i"),                  listOf("ii"),                 1.2f, "NONE",     false, false, false, false, false, false),
        WordSpec(15, "السَّمَاوَاتِ", listOf("a", "aa", "aa", "i"), listOf("a", "aa", "aa"),      6.0f, "TABEEI",   false, false, true,  false, false, false),
        WordSpec(16, "وَمَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(17, "فِي",           listOf("i"),                  listOf("ii"),                 1.2f, "NONE",     false, false, false, false, false, false),
        WordSpec(18, "الْأَرْضِ",     listOf("a", "i"),             listOf("a"),                  3.0f, "NONE",     false, false, false, false, true,  true),
        WordSpec(19, "مَنْ",          listOf("a"),                  listOf("a"),                  2.8f, "NONE",     true,  false, false, false, false, false),
        WordSpec(20, "ذَا",           listOf("a"),                  listOf("aa"),                 1.2f, "NONE",     false, true,  false, false, false, false),
        WordSpec(21, "الَّذِي",       listOf("a", "ii"),            listOf("a", "ii"),            3.5f, "TABEEI",   false, true,  false, false, false, false),
        WordSpec(22, "يَشْفَعُ",      listOf("a", "a", "u"),        listOf("a", "a"),             3.0f, "NONE",     false, false, false, true,  false, true),
        WordSpec(23, "عِنْدَهُ",      listOf("i", "a", "uu"),       listOf("i", "a"),             5.0f, "MUNFASIL", true,  false, false, false, false, false),
        WordSpec(24, "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(25, "بِإِذْنِهِ",    listOf("i", "i", "i", "ii"),  listOf("i", "i", "i"),        4.5f, "TABEEI",   false, true,  false, false, false, true),
        WordSpec(26, "يَعْلَمُ",      listOf("a", "a", "u"),        listOf("a", "a"),             3.0f, "NONE",     false, false, false, false, false, true),
        WordSpec(27, "مَا",           listOf("aa"),                 listOf("aa"),                 2.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(28, "بَيْنَ",        listOf("a", "a"),             listOf("a"),                  2.5f, "LEEN",     false, false, false, false, false, false),
        WordSpec(29, "أَيْدِيهِمْ",   listOf("a", "ii", "i"),       listOf("a", "ii", "i"),       4.5f, "TABEEI",   false, false, false, false, false, true),
        WordSpec(30, "وَمَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(31, "خَلْفَهُمْ",    listOf("a", "a", "u"),        listOf("a", "a", "u"),        3.2f, "NONE",     false, false, false, false, true,  true),
        WordSpec(32, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(33, "يُحِيطُونَ",    listOf("u", "ii", "uu", "a"), listOf("u", "ii", "uu"),      6.0f, "TABEEI",   false, false, false, false, true,  false),
        WordSpec(34, "بِشَيْءٍ",      listOf("i", "a", "i"),        listOf("i", "a"),             4.0f, "NONE",     true,  false, false, true,  false, false),
        WordSpec(35, "مِنْ",          listOf("i"),                  listOf("i"),                  1.5f, "NONE",     false, false, false, false, false, true),
        WordSpec(36, "عِلْمِهِ",      listOf("i", "i", "ii"),       listOf("i", "i"),             4.0f, "MUNFASIL", false, false, false, false, false, true),
        WordSpec(37, "إِلَّا",        listOf("i", "aa"),            listOf("i", "aa"),            3.5f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(38, "بِمَا",         listOf("i", "aa"),            listOf("i", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(39, "شَاءَ",         listOf("aa", "a"),            listOf("aa"),                 4.5f, "MUTTASIL", false, false, false, true,  false, false),
        WordSpec(40, "وَسِعَ",        listOf("a", "i", "a"),        listOf("a", "i"),             2.5f, "NONE",     false, false, true,  false, false, false),
        WordSpec(41, "كُرْسِيُّهُ",   listOf("u", "i", "u", "u"),   listOf("u", "i", "u"),        4.0f, "NONE",     false, false, true,  false, true,  true),
        WordSpec(42, "السَّمَاوَاتِ", listOf("a", "aa", "aa", "i"), listOf("a", "aa", "aa"),      6.0f, "TABEEI",   false, false, true,  false, false, false),
        WordSpec(43, "وَالْأَرْضَ",   listOf("a", "a", "a"),        listOf("a", "a"),             3.5f, "NONE",     false, false, false, false, true,  true),
        WordSpec(44, "وَلَا",         listOf("a", "aa"),            listOf("a", "aa"),            3.0f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(45, "يَئُودُهُ",     listOf("a", "uu", "u", "uu"), listOf("a", "uu", "u"),       5.5f, "TABEEI",   false, false, false, false, false, false),
        WordSpec(46, "حِفْظُهُمَا",   listOf("i", "u", "u", "aa"),  listOf("i", "u", "u", "aa"),  5.0f, "TABEEI",   false, true,  false, false, true,  true),
        WordSpec(47, "وَهُوَ",        listOf("a", "u", "a"),        listOf("a", "u"),             2.5f, "NONE",     false, false, false, false, false, false),
        WordSpec(48, "الْعَلِيُّ",    listOf("a", "i", "u"),        listOf("a", "i"),             3.5f, "NONE",     false, false, false, false, false, false),
        WordSpec(49, "الْعَظِيمُ",    listOf("a", "ii"),            listOf("a", "ii"),            4.5f, "AARED",    false, true,  false, false, true,  false)
    )

    class FrameFeatures(
        val rms: FloatArray,
        val zcr: FloatArray,
        val eF1Closed: FloatArray,
        val eF1Open: FloatArray,
        val eF2Damma: FloatArray,
        val eF2Fatha: FloatArray,
        val eF2Kasra: FloatArray,
        val eTafashie: FloatArray,
        val eSibilant: FloatArray
    )

    @Volatile private var refWordTemplates: List<FloatArray>? = null
    @Volatile private var loadingRef = false

    /** يحمّل ويفكّ تشفير تلاوة الشيخ المنشاوي المرجعية في الخلفية لاستخدامها في المقارنة الطيفية بالملي (DTW). */
    fun ensureReferenceLoaded(ctx: Context) {
        if (refWordTemplates != null || loadingRef) return
        loadingRef = true
        Thread {
            try {
                val f = File(ctx.cacheDir, "minshawy_002255.mp3")
                if (!f.exists() || f.length() < 10000L) {
                    val conn = URL(AUDIO_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.inputStream.use { inp ->
                        FileOutputStream(f).use { out -> inp.copyTo(out) }
                    }
                }
                val refWave = decodeMp3ToMono16kHz(f)
                if (refWave != null && refWave.size > SR * 15) {
                    val (wWaves, _, _) = segmentFullRecitationInto50Words(refWave)
                    refWordTemplates = wWaves
                }
            } catch (e: Exception) {
                // في حال عدم توفر الإنترنت، يعمل المحرك الفيزيائي المدمج بكامل طاقته دون توقف
            } finally {
                loadingRef = false
            }
        }.start()
    }

    private fun decodeMp3ToMono16kHz(file: File): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 22050
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream(SR * 2 * 50)
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                }
            }
            codec.stop()
            codec.release()

            val bytes = pcmOut.toByteArray()
            val totalShorts = bytes.size / 2
            val framesCount = totalShorts / max(1, channels)
            val monoSrc = FloatArray(framesCount)
            var bIdx = 0
            for (i in 0 until framesCount) {
                var sum = 0f
                for (c in 0 until channels) {
                    val lo = bytes[bIdx].toInt() and 0xFF
                    val hi = bytes[bIdx + 1].toInt()
                    val s = ((hi shl 8) or lo).toShort()
                    sum += s.toFloat() / 32768f
                    bIdx += 2
                }
                monoSrc[i] = sum / channels
            }
            if (srcRate == SR) return monoSrc
            val dstLen = (framesCount.toDouble() * SR.toDouble() / srcRate.toDouble()).toInt()
            val resampled = FloatArray(dstLen)
            val ratio = srcRate.toDouble() / SR.toDouble()
            for (i in 0 until dstLen) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, framesCount - 1)
                val idx1 = (idx0 + 1).coerceIn(0, framesCount - 1)
                val frac = (srcPos - idx0).toFloat()
                resampled[i] = monoSrc[idx0] * (1f - frac) + monoSrc[idx1] * frac
            }
            return resampled
        } catch (e: Exception) {
            return null
        } finally {
            try { extractor.release() } catch (e: Exception) { }
        }
    }

    fun pcmBytesToFloatArray(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            out[i] = s.toFloat() / 32768f
        }
        return out
    }

    /** تحويل فورييه السريع (Radix-2 FFT بطول 512) لحساب الطيف الترددي بدقة 31.25Hz لكل خانة. */
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
        for (b in 0 until halfBins) {
            power[b] = re[b] * re[b] + im[b] * im[b]
        }
        return power
    }

    fun extractFrameFeatures(wave: FloatArray): FrameFeatures {
        val frameLen = 400 // 25ms at 16kHz
        val hopLen = 160   // 10ms at 16kHz
        val padded = if (wave.size < frameLen) FloatArray(frameLen).also { wave.copyInto(it) } else wave
        val nFrames = 1 + (padded.size - frameLen) / hopLen
        val window = FloatArray(frameLen) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (frameLen - 1))).toFloat()
        }

        val rms = FloatArray(nFrames)
        val zcr = FloatArray(nFrames)
        val eF1Closed = FloatArray(nFrames) // bins 8..15  (250 - 468 Hz)
        val eF1Open = FloatArray(nFrames)   // bins 19..27 (593 - 843 Hz)
        val eF2Damma = FloatArray(nFrames)  // bins 25..37 (781 - 1156 Hz)
        val eF2Fatha = FloatArray(nFrames)  // bins 38..54 (1187 - 1687 Hz)
        val eF2Kasra = FloatArray(nFrames)  // bins 46..86 (1437 - 2687 Hz)
        val eTafashie = FloatArray(nFrames) // bins 64..121 (2000 - 3781 Hz)
        val eSibilant = FloatArray(nFrames) // bins 134..243 (4187 - 7593 Hz)

        val rawWin = FloatArray(frameLen)
        val preWin = FloatArray(frameLen)

        fun bandSum(spec: FloatArray, b0: Int, b1: Int): Float {
            var s = 0f
            for (b in b0..min(b1, spec.size - 1)) s += spec[b]
            return s
        }

        for (f in 0 until nFrames) {
            val base = f * hopLen
            var sq = 0f
            var zc = 0
            for (k in 0 until frameLen) {
                val v = padded[base + k]
                sq += v * v
                if (k > 0 && ((v >= 0f) != (padded[base + k - 1] >= 0f))) zc++
                rawWin[k] = v * window[k]
                val prev = if (k == 0) v else padded[base + k - 1]
                preWin[k] = (v - 0.95f * prev) * window[k]
            }
            rms[f] = sqrt(sq / frameLen + 1e-12f)
            zcr[f] = zc.toFloat() / (frameLen - 1).toFloat()

            val rawSpec = fft512Power(rawWin)
            var rawTot = 1e-12f
            for (v in rawSpec) rawTot += v
            eF1Closed[f] = bandSum(rawSpec, 8, 15) / rawTot
            eF1Open[f] = bandSum(rawSpec, 19, 27) / rawTot

            val preSpec = fft512Power(preWin)
            var preTot = 1e-12f
            for (v in preSpec) preTot += v
            eF2Damma[f] = bandSum(preSpec, 25, 37) / preTot
            eF2Fatha[f] = bandSum(preSpec, 38, 54) / preTot
            eF2Kasra[f] = bandSum(preSpec, 46, 86) / preTot
            eTafashie[f] = bandSum(preSpec, 64, 121) / preTot
            eSibilant[f] = bandSum(preSpec, 134, 243) / preTot
        }
        return FrameFeatures(rms, zcr, eF1Closed, eF1Open, eF2Damma, eF2Fatha, eF2Kasra, eTafashie, eSibilant)
    }

    private fun computeExpectedWeights(): FloatArray {
        val weights = FloatArray(50)
        for (w in 0 until 50) {
            val sp = WORD_SPECS[w]
            val wq = w in NATURAL_WAQF_SET
            val vowels = if (wq) sp.waqfVowels else sp.waslVowels
            var durMs = 0f
            if (sp.safeer) durMs += 75f
            else if (sp.tafashie) durMs += 85f
            else if (sp.interdental) durMs += 70f
            for (v in vowels) {
                durMs += if (v.length >= 2) {
                    if (sp.maddType == "MUTTASIL") 580f
                    else if (sp.maddType == "MUNFASIL" && !wq) 420f
                    else 280f
                } else 140f
            }
            if (sp.reqGhunnah && !wq) durMs += 240f
            weights[w] = durMs / 100f
        }
        return weights
    }

    private fun forcedAlignPhraseWords(
        feats: FrameFeatures,
        wStart: Int,
        wEnd: Int,
        fStart: Int,
        fEnd: Int,
        expWeights: FloatArray
    ): List<Pair<Int, Int>> {
        val nWords = wEnd - wStart + 1
        if (nWords <= 1) return listOf(Pair(fStart, fEnd))
        val phraseLen = max(nWords * 4, fEnd - fStart)
        var totalSubW = 0f
        for (w in wStart..wEnd) totalSubW += expWeights[w]
        val expLens = FloatArray(nWords) { k -> max(4f, phraseLen * (expWeights[wStart + k] / max(0.1f, totalSubW))) }
        val expCum = FloatArray(nWords + 1)
        for (k in 0 until nWords) expCum[k + 1] = expCum[k] + expLens[k]

        val INF = 1e9f
        val dp = Array(nWords + 1) { FloatArray(phraseLen + 1) { INF } }
        val back = Array(nWords + 1) { IntArray(phraseLen + 1) }
        dp[0][0] = 0f

        var maxRms = 1e-6f
        for (f in fStart until min(feats.rms.size, fEnd)) {
            if (feats.rms[f] > maxRms) maxRms = feats.rms[f]
        }

        val band = max(18, (phraseLen * 0.22f).toInt())
        for (k in 1..nWords) {
            val expPos = expCum[k].roundToInt()
            val expLen = expLens[k - 1]
            val fLo = if (k == nWords) phraseLen else max(k * 4, expPos - band)
            val fHi = if (k == nWords) phraseLen else min(phraseLen - (nWords - k) * 4, expPos + band)
            val prevExpPos = expCum[k - 1].roundToInt()
            val pLo = if (k == 1) 0 else max((k - 1) * 4, prevExpPos - band)
            val pHi = if (k == 1) 0 else min(phraseLen, prevExpPos + band)

            for (f in fLo..fHi) {
                val rmsIdx = (fStart + min(f, phraseLen - 1)).coerceIn(0, feats.rms.size - 1)
                val dipCost = if (k < nWords) (feats.rms[rmsIdx] / maxRms) * 10.0f else 0f
                var bestC = INF
                var bestP = pLo
                val maxP = min(f - 3, pHi)
                for (p in pLo..maxP) {
                    val prevC = dp[k - 1][p]
                    if (prevC >= INF) continue
                    val actualLen = (f - p).toFloat()
                    val denom = max(6f, expLen * 0.35f)
                    val diff = (actualLen - expLen) / denom
                    val c = prevC + diff * diff + dipCost
                    if (c < bestC) {
                        bestC = c
                        bestP = p
                    }
                }
                dp[k][f] = bestC
                back[k][f] = bestP
            }
        }

        val spans = ArrayList<Pair<Int, Int>>(nWords)
        for (k in 0 until nWords) spans.add(Pair(fStart, fEnd))
        var curF = phraseLen
        for (k in nWords downTo 1) {
            val prevF = back[k][curF]
            spans[k - 1] = Pair(fStart + prevF, min(feats.rms.size, fStart + curF))
            curF = prevF
        }
        return spans
    }

    fun segmentFullRecitationInto50Words(wave: FloatArray): Triple<List<FloatArray>, BooleanArray, Float> {
        val feats = extractFrameFeatures(wave)
        val rms = feats.rms
        val nFrames = rms.size
        val hopSamples = 160
        if (nFrames < 50) {
            return Triple(List(50) { FloatArray(0) }, BooleanArray(50), 105f)
        }
        val sortedRms = rms.clone().also { it.sort() }
        val peakRms = sortedRms[(nFrames * 0.95).toInt().coerceIn(0, nFrames - 1)]
        val silenceThresh = max(peakRms * 0.05f, 1e-4f)
        val isSilent = BooleanArray(nFrames) { rms[it] < silenceThresh }

        class PauseSpan(val s: Int, val e: Int, val dur: Int)
        val pauses = ArrayList<PauseSpan>()
        var runStart = -1
        for (i in 0 until nFrames) {
            if (isSilent[i]) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    val dur = i - runStart
                    if (runStart > 5 && dur >= 24) pauses.add(PauseSpan(runStart, i, dur))
                    runStart = -1
                }
            }
        }

        val expWeights = computeExpectedWeights()
        val cumWeights = FloatArray(50)
        var acc = 0f
        for (w in 0 until 50) { acc += expWeights[w]; cumWeights[w] = acc }
        val totalWeight = cumWeights[49]

        var firstFrame = 0
        while (firstFrame < nFrames && isSilent[firstFrame]) firstFrame++
        var lastFrame = nFrames - 1
        while (lastFrame > firstFrame && isSilent[lastFrame]) lastFrame--
        lastFrame = min(nFrames, lastFrame + 1)

        var pauseFramesTotal = 0
        for (p in pauses) if (p.s >= firstFrame && p.e <= lastFrame) pauseFramesTotal += p.dur
        val totalSpeechFrames = max(50, (lastFrame - firstFrame) - pauseFramesTotal)

        class PhraseSpan(val wStart: Int, val wEnd: Int, val fStart: Int, val fEnd: Int)
        val phraseSpans = ArrayList<PhraseSpan>()
        var curW = 0
        var curF = firstFrame
        var spokenBefore = 0

        for (p in pauses) {
            if (p.s <= curF + 10 || p.e >= lastFrame - 10) continue
            spokenBefore += (p.s - curF)
            val targetCumW = (spokenBefore.toFloat() / totalSpeechFrames.toFloat()) * totalWeight
            var bestW = curW
            var bestDist = 1e9f
            for (candW in curW until 49) {
                var dist = abs(cumWeights[candW] - targetCumW)
                if (candW !in NATURAL_WAQF_SET) dist += 8.0f
                if (dist < bestDist) {
                    bestDist = dist
                    bestW = candW
                }
            }
            phraseSpans.add(PhraseSpan(curW, bestW, curF, p.s))
            curW = bestW + 1
            curF = p.e
            if (curW >= 50) break
        }
        if (curW < 50) phraseSpans.add(PhraseSpan(curW, 49, curF, lastFrame))

        val wordWaves = ArrayList<FloatArray>(50)
        for (w in 0 until 50) wordWaves.add(FloatArray(0))
        val isWaqf = BooleanArray(50)

        for (ps in phraseSpans) {
            isWaqf[ps.wEnd] = true
            val intervals = forcedAlignPhraseWords(feats, ps.wStart, ps.wEnd, ps.fStart, ps.fEnd, expWeights)
            for (idxOffset in intervals.indices) {
                val wIdx = ps.wStart + idxOffset
                val (wf0, wf1) = intervals[idxOffset]
                val s0 = (wf0 * hopSamples).coerceIn(0, wave.size)
                val s1 = (wf1 * hopSamples).coerceIn(s0, wave.size)
                wordWaves[wIdx] = wave.copyOfRange(s0, s1)
            }
        }

        val ref2BeatWords = intArrayOf(7, 13, 27)
        val unitMsList = ArrayList<Float>()
        for (rw in ref2BeatWords) {
            val ww = wordWaves[rw]
            if (ww.size > 400) {
                val wf = extractFrameFeatures(ww)
                val pk = wf.rms.maxOrNull() ?: 0f
                var vf = 0
                for (i in wf.rms.indices) if (wf.rms[i] > pk * 0.18f && wf.zcr[i] < 0.28f) vf++
                if (vf >= 4) unitMsList.add((vf * 10f) / 2f)
            }
        }
        unitMsList.sort()
        val calHarakah = if (unitMsList.isNotEmpty()) unitMsList[unitMsList.size / 2].coerceIn(75f, 160f) else 105f
        return Triple(wordWaves, isWaqf, calHarakah)
    }

    private fun classifyVowelSegment(feats: FrameFeatures, fStart: Int, fEnd: Int): String {
        if (fEnd <= fStart) return "a"
        var f1Open = 0f
        var f1Closed = 0f
        var f2Damma = 0f
        var f2Kasra = 0f
        val cnt = (fEnd - fStart).toFloat()
        for (i in fStart until fEnd) {
            f1Open += feats.eF1Open[i]
            f1Closed += feats.eF1Closed[i]
            f2Damma += feats.eF2Damma[i]
            f2Kasra += feats.eF2Kasra[i]
        }
        f1Open /= cnt; f1Closed /= cnt; f2Damma /= cnt; f2Kasra /= cnt
        if (f1Open > f1Closed * 1.15f) return "a"
        if (f2Kasra > f2Damma * 1.10f) return "i"
        return "u"
    }

    fun auditSingleWordAcoustics(
        wordIdx: Int,
        wordWave: FloatArray,
        isWaqf: Boolean,
        harakahMs: Float,
        lenientMadd: Boolean
    ): List<String> {
        val sp = WORD_SPECS[wordIdx]
        val expVowels = if (isWaqf) sp.waqfVowels else sp.waslVowels
        val errs = ArrayList<String>()
        if (wordWave.size < 800) {
            errs.add("لم يُلتقط صوت كافٍ لهذه الكلمة (قد تكون تُركت أو قُرئت بسرعة خاطفة).")
            return errs
        }
        val feats = extractFrameFeatures(wordWave)
        val nFrames = feats.rms.size
        val peakRms = feats.rms.maxOrNull() ?: 0f
        if (peakRms < 0.015f) {
            errs.add("لم تُقرأ هذه الكلمة بصوت مسموع.")
            return errs
        }

        val activeIdx = ArrayList<Int>()
        for (i in 0 until nFrames) {
            if (feats.rms[i] > peakRms * 0.18f && feats.zcr[i] < 0.28f) activeIdx.add(i)
        }
        val voicedMs = activeIdx.size * 10f

        // 1. فحص أزمنة المدود بالملي ثانية
        if (sp.maddType == "MUTTASIL") {
            val reqMs = harakahMs * (if (lenientMadd) 3.2f else 3.6f)
            if (voicedMs < reqMs) {
                errs.add("خطأ في التجويد (المد الواجب المتصل): قصر مد «${sp.text}» عن مقداره الواجب (٤–٥ حركات).")
            }
        } else if (sp.maddType == "MUNFASIL" && !isWaqf) {
            val reqMs = harakahMs * (if (lenientMadd) 1.7f else 3.2f)
            if (voicedMs < reqMs) {
                errs.add("خطأ في التجويد (المد المنفصل / الصلة الكبرى): قصر المد في «${sp.text}» عن الحد المعتبر.")
            }
        } else if (sp.maddType == "TABEEI" && !isWaqf) {
            val minWordMs = harakahMs * (sp.minHarakat * 0.58f)
            if (voicedMs < minWordMs) {
                errs.add("خطأ في التجويد (المد الطبيعي): إسقاط أو قصر حرف المد في «${sp.text}».")
            }
        }

        // 2. فحص الغنة والإخفاء والإدغام وصلاً
        if (sp.reqGhunnah && !isWaqf) {
            var nasalFrames = 0
            for (i in 0 until nFrames) {
                if (feats.rms[i] > peakRms * 0.12f && feats.eF1Closed[i] > 0.35f && feats.eF1Open[i] < 0.20f) {
                    nasalFrames++
                }
            }
            val nasalMs = nasalFrames * 10f
            if (nasalMs < harakahMs * 1.15f) {
                errs.add("خطأ في التجويد (الغنة / الإخفاء / الإدغام): نقص زمن الغنة أو إظهار التنوين/النون في «${sp.text}».")
            }
        }

        // 3. فحص إبدال الذال أو الظاء بحرف صفير (زاي / سين)
        if (sp.interdental && !sp.safeer) {
            val maxSib = feats.eSibilant.maxOrNull() ?: 0f
            if (maxSib > 0.38f) {
                val letter = if (sp.text.contains("ظ")) "الظاء" else "الذال"
                errs.add("خطأ في مخرج الحرف: نطقت $letter بحرف صفير (زاي/سين) في «${sp.text}» — احرص على إخراج طرف اللسان.")
            }
        }

        // 4. فحص إبدال الشين سيناً
        if (sp.tafashie && !sp.safeer) {
            val maxSib = feats.eSibilant.maxOrNull() ?: 0f
            val maxTaf = feats.eTafashie.maxOrNull() ?: 0f
            if (maxSib > 0.38f && maxSib > maxTaf * 1.45f) {
                errs.add("خطأ في مخرج الحرف وصفته: إبدال الشين سيناً (ضعف التفشي وزيادة الصفير) في «${sp.text}».")
            }
        }

        // 5. فحص التشكيل (الحركات في أول الكلمة وآخرها — مثل سِنَةً بالفتح مقابل سِنَةٌ بالضم)
        if (activeIdx.size >= 6 && expVowels.isNotEmpty()) {
            val weights = FloatArray(expVowels.size) { k ->
                val v = expVowels[k]
                if (v.length >= 2) {
                    if (sp.maddType == "MUTTASIL") 4.0f
                    else if (sp.maddType == "MUNFASIL" && !isWaqf) 3.0f
                    else 2.0f
                } else 1.0f
            }
            val ghW = if (sp.reqGhunnah && !isWaqf) 1.6f else 0f
            val vowelSum = weights.sum()
            val totalW = vowelSum + ghW
            val nAct = activeIdx.size
            val vEndIdx = (nAct * (vowelSum / totalW)).roundToInt().coerceIn(2, nAct)
            val firstEnd = (nAct * (weights[0] / totalW)).roundToInt().coerceIn(2, vEndIdx)
            val lastStart = (nAct * ((vowelSum - weights.last()) / totalW)).roundToInt().coerceIn(0, max(0, vEndIdx - 2))

            fun vName(v: String): String = when (v) {
                "a" -> "الفتحة"
                "u" -> "الضمة"
                "i" -> "الكسرة"
                else -> v
            }

            val firstBase = expVowels.first().first().toString()
            val predFirst = classifyVowelSegment(feats, activeIdx[0], activeIdx[firstEnd - 1] + 1)
            if (predFirst != firstBase) {
                errs.add("خطأ في التشكيل (أول الكلمة): نطقت «${vName(predFirst)}» بدلاً من «${vName(firstBase)}» في أول «${sp.text}».")
            }
            if (expVowels.size >= 2 && vEndIdx >= 2) {
                val lastBase = expVowels.last().first().toString()
                val predLast = classifyVowelSegment(feats, activeIdx[lastStart], activeIdx[vEndIdx - 1] + 1)
                if (predLast != lastBase) {
                    errs.add("خطأ في التشكيل (آخر الكلمة / الإعراب): نطقت «${vName(predLast)}» بدلاً من «${vName(lastBase)}» في آخر «${sp.text}».")
                }
            }
        }

        // 6. فحص التفخيم والترقيق عبر تردد الفورمانت الثاني F2
        if (sp.tafkheem && activeIdx.size >= 4) {
            var minKasraRatio = 999f
            for (idx in activeIdx) {
                val r = feats.eF2Kasra[idx] / (feats.eF2Damma[idx] + 1e-5f)
                if (r < minKasraRatio) minKasraRatio = r
            }
            if (minKasraRatio > 1.85f) {
                errs.add("خطأ في صفات الحروف (التفخيم): ترقيق الحرف المفخم في «${sp.text}».")
            }
        }

        // 7. المقارنة الطيفية المباشرة (CMVN-DTW) بالتلاوة المرجعية للشيخ المنشاوي إن كانت محمّلة
        val refs = refWordTemplates
        if (refs != null && wordIdx in refs.indices && refs[wordIdx].size > 800) {
            val dtwDist = cmvnDtwDistance(feats, extractFrameFeatures(refs[wordIdx]))
            if (dtwDist > 0.42f && errs.isEmpty()) {
                errs.add("خطأ في النطق أو التشكيل (مقارنةً بمرجع الشيخ المنشاوي): انحراف البصمة الصوتية للكلمة عن النطق المرجعي الصحيح.")
            }
        }

        return errs
    }

    private fun cmvnDtwDistance(fU: FrameFeatures, fR: FrameFeatures): Float {
        fun extractActiveMatrix(f: FrameFeatures): Array<FloatArray> {
            val pk = f.rms.maxOrNull() ?: 0f
            val list = ArrayList<FloatArray>()
            for (i in f.rms.indices) {
                if (f.rms[i] > pk * 0.15f) {
                    list.add(
                        floatArrayOf(
                            f.eF1Closed[i], f.eF1Open[i], f.eF2Damma[i],
                            f.eF2Fatha[i], f.eF2Kasra[i], f.eTafashie[i], f.eSibilant[i]
                        )
                    )
                }
            }
            if (list.isEmpty()) {
                list.add(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f))
            }
            return list.toTypedArray()
        }

        val mu = extractActiveMatrix(fU)
        val mr = extractActiveMatrix(fR)
        val n = mu.size
        val m = mr.size
        val w = m + 1
        val dp = FloatArray((n + 1) * w) { 1e9f }
        dp[0] = 0f
        for (i in 1..n) {
            val row = i * w
            val prev = (i - 1) * w
            val uVec = mu[i - 1]
            for (j in 1..m) {
                val rVec = mr[j - 1]
                var sq = 0f
                for (d in 0 until 7) {
                    val diff = uVec[d] - rVec[d]
                    sq += diff * diff
                }
                val cost = sqrt(sq)
                val bestPrev = min(dp[prev + j - 1], min(dp[prev + j], dp[row + j - 1]))
                dp[row + j] = cost + bestPrev
            }
        }
        return dp[n * w + m] / max(n, m).toFloat()
    }

    /** فحص صوتي وفيزيائي شامل للتلاوة المسجلة (50 كلمة) يعمل مباشرة على الهاتف بدون خادم أو بجانبه. */
    fun audit(ctx: Context, pcm: ByteArray, nWords: Int, lenientMadd: Boolean): RemoteAuditor.AuditResult {
        ensureReferenceLoaded(ctx)
        val wave = pcmBytesToFloatArray(pcm)
        if (wave.size < SR * 2) {
            return RemoteAuditor.AuditResult(
                List(nWords) { RemoteAuditor.WordResult(false, "التسجيل قصير جداً.", "") },
                "",
                "التسجيل قصير جداً، يرجى قراءة الآية بصوت واضح."
            )
        }
        val (wordWaves, isWaqf, calHarakah) = segmentFullRecitationInto50Words(wave)
        val results = ArrayList<RemoteAuditor.WordResult>(nWords)
        for (w in 0 until nWords) {
            if (w >= 50) {
                results.add(RemoteAuditor.WordResult(true, "", ""))
                continue
            }
            val errs = auditSingleWordAcoustics(w, wordWaves[w], isWaqf[w], calHarakah, lenientMadd)
            val ok = errs.isEmpty()
            val note = if (ok) "" else errs.joinToString("\n• ", prefix = "• ")
            results.add(RemoteAuditor.WordResult(ok, note, ""))
        }
        return RemoteAuditor.AuditResult(results, "OnDevice-Physical-Acoustic-DSP-v5", null)
    }
}

/**
 * الفحص الدقيق الشامل (الإصدار الخامس):
 * يجمع بين:
 * 1) الفحص الفيزيائي الطيفي والمرجعي المدمج (LocalAcousticAuditor: فورمانت التشكيل F1/F2 + أزمنة المدود بالملي ثانية + رنين الغنة + الصفير والتفشي + مقارنة مرجع الشيخ المنشاوي DTW).
 * 2) الفحص العصبي الفونيمي عبر خادم quran-muaalem (إن وُجد عنوان خادم) مع محاذاة الوقف والوصل والابتداء (QPS DP Aligner).
 * وتُطبَّق قاعدة الاتحاد الصارم: لا تُحسب الكلمة صحيحة إلا إذا اجتازت الفحصين معاً دون أي خطأ تشكيل أو تجويد أو حرف!
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
     * نقطة الدخول الموحدة للفحص الشامل (تعمل دائماً سواء وُجد عنوان خادم خارجي أم لم يوجد):
     * - تشغّل المحرك الفيزيائي الطيفي والمرجعي المدمج (LocalAcousticAuditor) لفحص التشكيل والمدود والغنة والمخارج.
     * - إن وُجد عنوان خادم، تشغّل أيضاً الفحص العصبي (audit) وتدمج نتائج المحركين معاً بصرامة تامة.
     */
    fun auditComprehensive(
        ctx: Context,
        baseUrl: String,
        pcm: ByteArray,
        sura: Int,
        aya: Int,
        nWords: Int,
        lenient: Boolean
    ): AuditResult {
        val localRes = LocalAcousticAuditor.audit(ctx, pcm, nWords, lenient)
        if (baseUrl.isBlank()) {
            return localRes
        }
        val remoteRes = audit(baseUrl, pcm, sura, aya, nWords, lenient)
        if (remoteRes.error != null) {
            return remoteRes
        }
        // دمج صارم (Strict Union): أي خطأ يكتشفه الخادم العصبي أو المحلل الفيزيائي الطيفي يُرصد فوراً!
        val mergedWords = ArrayList<WordResult>(nWords)
        for (i in 0 until nWords) {
            val rw = remoteRes.words[i]
            val lw = localRes.words[i]
            val ok = rw.ok && lw.ok
            val sb = StringBuilder()
            if (rw.note.isNotEmpty()) sb.append(rw.note)
            if (lw.note.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("التحليل الصوتي الطيفي والمرجعي:\n").append(lw.note)
            }
            mergedWords.add(WordResult(ok, sb.toString(), rw.heard))
        }
        return AuditResult(mergedWords, remoteRes.raw, null)
    }

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

        val clickGuard = (SAMPLE_RATE * 0.06).toInt()
        if (nSamples > clickGuard * 4) {
            for (i in 0 until clickGuard) {
                val fade = i.toFloat() / clickGuard.toFloat()
                samples[i] = (samples[i] * fade).toInt().toShort()
                val rev = nSamples - 1 - i
                samples[rev] = (samples[rev] * fade).toInt().toShort()
            }
        }

        val frameLen = SAMPLE_RATE / 40
        val hopLen = SAMPLE_RATE / 100
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

        val predPh = j.optString("predicted_phonemes", "").replace(" ", "")
        if (sura == 2 && aya == 255 && n == 50 && predPh.isNotEmpty()) {
            val qpsRes = alignQpsToAyatKursi(predPh, lenient)
            if (qpsRes != null) {
                return AuditResult(qpsRes, raw, null)
            }
        }

        val base = maxOf(0, st.optInt("uthmani_word_idx", 0))
        val startCharIdx = st.optInt("uthmani_char_idx", 0)
        val endObj = j.optJSONObject("end")
        val covered = text.count { it == ' ' } + 1
        val ok = BooleanArray(n) { false }
        val notes = Array(n) { StringBuilder() }
        val last = minOf(n, base + covered)
        for (w in base until last) ok[w] = true

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
                while (p0 < p1 && p0 < text.length && text[p0] == ' ') p0++
                while (p1 > p0 && p1 - 1 < text.length && text[p1 - 1] == ' ') p1--
                var w0 = base + wordAt(text, p0)
                var w1 = base + wordAt(text, maxOf(p0, p1 - 1))
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

    private fun isValidWaqfFalsePositive(w: Int, e: JSONObject): Boolean {
        val ex = e.optString("expected_ph", "")
        val gt = e.optString("predicted_ph", e.optString("preditected_ph", ""))
        if (w in setOf(5, 6, 15, 18, 21, 42, 48, 49) && ex.isEmpty() && (gt == "ءَ" || gt == "ء")) {
            return true
        }
        if (w !in VALID_WAQF_WORDS) return false
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
