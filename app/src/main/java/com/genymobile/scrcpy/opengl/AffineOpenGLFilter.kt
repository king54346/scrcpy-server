package com.genymobile.scrcpy.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.genymobile.scrcpy.util.AffineMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 仿射变换的 OpenGL 滤镜
 * 对输入纹理应用仿射变换(旋转、缩放、平移、错切等)并渲染到帧缓冲区
 */
class AffineOpenGLFilter(transform: AffineMatrix) : OpenGLFilter {

    // 着色器程序和缓冲区
    private var program = 0
    private var vertexBuffer: FloatBuffer
    private var texCoordsBuffer: FloatBuffer
    private val userMatrix = transform.to4x4()

    // 着色器位置句柄
    private var vertexPosLoc = -1
    private var texCoordsInLoc = -1
    private var texLoc = -1
    private var texMatrixLoc = -1
    private var userMatrixLoc = -1

    init {
        // 预先创建缓冲区(避免在 init() 中可能的重复创建)
        vertexBuffer = createFloatBuffer(VERTICES)
        texCoordsBuffer = createFloatBuffer(TEX_COORDS)
    }

    @Throws(OpenGLException::class)
    override fun init() {
        // 创建并编译着色器程序
        program = GLUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            throw OpenGLException("Failed to create OpenGL program")
        }

        // 获取着色器变量位置
        vertexPosLoc = GLES20.glGetAttribLocation(program, "vertex_pos")
        texCoordsInLoc = GLES20.glGetAttribLocation(program, "tex_coords_in")
        texLoc = GLES20.glGetUniformLocation(program, "tex")
        texMatrixLoc = GLES20.glGetUniformLocation(program, "tex_matrix")
        userMatrixLoc = GLES20.glGetUniformLocation(program, "user_matrix")

        // 验证位置有效性
        require(vertexPosLoc != -1) { "Cannot find vertex_pos attribute" }
        require(texCoordsInLoc != -1) { "Cannot find tex_coords_in attribute" }
        require(texLoc != -1) { "Cannot find tex uniform" }
        require(texMatrixLoc != -1) { "Cannot find tex_matrix uniform" }
        require(userMatrixLoc != -1) { "Cannot find user_matrix uniform" }
    }

    override fun draw(textureId: Int, texMatrix: FloatArray?) {
        // 使用着色器程序
        GLES20.glUseProgram(program)

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(vertexPosLoc)
        GLES20.glEnableVertexAttribArray(texCoordsInLoc)

        // 设置顶点和纹理坐标
        GLES20.glVertexAttribPointer(vertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(texCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordsBuffer)

        // 绑定外部纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texLoc, 0)

        // 设置变换矩阵
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(userMatrixLoc, 1, false, userMatrix, 0)

        // 清除并绘制
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性数组(可选,但是好习惯)
        GLES20.glDisableVertexAttribArray(vertexPosLoc)
        GLES20.glDisableVertexAttribArray(texCoordsInLoc)

        // 如果需要调试,可以在这里检查错误
        // GLUtils.checkGlError()
    }

    override fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    companion object {
        // 全屏四边形顶点(NDC坐标)
        private val VERTICES = floatArrayOf(
            -1f, -1f,  // 左下
            1f, -1f,  // 右下
            -1f,  1f,  // 左上
            1f,  1f   // 右上
        )

        // 纹理坐标
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,  // 左下
            1f, 0f,  // 右下
            0f, 1f,  // 左上
            1f, 1f   // 右上
        )

        // 顶点着色器
        private const val VERTEX_SHADER = """#version 100
attribute vec4 vertex_pos;
attribute vec4 tex_coords_in;
varying vec2 tex_coords;
uniform mat4 tex_matrix;
uniform mat4 user_matrix;

void main() {
    gl_Position = vertex_pos;
    tex_coords = (tex_matrix * user_matrix * tex_coords_in).xy;
}"""

        // 片段着色器
        private const val FRAGMENT_SHADER = """#version 100
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES tex;
varying vec2 tex_coords;

void main() {
    // 只渲染有效纹理坐标范围内的像素
    if (tex_coords.x >= 0.0 && tex_coords.x <= 1.0 &&
        tex_coords.y >= 0.0 && tex_coords.y <= 1.0) {
        gl_FragColor = texture2D(tex, tex_coords);
    } else {
        gl_FragColor = vec4(0.0);  // 超出范围显示透明
    }
}"""

        /**
         * 创建 FloatBuffer(使用 native order 以提高性能)
         */
        private fun createFloatBuffer(data: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
        }
    }
}