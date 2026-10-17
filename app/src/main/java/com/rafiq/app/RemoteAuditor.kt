package com.rafiq.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** تسجيل صوت خام 16kHz أحادي (PCM 16-bit) مع تنقية الإشارة الصوتية. */
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

object RemoteAuditor {
    const val SAMPLE_RATE = 16000

    class WordResult(val ok: Boolean, val note: String, val heard: String)
    class AuditResult(val words: List<WordResult>, val raw: String, val error: String?)

    fun conditionPcm(raw: ByteArray): ByteArray {
        val nSamples = raw.size / 2
        if (nSamples < 1600) return raw
        val samples = ShortArray(nSamples)
        var sum = 0L
        for (i in 0 until nSamples) {
            val lo = raw[i * 2].toInt() and 0xFF
            val hi = raw[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            samples[i] = s
            sum += s.toLong()
        }
        val dc = (sum / nSamples).toInt()
        var maxAbs = 1
        for (i in 0 until nSamples) {
            val centered = (samples[i].toInt() - dc).coerceIn(-32767, 32767)
            samples[i] = centered.toShort()
            val a = abs(centered)
            if (a > maxAbs) maxAbs = a
        }
        val targetPeak = 28500f
        val gain = if (maxAbs > 400) (targetPeak / maxAbs.toFloat()).coerceIn(0.8f, 6.0f) else 1.0f
        val out = ByteArray(nSamples * 2)
        for (i in 0 until nSamples) {
            val v = (samples[i].toFloat() * gain).toInt().coerceIn(-32767, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
