package com.instaembed.instaembed

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoCompressor {
    private const val TAG = "VideoCompressor"
    private const val TIMEOUT_US = 10_000L
    private const val AUDIO_BIT_RATE_ESTIMATE = 64_000
    private const val MIN_VIDEO_BIT_RATE = 150_000
    private const val MAX_VIDEO_BIT_RATE = 6_000_000
    // Headroom for MP4 container/moov overhead and VBR encoder variance
    private const val SIZE_SAFETY_MARGIN = 0.85

    fun compress(input: File, output: File, targetSizeBytes: Long): Boolean {
        if (!input.exists() || input.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty: ${input.path}")
            return false
        }

        // Pass 1: standard compression with safety margin
        val pass1Success = doCompress(input, output, targetSizeBytes, qualityMultiplier = 1.0f)
        if (pass1Success && output.exists() && output.length() > 0) {
            if (output.length() <= targetSizeBytes) {
                Log.i(TAG, "Compression succeeded on pass 1: ${output.length()} bytes (target: $targetSizeBytes bytes)")
                return true
            }
            Log.w(TAG, "Pass 1 output (${output.length()} bytes) exceeded target ($targetSizeBytes bytes). Attempting pass 2...")
            output.delete()
        }

        // Pass 2: more aggressive reduction if pass 1 slightly overshot target size
        val pass2Success = doCompress(input, output, targetSizeBytes, qualityMultiplier = 0.65f)
        if (pass2Success && output.exists() && output.length() > 0) {
            if (output.length() <= targetSizeBytes) {
                Log.i(TAG, "Compression succeeded on pass 2: ${output.length()} bytes (target: $targetSizeBytes bytes)")
                return true
            }
            Log.w(TAG, "Pass 2 output (${output.length()} bytes) still exceeded target ($targetSizeBytes bytes). Attempting pass 3...")
            output.delete()
        }

        // Pass 3: lowest bitrate fallback
        val pass3Success = doCompress(input, output, targetSizeBytes, qualityMultiplier = 0.45f)
        if (pass3Success && output.exists() && output.length() > 0 && output.length() <= targetSizeBytes) {
            Log.i(TAG, "Compression succeeded on pass 3: ${output.length()} bytes")
            return true
        }

        if (output.exists() && output.length() > targetSizeBytes) {
            Log.e(TAG, "All compression passes produced file larger than target size. Deleting output.")
            output.delete()
            return false
        }

        return output.exists() && output.length() > 0 && output.length() < input.length()
    }

    private fun doCompress(
        input: File,
        output: File,
        targetSizeBytes: Long,
        qualityMultiplier: Float
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(input.path) }

            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoTrackIdx == -1 -> {
                        videoTrackIdx = i
                        videoFormat = fmt
                    }
                    mime.startsWith("audio/") && audioTrackIdx == -1 -> {
                        audioTrackIdx = i
                        audioFormat = fmt
                    }
                }
            }

            if (videoTrackIdx == -1 || videoFormat == null) {
                Log.e(TAG, "No video track found in input")
                return false
            }

            // Extract metadata with fallback to MediaMetadataRetriever
            val (inputW, inputH, durationUs, rotation, frameRate) = extractVideoMetadata(input, videoFormat, audioFormat)
            val durationSec = (durationUs / 1_000_000.0).coerceAtLeast(1.0)
            val hasAudio = audioTrackIdx != -1 && audioFormat != null
            val audioBitRate = if (hasAudio) AUDIO_BIT_RATE_ESTIMATE else 0

            // Bitrate calculation
            val budgetBits = (targetSizeBytes * 8 * SIZE_SAFETY_MARGIN * qualityMultiplier).toLong()
            val rawVideoBitRate = ((budgetBits / durationSec) - audioBitRate).toInt()
            val videoBitRate = rawVideoBitRate.coerceIn(MIN_VIDEO_BIT_RATE, MAX_VIDEO_BIT_RATE)

            // Resolution calculation based on bitrate and orientation
            val isPortrait = inputH > inputW
            val maxDimensions = when {
                videoBitRate < 600_000 || qualityMultiplier <= 0.5f ->
                    if (isPortrait) Pair(480, 854) else Pair(854, 480)
                videoBitRate < 1_200_000 || qualityMultiplier <= 0.7f ->
                    if (isPortrait) Pair(720, 1280) else Pair(1280, 720)
                else ->
                    if (isPortrait) Pair(1080, 1920) else Pair(1920, 1080)
            }
            val (outW, outH) = calcSize(inputW, inputH, maxDimensions.first, maxDimensions.second)

            Log.i(
                TAG,
                "Starting compression: duration=${durationSec}s, target=${targetSizeBytes}B, " +
                    "videoBitRate=$videoBitRate, resolution=${outW}x${outH} (in=${inputW}x${inputH}), " +
                    "fps=$frameRate, rotation=$rotation, qMult=$qualityMultiplier"
            )

            // Setup audio extractor if audio track exists
            if (hasAudio) {
                audioExtractor = MediaExtractor().apply {
                    setDataSource(input.path)
                    selectTrack(audioTrackIdx)
                }
            }

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }

            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(videoFormat, inputSurface, null, 0)
            decoder.start()

            videoExtractor.selectTrack(videoTrackIdx)
            val bufferInfo = MediaCodec.BufferInfo()
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderDone = false

            while (!encoderDone) {
                // Feed decoder input
                if (!decoderInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = videoExtractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderInputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, sampleSize.toInt(),
                                videoExtractor.sampleTime, 0
                            )
                            videoExtractor.advance()
                        }
                    }
                }

                // Process decoder output
                if (!decoderOutputDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(decOutIdx, !isEos)
                        if (isEos) {
                            decoderOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // Process encoder output
                val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (hasAudio && audioFormat != null) {
                                try {
                                    muxerAudioTrack = muxer.addTrack(audioFormat)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not add audio track directly: ${e.message}")
                                    muxerAudioTrack = -1
                                }
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encOutIdx >= 0 -> {
                        val data = encoder.getOutputBuffer(encOutIdx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }

            // Copy audio samples cleanly using the dedicated audio extractor
            if (hasAudio && audioExtractor != null && muxerAudioTrack >= 0 && muxerStarted) {
                copyAudioSamples(audioExtractor, muxer, muxerAudioTrack)
            }

            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop muxer: ${e.message}")
                    return false
                }
            }

            return output.exists() && output.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Compression error: ${e.message}", e)
            if (output.exists()) output.delete()
            return false
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    private fun copyAudioSamples(
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerAudioTrack: Int
    ) {
        val audioBuffer = ByteBuffer.allocateDirect(1024 * 512)
        val audioBufferInfo = MediaCodec.BufferInfo()

        while (true) {
            audioBuffer.clear()
            val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
            if (sampleSize < 0) break

            audioBufferInfo.offset = 0
            audioBufferInfo.size = sampleSize
            audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
            audioBufferInfo.flags = audioExtractor.sampleFlags

            muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
            audioExtractor.advance()
        }
    }

    private data class VideoMeta(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val rotation: Int,
        val frameRate: Int
    )

    private fun extractVideoMetadata(
        input: File,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?
    ): VideoMeta {
        var width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        } else 0

        var height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        } else 0

        var durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        if (durationUs <= 0L && audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            durationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
        }

        var rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            videoFormat.getInteger(MediaFormat.KEY_ROTATION)
        } else 0

        var frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else 30

        if (width <= 0 || height <= 0 || durationUs <= 0L) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(input.path)

                if (width <= 0) {
                    val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    width = wStr?.toIntOrNull() ?: 1080
                }
                if (height <= 0) {
                    val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    height = hStr?.toIntOrNull() ?: 1920
                }
                if (durationUs <= 0L) {
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durMs = durStr?.toLongOrNull() ?: 0L
                    durationUs = durMs * 1000L
                }
                if (rotation == 0) {
                    val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    rotation = rotStr?.toIntOrNull() ?: 0
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever fallback failed: ${e.message}")
            }
        }

        if (width <= 0) width = 1080
        if (height <= 0) height = 1920
        frameRate = frameRate.coerceIn(24, 60)

        return VideoMeta(width, height, durationUs, rotation, frameRate)
    }

    private fun calcSize(inW: Int, inH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val ratio = minOf(1.0f, minOf(maxW.toFloat() / inW, maxH.toFloat() / inH))
        var w = (inW * ratio).toInt()
        var h = (inH * ratio).toInt()

        // Align to multiples of 16 for broad hardware encoder compatibility
        w = maxOf(16, (w / 16) * 16)
        h = maxOf(16, (h / 16) * 16)

        return Pair(w, h)
    }
}
