package com.genymobile.scrcpy.vulkan

import VulkanException
import android.content.Context
import android.util.Log
import com.genymobile.scrcpy.util.AffineMatrix
import java.io.IOException
import kotlin.jvm.Throws

object AffineShaderLoader {
    private const val VERTEX_SHADER_PATH = "assets/shaders/affine_vert.spv"
    private const val FRAGMENT_SHADER_PATH = "assets/shaders/affine_frag.spv"
    @Throws(IOException::class)
    fun loadVertexShader(): ByteArray {
        // 调用 NativeLibraryLoader 的通用读取方法
        return NativeLibraryLoader.loadJarResource(VERTEX_SHADER_PATH)
    }

    @Throws(IOException::class)
    fun loadFragmentShader(): ByteArray {
        return NativeLibraryLoader.loadJarResource(FRAGMENT_SHADER_PATH)
    }
}

/**
 * Vulkan 仿射变换滤镜
 *
 * 功能：
 * - 旋转（任意角度）
 * - 缩放（X/Y独立）
 * - 平移
 * - 裁剪（Reframing）
 * - 水平/垂直翻转
 * - 组合变换
 *
 * 使用示例：
 * ```
 * // 旋转90度
 * val transform = AffineMatrix.rotateOrtho(1).fromCenter()
 * val filter = AffineVulkanFilter(context, transform)
 *
 * // 缩放并居中
 * val transform = AffineMatrix.scale(0.5, 0.5).fromCenter()
 * val filter = AffineVulkanFilter(context, transform)
 *
 * // 裁剪区域
 * val transform = AffineMatrix.reframe(0.25, 0.25, 0.5, 0.5)
 * val filter = AffineVulkanFilter(context, transform)
 * ```
 */
class AffineVulkanFilter(
    private val context: Context,
    private val userTransform: AffineMatrix = AffineMatrix.IDENTITY
) : VulkanFilter {

    private var vkDevice: Long = 0
    private var vkPipeline: Long = 0
    private var vkPipelineLayout: Long = 0
    private var vkDescriptorSetLayout: Long = 0
    private var vkDescriptorPool: Long = 0
    private var vkDescriptorSet: Long = 0
    private var vkSampler: Long = 0

    private var vertexShaderModule: Long = 0
    private var fragmentShaderModule: Long = 0

    private var isInitialized = false
    private var currentTextureView: Long = 0

    // 添加：表面尺寸（用于裁剪计算，可选）
    private var surfaceWidth: Int = 1920
    private var surfaceHeight: Int = 1080

    override fun init(device: Long, renderPass: Long) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        this.vkDevice = device
        Log.d(TAG, "=== Initializing AffineVulkanFilter ===")

        try {
            // 1. Load shaders
            val vertexShaderCode = AffineShaderLoader.loadVertexShader()
            val fragmentShaderCode = AffineShaderLoader.loadFragmentShader()
            Log.d(TAG, "✓ Shaders loaded: vert=${vertexShaderCode.size} bytes, frag=${fragmentShaderCode.size} bytes")

            // 2. Create shader modules
            vertexShaderModule = nativeCreateShaderModule(device, vertexShaderCode)
            fragmentShaderModule = nativeCreateShaderModule(device, fragmentShaderCode)

            if (vertexShaderModule == 0L || fragmentShaderModule == 0L) {
                throw VulkanException("Failed to create shader modules")
            }
            Log.d(TAG, "✓ Shader modules created")

            // 3. Create descriptor set layout
            vkDescriptorSetLayout = nativeCreateDescriptorSetLayout(device)
            if (vkDescriptorSetLayout == 0L) {
                throw VulkanException("Failed to create descriptor set layout")
            }
            Log.d(TAG, "✓ Descriptor set layout created")

            // 4. Create pipeline layout (包含 2 个 4x4 矩阵的 Push Constants)
            vkPipelineLayout = nativeCreatePipelineLayout(device, vkDescriptorSetLayout)
            if (vkPipelineLayout == 0L) {
                throw VulkanException("Failed to create pipeline layout")
            }
            Log.d(TAG, "✓ Pipeline layout created with Push Constants support (2x mat4)")

            // 5. Create graphics pipeline
            vkPipeline = nativeCreateGraphicsPipeline(
                device,
                renderPass,
                vkPipelineLayout,
                vertexShaderModule,
                fragmentShaderModule
            )
            if (vkPipeline == 0L) {
                throw VulkanException("Failed to create graphics pipeline")
            }
            Log.d(TAG, "✓ Graphics pipeline created")

            // 6. Create descriptor pool
            vkDescriptorPool = nativeCreateDescriptorPool(device)
            if (vkDescriptorPool == 0L) {
                throw VulkanException("Failed to create descriptor pool")
            }
            Log.d(TAG, "✓ Descriptor pool created")

            // 7. Create sampler
            vkSampler = nativeCreateSampler(device)
            if (vkSampler == 0L) {
                throw VulkanException("Failed to create sampler")
            }
            Log.d(TAG, "✓ Sampler created")

            // 8. Allocate descriptor set
            vkDescriptorSet = nativeAllocateDescriptorSet(
                vkDevice,
                vkDescriptorPool,
                vkDescriptorSetLayout
            )

            if (vkDescriptorSet == 0L) {
                throw VulkanException("Failed to allocate descriptor set")
            }
            Log.d(TAG, "✓ Descriptor set allocated")

            isInitialized = true
            Log.i(TAG, "=== AffineVulkanFilter initialized successfully ===")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize filter", e)
            release()
            throw e
        }
    }

    // 添加：设置表面尺寸
    fun setSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.d(TAG, "Surface size set: ${width}x${height}")
    }

    override fun draw(commandBuffer: Long, inputTexture: Long, transformMatrix: FloatArray) {
        if (!isInitialized) {
            Log.e(TAG, "Filter not initialized!")
            return
        }

        if (inputTexture == 0L) {
            Log.e(TAG, "Invalid input texture: 0")
            return
        }

        // Update descriptor set if texture changed
        if (currentTextureView != inputTexture) {
            Log.d(TAG, "Updating descriptor set with texture: $inputTexture")
            nativeUpdateDescriptorSet(vkDevice, vkDescriptorSet, inputTexture, vkSampler)
            currentTextureView = inputTexture
        }

        // Bind pipeline
        nativeBindPipeline(commandBuffer, vkPipeline)

        // Bind descriptor sets
        nativeBindDescriptorSets(commandBuffer, vkPipelineLayout, vkDescriptorSet)

        // 准备 Push Constants 数据
        // Push Constants 布局：
        // - mat4 tex_matrix   (16 floats, 64 bytes) - 来自 MediaCodec/Android
        // - mat4 user_matrix  (16 floats, 64 bytes) - 用户定义的仿射变换
        // 总共：32 floats, 128 bytes

        val pushConstantsData = FloatArray(32) // 2 个 4x4 矩阵

        // 第一个矩阵：tex_matrix (来自 transformMatrix 参数)
        // 这是 Android/MediaCodec 提供的纹理变换矩阵
        if (transformMatrix.size >= 16) {
            System.arraycopy(transformMatrix, 0, pushConstantsData, 0, 16)
        } else {
            // 如果没有提供，使用单位矩阵
            pushConstantsData[0] = 1f
            pushConstantsData[5] = 1f
            pushConstantsData[10] = 1f
            pushConstantsData[15] = 1f
        }

        // 第二个矩阵：user_matrix (用户定义的仿射变换)
        // 使用 AffineMatrix 内置的 to4x4() 方法
        val userMatrix = userTransform.to4x4()
        System.arraycopy(userMatrix, 0, pushConstantsData, 16, 16)

        // 推送矩阵数据到 GPU
        nativePushConstants(commandBuffer, vkPipelineLayout, pushConstantsData)

        // Draw fullscreen triangle (3 vertices)
        nativeDraw(commandBuffer, 3, 1, 0, 0)

        frameCount++
        if (frameCount % 60 == 0) {
            Log.v(TAG, "Rendered frame $frameCount with transform: $userTransform")
        }
    }

    override fun release() {
        if (!isInitialized) return

        Log.d(TAG, "Releasing filter resources")

        if (vkDescriptorPool != 0L) {
            nativeDestroyDescriptorPool(vkDevice, vkDescriptorPool)
            vkDescriptorPool = 0L
        }
        if (vkSampler != 0L) {
            nativeDestroySampler(vkDevice, vkSampler)
            vkSampler = 0L
        }
        if (vkPipeline != 0L) {
            nativeDestroyPipeline(vkDevice, vkPipeline)
            vkPipeline = 0L
        }
        if (vkPipelineLayout != 0L) {
            nativeDestroyPipelineLayout(vkDevice, vkPipelineLayout)
            vkPipelineLayout = 0L
        }
        if (vkDescriptorSetLayout != 0L) {
            nativeDestroyDescriptorSetLayout(vkDevice, vkDescriptorSetLayout)
            vkDescriptorSetLayout = 0L
        }
        if (vertexShaderModule != 0L) {
            nativeDestroyShaderModule(vkDevice, vertexShaderModule)
            vertexShaderModule = 0L
        }
        if (fragmentShaderModule != 0L) {
            nativeDestroyShaderModule(vkDevice, fragmentShaderModule)
            fragmentShaderModule = 0L
        }

        isInitialized = false
        currentTextureView = 0
        Log.d(TAG, "Filter resources released")
    }

    // ==================== Native JNI Methods ====================

    private external fun nativeCreateDescriptorSetLayout(device: Long): Long
    private external fun nativeCreateGraphicsPipeline(
        device: Long,
        renderPass: Long,
        pipelineLayout: Long,
        vertShaderModule: Long,
        fragShaderModule: Long
    ): Long
    private external fun nativeCreateDescriptorPool(device: Long): Long
    private external fun nativeCreateSampler(device: Long): Long
    private external fun nativeCreateShaderModule(device: Long, code: ByteArray): Long
    private external fun nativeAllocateDescriptorSet(
        device: Long,
        descriptorPool: Long,
        descriptorSetLayout: Long
    ): Long
    private external fun nativeUpdateDescriptorSet(
        device: Long,
        descriptorSet: Long,
        imageView: Long,
        sampler: Long
    )
    private external fun nativeCreatePipelineLayout(device: Long, descriptorSetLayout: Long): Long
    private external fun nativeBindPipeline(commandBuffer: Long, pipeline: Long)
    private external fun nativeBindDescriptorSets(
        commandBuffer: Long,
        pipelineLayout: Long,
        descriptorSet: Long
    )
    private external fun nativePushConstants(
        commandBuffer: Long,
        pipelineLayout: Long,
        data: FloatArray
    )
    private external fun nativeDraw(
        commandBuffer: Long,
        vertexCount: Int,
        instanceCount: Int,
        firstVertex: Int,
        firstInstance: Int
    )
    private external fun nativeDestroyDescriptorPool(device: Long, descriptorPool: Long)
    private external fun nativeDestroySampler(device: Long, sampler: Long)
    private external fun nativeDestroyPipeline(device: Long, pipeline: Long)
    private external fun nativeDestroyPipelineLayout(device: Long, pipelineLayout: Long)
    private external fun nativeDestroyDescriptorSetLayout(device: Long, descriptorSetLayout: Long)
    private external fun nativeDestroyShaderModule(device: Long, shaderModule: Long)

    companion object {
        private const val TAG = "AffineVulkanFilter"
        private var frameCount = 0

        init {
            NativeLibraryLoader.loadLibraryFromJar("myapplication")
        }
    }
}