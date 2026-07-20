package com.instaembed.instaembed

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaExtractor
import android.util.Log
import java.io.File

object VideoCompressor {
    private const val TAG = "VideoCompressor"
    private const val TIMEOUT_US = 10_000L

    fun compress(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open input: ${e.message}")
            extractor.release()
            return false
        }

        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
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
            extractor.release()
            return false
        }

        val inputW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val inputH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val (outW, outH) = calcSize(inputW, inputH, 1280, 720)

        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null

        try {
            val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(videoFormat, inputSurface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrackIdx)
            val bufferInfo = MediaCodec.BufferInfo()
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (!decoderDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize.toInt(),
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Decoder format changed: ${decoder.outputFormat}")
                    } else if (decOutIdx >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(decOutIdx, true)
                        if (isEos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioTrackIdx != -1 && audioFormat != null) {
                                val audioEncFormat = MediaFormat.createAudioFormat(
                                    "audio/mp4a-latm",
                                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                ).apply {
                                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                                    setInteger(MediaFormat.KEY_AAC_PROFILE,
                                        MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                                }
                                muxerAudioTrack = muxer.addTrack(audioEncFormat)
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

            if (audioTrackIdx != -1 && audioFormat != null && muxerAudioTrack >= 0) {
                transcodeAudio(extractor, audioTrackIdx, audioFormat, muxer, muxerAudioTrack)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Compression failed: ${e.message}", e)
            try { muxer.release() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            output.delete()
            return false
        }

        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
            encoder?.release()
            decoder?.release()
            extractor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}")
        }

        return output.exists() && output.length() > 0 && output.length() < input.length()
    }

    private fun transcodeAudio(
        extractor: MediaExtractor,
        trackIdx: Int,
        inputFormat: MediaFormat,
        muxer: MediaMuxer,
        muxerTrack: Int
    ) {
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val encFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        extractor.selectTrack(trackIdx)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false

        while (!encoderDone) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize.toInt(),
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            if (!decoderDone) {
                val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (decOutIdx >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (!isEos && bufferInfo.size > 0) {
                        val decodedBuf = decoder.getOutputBuffer(decOutIdx)!!
                        val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encInIdx >= 0) {
                            val encBuf = encoder.getInputBuffer(encInIdx)!!
                            encBuf.clear()
                            val pos = minOf(bufferInfo.size, encBuf.capacity())
                            decodedBuf.position(bufferInfo.offset)
                            decodedBuf.limit(bufferInfo.offset + pos)
                            encBuf.put(decodedBuf)
                            encoder.queueInputBuffer(encInIdx, 0, pos,
                                bufferInfo.presentationTimeUs, 0)
                        }
                    }
                    decoder.releaseOutputBuffer(decOutIdx, false)
                    if (isEos) {
                        decoderDone = true
                        val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encInIdx >= 0) {
                            encoder.queueInputBuffer(encInIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }
            }

            val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encOutIdx >= 0) {
                val data = encoder.getOutputBuffer(encOutIdx)!!
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    data.position(bufferInfo.offset)
                    data.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(muxerTrack, data, bufferInfo)
                }
                encoder.releaseOutputBuffer(encOutIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoderDone = true
                }
            }
        }

        decoder.release()
        encoder.release()
    }

    private fun calcSize(inW: Int, inH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (inW <= maxW && inH <= maxH) {
            var w = inW
            var h = inH
            if (w % 2 != 0) w++
            if (h % 2 != 0) h++
            return Pair(w, h)
        }
        val ratio = minOf(maxW.toFloat() / inW, maxH.toFloat() / inH)
        var w = (inW * ratio).toInt()
        var h = (inH * ratio).toInt()
        if (w % 2 != 0) w++
        if (h % 2 != 0) h++
        return Pair(w, h)
    }
}
