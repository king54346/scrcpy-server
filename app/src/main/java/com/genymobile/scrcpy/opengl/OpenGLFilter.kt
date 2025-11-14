package com.genymobile.scrcpy.opengl

interface OpenGLFilter {
    /**
     * Initialize the OpenGL filter (typically compile the shaders and create the program).
     *
     * @throws OpenGLException if an initialization error occurs
     */
    @Throws(OpenGLException::class)
    fun init()

    /**
     * Render a frame (call for each frame).
     */
    fun draw(textureId: Int, texMatrix: FloatArray?)

    /**
     * Release resources.
     */
    fun release()
}