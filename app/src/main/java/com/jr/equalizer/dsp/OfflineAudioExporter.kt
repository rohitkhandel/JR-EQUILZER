package com.jr.equalizer.dsp

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class ExportFormat(val extension: String, val mimeType: String) {
    WAV("wav", "audio/wav"),
    M4A("m4a", "audio/mp4")
}

class OfflineAudioExporter(private val context: Context) {
    suspend fun export(
        source: Uri,
        settings: DspSettings,
        format: ExportFormat,
        onProgress: (Float) -> Unit = {},
        onSpectrum: (FloatArray) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        val intermediate = File(context.cacheDir, "jr-eq-" + UUID.randomUUID() + ".wav")
        val encoded = File(context.cacheDir, "jr-eq-" + UUID.randomUUID() + ".m4a")
        var inserted: Uri? = null
        try {
            decodeAndProcess(source, intermediate, settings, onProgress, onSpectrum)
            val output = if (format == ExportFormat.M4A) {
                encodeAac(intermediate, encoded) { onProgress(0.65f + it * 0.30f) }
                encoded
            } else intermediate

            val name = "JR-" + System.currentTimeMillis() + "-Equalized." + format.extension
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/JR-Equalizer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            inserted = context.contentResolver.insert(collection, values)
                ?: error("Android could not create the exported file.")
            context.contentResolver.openOutputStream(inserted!!, "w")?.use { destination ->
                output.inputStream().use { sourceStream -> sourceStream.copyTo(destination) }
            } ?: error("Could not open the exported file.")
            context.contentResolver.update(
                inserted!!,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            onProgress(1.0f)
            inserted!!
        } catch (error: Throwable) {
            inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            intermediate.delete()
            encoded.delete()
        }
    }

    private fun decodeAndProcess(
        source: Uri,
        target: File,
        settings: DspSettings,
        onProgress: (Float) -> Unit,
        onSpectrum: (FloatArray) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val writer = WavWriter(target)
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track was found in the selected file.")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format.")
            val duration = inputFormat.longOr(MediaFormat.KEY_DURATION, 0L)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var inputFinished = false
            var outputFinished = false
            var configured = false
            var rate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var processor: BiquadFilterEngine? = null
            var analyzer: FftAnalyzer? = null
            val info = MediaCodec.BufferInfo()

            while (!outputFinished) {
                if (!inputFinished) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        rate = out.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = out.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceIn(1, 8)
                        encoding = out.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        writer.begin(rate, channels)
                        processor = BiquadFilterEngine(rate, channels, settings)
                        analyzer = FftAnalyzer(rate)
                        configured = true
                    }
                    outputIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && buffer != null) {
                            if (!configured) {
                                writer.begin(rate, channels)
                                processor = BiquadFilterEngine(rate, channels, settings)
                                analyzer = FftAnalyzer(rate)
                                configured = true
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val samples = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    FloatArray(pcm.remaining() / 4) { pcm.getFloat() }
                                }
                                AudioFormat.ENCODING_PCM_16BIT -> {
                                    FloatArray(pcm.remaining() / 2) { pcm.getShort() / 32768.0f }
                                }
                                else -> error("The device decoder returned an unsupported PCM format.")
                            }
                            processor?.process(samples)
                            analyzer?.push(samples, channels, onSpectrum)
                            writer.write(samples)
                            if (duration > 0L) {
                                onProgress(0.65f * (info.presentationTimeUs.toFloat() / duration).coerceIn(0.0f, 1.0f))
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputFinished = true
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(configured) { "No decoded PCM audio was produced." }
            writer.finish()
        } finally {
            runCatching { writer.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun encodeAac(inputWav: File, outputM4a: File, progress: (Float) -> Unit) {
        val reader = WavReader(inputWav)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, reader.sampleRate, reader.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        var inputFinished = false
        var outputFinished = false
        var framesQueued = 0L
        val totalFrames = reader.dataBytes.toLong() / (reader.channels * 2L)
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(8192)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            while (!outputFinished) {
                if (!inputFinished) {
                    val inputIndex = encoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val input = encoder.getInputBuffer(inputIndex) ?: error("AAC input buffer unavailable.")
                        input.clear()
                        val read = reader.read(chunk).coerceAtLeast(0)
                        val aligned = read - read % (reader.channels * 2)
                        if (aligned == 0) {
                            val pts = framesQueued * 1000000L / reader.sampleRate
                            encoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        } else {
                            input.put(chunk, 0, aligned)
                            val frames = aligned / (reader.channels * 2)
                            val pts = framesQueued * 1000000L / reader.sampleRate
                            encoder.queueInputBuffer(inputIndex, 0, aligned, pts, 0)
                            framesQueued += frames
                            if (totalFrames > 0L) progress((framesQueued.toFloat() / totalFrames).coerceIn(0.0f, 1.0f))
                        }
                    }
                }
                val outputIndex = encoder.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC encoder changed output format unexpectedly." }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val output = encoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && output != null) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            } else {
                                check(muxerStarted) { "AAC output format is not ready." }
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, output, info)
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputFinished = true
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            reader.close()
        }
    }

    private class WavWriter(file: File) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var sampleRate = 44100
        private var channels = 2
        private var bytesWritten = 0L

        fun begin(rate: Int, channelCount: Int) {
            require(rate > 0 && channelCount in 1..8)
            sampleRate = rate
            channels = channelCount
            bytesWritten = 0L
            output.setLength(0L)
            output.write(ByteArray(44))
        }

        fun write(samples: FloatArray) {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { sample ->
                val value = sample.coerceIn(-1.0f, 1.0f)
                val scaled = if (value < 0) value * 32768.0f else value * 32767.0f
                bytes.putShort(scaled.toInt().coerceIn(-32768, 32767).toShort())
            }
            val block = bytes.array()
            output.write(block)
            bytesWritten += block.size
        }

        fun finish() {
            require(bytesWritten <= Int.MAX_VALUE - 36L) { "WAV export is limited to 2 GB per file." }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36L + bytesWritten).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channels * 2)
                putShort((channels * 2).toShort())
                putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(bytesWritten.toInt())
            }.array()
            output.seek(0L)
            output.write(header)
            output.fd.sync()
        }

        override fun close() = output.close()
    }

    private class WavReader(file: File) : AutoCloseable {
        private val input = RandomAccessFile(file, "r")
        val sampleRate: Int
        val channels: Int
        val dataBytes: Int

        init {
            val header = ByteArray(44)
            input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            b.position(22)
            channels = b.short.toInt() and 0xffff
            sampleRate = b.int
            b.position(40)
            dataBytes = b.int
            require(channels in 1..8 && sampleRate > 0 && dataBytes >= 0)
        }

        fun read(target: ByteArray): Int {
            val remaining = (44L + dataBytes - input.filePointer).coerceAtLeast(0L)
            if (remaining == 0L) return -1
            return input.read(target, 0, minOf(target.size.toLong(), remaining).toInt())
        }

        override fun close() = input.close()
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback
}
