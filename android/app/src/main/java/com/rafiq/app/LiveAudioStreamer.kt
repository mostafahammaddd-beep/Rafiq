package com.rafiq.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * البث الصوتي اللحظي المقسم حسب الوقفات والحدود الصوتية (`LiveAudioStreamer`):
 * 1) يلتقط الصوت بتردد 16kHz Mono 16-bit PCM عبر نسخة وحيدة من `AudioRecord` (بدون أي تعارض مع `SpeechRecognizer`).
 * 2) يبث مقاطع الصوت حياً عبر WebSocket (`/ws/live-recitation`) ويُطلق تقييماً لحظياً تلقائياً
 *    عند كل وقفة قصيرة (Waqf / Micro-Pause) أو انتقال بين الكلمات دون أي شاشات تحميل تعطل القراءة.
 * 3) يتضمن توافقاً تلقائياً غير متزامن مع نقطة النهاية `POST /correct-recitation` لضمان استمرار
 *    التدقيق اللحظي في جميع ظروف الشبكة.
 */
object LiveAudioStreamer {
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 640 // 40ms per chunk at 16kHz (1280 bytes)
    private const val MAX_SESSION_SECONDS = 180

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streaming = AtomicBoolean(false)
    private val httpEvalInFlight = AtomicBoolean(false)
    private var captureThread: Thread? = null

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeWebSocket: WebSocket? = null
    @Volatile private var wsConnected: Boolean = false
    private val cumulativePcmBuffer = ByteArrayOutputStream(SAMPLE_RATE * 2 * 60)

    fun isStreaming(): Boolean = streaming.get()

    @SuppressLint("MissingPermission")
    fun startLiveStreaming(
        ctx: Context,
        suraIdx: Int = RecitationConfig.DEFAULT_SURA_IDX,
        ayaIdx: Int = RecitationConfig.DEFAULT_AYA_IDX,
        onAudioProgress: (rms: Float, elapsedMs: Long, localEstWord: Int) -> Unit,
        onLiveDiff: (LiveRecitationDiff) -> Unit,
        onConnectionState: (connected: Boolean, statusText: String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (streaming.get()) return true

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
        } catch (_: Exception) {
            null
        }

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            try { audioRecord?.release() } catch (_: Exception) {}
            onError("تعذّر تهيئة الميكروفون (16kHz Mono PCM). تأكد من منح صلاحية الميكروفون.")
            return false
        }

        synchronized(cumulativePcmBuffer) {
            cumulativePcmBuffer.reset()
        }
        streaming.set(true)
        httpEvalInFlight.set(false)

        // فتح قناة WebSocket المباشرة (/ws/live-recitation)
        openWebSocketChannel(
            ctx = ctx,
            suraIdx = suraIdx,
            ayaIdx = ayaIdx,
            onLiveDiff = onLiveDiff,
            onConnectionState = onConnectionState
        )

        captureThread = Thread({
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            val byteBuf = ByteArray(CHUNK_SAMPLES * 2)
            val startTime = SystemClock.elapsedRealtime()
            var totalSamples = 0L
            var voicedMsTotal = 0L
            var voicedMsSinceLastEval = 0L
            var silenceRunMs = 0L
            var lastEvalTriggerTime = startTime
            var inSyllable = false
            var syllableCooldownMs = 0L
            var syllablePulses = 0
            var noiseFloor = 0.004f

            try {
                audioRecord.startRecording()
                while (streaming.get()) {
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

                    val byteCount = read * 2
                    synchronized(cumulativePcmBuffer) {
                        if (totalSamples < SAMPLE_RATE * MAX_SESSION_SECONDS) {
                            cumulativePcmBuffer.write(byteBuf, 0, byteCount)
                        }
                    }

                    // إرسال الإطار الصوتي الخام مباشرةً عبر WebSocket
                    val ws = activeWebSocket
                    if (ws != null && wsConnected) {
                        ws.send(byteBuf.toByteString(0, byteCount))
                    }

                    totalSamples += read
                    val chunkMs = (read * 1000L) / SAMPLE_RATE
                    val rms = sqrt(sumSq / read.toFloat())
                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startTime

                    if (rms > 0.0002f && rms < noiseFloor) {
                        noiseFloor = 0.90f * noiseFloor + 0.10f * rms
                    } else if (rms < noiseFloor * 1.4f) {
                        noiseFloor = 0.98f * noiseFloor + 0.02f * rms
                    }
                    val voiceThreshold = max(0.006f, noiseFloor * 2.7f)

                    if (syllableCooldownMs > 0L) {
                        syllableCooldownMs = (syllableCooldownMs - chunkMs).coerceAtLeast(0L)
                    }

                    if (rms >= voiceThreshold) {
                        voicedMsTotal += chunkMs
                        voicedMsSinceLastEval += chunkMs
                        silenceRunMs = 0L
                        if (!inSyllable && syllableCooldownMs == 0L) {
                            inSyllable = true
                            syllablePulses++
                            syllableCooldownMs = 145L
                        }
                    } else {
                        if (rms < voiceThreshold * 0.72f) {
                            inSyllable = false
                        }
                        silenceRunMs += chunkMs
                    }

                    val localEstWord = (syllablePulses / 3).coerceIn(0, WORDS.size - 1)
                    mainHandler.post {
                        if (streaming.get()) {
                            onAudioProgress(rms, elapsedMs, localEstWord)
                        }
                    }

                    // إطلاق التقييم اللحظي التلقائي عند:
                    // أ) رصد وقفة قصيرة (Waqf >= 240ms) بعد نطق مقطع صوتي
                    // ب) أو مرور ~880ms من التلاوة المتصلة لتحديث الكلمات أولاً بأول أثناء القراءة
                    val isWaqfPause = voicedMsSinceLastEval >= 220L && silenceRunMs >= 240L
                    val isRollingWordTransition = voicedMsSinceLastEval >= 880L && (now - lastEvalTriggerTime) >= 920L

                    if (voicedMsTotal >= 320L && (isWaqfPause || isRollingWordTransition)) {
                        voicedMsSinceLastEval = 0L
                        lastEvalTriggerTime = now
                        if (ws != null && wsConnected) {
                            ws.send("""{"type":"waqf"}""")
                        } else {
                            triggerNonBlockingHttpSnapshot(
                                ctx = ctx,
                                suraIdx = suraIdx,
                                ayaIdx = ayaIdx,
                                isFinal = false,
                                onLiveDiff = onLiveDiff
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { audioRecord.stop() } catch (_: Exception) {}
                try { audioRecord.release() } catch (_: Exception) {}
            }
        }, "RafiqLiveAudioStreamer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }

        return true
    }

    /**
     * يوقف البث ويرسل إشارة الإنهاء الفورية (`flush`) لاستلام التقييم الختامي الكامل للآية دون حجب الواجهة.
     */
    fun stopLiveStreamingAndFlush(
        ctx: Context,
        suraIdx: Int = RecitationConfig.DEFAULT_SURA_IDX,
        ayaIdx: Int = RecitationConfig.DEFAULT_AYA_IDX,
        onFinalDiff: (LiveRecitationDiff) -> Unit
    ) {
        streaming.set(false)
        try {
            captureThread?.join(300)
        } catch (_: Exception) {}
        captureThread = null

        val ws = activeWebSocket
        if (ws != null && wsConnected) {
            try {
                ws.send("""{"type":"flush"}""")
            } catch (_: Exception) {}
        }

        // نرسل أيضاً لقطة الـ WAV الكاملة إلى POST /correct-recitation لضمان استلام التقرير النهائي الكامل
        triggerNonBlockingHttpSnapshot(
            ctx = ctx,
            suraIdx = suraIdx,
            ayaIdx = ayaIdx,
            isFinal = true,
            onLiveDiff = { diff ->
                onFinalDiff(diff)
                closeWebSocket()
            }
        )
    }

    fun cancelStreaming() {
        streaming.set(false)
        try { captureThread?.join(200) } catch (_: Exception) {}
        captureThread = null
        closeWebSocket()
        synchronized(cumulativePcmBuffer) {
            cumulativePcmBuffer.reset()
        }
    }

    private fun openWebSocketChannel(
        ctx: Context,
        suraIdx: Int,
        ayaIdx: Int,
        onLiveDiff: (LiveRecitationDiff) -> Unit,
        onConnectionState: (connected: Boolean, statusText: String) -> Unit
    ) {
        closeWebSocket()
        val wsUrl = RecitationConfig.buildWebSocketUrl(ctx, suraIdx, ayaIdx)
        val request = Request.Builder().url(wsUrl).build()

        activeWebSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsConnected = true
                    webSocket.send("""{"type":"config","sura_idx":$suraIdx,"aya_idx":$ayaIdx,"reset":true}""")
                    mainHandler.post {
                        onConnectionState(true, "متصل مباشرةً بمحرك Quran-Muaalem GPU (بث حي)")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val diff = MuaalemJsonParser.parseDiffJson(text) ?: return
                    mainHandler.post {
                        onLiveDiff(diff)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    wsConnected = false
                    mainHandler.post {
                        onConnectionState(
                            false,
                            "وضع البث المقطعي التلقائي (HTTP Waqf Segments) نشط"
                        )
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsConnected = false
                }
            }
        )
    }

    private fun closeWebSocket() {
        wsConnected = false
        try {
            activeWebSocket?.close(1000, "done")
        } catch (_: Exception) {}
        activeWebSocket = null
    }

    private fun triggerNonBlockingHttpSnapshot(
        ctx: Context,
        suraIdx: Int,
        ayaIdx: Int,
        isFinal: Boolean,
        onLiveDiff: (LiveRecitationDiff) -> Unit
    ) {
        if (!isFinal && !httpEvalInFlight.compareAndSet(false, true)) {
            return
        }
        val pcmBytes: ByteArray = synchronized(cumulativePcmBuffer) {
            cumulativePcmBuffer.toByteArray()
        }
        if (pcmBytes.size < SAMPLE_RATE / 2) {
            if (!isFinal) httpEvalInFlight.set(false)
            return
        }

        val wavBytes = pcm16MonoToWavBytes(pcmBytes, SAMPLE_RATE)
        val url = RecitationConfig.buildCorrectRecitationUrl(ctx)

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "live_recitation.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("sura_idx", suraIdx.toString())
            .addFormDataPart("aya_idx", ayaIdx.toString())
            .addFormDataPart("is_partial", (!isFinal).toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipartBody)
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isFinal) httpEvalInFlight.set(false)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyStr = response.body?.string() ?: ""
                    if (response.isSuccessful && bodyStr.isNotEmpty()) {
                        val parsed = MuaalemJsonParser.parseDiffJson(bodyStr)
                        if (parsed != null) {
                            val finalTagged = if (isFinal) parsed.copy(isFinal = true) else parsed
                            mainHandler.post { onLiveDiff(finalTagged) }
                        }
                    }
                } finally {
                    response.close()
                    if (!isFinal) httpEvalInFlight.set(false)
                }
            }
        })
    }

    fun pcm16MonoToWavBytes(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
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
        header.putInt(16)
        header.putShort(1.toShort())
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
