package com.futurehouser.instarecording

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer

private const val TAG = "InstaRecording"
private const val OUT_W = 1080
private const val OUT_H = 1920
private const val AUDIO_RATE = 48000
private const val AUDIO_CHANNELS = 2
private const val AUDIO_BITRATE = 320_000

/**
 * Mirrors the screen into an OpenGL texture from the moment it's created, so the capture
 * permission is used straight away; start() then sends composited frames to the encoder.
 */
class Recorder(private val projection: MediaProjection, width: Int, height: Int,
               private val dpi: Int) {
    private val thread = HandlerThread("InstaRecorderGl").apply { start() }
    private val gl = Handler(thread.looper)

    private var srcW = width
    private var srcH = height
    private lateinit var compositor: Compositor
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface
    private var display: VirtualDisplay? = null
    private val transform = FloatArray(16)

    private val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var encoder: MediaCodec? = null
    private var encoderInput: Surface? = null
    private var mux: Mux? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var crop = RectF(0f, 0f, 1f, 1f)
    private var startNs = 0L
    private var frameNs = 0L
    private var frameCount = 0L

    private var released = false
    @Volatile private var recording = false
    @Volatile private var endingVideo = false
    @Volatile private var audioRecord: AudioRecord? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            drawFrame()
            frameCount++
            val next = startNs + frameCount * frameNs
            gl.postDelayed(this, maxOf(0L, (next - System.nanoTime()) / 1_000_000))
        }
    }

    init {
        gl.post {
            setUpEgl()
            compositor = Compositor()
            texture = SurfaceTexture(compositor.sourceTexture)
            texture.setDefaultBufferSize(width, height)
            // A last frame can still be in the queue when the capture is released.
            texture.setOnFrameAvailableListener({ if (!released) it.updateTexImage() }, gl)
            surface = Surface(texture)
            display = projection.createVirtualDisplay("Insta-recording", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, gl)
        }
    }

    /** The screen rotated. */
    fun resize(width: Int, height: Int) = gl.post {
        srcW = width
        srcH = height
        texture.setDefaultBufferSize(width, height)
        display?.resize(width, height, dpi)
    }

    fun start(area: RectF, fps: Int, withAudio: Boolean, limitSeconds: Int?, output: FileDescriptor,
              onFailed: (Exception) -> Unit) = gl.post {
        try {
            crop = area
            val fpsUsed = createEncoder(fps)
            mux = Mux(output, withAudio, limitSeconds?.let { it * 1_000_000L } ?: Long.MAX_VALUE)
            encoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInput,
                intArrayOf(EGL14.EGL_NONE), 0)
            encoder!!.start()
            makeCurrent(encoderSurface)
            frameNs = 1_000_000_000L / fpsUsed
            frameCount = 0
            startNs = System.nanoTime()
            mux!!.startNs = startNs
            endingVideo = false
            recording = true
            videoThread = Thread(::drainVideo, "InstaVideo").apply { start() }
            if (withAudio) audioThread = Thread(::recordAudio, "InstaAudio").apply { start() }
            tick.run()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start recording", e)
            onFailed(e)
        }
    }

    /**
     * Finishes the file; onDone runs on a background thread with whether it's playable.
     * @param trimEnd cut the last seconds, which show the user reaching for the notification
     */
    fun stop(trimEnd: Boolean, onDone: (Boolean) -> Unit) {
        gl.post {
            recording = false
            gl.removeCallbacks(tick)
            endingVideo = true
            try {
                encoder?.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't end the video stream", e)
            }
        }
        Thread({
            // Wait for the GL thread above to have run before looking at the worker threads.
            val posted = java.util.concurrent.CountDownLatch(1)
            gl.post { posted.countDown() }
            posted.await()
            audioRecord?.let { runCatching { it.stop() } }
            audioThread?.join(5000)
            videoThread?.join(10000)
            val ok = mux?.finish(trimEnd) ?: false
            onDone(ok)
        }, "InstaFinish").start()
    }

    fun release() = gl.post {
        released = true
        display?.release()
        runCatching { encoder?.release() }
        encoderInput?.release()
        surface.release()
        texture.release()
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (encoderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderSurface)
        EGL14.eglDestroySurface(eglDisplay, idleSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        thread.quitSafely()
    }

    private fun setUpEgl() {
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
        check(count[0] > 0) { "No recordable EGL config" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idleSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(idleSurface)
    }

    private fun makeCurrent(target: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun drawFrame() {
        texture.getTransformMatrix(transform)
        compositor.draw(OUT_W, OUT_H, transform, crop, srcW, srcH)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
    }

    /** Best settings the phone accepts, falling back for weaker encoders. Returns the fps. */
    private fun createEncoder(fps: Int): Int {
        val attempts = listOf(fps to true, fps to false, 30 to true, 30 to false)
        var failure: Exception? = null
        for ((rate, high) in attempts.distinct()) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (rate > 30) 24_000_000 else 16_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    if (high) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                    }
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderInput = codec.createInputSurface()
                encoder = codec
                return rate
            } catch (e: Exception) {
                Log.w(TAG, "Encoder rejected $rate fps, high=$high", e)
                failure = e
                codec.release()
            }
        }
        throw failure!!
    }

    private fun drainVideo() {
        val codec = encoder!!
        val info = MediaCodec.BufferInfo()
        var deadline = Long.MAX_VALUE
        try {
            while (System.nanoTime() < deadline) {
                if (endingVideo && deadline == Long.MAX_VALUE) deadline = System.nanoTime() + 5_000_000_000L
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mux?.setFormat(video = true, codec.outputFormat)
                } else if (index >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        mux?.write(video = true, codec.getOutputBuffer(index)!!, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Video encoding failed", e)
        }
    }

    private fun recordAudio() {
        var codec: MediaCodec? = null
        try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val pcm = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder().setAudioFormat(pcm)
                .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_RATE)).setAudioPlaybackCaptureConfig(capture).build()
            audioRecord = record
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()

            val info = MediaCodec.BufferInfo()
            val chunk = ByteArray(CHUNK_BYTES)
            var firstChunk = true
            var written = 0L          // samples per channel so far
            var skippedUs = 0L
            val startUs = startNs / 1000
            while (recording) {
                // A silent first chunk gives the file its audio track even if nothing is playing yet.
                val size = if (firstChunk) CHUNK_BYTES else record.read(chunk, 0, chunk.size)
                if (size <= 0) {
                    if (size < 0 && recording) throw IllegalStateException("AudioRecord error $size")
                    continue
                }
                firstChunk = false
                val samples = size / BYTES_PER_SAMPLE
                val nowUs = System.nanoTime() / 1000
                var ptsUs = startUs + skippedUs + written * 1_000_000 / AUDIO_RATE
                // Some phones deliver nothing during silence: keep the sound in sync with the video.
                val behindUs = nowUs - samples * 1_000_000L / AUDIO_RATE - ptsUs
                if (behindUs > 100_000) {
                    skippedUs += behindUs
                    ptsUs += behindUs
                }
                feedAudio(codec, chunk, size, ptsUs, 0, info)
                written += samples
            }
            feedAudio(codec, chunk, 0, startUs + skippedUs + written * 1_000_000 / AUDIO_RATE,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM, info)
            var drained = false
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!drained && System.nanoTime() < deadline) drained = drainAudio(codec, info)
        } catch (e: Exception) {
            Log.e(TAG, "System audio failed; keeping video only", e)
            mux?.noAudio()
        } finally {
            audioRecord?.let { runCatching { it.release() } }
            audioRecord = null
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        }
    }

    private fun feedAudio(codec: MediaCodec, data: ByteArray, size: Int, ptsUs: Long, flags: Int,
                          info: MediaCodec.BufferInfo) {
        var index: Int
        do {
            index = codec.dequeueInputBuffer(10_000)
            if (index < 0) drainAudio(codec, info)
        } while (index < 0)
        codec.getInputBuffer(index)!!.apply { clear(); put(data, 0, size) }
        codec.queueInputBuffer(index, 0, size, ptsUs, flags)
        drainAudio(codec, info)
    }

    /** Returns true at the end of the stream. */
    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mux?.setFormat(video = false, codec.outputFormat)
            } else if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    mux?.write(video = false, codec.getOutputBuffer(index)!!, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            } else {
                return false
            }
        }
    }

    /**
     * Writes the MP4. The newest samples wait here so that the end can still be cut off, and
     * all of them wait until both tracks are known, which MediaMuxer requires.
     */
    private class Mux(output: FileDescriptor, private var expectAudio: Boolean,
                      private val limitUs: Long) {
        private class Sample(val video: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

        private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var videoSamples = 0
        private var newestUs = 0L
        private val held = ArrayDeque<Sample>()
        var startNs = 0L

        @Synchronized
        fun setFormat(video: Boolean, format: MediaFormat) {
            val track = muxer.addTrack(format)
            if (video) videoTrack = track else audioTrack = track
            startIfReady()
        }

        @Synchronized
        fun noAudio() {
            expectAudio = false
            startIfReady()
        }

        @Synchronized
        fun write(video: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val pts = info.presentationTimeUs - startNs / 1000
            if (pts < 0 || pts > limitUs) return
            val copy = ByteBuffer.allocate(info.size)
            copy.put(data.duplicate().apply { position(info.offset); limit(info.offset + info.size) })
            copy.flip()
            held.addLast(Sample(video, copy, MediaCodec.BufferInfo().apply {
                set(0, info.size, pts, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
            }))
            newestUs = maxOf(newestUs, pts)
            if (started) writeUpTo(newestUs - TRIM_US)
        }

        private fun startIfReady() {
            if (started || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
            muxer.start()
            started = true
        }

        private fun writeUpTo(us: Long) {
            while (held.isNotEmpty() && held.first().info.presentationTimeUs <= us) {
                val s = held.removeFirst()
                val track = if (s.video) videoTrack else audioTrack
                if (track >= 0) {
                    muxer.writeSampleData(track, s.data, s.info)
                    if (s.video) videoSamples++
                }
            }
        }

        @Synchronized
        fun finish(trimEnd: Boolean): Boolean = try {
            if (started) writeUpTo(if (trimEnd && newestUs > TRIM_US * 2) newestUs - TRIM_US else newestUs)
            val ok = started && videoSamples > 0
            if (started) muxer.stop()
            muxer.release()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't finish the file", e)
            false
        }

        private companion object {
            const val TRIM_US = 2_000_000L
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val BYTES_PER_SAMPLE = 2 * AUDIO_CHANNELS
        const val CHUNK_BYTES = 2048 * BYTES_PER_SAMPLE
    }
}
