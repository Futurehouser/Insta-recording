package com.futurehouser.instarecording

import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws one Reels frame with OpenGL: a blurred, enlarged copy of the capture fills the canvas
 * and the whole capture sits sharp in the centre. It runs live, so nothing is left to convert
 * after recording. Must be used on the thread that owns the EGL context.
 */
class Compositor {
    val sourceTexture: Int

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).apply { position(0) }
    private val external = Program(EXTERNAL_FRAGMENT)
    private val copy = Program(COPY_FRAGMENT)
    private val blur = Program(BLUR_FRAGMENT)
    private val half = Target(BLUR_W * 2, BLUR_H * 2)
    private val blurA = Target(BLUR_W, BLUR_H)
    private val blurB = Target(BLUR_W, BLUR_H)

    init {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        sourceTexture = id[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTexture)
        filter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    }

    /**
     * @param transform the SurfaceTexture's matrix
     * @param crop recorded area as fractions of the screen, top-left origin
     */
    fun draw(outW: Int, outH: Int, transform: FloatArray, crop: RectF, srcW: Int, srcH: Int) {
        val area = (crop.width() * srcW) / (crop.height() * srcH)
        val canvas = outW.toFloat() / outH

        // Background: the middle of the area, cropped to fill the canvas.
        var u = crop.left
        var v = 1f - crop.bottom
        var uw = crop.width()
        var vh = crop.height()
        if (area > canvas) {
            val keep = canvas / area
            u += uw * (1f - keep) / 2f
            uw *= keep
        } else {
            val keep = area / canvas
            v += vh * (1f - keep) / 2f
            vh *= keep
        }
        half.bind()
        drawExternal(transform, u, v, uw, vh, -1f, -1f, 2f, 2f)
        blurA.bind()
        drawTexture(copy, half.texture)
        repeat(2) {
            blurB.bind()
            drawTexture(blur, blurA.texture, BLUR_STEP / BLUR_W, 0f)
            blurA.bind()
            drawTexture(blur, blurB.texture, 0f, BLUR_STEP / BLUR_H)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        drawTexture(copy, blurA.texture)

        // Foreground: the whole area, fitted to the canvas.
        val fitW = if (area > canvas) 2f else 2f * area / canvas
        val fitH = if (area > canvas) 2f * canvas / area else 2f
        drawExternal(transform, crop.left, 1f - crop.bottom, crop.width(), crop.height(),
            -fitW / 2f, -fitH / 2f, fitW, fitH)
    }

    private fun drawExternal(transform: FloatArray, u: Float, v: Float, uw: Float, vh: Float,
                             x: Float, y: Float, w: Float, h: Float) {
        external.use()
        GLES20.glUniformMatrix4fv(external.transform, 1, false, transform, 0)
        GLES20.glUniform4f(external.uvRect, u, v, uw, vh)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTexture)
        drawQuad(external, x, y, w, h)
    }

    private fun drawTexture(program: Program, texture: Int, dx: Float = 0f, dy: Float = 0f) {
        program.use()
        GLES20.glUniformMatrix4fv(program.transform, 1, false, IDENTITY, 0)
        GLES20.glUniform4f(program.uvRect, 0f, 0f, 1f, 1f)
        GLES20.glUniform2f(program.direction, dx, dy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        drawQuad(program, -1f, -1f, 2f, 2f)
    }

    private fun drawQuad(program: Program, x: Float, y: Float, w: Float, h: Float) {
        GLES20.glUniform4f(program.destRect, x, y, w, h)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private class Program(fragment: String) {
        private val id = GLES20.glCreateProgram()
        val position: Int
        val transform: Int
        val uvRect: Int
        val destRect: Int
        val direction: Int

        init {
            GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(id)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetProgramInfoLog(id) }
            position = GLES20.glGetAttribLocation(id, "aPos")
            transform = GLES20.glGetUniformLocation(id, "uTransform")
            uvRect = GLES20.glGetUniformLocation(id, "uUvRect")
            destRect = GLES20.glGetUniformLocation(id, "uDestRect")
            direction = GLES20.glGetUniformLocation(id, "uDirection")
        }

        fun use() = GLES20.glUseProgram(id)

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
    }

    private class Target(val width: Int, val height: Int) {
        val texture: Int
        private val framebuffer: Int

        init {
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            texture = id[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            filter(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, id, 0)
            framebuffer = id[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0)
        }

        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, width, height)
        }
    }

    companion object {
        // The blur works on a 1/8-size image: as strong as the PC version's 80 % but light on the GPU.
        private const val BLUR_W = 135
        private const val BLUR_H = 240
        private const val BLUR_STEP = 2f

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        private fun filter(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uTransform;
            uniform vec4 uUvRect;
            uniform vec4 uDestRect;
            varying vec2 vUv;
            void main() {
                vUv = (uTransform * vec4(uUvRect.xy + aPos * uUvRect.zw, 0.0, 1.0)).xy;
                gl_Position = vec4(uDestRect.xy + aPos * uDestRect.zw, 0.0, 1.0);
            }"""

        private const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""

        private const val COPY_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""

        private const val BLUR_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTexture, vUv) * 0.204;
                c += (texture2D(uTexture, vUv + uDirection) + texture2D(uTexture, vUv - uDirection)) * 0.180;
                c += (texture2D(uTexture, vUv + uDirection * 2.0) + texture2D(uTexture, vUv - uDirection * 2.0)) * 0.124;
                c += (texture2D(uTexture, vUv + uDirection * 3.0) + texture2D(uTexture, vUv - uDirection * 3.0)) * 0.066;
                c += (texture2D(uTexture, vUv + uDirection * 4.0) + texture2D(uTexture, vUv - uDirection * 4.0)) * 0.028;
                gl_FragColor = vec4(c.rgb, 1.0);
            }"""
    }
}
