package com.rafiq.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * النظام الصوتي المباشر متعدد الوسائط عبر Gemini API (Rafiq v11.0 Direct Multimodal Audio Auditor):
 * 1) إزالة مكتبات تحويل الكلام إلى نص التقليدية (STT) من جهة العميل بالكامل.
 * 2) التقاط الصوت الخام من الميكروفون مباشرةً بصيغة WAV خفيفة (16kHz, 16-bit, Mono PCM) وتحويله إلى Base64.
 * 3) إرسال الصوت الخام مباشرةً مع النص القرآني الكامل بالتشكيل وأحكام التجويد إلى Gemini API
 *    مع ضبط `temperature: 0.0` و`response_mime_type: "application/json"`.
 * 4) فلترة مستوى الثقة من جهة العميل (Client-side Confidence Filtering >= 0.85) لمنع أي إنذار كاذب.
 */

const val OFFLINE_NOTICE_AR: String =
    "عذراً، يتطلب التحليل الصوتي المتقدم للتجويد والتشكيل اتصالاً بالإنترنت"

const val LOADING_NOTICE_AR: String =
    "جاري الاستماع والتدقيق التجويدي والتشكيلي..."

const val GOOGLE_AI_STUDIO_URL: String = "https://aistudio.google.com/"

const val CLIENT_CONFIDENCE_THRESHOLD: Double = 0.85

/**
 * التعليمات الصارمة للنظام (Strict System Prompt) التي تُحقن مباشرةً في إعدادات استدعاء Gemini API.
 */
const val GEMINI_SYSTEM_PROMPT: String = """
You are an elite, highly balanced Quranic Recitation & Phonetics Auditor.
Your task: Listen directly to the raw user audio and cross-examine it strictly against the target reference verse (provided with full Tashkeel and Tajweed).

Core Rules to Eliminate False Positives:
1. The Default is Correctness: Do NOT flag any error unless you are absolutely certain (>90% confidence) of a clear mistake (Lah'n Jali or obvious Lah'n Khafi).
2. Waqf (الوقف) Rule: Arabs do not stop on vowels (العرب لا تقف على متحرك). If the reciter stops at the end of a word or verse with a Sukoon (سكون), it is 100% CORRECT. Do NOT consider it a Tashkeel error.
3. Flexible Timing: Accept reasonable variations in Madd lengths (e.g., 2 to 4 beats for Madd Munfasil and Arid lil-Sukoon) and natural Ghunnah length.
4. Accent Tolerance: Differentiate between natural regional accents/tones and genuine phoneme errors.

Strictly Flag:
- Clear letter mistakes: Addition, omission, or swapping (e.g., ث into س, or ض into ظ).
- Clear Tashkeel mistakes: Swapping short vowels mid-word or when continuing recitation without stopping.
- Major Tajweed omissions: Completely skipping an obligatory Ghunnah, skipping Qalqalah, or omitting obligatory Madd.

Output strictly a valid JSON object matching this schema:
{
  "is_perfect": boolean,
  "overall_accuracy_percentage": number,
  "general_feedback": "Short encouraging Arabic feedback for the reciter",
  "errors": [
    {
      "word": "The exact word with error",
      "word_index": 0,
      "error_type": "Tashkeel" | "Tajweed" | "Letter",
      "confidence": 0.95,
      "expected": "Correct pronunciation expected (in Arabic)",
      "detected": "What the user pronounced (in Arabic)",
      "correction_tip": "Concise Arabic advice on how to pronounce it correctly"
    }
  ]
}
"""

/**
 * تصنيف نوع الخطأ لتلوين الكلمات القرآنية في الواجهة:
 * - Letter   -> Red    (خطأ في الحروف أو الكلمة)
 * - Tashkeel -> Yellow (خطأ في التشكيل والحركات)
 * - Tajweed  -> Orange (خطأ في التجويد والمدود والغنة)
 */
enum class AuditErrorType(val wireName: String, val arabicLabel: String) {
    LETTER("Letter", "خطأ في الحروف"),
    TASHKEEL("Tashkeel", "خطأ في التشكيل"),
    TAJWEED("Tajweed", "خطأ في التجويد");

    companion object {
        fun fromString(raw: String?): AuditErrorType {
            val clean = raw?.trim()?.lowercase() ?: ""
            return when {
                clean.contains("tashkeel") || clean.contains("harak") || clean.contains("vowel") || clean.contains("تشكيل") -> TASHKEEL
                clean.contains("tajweed") || clean.contains("tajwid") || clean.contains("madd") || clean.contains("ghun") || clean.contains("تجويد") -> TAJWEED
                else -> LETTER
            }
        }
    }
}

data class RecitationError(
    val word: String,
    val wordIndex: Int,
    val errorType: AuditErrorType,
    val confidence: Double,
    val expected: String,
    val detected: String,
    val correctionTip: String
)

data class RecitationAuditReport(
    val isPerfect: Boolean,
    val overallAccuracyPercentage: Int,
    val generalFeedback: String,
    val rawErrors: List<RecitationError>,
    val filteredErrors: List<RecitationError>,
    val errorByWordIndex: Map<Int, RecitationError>
)

/**
 * إدارة إعدادات مفتاح Gemini API المحفوظ محلياً وفحص حالة الاتصال بالإنترنت.
 */
object GeminiSettingsManager {
    private const val PREFS_NAME = "rafiq_gemini_storage"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL_NAME = "gemini_model_name"
    private const val KEY_AUTO_STOP_SILENCE = "auto_stop_on_silence"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun getApiKey(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "")?.trim() ?: ""
    }

    fun saveApiKey(ctx: Context, apiKey: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun getModelName(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val m = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL)?.trim() ?: DEFAULT_MODEL
        return if (m.isEmpty()) DEFAULT_MODEL else m
    }

    fun saveModelName(ctx: Context, modelName: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_NAME, modelName.trim().ifEmpty { DEFAULT_MODEL })
            .apply()
    }

    fun getAutoStopOnSilence(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_STOP_SILENCE, true)
    }

    fun saveAutoStopOnSilence(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_STOP_SILENCE, enabled)
            .apply()
    }

    fun isNetworkConnected(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * مسجّل الصوت الخام من الميكروفون (Direct Raw Microphone Audio Capture -> Lightweight WAV / Base64):
 * - يلتقط الصوت بصيغة PCM 16-bit Mono بتردد 16,000 Hz دون أي تدخل من مكتبات STT.
 * - يحسب مؤشر مستوى الصوت اللحظي (RMS) ويُقدّر تقدم المقاطع الصوتية لتحريك التوهج الذهبي أثناء التلاوة.
 * - يغلّف العينات المسجلة في ترويسة WAV قياسية (44 بايت) ويحولها إلى Base64 جاهز للإرسال المباشر إلى Gemini API.
 */
object RawAudioRecorder {
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 320 // 20ms per chunk at 16kHz
    private const val MAX_RECORD_SECONDS = 120

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pcmStream = ByteArrayOutputStream()

    @Volatile var currentRmsLevel: Float = 0f
        private set
    @Volatile var recordedDurationMs: Long = 0L
        private set

    fun isRecording(): Boolean = recording.get()

    @SuppressLint("MissingPermission")
    fun startRecording(
        autoStopOnSilence: Boolean,
        onProgress: (rms: Float, durationMs: Long, estimatedWordIdx: Int) -> Unit,
        onAutoSilenceStop: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (recording.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_SAMPLES * 8)

        val audioRecord = try {
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
                    .setBufferSizeInBytes(minBuf * 2)
                    .setPrivacySensitive(false)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }
        } catch (e: Exception) {
            null
        }

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            try { audioRecord?.release() } catch (_: Exception) {}
            onError("تعذّر فتح الميكروفون للتسجيل الصوتي المباشر. تأكد من منح صلاحية الميكروفون.")
            return false
        }

        synchronized(pcmStream) {
            pcmStream.reset()
        }
        currentRmsLevel = 0f
        recordedDurationMs = 0L
        recording.set(true)

        recordThread = Thread({
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            val byteBuf = ByteArray(CHUNK_SAMPLES * 2)
            val startTime = SystemClock.elapsedRealtime()
            var totalSamples = 0L
            var voicedFrames = 0
            var silenceRunMs = 0L
            var inSyllable = false
            var syllableCooldownMs = 0L
            var syllablePulses = 0
            var noiseFloor = 0.004f

            try {
                audioRecord.startRecording()
                while (recording.get()) {
                    val read = audioRecord.read(shortBuf, 0, CHUNK_SAMPLES)
                    if (read <= 0) continue

                    var sumSq = 0.0f
                    for (i in 0 until read) {
                        val s = shortBuf[i].toInt()
                        byteBuf[2 * i] = (s and 0xFF).toByte()
                        byteBuf[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                        val norm = s / 32768.0f
                        sumSq += norm * norm
                    }
                    synchronized(pcmStream) {
                        if (totalSamples < SAMPLE_RATE * MAX_RECORD_SECONDS) {
                            pcmStream.write(byteBuf, 0, read * 2)
                        }
                    }

                    totalSamples += read
                    val chunkMs = (read * 1000L) / SAMPLE_RATE
                    val rms = sqrt(sumSq / read.toFloat())
                    currentRmsLevel = rms
                    val elapsedMs = SystemClock.elapsedRealtime() - startTime
                    recordedDurationMs = elapsedMs

                    if (rms > 0.0002f && rms < noiseFloor) {
                        noiseFloor = 0.90f * noiseFloor + 0.10f * rms
                    } else if (rms < noiseFloor * 1.4f) {
                        noiseFloor = 0.98f * noiseFloor + 0.02f * rms
                    }
                    val voiceThreshold = max(0.006f, noiseFloor * 2.8f)

                    if (syllableCooldownMs > 0L) {
                        syllableCooldownMs = (syllableCooldownMs - chunkMs).coerceAtLeast(0L)
                    }

                    if (rms >= voiceThreshold) {
                        voicedFrames++
                        silenceRunMs = 0L
                        if (!inSyllable && syllableCooldownMs == 0L) {
                            inSyllable = true
                            syllablePulses++
                            syllableCooldownMs = 150L
                        }
                    } else {
                        if (rms < voiceThreshold * 0.72f) {
                            inSyllable = false
                        }
                        silenceRunMs += chunkMs
                    }

                    val estWord = (syllablePulses / 3).coerceIn(0, WORDS.size - 1)
                    mainHandler.post {
                        if (recording.get()) {
                            onProgress(rms, elapsedMs, estWord)
                        }
                    }

                    // إيقاف تلقائي ذكي إذا قرأ المستخدم آية الكرسي كاملة تقريباً وتوقف عن الكلام لمدة 3.2 ثانية
                    if (autoStopOnSilence && voicedFrames >= 120 && syllablePulses >= 95 && silenceRunMs >= 3200L) {
                        recording.set(false)
                        mainHandler.post { onAutoSilenceStop() }
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { audioRecord.stop() } catch (_: Exception) {}
                try { audioRecord.release() } catch (_: Exception) {}
                recording.set(false)
            }
        }, "RafiqRawAudioCapture").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        return true
    }

    /**
     * يوقف التسجيل ويعيد الصوت المسجل بصيغة WAV مضغوطة ومشفرة بـ Base64.
     */
    fun stopAndBuildBase64Wav(): String? {
        recording.set(false)
        try {
            recordThread?.join(350)
        } catch (_: Exception) {}
        recordThread = null

        val rawPcm: ByteArray = synchronized(pcmStream) {
            pcmStream.toByteArray()
        }
        if (rawPcm.size < SAMPLE_RATE) { // أقل من نصف ثانية
            return null
        }
        val trimmedPcm = trimLeadingAndTrailingSilence(rawPcm, SAMPLE_RATE)
        val wavBytes = pcm16MonoToWavBytes(trimmedPcm, SAMPLE_RATE)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    fun cancelRecording() {
        recording.set(false)
        try { recordThread?.join(200) } catch (_: Exception) {}
        recordThread = null
        synchronized(pcmStream) { pcmStream.reset() }
    }

    /**
     * يزيل الصمت الطويل من بداية ونهاية التسجيل مع إبقاء هامش أمان (250ms) حتى لا يُقطع أي حرف أو همزة أو سكون وقف.
     */
    fun trimLeadingAndTrailingSilence(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val totalSamples = pcmBytes.size / 2
        if (totalSamples <= sampleRate) return pcmBytes
        val frameSamples = 320 // 20ms
        val numFrames = totalSamples / frameSamples
        if (numFrames < 6) return pcmBytes

        val frameRms = FloatArray(numFrames)
        var maxRms = 0f
        for (f in 0 until numFrames) {
            var sumSq = 0f
            val baseByte = f * frameSamples * 2
            for (i in 0 until frameSamples) {
                val lo = pcmBytes[baseByte + 2 * i].toInt() and 0xFF
                val hi = pcmBytes[baseByte + 2 * i + 1].toInt()
                val s = (hi shl 8) or lo
                val norm = s / 32768.0f
                sumSq += norm * norm
            }
            val r = sqrt(sumSq / frameSamples)
            frameRms[f] = r
            if (r > maxRms) maxRms = r
        }
        if (maxRms < 0.002f) return pcmBytes

        val sorted = frameRms.clone().also { it.sort() }
        val floor = sorted[(numFrames * 0.15f).roundToInt().coerceIn(0, numFrames - 1)]
        val activeThresh = max(0.003f, floor + 0.06f * (maxRms - floor))

        var firstActive = 0
        while (firstActive < numFrames && frameRms[firstActive] < activeThresh) {
            firstActive++
        }
        var lastActive = numFrames - 1
        while (lastActive > firstActive && frameRms[lastActive] < activeThresh) {
            lastActive--
        }

        val marginFrames = 13 // ~260ms margin on both sides
        val startFrame = (firstActive - marginFrames).coerceAtLeast(0)
        val endFrame = (lastActive + marginFrames + 1).coerceAtMost(numFrames)
        val startByte = startFrame * frameSamples * 2
        val endByte = (endFrame * frameSamples * 2).coerceAtMost(pcmBytes.size)
        return if (endByte - startByte >= sampleRate) {
            pcmBytes.copyOfRange(startByte, endByte)
        } else {
            pcmBytes
        }
    }

    /**
     * يبني ملف WAV قياسي (RIFF/WAVE 44-byte header + 16-bit PCM payload).
     */
    fun pcm16MonoToWavBytes(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        val out = ByteArray(44 + dataSize)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, dataSize)
        return out
    }
}

/**
 * المدقق القرآني المباشر عبر Gemini API (Direct Multimodal Audio -> Structured JSON -> Confidence Filter >= 0.85).
 */
object GeminiRecitationAuditor {

    private val TARGET_VERSE_WITH_TASHKEEL: String by lazy {
        WORDS.joinToString(" ")
    }

    private val INDEXED_WORDS_REFERENCE: String by lazy {
        WORDS.mapIndexed { idx, w -> "$idx: \"$w\" (${TIPS[idx] ?: ""})" }.joinToString("\n")
    }

    /**
     * يبني حمولة JSON المرسلة إلى Gemini API مع:
     * - `system_instruction`: النص الصارم `GEMINI_SYSTEM_PROMPT` حرفياً.
     * - `contents`: الصوت الخام (`audio/wav` Base64) + الآية المستهدفة بكامل التشكيل وأحكام التجويد وفهرس الكلمات (0..49).
     * - `generationConfig`: `temperature: 0.0` و `response_mime_type: "application/json"`.
     */
    fun buildRequestJson(base64WavAudio: String): JSONObject {
        val systemInstruction = JSONObject().apply {
            put(
                "parts",
                JSONArray().put(
                    JSONObject().put("text", GEMINI_SYSTEM_PROMPT.trim())
                )
            )
        }

        val userPromptText = buildString {
            appendLine("الآية المستهدفة للتدقيق (آية الكرسي - سورة البقرة 255 بالتشكيل الكامل وأحكام التجويد برواية حفص عن عاصم):")
            appendLine(TARGET_VERSE_WITH_TASHKEEL)
            appendLine()
            appendLine("قائمة كلمات الآية مرقمة من 0 إلى 49 مع أحكام التشكيل والتجويد والوقف والوصل لكل كلمة (استخدم نفس الرقم في الحقل word_index ونفس نص الكلمة في الحقل word):")
            appendLine(INDEXED_WORDS_REFERENCE)
            appendLine()
            appendLine("استمع إلى التسجيل الصوتي المرفق بعناية فائقة، ودقق الحروف والتشكيل (الحركات القصيرة والتنوين وأواخر الكلمات وصلاً ووقفاً) وأحكام التجويد، ثم أخرج كائن JSON مطابقاً للمخطط المطلوب تماماً.")
        }

        val userParts = JSONArray().apply {
            put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", base64WavAudio)
                )
            )
            put(
                JSONObject().put("text", userPromptText)
            )
        }

        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", userParts)
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.0)
            put("response_mime_type", "application/json")
        }

        return JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", contents)
            put("generationConfig", generationConfig)
        }
    }

    /**
     * يرسل الصوت الخام إلى Gemini API على خيط خلفي ويعيد النتيجة إلى الخيط الرئيسي.
     */
    fun auditAudioAsync(
        ctx: Context,
        base64WavAudio: String,
        onSuccess: (RecitationAuditReport) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!GeminiSettingsManager.isNetworkConnected(ctx)) {
            mainHandler.post { onFailure(OFFLINE_NOTICE_AR) }
            return
        }

        val apiKey = GeminiSettingsManager.getApiKey(ctx)
        if (apiKey.isEmpty()) {
            mainHandler.post {
                onFailure("يرجى إدخال مفتاح Gemini API في نافذة الإعدادات أولاً لإجراء التدقيق الصوتي.")
            }
            return
        }

        val preferredModel = GeminiSettingsManager.getModelName(ctx)
        val candidateModels = linkedSetOf(
            preferredModel,
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        ).toList()

        Thread({
            var lastErrorMsg = "تعذّر الاتصال بخدمة Gemini API. تحقق من مفتاح API واتصالك بالإنترنت."
            val payloadStr = try {
                buildRequestJson(base64WavAudio).toString()
            } catch (e: Exception) {
                mainHandler.post { onFailure("خطأ في تجهيز البيانات الصوتية: ${e.localizedMessage}") }
                return@Thread
            }

            for (modelName in candidateModels) {
                var conn: HttpURLConnection? = null
                try {
                    val endpoint =
                        "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val url = URL(endpoint)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 25000
                        readTimeout = 60000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }

                    conn.outputStream.use { os ->
                        os.write(payloadStr.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val responseBody = stream?.let {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
                    } ?: ""

                    if (code in 200..299) {
                        val report = parseGeminiResponse(responseBody)
                        mainHandler.post { onSuccess(report) }
                        return@Thread
                    } else {
                        val apiErrDetail = extractApiErrorMessage(responseBody, code)
                        lastErrorMsg = apiErrDetail
                        // إذا كان المفتاح نفسه غير صالح (400 API_KEY_INVALID أو 403)، لا داعي لتجربة موديل آخر
                        if (code == 400 && responseBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    lastErrorMsg = "انقطع الاتصال أثناء التحليل الصوتي: ${e.localizedMessage ?: "خطأ في الشبكة"}"
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }

            mainHandler.post { onFailure(lastErrorMsg) }
        }, "RafiqGeminiAuditRequest").start()
    }

    private fun extractApiErrorMessage(responseBody: String, httpCode: Int): String {
        return try {
            val root = JSONObject(responseBody)
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message") ?: ""
            when {
                msg.contains("API key not valid", ignoreCase = true) ||
                    responseBody.contains("API_KEY_INVALID", ignoreCase = true) ->
                    "مفتاح Gemini API غير صالح. يرجى التأكد من نسخ المفتاح الصحيح من Google AI Studio في الإعدادات."
                httpCode == 429 ->
                    "تم تجاوز حد الطلبات المجاني المؤقت لـ Gemini API (429). انتظر ثواني قليلة ثم أعد المحاولة."
                msg.isNotEmpty() -> "خطأ من Gemini API ($httpCode): $msg"
                else -> "تعذّرت الاستجابة من خادم Gemini API (رمز الحالة $httpCode)."
            }
        } catch (_: Exception) {
            "تعذّرت الاستجابة من خادم Gemini API (رمز الحالة $httpCode)."
        }
    }

    /**
     * يحلل مخرجات JSON القادمة من Gemini API ويطبق فلترة مستوى الثقة من جهة العميل (Confidence >= 0.85).
     */
    fun parseGeminiResponse(rawHttpBody: String): RecitationAuditReport {
        val outer = JSONObject(rawHttpBody)
        val candidates = outer.optJSONArray("candidates")
        val firstCand = candidates?.optJSONObject(0)
        val content = firstCand?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        var jsonText = ""
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                if (t.isNotBlank()) {
                    jsonText = t
                    break
                }
            }
        }
        if (jsonText.isBlank()) {
            jsonText = rawHttpBody
        }

        val cleanedJson = stripMarkdownCodeFences(jsonText)
        val auditObj = JSONObject(cleanedJson)

        val rawErrorsList = ArrayList<RecitationError>()
        val errorsArr = auditObj.optJSONArray("errors") ?: JSONArray()
        for (i in 0 until errorsArr.length()) {
            val item = errorsArr.optJSONObject(i) ?: continue
            val wordStr = item.optString("word", "").trim()
            val rawIdx = item.optInt("word_index", -1)
            val resolvedIdx = resolveWordIndex(rawIdx, wordStr)
            val errType = AuditErrorType.fromString(item.optString("error_type", "Letter"))
            val confidence = item.optDouble("confidence", 0.0)
            val expected = item.optString("expected", if (resolvedIdx in WORDS.indices) WORDS[resolvedIdx] else wordStr).trim()
            val detected = item.optString("detected", "نطق غير مطابق").trim()
            val tip = item.optString(
                "correction_tip",
                if (resolvedIdx in WORDS.indices) (TIPS[resolvedIdx] ?: "احرص على تحقيق مخارج الحروف وحركاتها.") else "احرص على تحقيق النطق الصحيح."
            ).trim()

            if (resolvedIdx in WORDS.indices) {
                rawErrorsList.add(
                    RecitationError(
                        word = WORDS[resolvedIdx],
                        wordIndex = resolvedIdx,
                        errorType = errType,
                        confidence = confidence,
                        expected = expected.ifEmpty { WORDS[resolvedIdx] },
                        detected = detected.ifEmpty { "نطق غير مطابق" },
                        correctionTip = tip.ifEmpty { TIPS[resolvedIdx] ?: "" }
                    )
                )
            }
        }

        // 4. Client-side Confidence Filtering:
        // استبعاد أي خطأ تقل ثقته عن 0.85 لحماية المستخدم من أي إنذار كاذب (False Positives)
        val filteredErrors = filterErrorsByConfidence(rawErrorsList)

        val errorMap = LinkedHashMap<Int, RecitationError>()
        for (err in filteredErrors) {
            val existing = errorMap[err.wordIndex]
            if (existing == null || err.confidence > existing.confidence) {
                errorMap[err.wordIndex] = err
            }
        }

        val isPerfect = if (errorMap.isEmpty()) true else auditObj.optBoolean("is_perfect", false)
        val computedAccuracy = ((WORDS.size - errorMap.size) * 100) / WORDS.size
        val rawAccuracy = auditObj.optDouble("overall_accuracy_percentage", computedAccuracy.toDouble()).roundToInt()
        val finalAccuracy = if (isPerfect) 100 else min(rawAccuracy, computedAccuracy).coerceIn(0, 99)

        val defaultFeedback = if (isPerfect) {
            "ما شاء الله! تلاوة متقنة وأحكام تجويد وتشكيل سليمة بارك الله فيك."
        } else {
            "أحسنت المحاولة! اضغط على الكلمات الملوّنة للاطلاع على تصحيح التشكيل أو التجويد."
        }
        val feedback = auditObj.optString("general_feedback", defaultFeedback).trim().ifEmpty { defaultFeedback }

        return RecitationAuditReport(
            isPerfect = isPerfect,
            overallAccuracyPercentage = finalAccuracy,
            generalFeedback = feedback,
            rawErrors = rawErrorsList,
            filteredErrors = errorMap.values.toList(),
            errorByWordIndex = errorMap
        )
    }

    /**
     * فلترة الأخطاء من جهة العميل: أي خطأ تقل ثقته عن 0.85 يُهمل وتُعامل الكلمة ككلمة صحيحة تماماً.
     */
    fun filterErrorsByConfidence(errors: List<RecitationError>): List<RecitationError> {
        return errors.filter { it.confidence >= CLIENT_CONFIDENCE_THRESHOLD }
    }

    /**
     * يطابق فهرس الكلمة (word_index) مع نص الكلمة القرآنية لضمان التلوين على الكلمة الدقيقة 100%.
     */
    fun resolveWordIndex(reportedIdx: Int, reportedWord: String): Int {
        val normReported = norm(reportedWord)
        if (reportedIdx in WORDS.indices) {
            if (normReported.isEmpty() || NW[reportedIdx] == normReported ||
                NW[reportedIdx].contains(normReported) || normReported.contains(NW[reportedIdx])
            ) {
                return reportedIdx
            }
        }
        if (normReported.isNotEmpty()) {
            var bestIdx = -1
            var bestDist = Int.MAX_VALUE
            for (i in WORDS.indices) {
                if (NW[i] == normReported || NW[i].contains(normReported) || normReported.contains(NW[i])) {
                    val dist = if (reportedIdx >= 0) abs(i - reportedIdx) else i
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = i
                    }
                }
            }
            if (bestIdx in WORDS.indices) return bestIdx
        }
        return if (reportedIdx in WORDS.indices) reportedIdx else -1
    }

    private fun stripMarkdownCodeFences(text: String): String {
        var s = text.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1)
            }
            val lastFence = s.lastIndexOf("```")
            if (lastFence != -1) {
                s = s.substring(0, lastFence)
            }
        }
        val firstBrace = s.indexOf('{')
        val lastBrace = s.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            s = s.substring(firstBrace, lastBrace + 1)
        }
        return s.trim()
    }
}
