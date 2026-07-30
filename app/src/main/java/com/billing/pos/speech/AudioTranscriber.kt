package com.billing.pos.speech

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Transcribes an already-recorded audio file.
 *
 * Android has no general "transcribe this file" API — the recogniser is built around the
 * microphone. The one opening is [RecognizerIntent.EXTRA_AUDIO_SOURCE] (API 33), which
 * hands the recogniser an open descriptor instead of the mic.
 *
 * Two caveats are baked into how this is used:
 *  - it needs API 33, so [isSupported] gates the whole feature;
 *  - the extra is optional for recogniser implementations, and one that ignores it opens
 *    the microphone instead. There is no flag to test for that, so the caller warns the
 *    user rather than pretending the result is trustworthy.
 *
 * Recordings are AAC in an MP4 container, but the recogniser wants raw PCM, so the file
 * is decoded first with MediaExtractor + MediaCodec.
 */
object AudioTranscriber {

    /** File transcription needs Android 13; below that only live dictation works. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    sealed interface Result {
        data class Text(val value: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** Decoded audio plus the format the recogniser has to be told about. */
    private data class Pcm(val file: File, val sampleRate: Int, val channels: Int)

    suspend fun transcribe(context: Context, path: String, languageTag: String): Result {
        if (!isSupported) {
            return Result.Failed("Reading a saved recording needs Android 13 or newer")
        }
        val source = File(path)
        if (!source.exists() || source.length() == 0L) return Result.Failed("Recording not found")

        val pcm = withContext(Dispatchers.IO) { runCatching { decodeToPcm(context, source) }.getOrNull() }
            ?: return Result.Failed("Could not read this audio format")

        return try {
            // A recogniser that ignores the file source can simply never call back; the
            // timeout guarantees we always return instead of hanging with "Converting…".
            kotlinx.coroutines.withTimeoutOrNull(90_000) {
                recognizeFile(context, pcm, languageTag)
            } ?: Result.Failed(
                "This phone's speech recogniser did not return anything for a saved recording. " +
                    "Try the microphone button to dictate instead."
            )
        } finally {
            pcm.file.delete()
        }
    }

    /** Decodes any container/codec the device can open into headerless 16-bit PCM. */
    private fun decodeToPcm(context: Context, source: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i; format = f; break
            }
        }
        val fmt = format ?: error("no audio track")
        extractor.selectTrack(track)

        val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val out = File(context.cacheDir, "stt_${System.nanoTime()}.pcm")
        FileOutputStream(out).use { sink ->
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk)
                        sink.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        if (out.length() == 0L) error("decoded to nothing")
        return Pcm(out, sampleRate, channels)
    }

    /** Streams the PCM into the recogniser through a pipe and waits for the transcript. */
    private suspend fun recognizeFile(context: Context, pcm: Pcm, languageTag: String): Result =
        withContext<Result>(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return@withContext Result.Failed("No speech recogniser on this phone")
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val write = pipe[1]

            // Feed the decoded audio in on a background thread; the recogniser reads the
            // other end. Closing the write side is what signals end-of-audio.
            Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { os ->
                        pcm.file.inputStream().use { it.copyTo(os) }
                    }
                }
            }.start()

            // The on-device recogniser (API 31+) is far more likely to honour a file source
            // than the default one, which often just opens the mic and ignores the extra.
            val recognizer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            suspendCancellableCoroutine<Result> { cont ->
                fun finish(r: Result) {
                    if (cont.isActive) cont.resume(r)
                    runCatching { recognizer.destroy() }
                    runCatching { read.close() }
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        finish(if (text.isBlank()) Result.Failed("No speech recognised") else Result.Text(text))
                    }
                    override fun onError(error: Int) = finish(Result.Failed(errorText(error)))
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
                recognizer.startListening(intent)
            }
        }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised in this recording"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard in this recording"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The recogniser needed the internet and could not reach it"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser is busy — try again"
        else -> "Could not transcribe (error $code)"
    }
}
