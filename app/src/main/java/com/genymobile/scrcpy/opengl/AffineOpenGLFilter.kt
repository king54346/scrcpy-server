package com.genymobile.scrcpy.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.genymobile.scrcpy.util.AffineMatrix
import java.nio.FloatBuffer

class AffineOpenGLFilter(transform: AffineMatrix) : OpenGLFilter {
    private var program = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordsBuffer: FloatBuffer? = null
    private val userMatrix = transform.to4x4()

    private var vertexPosLoc = 0
    private var texCoordsInLoc = 0

    private var texLoc = 0
    private var texMatrixLoc = 0
    private var userMatrixLoc = 0

    @Throws(OpenGLException::class)
    override fun init() {
        // @formatter:off
        val vertexShaderCode = ("""#version 100
attribute vec4 vertex_pos;
attribute vec4 tex_coords_in;
varying vec2 tex_coords;
uniform mat4 tex_matrix;
uniform mat4 user_matrix;
void main() {
    gl_Position = vertex_pos;
    tex_coords = (tex_matrix * user_matrix * tex_coords_in).xy;
}""")

        // @formatter:off
        val fragmentShaderCode = ("""#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES tex;
varying vec2 tex_coords;
void main() {
    if (tex_coords.x >= 0.0 && tex_coords.x <= 1.0
            && tex_coords.y >= 0.0 && tex_coords.y <= 1.0) {
        gl_FragColor = texture2D(tex, tex_coords);
    } else {
        gl_FragColor = vec4(0.0);
    }
}""")

        program = GLUtils.createProgram(vertexShaderCode, fragmentShaderCode)
        if (program == 0) {
            throw OpenGLException("Cannot create OpenGL program")
        }

        val vertices = floatArrayOf(-1f, -1f,  // Bottom-left
            1f, -1f,  // Bottom-right
            -1f, 1f,  // Top-left
            1f, 1f,  // Top-right
        )

        val texCoords = floatArrayOf(0f, 0f,  // Bottom-left
            1f, 0f,  // Bottom-right
            0f, 1f,  // Top-left
            1f, 1f,  // Top-right
        )

        // OpenGL will fill the 3rd and 4th coordinates of the vec4 automatically with 0.0 and 1.0 respectively
        vertexBuffer = GLUtils.createFloatBuffer(vertices)
        texCoordsBuffer = GLUtils.createFloatBuffer(texCoords)

        vertexPosLoc = GLES20.glGetAttribLocation(program, "vertex_pos")
        assert(vertexPosLoc != -1)

        texCoordsInLoc = GLES20.glGetAttribLocation(program, "tex_coords_in")
        assert(texCoordsInLoc != -1)

        texLoc = GLES20.glGetUniformLocation(program, "tex")
        assert(texLoc != -1)

        texMatrixLoc = GLES20.glGetUniformLocation(program, "tex_matrix")
        assert(texMatrixLoc != -1)

        userMatrixLoc = GLES20.glGetUniformLocation(program, "user_matrix")
        assert(userMatrixLoc != -1)
    }

    override fun draw(textureId: Int, texMatrix: FloatArray?) {
        GLES20.glUseProgram(program)
        GLUtils.checkGlError()

        GLES20.glEnableVertexAttribArray(vertexPosLoc)
        GLUtils.checkGlError()
        GLES20.glEnableVertexAttribArray(texCoordsInLoc)
        GLUtils.checkGlError()

        GLES20.glVertexAttribPointer(vertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLUtils.checkGlError()
        GLES20.glVertexAttribPointer(texCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordsBuffer)
        GLUtils.checkGlError()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLUtils.checkGlError()
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLUtils.checkGlError()
        GLES20.glUniform1i(texLoc, 0)
        GLUtils.checkGlError()

        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        GLUtils.checkGlError()

        GLES20.glUniformMatrix4fv(userMatrixLoc, 1, false, userMatrix, 0)
        GLUtils.checkGlError()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLUtils.checkGlError()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLUtils.checkGlError()
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
        GLUtils.checkGlError()
    }
}