package com.genymobile.scrcpy.vulkan

import NativeLibraryLoader
import VulkanException
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.genymobile.scrcpy.device.Size
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class VulkanRunner @JvmOverloads constructor(
    private val filter: VulkanFilter,
    private val overrideTransformMatrix: FloatArray? = null
) {
    // Vulkan handles
    private var vkInstance: Long = 0
    private var vkDevice: Long = 0
    private var vkRenderPass: Long = 0
    private var vkSwapchain: Long = 0
    private var vkCommandPool: Long = 0
    private var vkCommandBuffers: LongArray = LongArray(0)

    // Synchronization objects
    private var imageAvailableSemaphores: LongArray = LongArray(0)
    private var renderFinishedSemaphores: LongArray = LongArray(0)
    private var inFlightFences: LongArray = LongArray(0)
    private var currentFrame = 0

    // Input surface and texture
    private var inputSurface: Surface? = null
    private var inputTexture: Long = 0

    private var stopped = false
    private val isInitialized = AtomicBoolean(false)
    private var cachedReusableBuffer: ByteArray? = null
    // 🔥 新增：ImageReader 管理
    private var imageReader: ImageReader? = null
    private val imageReaderListener = ImageReader.OnImageAvailableListener { reader ->
        handleImageAvailable(reader)
    }

    @Throws(VulkanException::class)
    fun start(inputSize: Size, outputSize: Size, outputSurface: Surface): Surface? {
        initOnce()

        val sem = Semaphore(0)
        val throwableRef = arrayOfNulls<Throwable>(1)
        val surfaceRef = arrayOfNulls<Surface>(1)

        handler!!.post {
            try {
                surfaceRef[0] = run(inputSize, outputSize, outputSurface)
            } catch (throwable: Throwable) {
                throwableRef[0] = throwable
            } finally {
                sem.release()
            }
        }

        try {
            sem.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val throwable = throwableRef[0]
        if (throwable != null) {
            if (throwable is VulkanException) {
                throw throwable
            }
            throw VulkanException("Asynchronous Vulkan runner init failed", throwable)
        }

        // 🔥 允许返回 null（表示直接使用纹理，不需要输入 Surface）
        return surfaceRef[0]
    }

    @Throws(VulkanException::class)
    private fun run(inputSize: Size, outputSize: Size, outputSurface: Surface): Surface? {
        Log.d(TAG, "=== Initializing Vulkan Runner ===")

        // 1. Create Vulkan instance
        vkInstance = nativeCreateInstance()
        if (!validateHandle(vkInstance, "Instance")) {
            throw VulkanException("Failed to create Vulkan instance")
        }

        // 2. Create device
        vkDevice = nativeCreateDevice(vkInstance, outputSurface)
        if (!validateHandle(vkDevice, "Device")) {
            cleanup()
            throw VulkanException("Failed to create Vulkan device")
        }

        // 3. Create render pass
        vkRenderPass = nativeCreateRenderPass(vkDevice)
        if (!validateHandle(vkRenderPass, "RenderPass")) {
            cleanup()
            throw VulkanException("Failed to create render pass")
        }

        // 4. Create swapchain
        vkSwapchain = nativeCreateSwapchain(vkDevice, outputSurface)
        if (!validateHandle(vkSwapchain, "Swapchain")) {
            cleanup()
            throw VulkanException("Failed to create swapchain")
        }

        // 5. Create framebuffers
        if (!nativeCreateFramebuffers(vkDevice, vkSwapchain, vkRenderPass)) {
            cleanup()
            throw VulkanException("Failed to create framebuffers")
        }
        Log.d(TAG, "✓ Framebuffers created")

        // 6. Create command pool
        vkCommandPool = nativeCreateCommandPool(vkDevice)
        if (!validateHandle(vkCommandPool, "CommandPool")) {
            cleanup()
            throw VulkanException("Failed to create command pool")
        }

        // 7. Allocate command buffers
        val imageCount = nativeGetSwapchainImageCount(vkSwapchain)
        vkCommandBuffers = LongArray(imageCount)
        if (!nativeAllocateCommandBuffers(vkDevice, vkCommandPool, imageCount, vkCommandBuffers)) {
            cleanup()
            throw VulkanException("Failed to allocate command buffers")
        }
        Log.d(TAG, "✓ Allocated $imageCount command buffers")

        // 8. Create synchronization objects
        imageAvailableSemaphores = LongArray(MAX_FRAMES_IN_FLIGHT)
        renderFinishedSemaphores = LongArray(MAX_FRAMES_IN_FLIGHT)
        inFlightFences = LongArray(MAX_FRAMES_IN_FLIGHT)

        if (!nativeCreateSyncObjects(
                vkDevice,
                MAX_FRAMES_IN_FLIGHT,
                imageAvailableSemaphores,
                renderFinishedSemaphores,
                inFlightFences
            )) {
            cleanup()
            throw VulkanException("Failed to create sync objects")
        }
        Log.d(TAG, "✓ Sync objects created")

        // 9. Create input texture
        inputTexture = nativeCreateInputTexture(vkDevice, inputSize.width, inputSize.height)
        if (!validateHandle(inputTexture, "InputTexture")) {
            cleanup()
            throw VulkanException("Failed to create input texture")
        }

        // 10. Create input Surface (可能返回 null)
        inputSurface = nativeCreateSurfaceFromTexture(inputTexture)

        // 🔥 修改：不检查 inputSurface 是否为 null，允许它为 null
        if (inputSurface == null) {
            Log.i(TAG, "No input surface created - using direct texture rendering")
        } else {
            Log.i(TAG, "Input surface created successfully")
        }

        // 11. Initialize filter
        try {
            filter.init(vkDevice, vkRenderPass)
        } catch (e: Exception) {
            cleanup()
            throw VulkanException("Failed to initialize filter", e)
        }

        // 12. Set up frame callback (如果需要)
        if (inputSurface != null) {
            Log.i(TAG, "Setting up ImageReader listener")
            setupImageReaderListener()
        } else {
            Log.i(TAG, "Starting timer-based render loop")
            startTimerRenderLoop(outputSize)
        }

        isInitialized.set(true)
        Log.i(TAG, "=== Vulkan Runner initialized successfully ===")

        // 🔥 允许返回 null
        return inputSurface
    }

    private external fun nativeGetImageReader(textureHandle: Long): Any?

    private fun setupImageReaderListener() {
        // 从 Native 获取 ImageReader 对象 (假设 Native 返回的是 jobject)
        val imageReaderObj = nativeGetImageReader(inputTexture)
        if (imageReaderObj is ImageReader) {
            this.imageReader = imageReaderObj
            // 确保 handler 不为空
            handler?.let { h ->
                imageReaderObj.setOnImageAvailableListener(imageReaderListener, h)
                Log.d(TAG, "✓ ImageReader listener attached")
            }
        } else {
            Log.w(TAG, "Failed to get valid ImageReader from native")
        }
    }

    private fun startTimerRenderLoop(outputSize: Size) {
        val loopRunnable = object : Runnable {
            override fun run() {
                if (!stopped && isInitialized.get()) {
                    // 这里可以加入颜色相位更新逻辑
                    render(outputSize)
                    handler?.postDelayed(this, 16)
                }
            }
        }
        handler?.post(loopRunnable)
    }
    /**
     * 🔥 处理新的图像帧
     */
    private fun handleImageAvailable(reader: ImageReader) {
        // use 扩展函数会自动调用 image.close()，即使发生异常
        reader.acquireLatestImage()?.use { image ->
            val planes = image.planes
            if (planes.isEmpty()) return

            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // 计算需要的数组大小 (RGBA = 4 bytes)
            val expectedSize = width * height * 4

            // 🔥 1. 缓冲区复用：只有当为空或大小改变时才重新分配
            var data = cachedReusableBuffer
            if (data == null || data.size != expectedSize) {
                data = ByteArray(expectedSize)
                cachedReusableBuffer = data
                Log.d(TAG, "Allocated new buffer of size: $expectedSize")
            }

            buffer.rewind()

            // 🔥 2. 高效拷贝策略
            val rowPadding = rowStride - pixelStride * width

            if (pixelStride == 4 && rowPadding == 0) {
                // 极速模式：没有 Padding，直接整块拷贝
                // 这是最理想的情况，速度最快
                buffer.get(data)
            } else {
                // 兼容模式：有 Padding 或 Stride 不对齐
                // 优化：使用逐行拷贝 (Row-by-Row copy)，而不是逐像素 (Pixel-by-Pixel)
                // 逐像素拷贝在 Java/Kotlin 层极其缓慢

                var offset = 0
                val rowLength = width * 4 // 目标每行字节数

                // 如果 pixelStride == 4 但有 rowPadding，我们可以逐行拷贝
                if (pixelStride == 4) {
                    for (y in 0 until height) {
                        buffer.get(data, offset, rowLength)
                        offset += rowLength
                        // 跳过 Padding
                        if (y < height - 1) { // 最后一行不需要跳过，防止 buffer overflow
                            buffer.position(buffer.position() + rowPadding)
                        }
                    }
                } else {
                    // 最慢的情况：pixelStride != 4 (极少见于 RGBA8888)，不得不逐像素
                    // 如果遇到这种情况，建议在 Native 层处理，或者这里依然保持你的逻辑，
                    // 但通常 ImageFormat.FLEX_RGBA_8888 配合 pixelStride=4 是主流。
                    // 此处保留原逻辑作为兜底，但极力推荐上述两种路径。
                    legacyPixelCopy(buffer, data, width, height, pixelStride, rowPadding)
                }
            }

            // 更新纹理并渲染
            // 注意：inputSize 可能为空，使用 image 的尺寸更安全
            val renderSize = Size(width, height)
            updateInputTexture(data)
            render(renderSize)
        }.runCatching {
            Log.e(TAG, "Error processing image frame")
        }
    }

    // 兜底的慢速拷贝（仅当 stride != 4 时使用）
    private fun legacyPixelCopy(buffer: ByteBuffer, data: ByteArray, w: Int, h: Int, pixelStride: Int, rowPadding: Int) {
        var offset = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                data[offset++] = buffer.get()
                data[offset++] = buffer.get()
                data[offset++] = buffer.get()
                data[offset++] = buffer.get()
                if (pixelStride > 4) {
                    buffer.position(buffer.position() + pixelStride - 4)
                }
            }
            if (rowPadding > 0) {
                buffer.position(buffer.position() + rowPadding)
            }
        }
    }
    /**
     * 使用纯色更新输入纹理
     * @param r 红色分量 (0-255)
     * @param g 绿色分量 (0-255)
     * @param b 蓝色分量 (0-255)
     * @param a 透明度分量 (0-255)，默认255
     */
    fun updateTextureColor(r: Int, g: Int, b: Int, a: Int = 255) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot update texture - not initialized")
            return
        }

        handler?.post {
            nativeUpdateInputTextureColor(vkDevice, inputTexture, r, g, b, a)
        }
    }
    /**
     * 更新输入纹理（使用字节数组）
     * @param data RGBA 格式的像素数据，大小必须匹配纹理尺寸
     */
    fun updateInputTexture(data: ByteArray) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot update texture - not initialized")
            return
        }

        handler?.post {
            nativeUpdateInputTexture(vkDevice, inputTexture, data)
        }
    }


    private fun validateHandle(handle: Long, resourceName: String): Boolean {
        return if (handle == 0L) {
            Log.e(TAG, "Failed to create $resourceName")
            false
        } else {
            Log.d(TAG, "✓ $resourceName created: $handle")
            true
        }
    }

    private fun render(outputSize: Size) {
        if (!isInitialized.get() || stopped) {
            return
        }

        try {
            // Wait for the previous frame to finish
            nativeWaitForFence(vkDevice, inFlightFences[currentFrame])

            // Acquire next image
            val result = nativeAcquireNextImageWithSemaphore(
                vkDevice,
                vkSwapchain,
                imageAvailableSemaphores[currentFrame]
            )

            val imageIndex = (result and 0xFFFFFFFF).toInt()
            val resultCode = (result shr 32).toInt()

            if (resultCode < 0) {
                Log.w(TAG, "Failed to acquire image: $resultCode")
                return
            }

            // Reset fence
            nativeResetFence(vkDevice, inFlightFences[currentFrame])

            // Record command buffer
            recordCommandBuffer(imageIndex, outputSize)

            // Submit command buffer
            nativeSubmitCommandBufferWithSync(
                vkDevice,
                vkCommandBuffers[imageIndex],
                imageAvailableSemaphores[currentFrame],
                renderFinishedSemaphores[currentFrame],
                inFlightFences[currentFrame]
            )

            // Present
            val timestamp = nativeGetTextureTimestamp(inputTexture)
            nativePresentImageWithSyncAndTimestamp(
                vkDevice,
                vkSwapchain,
                imageIndex,
                renderFinishedSemaphores[currentFrame],
                timestamp
            )

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT

        } catch (e: Exception) {
            Log.e(TAG, "Error rendering frame", e)
        }
    }

    private fun recordCommandBuffer(imageIndex: Int, outputSize: Size) {
        val commandBuffer = vkCommandBuffers[imageIndex]

        // Reset and begin command buffer
        nativeResetCommandBuffer(commandBuffer)
        nativeBeginCommandBuffer(commandBuffer)

        // Set viewport
        nativeSetViewport(commandBuffer, 0, 0, outputSize.width, outputSize.height)

        // Begin render pass
        nativeBeginRenderPass(commandBuffer, vkRenderPass, imageIndex, vkSwapchain)

        // Get texture image view
        val textureImageView = nativeGetTextureImageView(inputTexture)
        if (textureImageView == 0L) {
            Log.e(TAG, "Invalid texture image view!")
            nativeEndRenderPass(commandBuffer)
            nativeEndCommandBuffer(commandBuffer)
            return
        }

        // Get transform matrix
        val matrix: FloatArray = if (overrideTransformMatrix != null) {
            overrideTransformMatrix
        } else {
            nativeGetTextureTransformMatrix(inputTexture)
        }

        // Draw with filter
        filter.draw(commandBuffer, textureImageView, matrix)

        // End render pass and command buffer
        nativeEndRenderPass(commandBuffer)
        nativeEndCommandBuffer(commandBuffer)
    }

    fun stopAndRelease() {
        val sem = Semaphore(0)

        handler!!.post {
            stopped = true
            cleanup()
            sem.release()
        }

        try {
            sem.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanup() {
        if (!isInitialized.compareAndSet(true, false)) {
            return
        }

        Log.d(TAG, "Cleaning up Vulkan Runner resources")

        // Disable frame callback
        if (inputTexture != 0L) {
            nativeSetFrameCallback(inputTexture, null)
        }

        // Wait for device to be idle
        if (vkDevice != 0L) {
            nativeDeviceWaitIdle(vkDevice)
        }

        // Release filter
        try {
            filter.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing filter", e)
        }

        // Destroy synchronization objects
        if (inFlightFences.isNotEmpty()) {
            nativeDestroySyncObjects(
                vkDevice,
                imageAvailableSemaphores,
                renderFinishedSemaphores,
                inFlightFences
            )
            imageAvailableSemaphores = LongArray(0)
            renderFinishedSemaphores = LongArray(0)
            inFlightFences = LongArray(0)
        }

        // Destroy input surface and texture
        inputSurface?.release()
        inputSurface = null

        destroyResource(inputTexture, "InputTexture") {
            nativeDestroyTexture(vkDevice, it)
        }

        // Free command buffers
        if (vkCommandBuffers.isNotEmpty()) {
            nativeFreeCommandBuffers(vkDevice, vkCommandPool, vkCommandBuffers)
            vkCommandBuffers = LongArray(0)
        }

        // Destroy Vulkan resources
        destroyResource(vkCommandPool, "CommandPool") {
            nativeDestroyCommandPool(vkDevice, it)
        }
        destroyResource(vkSwapchain, "Swapchain") {
            nativeDestroySwapchain(vkDevice, it)
        }
        destroyResource(vkRenderPass, "RenderPass") {
            nativeDestroyRenderPass(vkDevice, it)
        }
        destroyResource(vkDevice, "Device") {
            nativeDestroyDevice(it)
        }
        destroyResource(vkInstance, "Instance") {
            nativeDestroyInstance(it)
        }
        // 🔥 清理 ImageReader
        imageReader?.let {
            try {
                it.setOnImageAvailableListener(null, null)
                it.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ImageReader", e)
            }
        }
        // Reset handles
        inputTexture = 0
        vkCommandPool = 0
        vkSwapchain = 0
        vkRenderPass = 0
        vkDevice = 0
        vkInstance = 0
        imageReader = null
        Log.d(TAG, "Cleanup completed")
    }

    private inline fun destroyResource(handle: Long, name: String, destroy: (Long) -> Unit) {
        if (handle != 0L) {
            try {
                destroy(handle)
                Log.d(TAG, "✓ Destroyed $name")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying $name", e)
            }
        }
    }
    // Native 方法声明
    private external fun nativeUpdateInputTexture(
        deviceHandle: Long,
        textureHandle: Long,
        data: ByteArray
    )

    private external fun nativeUpdateInputTextureColor(
        deviceHandle: Long,
        textureHandle: Long,
        r: Int,
        g: Int,
        b: Int,
        a: Int
    )
    // ========== Native Methods ==========

    private external fun nativeCreateInstance(): Long
    private external fun nativeCreateDevice(instance: Long, surface: Surface): Long
    private external fun nativeCreateRenderPass(device: Long): Long
    private external fun nativeCreateSwapchain(device: Long, surface: Surface): Long
    private external fun nativeCreateCommandPool(device: Long): Long
    private external fun nativeCreateFramebuffers(
        device: Long,
        swapchain: Long,
        renderPass: Long
    ): Boolean

    private external fun nativeGetSwapchainImageCount(swapchain: Long): Int
    private external fun nativeAllocateCommandBuffers(
        device: Long,
        commandPool: Long,
        count: Int,
        commandBuffers: LongArray
    ): Boolean

    private external fun nativeCreateSyncObjects(
        device: Long,
        count: Int,
        imageAvailableSemaphores: LongArray,
        renderFinishedSemaphores: LongArray,
        inFlightFences: LongArray
    ): Boolean

    private external fun nativeCreateInputTexture(
        device: Long,
        width: Int,
        height: Int
    ): Long

    private external fun nativeCreateSurfaceFromTexture(texture: Long): Surface?
    private external fun nativeSetFrameCallback(texture: Long, callback: (() -> Unit)?)
    private external fun nativeGetTextureImageView(texture: Long): Long
    private external fun nativeGetTextureTransformMatrix(texture: Long): FloatArray
    private external fun nativeGetTextureTimestamp(texture: Long): Long

    private external fun nativeWaitForFence(device: Long, fence: Long)
    private external fun nativeResetFence(device: Long, fence: Long)

    private external fun nativeAcquireNextImageWithSemaphore(
        device: Long,
        swapchain: Long,
        semaphore: Long
    ): Long

    private external fun nativeResetCommandBuffer(commandBuffer: Long)
    private external fun nativeBeginCommandBuffer(commandBuffer: Long)
    private external fun nativeSetViewport(
        commandBuffer: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    )
    private external fun nativeBeginRenderPass(
        commandBuffer: Long,
        renderPass: Long,
        imageIndex: Int,
        swapchain: Long
    )
    private external fun nativeEndRenderPass(commandBuffer: Long)
    private external fun nativeEndCommandBuffer(commandBuffer: Long)

    private external fun nativeSubmitCommandBufferWithSync(
        device: Long,
        commandBuffer: Long,
        waitSemaphore: Long,
        signalSemaphore: Long,
        fence: Long
    )

    private external fun nativePresentImageWithSyncAndTimestamp(
        device: Long,
        swapchain: Long,
        imageIndex: Int,
        waitSemaphore: Long,
        timestamp: Long
    )

    private external fun nativeDeviceWaitIdle(device: Long)

    private external fun nativeDestroySyncObjects(
        device: Long,
        imageAvailableSemaphores: LongArray,
        renderFinishedSemaphores: LongArray,
        inFlightFences: LongArray
    )
    private external fun nativeDestroyTexture(device: Long, texture: Long)
    private external fun nativeFreeCommandBuffers(
        device: Long,
        commandPool: Long,
        commandBuffers: LongArray
    )
    private external fun nativeDestroyCommandPool(device: Long, commandPool: Long)
    private external fun nativeDestroySwapchain(device: Long, swapchain: Long)
    private external fun nativeDestroyRenderPass(device: Long, renderPass: Long)
    private external fun nativeDestroyDevice(device: Long)
    private external fun nativeDestroyInstance(instance: Long)

    companion object {
        private const val TAG = "VulkanRunner"
        private const val MAX_FRAMES_IN_FLIGHT = 2

        private var handlerThread: HandlerThread? = null
        private var handler: Handler? = null
        private var quit = false

        @Synchronized
        fun initOnce() {
            if (handlerThread == null) {
                check(!quit) { "Could not init VulkanRunner after it is quit" }
                handlerThread = HandlerThread("VulkanRunner")
                handlerThread!!.start()
                handler = Handler(handlerThread!!.looper)
            }
        }

        fun quit() {
            val thread: HandlerThread?
            synchronized(VulkanRunner::class.java) {
                thread = handlerThread
                quit = true
            }
            thread?.quitSafely()
        }

        @Throws(InterruptedException::class)
        fun join() {
            val thread: HandlerThread?
            synchronized(VulkanRunner::class.java) {
                thread = handlerThread
            }
            thread?.join()
        }

        init {
//            System.loadLibrary("myapplication")
            NativeLibraryLoader.loadLibraryFromJar("myapplication")
        }
    }
}
