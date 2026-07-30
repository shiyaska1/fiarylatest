package com.billing.pos.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.nio.ByteOrder

/**
 * Sound-activated voice recorder.
 *
 * Unlike [MediaRecorder], this captures raw PCM from [AudioRecord] and only feeds it to the AAC
 * encoder while it hears sound above an adaptive noise floor — so the saved .m4a contains just the
 * spoken parts, with silence dropped. A short pre-roll ([PREROLL_MS]) keeps the start of each word,
 * and a hang-over ([HANGOVER_MS]) keeps the tail, so speech is not clipped at either end.
 *
 * It reacts to any sound loud enough relative to the background — it does not distinguish a human
 * voice from, say, a television. Recording runs on its own thread; [stop] blocks until the file is
 * finalised, so call it off the main thread.
 */
class VoiceRecorder(private val outputPath: String) {

    @Volatile private var running = false
    private var thread: Thread? = null

    /** True once at least one voiced frame was written — lets callers detect a silent recording. */
    @Volatile var capturedAudio = false
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread { runCatching { loop() } }.also { it.start() }
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        thread?.let { runCatching { it.join(5000) } }
        thread = null
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) // ~1s buffer
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) { runCatching { record.release() }; return }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var totalSamples = 0L

        fun drain(endOfStream: Boolean) {
            var guard = 0
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream || guard++ > 200) break
                    continue
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                } else if (outIdx >= 0) {
                    val encoded = codec.getOutputBuffer(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted && encoded != null) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                        capturedAudio = true
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        fun feed(samples: ShortArray, count: Int) {
            val inIdx = codec.dequeueInputBuffer(10000)
            if (inIdx >= 0) {
                val ib = codec.getInputBuffer(inIdx)
                if (ib != null) {
                    ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN)
                    ib.asShortBuffer().put(samples, 0, count)
                    val ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIdx, 0, count * 2, ptsUs, 0)
                    totalSamples += count
                }
            }
            drain(false)
        }

        record.startRecording()
        val preRoll = ArrayDeque<ShortArray>()
        val preRollMax = (SAMPLE_RATE * PREROLL_MS / 1000 / CHUNK).coerceAtLeast(1)
        var noiseFloor = 300.0
        var hangoverUntil = 0L
        val buf = ShortArray(CHUNK)

        while (running) {
            val n = record.read(buf, 0, CHUNK)
            if (n <= 0) continue
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
            val rms = Math.sqrt(sum / n)
            val threshold = maxOf(ABS_MIN_RMS, noiseFloor * TRIGGER_MULT)
            val now = System.currentTimeMillis()
            if (rms > threshold) hangoverUntil = now + HANGOVER_MS
            else noiseFloor = noiseFloor * 0.95 + rms * 0.05  // adapt to the room only while quiet
            if (now < hangoverUntil) {
                while (preRoll.isNotEmpty()) { val c = preRoll.removeFirst(); feed(c, c.size) }
                feed(buf, n)
            } else {
                preRoll.addLast(buf.copyOf(n))
                while (preRoll.size > preRollMax) preRoll.removeFirst()
            }
        }

        // Signal end of stream and flush the encoder.
        val inIdx = codec.dequeueInputBuffer(10000)
        if (inIdx >= 0) {
            codec.queueInputBuffer(inIdx, 0, 0, totalSamples * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)

        runCatching { record.stop() }; runCatching { record.release() }
        runCatching { codec.stop() }; runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK = 2048          // samples read per iteration (~46 ms)
        private const val PREROLL_MS = 300      // audio kept before speech is first detected
        private const val HANGOVER_MS = 1200L   // keep recording this long after the last loud frame
        private const val ABS_MIN_RMS = 600.0   // absolute floor so a dead-silent room never triggers
        private const val TRIGGER_MULT = 2.5    // fire when level exceeds noise floor by this factor
    }
}
