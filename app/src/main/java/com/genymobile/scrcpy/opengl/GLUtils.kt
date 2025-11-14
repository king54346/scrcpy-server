package com.genymobile.scrcpy.opengl

import android.opengl.GLES20
import android.opengl.GLU
import com.genymobile.scrcpy.BuildConfig
import com.genymobile.scrcpy.util.Ln.e
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GLUtils {
    private val DEBUG = BuildConfig.DEBUG

    fun createProgram(vertexSource: String?, fragmentSource: String?): Int {
        val vertexShader = createShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }

        val fragmentShader = createShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val program = GLES20.glCreateProgram()
        if (program == 0) {
            GLES20.glDeleteShader(fragmentShader)
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        GLES20.glAttachShader(program, vertexShader)
        checkGlError()
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError()
        GLES20.glLinkProgram(program)
        checkGlError()

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            e("Could not link program: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(fragmentShader)
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        return program
    }

    fun createShader(type: Int, source: String?): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            e(getGlErrorMessage("Could not create shader"))
            return 0
        }

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            e(
                "Could not compile " + getShaderTypeString(type) + ": " + GLES20.glGetShaderInfoLog(
                    shader
                )
            )
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun getShaderTypeString(type: Int): String {
        return when (type) {
            GLES20.GL_VERTEX_SHADER -> "vertex shader"
            GLES20.GL_FRAGMENT_SHADER -> "fragment shader"
            else -> "shader"
        }
    }

    /**
     * Throws a runtime exception if [GLES20.glGetError] returns an error (useful for debugging).
     */
    fun checkGlError() {
        if (DEBUG) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw RuntimeException(toErrorString(error))
            }
        }
    }

    fun getGlErrorMessage(userError: String): String {
        val glError = GLES20.glGetError()
        if (glError == GLES20.GL_NO_ERROR) {
            return userError
        }

        return userError + " (" + toErrorString(glError) + ")"
    }

    private fun toErrorString(glError: Int): String {
        val errorString = GLU.gluErrorString(glError)
        return "glError 0x" + Integer.toHexString(glError) + " " + errorString
    }

    fun createFloatBuffer(values: FloatArray): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        fb.put(values)
        fb.position(0)
        return fb
    }
}