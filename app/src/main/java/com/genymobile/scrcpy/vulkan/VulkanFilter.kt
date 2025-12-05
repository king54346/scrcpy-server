package com.genymobile.scrcpy.vulkan

// Filter interface for Vulkan
interface VulkanFilter {
    fun init(device: Long, renderPass: Long)
    fun draw(commandBuffer: Long, inputTexture: Long, transformMatrix: FloatArray)
    fun release()
}