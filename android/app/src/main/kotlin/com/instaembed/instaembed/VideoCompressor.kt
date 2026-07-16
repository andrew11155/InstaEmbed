package com.instaembed.instaembed

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaExtractor
import android.view.Surface
import java.io.File

object VideoCompressor {

    private const val DISCORD_LIMIT_BYTES = 10L * 1024 * 1024
    private const val TIMEOUT_US = 10_000L

    fun needsCompression(file: File): Boolean = file.length() > DISCORD_LIMIT_BYTES

    fun getReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    private fun signalEndOfInputStream(surface: Surface) {
        try {
            val method = Surface::class.java.getMethod("signalEndOfInputStream")
            method.invoke(surface)
        } catch (_: Exception) {}
    }

    fun compress(
        input: File,
        output: File,
        targetBytes: Long = DISCORD_LIMIT_BYTES,
        onProgress: ((percent: Int) -> Unit)? = null
    ): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                break
            }
        }
        if (videoTrackIndex == -1 || videoFormat == null) {
            extractor.release()
            return input
        }

        val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

        val maxDim = 1280
        var outWidth = width
        var outHeight = height
        if (width > maxDim || height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(width, height)
            outWidth = (width * scale).toInt() and 0xFFFE
            outHeight = (height * scale).toInt() and 0xFFFE
        }

        val durationSec = durationUs / 1_000_000.0
        val targetBits = (targetBytes * 0.75 * 8).toLong()
        var bitrate = (targetBits / durationSec).toInt()
        bitrate = maxOf(bitrate, 300_000)

        // Encoder: Surface input -> H.264 output
        val encoderFormat = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // Decoder: compressed input -> renders to encoder's input surface
        val decoder = MediaCodec.createDecoderByType(mime)
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        decoder.configure(videoFormat, inputSurface, null, 0)
        decoder.start()

        extractor.selectTrack(videoTrackIndex)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        var lastProgress = 0

        while (!encoderDone) {
            // Feed extractor -> decoder
            if (!extractorDone) {
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        extractor.advance()
                        if (durationUs > 0) {
                            val pct = ((pts.toDouble() / durationUs) * 100).toInt().coerceIn(0, 99)
                            if (pct > lastProgress) {
                                lastProgress = pct
                                onProgress?.invoke(pct)
                            }
                        }
                    }
                }
            }

            // Drain decoder output (renders to encoder surface)
            var decOut = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            while (decOut >= 0) {
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    decoderDone = true
                    signalEndOfInputStream(inputSurface)
                }
                decoder.releaseOutputBuffer(decOut, bufInfo.size > 0)
                if (decoderDone) break
                decOut = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            }

            // Drain encoder output -> muxer
            var encOut = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            while (encOut >= 0) {
                val encBuf = encoder.getOutputBuffer(encOut)!!

                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufInfo.size = 0
                }

                if (bufInfo.size > 0) {
                    if (!muxerStarted) {
                        muxerTrackIndex = muxer.addTrack(encoderFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    encBuf.position(bufInfo.offset)
                    encBuf.limit(bufInfo.offset + bufInfo.size)
                    muxer.writeSampleData(muxerTrackIndex, encBuf, bufInfo)
                }

                encoder.releaseOutputBuffer(encOut, false)

                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoderDone = true
                    break
                }
                encOut = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            }
        }

        onProgress?.invoke(100)

        try { decoder.stop() } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        extractor.release()

        return if (output.exists() && output.length() > 0) output else input
    }
}
