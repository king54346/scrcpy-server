package com.genymobile.scrcpy.opengl

import java.io.IOException

class OpenGLException : IOException {
    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}