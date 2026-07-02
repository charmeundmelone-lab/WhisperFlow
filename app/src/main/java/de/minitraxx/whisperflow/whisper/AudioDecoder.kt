package de.minitraxx.whisperflow.whisper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Dekodiert die bestehenden M4A/AAC-Aufnahmen (44,1 kHz) zu 16 kHz Mono Float-PCM,
 * dem Eingabeformat von whisper.cpp. Die Aufnahme-Pipeline bleibt dadurch
 * unverändert — der Cloud-Fallback bekommt weiterhin dieselbe M4A-Datei.
 */
internal object AudioDecoder {

    const val WHISPER_SAMPLE_RATE = 16_000

    private const val CODEC_TIMEOUT_US = 10_000L

    /** Wirft bei jedem Problem — der Aufrufer fängt via runCatching und fällt auf Cloud zurück. */
    fun decodeToWhisperPcm(file: File): FloatArray {
        require(file.exists() && file.length() > 0) { "Audiodatei fehlt oder ist leer" }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val trackFormat = format ?: error("Keine Audiospur gefunden")
            extractor.selectTrack(trackIndex)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: error("Kein MIME-Typ")
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val pcmChunks = ArrayList<ShortArray>()
            var totalSamples = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex) ?: error("Input-Buffer null")
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* weiter pollen */ }
                    else -> if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIndex) ?: error("Output-Buffer null")
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = ShortArray(bufferInfo.size / 2)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            pcmChunks.add(shorts)
                            totalSamples += shorts.size
                            // Schutz vor Ausreissern: > ~10 Minuten Stereo 48kHz ist hier nie legitim
                            require(totalSamples < 60_000_000) { "Audiodaten unerwartet groß" }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            require(totalSamples > 0) { "Decoder lieferte keine Samples" }
            require(channels in 1..2) { "Unerwartete Kanalzahl: $channels" }
            require(sampleRate in 8000..192_000) { "Unerwartete Samplerate: $sampleRate" }

            // Zusammenfügen + Mono-Downmix
            val interleaved = ShortArray(totalSamples.toInt())
            var pos = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, interleaved, pos, chunk.size)
                pos += chunk.size
            }
            val monoLen = interleaved.size / channels
            val mono = FloatArray(monoLen)
            if (channels == 1) {
                for (i in 0 until monoLen) mono[i] = interleaved[i] / 32768f
            } else {
                for (i in 0 until monoLen) {
                    mono[i] = ((interleaved[2 * i].toInt() + interleaved[2 * i + 1].toInt()) / 2) / 32768f
                }
            }

            return resampleLinear(mono, sampleRate, WHISPER_SAMPLE_RATE)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val last = input.size - 1
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceAtMost(last)
            val i1 = (i0 + 1).coerceAtMost(last)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
