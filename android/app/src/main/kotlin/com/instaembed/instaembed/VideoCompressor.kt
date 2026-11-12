package com.instaembed.instaembed

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToLong

object VideoCompressor {
    private const val TAG = "VideoCompressor"
    private const val TIMEOUT_US = 10_000L
    private const val MIN_VIDEO_BIT_RATE = 100_000
    private const val MAX_VIDEO_BIT_RATE = 8_000_000
    private const val MAX_FPS = 30
    private const val I_FRAME_INTERVAL_SEC = 3
    // Headroom for MP4 container/moov overhead and VBR encoder variance
    private const val SIZE_SAFETY_MARGIN = 0.92
    private const val MAX_ATTEMPTS = 4
    // Phone hardware encoders consistently overshoot low VBR targets by ~15-25%,
    // so aim low on the first try instead of paying for a second full encode.
    private const val FIRST_PASS_BITRATE_FACTOR = 0.82

    // Below this total bitrate (video + audio) a single file looks like mush,
    // so callers should split into parts instead.
    private const val MIN_GOOD_TOTAL_BIT_RATE_AVC = 700_000
    private const val MIN_GOOD_TOTAL_BIT_RATE_HEVC = 450_000
    const val MAX_PARTS = 10

    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    data class TimeRange(val startUs: Long, val endUs: Long)

    /** True if the device has a hardware encoder for [mime]. */
    fun hasHardwareEncoder(mime: String): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun probeDurationUs(input: File): Long {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(input.path) }
            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = maxOf(duration, fmt.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (duration > 0) return duration
        } catch (e: Exception) {
            Log.w(TAG, "probeDurationUs extractor failed: ${e.message}")
        } finally {
            extractor?.release()
        }
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(input.path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            ms * 1000L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Splits a video into equal-length parts so each one can hit [targetSizeBytes] at a
     * reasonable bitrate. Returns a single full-length range when no split is needed.
     */
    fun planParts(durationUs: Long, targetSizeBytes: Long, videoMime: String = MIME_AVC): List<TimeRange> {
        if (durationUs <= 0) return listOf(TimeRange(0, -1))
        val minTotalBitRate = if (videoMime == MIME_HEVC) MIN_GOOD_TOTAL_BIT_RATE_HEVC else MIN_GOOD_TOTAL_BIT_RATE_AVC
        val maxPartSec = targetSizeBytes * 8 * SIZE_SAFETY_MARGIN / minTotalBitRate
        val durationSec = durationUs / 1_000_000.0
        val count = ceil(durationSec / maxPartSec).toInt().coerceIn(1, MAX_PARTS)
        if (count == 1) return listOf(TimeRange(0, -1))
        val partUs = durationUs / count
        return (0 until count).map { i ->
            val start = i * partUs
            val end = if (i == count - 1) -1L else (i + 1) * partUs
            TimeRange(start, end)
        }
    }

    /**
     * Re-encodes [input] (optionally just the [startUs]..[endUs] slice) into an MP4 no larger
     * than [targetSizeBytes]. Audio is always re-encoded to AAC so its size is known up front
     * and odd source codecs (Opus, AC-3, etc.) can't silently get dropped by the muxer.
     */
    fun compress(
        input: File,
        output: File,
        targetSizeBytes: Long,
        startUs: Long = 0L,
        endUs: Long = -1L,
        videoMime: String = MIME_AVC
    ): Boolean {
        if (!input.exists() || input.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty: ${input.path}")
            return false
        }

        val tracks = findTracks(input) ?: return false
        val fullDurationUs = probeDurationUs(input)
        val sliceEndUs = if (endUs > 0) minOf(endUs, fullDurationUs.takeIf { it > 0 } ?: endUs) else fullDurationUs
        val sliceDurationSec = ((sliceEndUs - startUs) / 1_000_000.0).coerceAtLeast(1.0)
        val usableBits = targetSizeBytes * 8 * SIZE_SAFETY_MARGIN

        // Audio first: we then know exactly how many bytes are left over for video.
        var audio: EncodedAudio? = null
        if (tracks.audioIdx >= 0) {
            val totalBitRate = usableBits / sliceDurationSec
            val audioBitRate = when {
                totalBitRate > 2_000_000 -> 128_000
                totalBitRate > 900_000 -> 96_000
                totalBitRate > 400_000 -> 64_000
                else -> 48_000
            }
            audio = try {
                transcodeAudio(input, tracks.audioIdx, startUs, endUs, audioBitRate)
            } catch (e: Exception) {
                Log.e(TAG, "Audio transcode failed: ${e.message}", e)
                null
            }
            if (audio == null) {
                // Rather than silently ship a video with no sound, fall back to copying the
                // original audio as-is when the container allows it.
                audio = try {
                    copyAudio(input, tracks.audioIdx, startUs, endUs)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio copy fallback failed: ${e.message}", e)
                    null
                }
            }
            if (audio == null) {
                Log.e(TAG, "Input has audio but it could not be processed; refusing to output a silent video")
                return false
            }
            Log.i(TAG, "Audio ready: ${audio.samples.size} samples, ${audio.totalBytes} bytes")
        }

        val audioBits = (audio?.totalBytes ?: 0L) * 8
        var videoBitRate = ((usableBits - audioBits) / sliceDurationSec * FIRST_PASS_BITRATE_FACTOR).toInt()
            .coerceIn(MIN_VIDEO_BIT_RATE, MAX_VIDEO_BIT_RATE)

        for (attempt in 1..MAX_ATTEMPTS) {
            output.delete()
            val ok = encodeVideo(input, output, tracks.videoIdx, startUs, endUs, videoBitRate, videoMime, audio)
            if (!ok || !output.exists() || output.length() == 0L) {
                if (videoMime != MIME_AVC) {
                    Log.w(TAG, "$videoMime encode failed, falling back to AVC")
                    return compress(input, output, targetSizeBytes, startUs, endUs, MIME_AVC)
                }
                output.delete()
                return false
            }
            val size = output.length()
            if (size <= targetSizeBytes) {
                Log.i(TAG, "Compression succeeded on attempt $attempt: $size bytes (target $targetSizeBytes)")
                return true
            }
            if (videoBitRate <= MIN_VIDEO_BIT_RATE) break
            // Scale video bitrate by how far over we were (encoders overshoot unevenly).
            val actualVideoBytes = (size - (audio?.totalBytes ?: 0L)).coerceAtLeast(1L)
            val allowedVideoBytes = (targetSizeBytes * SIZE_SAFETY_MARGIN - (audio?.totalBytes ?: 0L)).coerceAtLeast(1.0)
            val scale = (allowedVideoBytes / actualVideoBytes).coerceIn(0.3, 0.95)
            Log.w(TAG, "Attempt $attempt output $size bytes > $targetSizeBytes; scaling video bitrate by $scale")
            videoBitRate = (videoBitRate * scale).toInt().coerceAtLeast(MIN_VIDEO_BIT_RATE)
        }

        output.delete()
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Track discovery

    private class Tracks(val videoIdx: Int, val audioIdx: Int)

    private fun findTracks(input: File): Tracks? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            var v = -1
            var a = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == -1) v = i
                if (mime.startsWith("audio/") && a == -1) a = i
            }
            if (v == -1) {
                Log.e(TAG, "No video track found in input")
                return null
            }
            return Tracks(v, a)
        } catch (e: Exception) {
            Log.e(TAG, "findTracks failed: ${e.message}", e)
            return null
        } finally {
            extractor.release()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Audio

    private class AudioSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class EncodedAudio(val format: MediaFormat, val samples: List<AudioSample>) {
        val totalBytes: Long = samples.sumOf { it.data.size.toLong() }
    }

    /** Decodes the source audio to PCM and re-encodes it as AAC-LC at [bitRate]. */
    private fun transcodeAudio(input: File, trackIdx: Int, startUs: Long, endUs: Long, bitRate: Int): EncodedAudio? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(input.path)
            extractor.selectTrack(trackIdx)
            val inFormat = extractor.getTrackFormat(trackIdx)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val samples = ArrayList<AudioSample>()
            var outFormat: MediaFormat? = null

            var srcRate = 0
            var srcChannels = 0
            var isFloat = false
            var decimation = 1
            var outRate = 0
            var outChannels = 0

            // PCM (16-bit interleaved, already downmixed/decimated) waiting for encoder input
            val pending = ByteArrayOutputStream()
            var pendingBytes = ByteArray(0)
            var pendingPos = 0
            var framesQueued = 0L

            var inputDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false

            fun createEncoder() {
                outRate = srcRate / decimation
                outChannels = minOf(srcChannels, 2)
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outChannels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels == 1) minOf(bitRate, 64_000) else bitRate)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                Log.i(TAG, "Audio: ${srcRate}Hz/${srcChannels}ch float=$isFloat -> AAC ${outRate}Hz/${outChannels}ch @ $bitRate")
            }

            fun readDecoderFormat(fmt: MediaFormat) {
                srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                isFloat = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                // AAC tops out at 48 kHz; crude decimation is fine for 88.2/96 kHz sources
                decimation = if (srcRate > 48_000) ceil(srcRate / 48_000.0).toInt() else 1
            }

            // Each pass moves every buffer that's ready through each stage; codec round trips are
            // slow enough that handling one buffer per pass leaves the pipeline mostly idle.
            while (!encoderDone) {
                var progressed = false
                // 1. Feed decoder
                while (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    progressed = true
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    val t = extractor.sampleTime
                    if (size < 0 || (endUs > 0 && t > endUs)) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, t, 0)
                        extractor.advance()
                    }
                }

                // 2. Drain decoder into pending PCM
                while (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(info, 0)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (encoder == null) {
                            readDecoderFormat(decoder.outputFormat)
                            createEncoder()
                        }
                    } else if (outIdx >= 0) {
                        if (encoder == null && info.size > 0) {
                            readDecoderFormat(decoder.outputFormat)
                            createEncoder()
                        }
                        if (info.size > 0 && encoder != null) {
                            val buf = decoder.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val pcm = toPcm16(buf, isFloat)
                            val bytesPerSrcFrame = 2 * srcChannels
                            val totalFrames = pcm.size / bytesPerSrcFrame
                            var firstFrame = 0
                            var lastFrame = totalFrames
                            val pts = info.presentationTimeUs
                            if (pts < startUs) {
                                firstFrame = ((startUs - pts) * srcRate / 1_000_000L).toInt().coerceAtMost(totalFrames)
                            }
                            if (endUs > 0) {
                                val endFrame = ((endUs - pts) * srcRate / 1_000_000L)
                                lastFrame = endFrame.coerceIn(0L, totalFrames.toLong()).toInt()
                            }
                            if (firstFrame < lastFrame) {
                                if (decimation == 1 && outChannels == srcChannels) {
                                    pending.write(pcm, firstFrame * bytesPerSrcFrame, (lastFrame - firstFrame) * bytesPerSrcFrame)
                                } else {
                                    // Downmix (keep front L/R) and/or decimate into one batch
                                    val bytesPerOutFrame = 2 * outChannels
                                    val outFrames = (lastFrame - firstFrame + decimation - 1) / decimation
                                    val packed = ByteArray(outFrames * bytesPerOutFrame)
                                    var o = 0
                                    var f = firstFrame
                                    while (f < lastFrame) {
                                        System.arraycopy(pcm, f * bytesPerSrcFrame, packed, o, bytesPerOutFrame)
                                        o += bytesPerOutFrame
                                        f += decimation
                                    }
                                    pending.write(packed, 0, o)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }

                val enc = encoder
                if (enc == null) {
                    if (decoderDone) {
                        Log.w(TAG, "Audio decoder produced no output")
                        return null
                    }
                    if (!progressed) Thread.sleep(1)
                    continue
                }

                // 3. Feed encoder from pending PCM
                while (!encoderEosQueued) {
                    if (pendingPos >= pendingBytes.size && pending.size() > 0) {
                        pendingBytes = pending.toByteArray()
                        pending.reset()
                        pendingPos = 0
                    }
                    val hasData = pendingPos < pendingBytes.size
                    if (!hasData && !decoderDone) break
                    val inIdx = enc.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    progressed = true
                    val bytesPerOutFrame = 2 * outChannels
                    val ptsUs = framesQueued * 1_000_000L / outRate
                    if (hasData) {
                        val buf = enc.getInputBuffer(inIdx)!!
                        buf.clear()
                        var n = minOf(buf.remaining(), pendingBytes.size - pendingPos)
                        n -= n % bytesPerOutFrame
                        buf.put(pendingBytes, pendingPos, n)
                        pendingPos += n
                        framesQueued += n / bytesPerOutFrame
                        enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                    } else {
                        enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosQueued = true
                    }
                }

                // 4. Drain encoder
                while (!encoderDone) {
                    val encIdx = enc.dequeueOutputBuffer(info, 0)
                    if (encIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = enc.outputFormat
                    } else if (encIdx >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            val buf = enc.getOutputBuffer(encIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            samples.add(AudioSample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                        }
                        enc.releaseOutputBuffer(encIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
                if (!progressed) Thread.sleep(1)
            }

            val fmt = outFormat ?: return null
            if (samples.isEmpty()) return null
            return EncodedAudio(fmt, samples)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** Converts a decoder output buffer to 16-bit little-endian PCM bytes. */
    private fun toPcm16(buf: ByteBuffer, isFloat: Boolean): ByteArray {
        if (!isFloat) {
            val out = ByteArray(buf.remaining())
            buf.get(out)
            return out
        }
        val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteArray(floats.remaining() * 2)
        var i = 0
        while (floats.hasRemaining()) {
            val s = (floats.get().coerceIn(-1f, 1f) * 32767f).toInt()
            out[i++] = (s and 0xff).toByte()
            out[i++] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Fallback: copy compressed audio samples untouched (only works for MP4-compatible codecs). */
    private fun copyAudio(input: File, trackIdx: Int, startUs: Long, endUs: Long): EncodedAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return null
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buf = ByteBuffer.allocateDirect(1024 * 512)
            val samples = ArrayList<AudioSample>()
            while (true) {
                val t = extractor.sampleTime
                if (t < 0 || (endUs > 0 && t >= endUs)) break
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val bytes = ByteArray(size)
                buf.get(bytes, 0, size)
                samples.add(AudioSample(bytes, (t - startUs).coerceAtLeast(0), MediaCodec.BUFFER_FLAG_KEY_FRAME))
                extractor.advance()
            }
            if (samples.isEmpty()) return null
            return EncodedAudio(format, samples)
        } finally {
            extractor.release()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Video

    private fun encodeVideo(
        input: File,
        output: File,
        videoTrackIdx: Int,
        startUs: Long,
        endUs: Long,
        videoBitRate: Int,
        videoMime: String,
        audio: EncodedAudio?
    ): Boolean {
        var extractor: MediaExtractor? = null
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var encoderSurface: Surface? = null
        var glPipeline: GLVideoPipeline? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor().apply { setDataSource(input.path) }
            val videoFormat = extractor.getTrackFormat(videoTrackIdx)
            val meta = extractVideoMetadata(input, videoFormat)
            val outFps = minOf(meta.frameRate, MAX_FPS)
            val (outW, outH) = pickOutputSize(meta.width, meta.height, videoBitRate, outFps, videoMime)

            Log.i(
                TAG,
                "Encoding video: mime=$videoMime bitrate=$videoBitRate ${outW}x${outH}@${outFps} " +
                    "(in=${meta.width}x${meta.height}@${meta.frameRate}, rot=${meta.rotation}) " +
                    "range=$startUs..$endUs"
            )

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (meta.rotation != 0) muxer.setOrientationHint(meta.rotation)

            val encFormat = MediaFormat.createVideoFormat(videoMime, outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            encoder = MediaCodec.createEncoderByType(videoMime)
            applyBestProfile(encoder, encFormat, videoMime)
            try {
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "Encoder rejected profile settings, retrying with defaults: ${e.message}")
                encFormat.removeKey(MediaFormat.KEY_PROFILE)
                encFormat.removeKey(MediaFormat.KEY_LEVEL)
                encoder.reset()
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = encoder.createInputSurface()
            encoder.start()

            glPipeline = GLVideoPipeline(encoderSurface, outW, outH)

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(videoFormat, glPipeline.decoderSurface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrackIdx)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            var audioPos = 0

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false
            var videoFramesWritten = 0

            val minFrameGapUs = (1_000_000L / outFps) * 8 / 10
            var lastRenderedPts = Long.MIN_VALUE

            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderDone = false
            var eosTryAgainCount = 0

            fun writeAudioUpTo(ptsUs: Long) {
                if (audio == null || muxerAudioTrack < 0) return
                val m = muxer!!
                while (audioPos < audio.samples.size) {
                    val s = audio.samples[audioPos]
                    if (ptsUs >= 0 && s.ptsUs > ptsUs) break
                    audioInfo.set(0, s.data.size, s.ptsUs, s.flags)
                    m.writeSampleData(muxerAudioTrack, ByteBuffer.wrap(s.data), audioInfo)
                    audioPos++
                }
            }

            /**
             * Pulls every encoded frame that's ready. Draining only one per loop lets output
             * back up until eglSwapBuffers blocks on a full encoder and the loop deadlocks.
             * Returns the number of buffers handled.
             */
            fun drainEncoder(timeoutUs: Long): Int {
                val enc = encoder!!
                val mux = muxer!!
                var handled = 0
                while (!encoderDone) {
                    val encOutIdx = enc.dequeueOutputBuffer(encInfo, if (handled == 0) timeoutUs else 0L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return handled
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerVideoTrack = mux.addTrack(enc.outputFormat)
                                if (audio != null) muxerAudioTrack = mux.addTrack(audio.format)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            eosTryAgainCount = 0
                            val data = enc.getOutputBuffer(encOutIdx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                            if (encInfo.size > 0 && muxerStarted) {
                                writeAudioUpTo(encInfo.presentationTimeUs)
                                data.position(encInfo.offset)
                                data.limit(encInfo.offset + encInfo.size)
                                mux.writeSampleData(muxerVideoTrack, data, encInfo)
                                videoFramesWritten++
                            }
                            enc.releaseOutputBuffer(encOutIdx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                        }
                    }
                    handled++
                }
                return handled
            }

            while (!encoderDone) {
                if (!decoderInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        // Keep feeding a little past endUs so B-frame reordering can't cut the tail
                        if (size < 0 || (endUs > 0 && t > endUs + 500_000)) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderOutputDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        val isEos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = decInfo.presentationTimeUs
                        val inRange = pts >= startUs && (endUs <= 0 || pts < endUs)
                        val fpsOk = lastRenderedPts == Long.MIN_VALUE || pts - lastRenderedPts >= minFrameGapUs
                        val doRender = decInfo.size != 0 && inRange && fpsOk
                        decoder.releaseOutputBuffer(decOutIdx, doRender)
                        if (doRender) {
                            drainEncoder(0L)
                            glPipeline.awaitAndRender(pts - startUs)
                            lastRenderedPts = pts
                        }
                        if (isEos) {
                            decoderOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Once input is exhausted, block briefly for the encoder's remaining output
                if (drainEncoder(if (decoderOutputDone) TIMEOUT_US else 0L) == 0 && decoderOutputDone) {
                    if (++eosTryAgainCount > 300) {
                        Log.w(TAG, "Encoder timed out waiting for EOS, exiting loop")
                        encoderDone = true
                    }
                }
            }

            if (!muxerStarted) return false
            writeAudioUpTo(-1L)
            muxer.stop()

            Log.i(TAG, "Wrote $videoFramesWritten video frames, $audioPos/${audio?.samples?.size ?: 0} audio samples")
            return output.exists() && output.length() > 0 && videoFramesWritten > 0
        } catch (e: Exception) {
            Log.e(TAG, "Video encode error: ${e.message}", e)
            return false
        } finally {
            try { glPipeline?.release() } catch (_: Exception) {}
            try { encoderSurface?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /** Requests High profile (AVC) / Main profile (HEVC) when the encoder supports it. */
    private fun applyBestProfile(encoder: MediaCodec, format: MediaFormat, mime: String) {
        try {
            val caps = encoder.codecInfo.getCapabilitiesForType(mime)
            val wanted = when (mime) {
                MIME_AVC -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                MIME_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                else -> return
            }
            val match = caps.profileLevels.filter { it.profile == wanted }.maxByOrNull { it.level } ?: return
            format.setInteger(MediaFormat.KEY_PROFILE, match.profile)
            format.setInteger(MediaFormat.KEY_LEVEL, match.level)
        } catch (e: Exception) {
            Log.w(TAG, "Could not query encoder profiles: ${e.message}")
        }
    }

    /**
     * Picks the largest standard resolution the bitrate can actually support, rather than
     * keeping 1080p and starving every frame of bits.
     */
    private fun pickOutputSize(inW: Int, inH: Int, bitRate: Int, fps: Int, mime: String): Pair<Int, Int> {
        // Rough bits-per-pixel needed for watchable output with phone hardware encoders
        val bpp = if (mime == MIME_HEVC) 0.03 else 0.045
        val maxPixels = bitRate / (fps * bpp)
        val shortIn = minOf(inW, inH)
        val longIn = maxOf(inW, inH)
        val aspect = longIn.toDouble() / shortIn
        val tiers = intArrayOf(1080, 720, 540, 480, 360)
        var shortOut = tiers.firstOrNull { it <= shortIn && it * it * aspect <= maxPixels } ?: 360
        shortOut = minOf(shortOut, shortIn)
        val longOut = (shortOut * aspect).roundToLong().toInt()

        val even = Pair(evenFloor(if (inW >= inH) longOut else shortOut), evenFloor(if (inW >= inH) shortOut else longOut))
        if (encoderSupportsSize(mime, even.first, even.second)) return even
        // Older encoders want multiples of 16
        val s = maxOf(16, (shortOut / 16) * 16)
        val l = maxOf(16, (longOut / 16) * 16)
        return if (inW >= inH) Pair(l, s) else Pair(s, l)
    }

    private fun evenFloor(v: Int) = maxOf(2, v - v % 2)

    private fun encoderSupportsSize(mime: String, w: Int, h: Int): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(w, h) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class VideoMeta(val width: Int, val height: Int, val rotation: Int, val frameRate: Int)

    private fun extractVideoMetadata(input: File, videoFormat: MediaFormat): VideoMeta {
        var width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) videoFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
        var height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
        var rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) videoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        var frameRate = try {
            if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) videoFormat.getNumber(MediaFormat.KEY_FRAME_RATE)?.toInt() ?: 30 else 30
        } catch (_: Exception) {
            30
        }

        if (width <= 0 || height <= 0 || rotation == 0) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(input.path)
                if (width <= 0) width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                if (height <= 0) height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rotation == 0) rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever fallback failed: ${e.message}")
            }
        }

        if (width <= 0) width = 1080
        if (height <= 0) height = 1920
        frameRate = frameRate.coerceIn(10, 120)
        return VideoMeta(width, height, rotation, frameRate)
    }
}
