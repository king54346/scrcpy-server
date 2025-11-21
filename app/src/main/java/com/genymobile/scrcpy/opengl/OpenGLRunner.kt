package com.genymobile.scrcpy.opengl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.opengl.GLUtils.checkGlError
import java.util.concurrent.Semaphore
// 接收 MediaCodec/MediaProjection 的输出，应用变换（如处理屏幕旋转），然后送给编码器进行视频编码和网络传输。
//start() → 在 Handler 线程初始化 EGL
//→ 创建 SurfaceTexture 和输入 Surface
//→ 设置帧可用监听器
//→ 返回输入 Surface 供上游写入
//
//每帧到达 → updateTexImage() 更新纹理
//→ filter.draw() 应用滤镜处理
//→ eglSwapBuffers() 输出到目标
//
//stopAndRelease() → 在 Handler 线程清理所有资源
//通过 OpenGLFilter 进行图像处理（如旋转、缩放、滤镜）
class OpenGLRunner @JvmOverloads constructor(
    private val filter: OpenGLFilter,
    private val overrideTransformMatrix: FloatArray? = null
) {
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var textureId = 0

    private var stopped = false

    @Throws(OpenGLException::class)
    fun start(inputSize: Size, outputSize: Size, outputSurface: Surface): Surface? {
        initOnce()

        // Simulate CompletableFuture, but working for all Android versions
        val sem = Semaphore(0)
        val throwableRef = arrayOfNulls<Throwable>(1)

        // The whole OpenGL execution must be performed on a Handler, so that SurfaceTexture.setOnFrameAvailableListener() works correctly.
        // See <https://github.com/Genymobile/scrcpy/issues/5444>
        handler!!.post {
            try {
                run(inputSize, outputSize, outputSurface)
            } catch (throwable: Throwable) {
                throwableRef[0] = throwable
            } finally {
                sem.release()
            }
        }

        try {
            sem.acquire()
        } catch (e: InterruptedException) {
            // Behave as if this method call was synchronous
            Thread.currentThread().interrupt()
        }

        val throwable = throwableRef[0]
        if (throwable != null) {
            if (throwable is OpenGLException) {
                throw throwable
            }
            throw OpenGLException("Asynchronous OpenGL runner init failed", throwable)
        }

        // Synchronization is ok: inputSurface is written before sem.release() and read after sem.acquire()
        return inputSurface
    }

    @Throws(OpenGLException::class)
    private fun run(inputSize: Size, outputSize: Size, outputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw OpenGLException("Unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw OpenGLException("Unable to initialize EGL14")
        }

        // @formatter:off
        val attribList = intArrayOf(EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        if (numConfigs[0] <= 0) {
            EGL14.eglTerminate(eglDisplay)
            throw OpenGLException("Unable to find ES2 EGL config")
        }
        val eglConfig = configs[0]

        // @formatter:off
        val contextAttribList = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0)
        if (eglContext == null) {
            EGL14.eglTerminate(eglDisplay)
            throw OpenGLException("Failed to create EGL context")
        }

        val surfaceAttribList = intArrayOf(EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0)
        if (eglSurface == null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            throw OpenGLException("Failed to create EGL window surface")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            throw OpenGLException("Failed to make EGL context current")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError()
        textureId = textures[0]

        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        checkGlError()
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        checkGlError()
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError()
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError()

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setDefaultBufferSize(inputSize.width, inputSize.height)
        inputSurface = Surface(surfaceTexture)

        filter.init()

        surfaceTexture!!.setOnFrameAvailableListener({
                surfaceTexture: SurfaceTexture? -> if (stopped) {
            // Make sure to never render after resources have been released
            return@setOnFrameAvailableListener
        }
            render(outputSize)
        }, handler)
    }

    private fun render(outputSize: Size) {
        GLES20.glViewport(0, 0, outputSize.width, outputSize.height)
        checkGlError()

        surfaceTexture!!.updateTexImage()

        val matrix: FloatArray
        if (overrideTransformMatrix != null) {
            matrix = overrideTransformMatrix
        } else {
            matrix = FloatArray(16)
            surfaceTexture!!.getTransformMatrix(matrix)
        }

        filter.draw(textureId, matrix)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture!!.timestamp)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun stopAndRelease() {
        val sem = Semaphore(0)

        handler!!.post{
            stopped = true
            surfaceTexture!!.setOnFrameAvailableListener(null, handler)

            filter.release()

            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            checkGlError()

            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            surfaceTexture!!.release()
            inputSurface!!.release()
            sem.release()
        }

        try {
            sem.acquire()
        }catch (e: InterruptedException) {
            // Behave as if this method call was synchronous
            Thread.currentThread().interrupt()
        }
    }
    companion object {
        private var handlerThread: HandlerThread? = null
        private var handler: Handler? = null
        private var quit = false

        @Synchronized fun initOnce() {
            if (handlerThread == null) {
                check(!quit) {"Could not init OpenGLRunner after it is quit"}
                handlerThread = HandlerThread("OpenGLRunner")
                handlerThread!!.start()
                handler = Handler(handlerThread!!.looper)
            }
        }

        fun quit() {
            val thread: HandlerThread?
            synchronized(OpenGLRunner::class.java) {
                thread = handlerThread
                quit = true
            }
            thread?.quitSafely()
        }

        @Throws(InterruptedException::class) fun join() {
            val thread: HandlerThread?
            synchronized(OpenGLRunner::class.java) {thread = handlerThread
            }
            thread?.join()
        }
    }}