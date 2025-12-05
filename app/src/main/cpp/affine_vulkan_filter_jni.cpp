//
// Created by 31483 on 2025/12/3.
//
// affine_vulkan_filter_jni.cpp - UNIFIED VERSION

#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <cstring>
#include "Vulkantypes.h"

#define LOG_TAG "AffineVulkanFilter-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

// ==================== Descriptor Set Layout ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreateDescriptorSetLayout(
        JNIEnv* env, jobject /* this */, jlong deviceHandle) {

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);

    // Binding 0: Combined image sampler (fragment shader)
    VkDescriptorSetLayoutBinding samplerLayoutBinding{};
    samplerLayoutBinding.binding = 0;
    samplerLayoutBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerLayoutBinding.descriptorCount = 1;
    samplerLayoutBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    samplerLayoutBinding.pImmutableSamplers = nullptr;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &samplerLayoutBinding;

    VkDescriptorSetLayout descriptorSetLayout;
    VkResult result = vkCreateDescriptorSetLayout(
            deviceInfo->device,
            &layoutInfo,
            nullptr,
            &descriptorSetLayout
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout: %d", result);
        return 0;
    }

    LOGI("✓ Descriptor set layout created: %p", (void*)descriptorSetLayout);
    return reinterpret_cast<jlong>(descriptorSetLayout);
}

// ==================== Pipeline Layout ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreatePipelineLayout(
        JNIEnv* env, jobject /* this */,
        jlong deviceHandle,
        jlong descriptorSetLayoutHandle) {

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDescriptorSetLayout descriptorSetLayout =
            reinterpret_cast<VkDescriptorSetLayout>(descriptorSetLayoutHandle);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout;

    // Push Constants: 2 个 4x4 矩阵
    // - mat4 tex_matrix   (64 bytes)
    // - mat4 user_matrix  (64 bytes)
    // 总计：128 bytes
    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;  // 顶点着色器使用
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(float) * 32;  // 2 个 4x4 矩阵 = 32 floats
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;

    VkPipelineLayout pipelineLayout;
    VkResult result = vkCreatePipelineLayout(
            deviceInfo->device,
            &pipelineLayoutInfo,
            nullptr,
            &pipelineLayout
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout: %d", result);
        return 0;
    }

    LOGI("✓ Pipeline layout created with 2x mat4 push constants (128 bytes)");
    return reinterpret_cast<jlong>(pipelineLayout);
}

// ==================== Shader Module ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreateShaderModule(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jbyteArray codeArray) {

    LOGI("=== nativeCreateShaderModule START ===");

    if (deviceHandle == 0) {
        LOGE("Device handle is null!");
        return 0;
    }

    if (codeArray == nullptr) {
        LOGE("Code array is null!");
        return 0;
    }

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;

    jsize codeSize = env->GetArrayLength(codeArray);
    LOGI("Shader code size: %d bytes", codeSize);

    if (codeSize % 4 != 0) {
        LOGE("Shader code size must be multiple of 4, got %d", codeSize);
        return 0;
    }

    if (codeSize < 16) {
        LOGE("Shader code too small: %d bytes", codeSize);
        return 0;
    }

    // 关键修复：复制到对齐的缓冲区
    std::vector<uint32_t> alignedCode(codeSize / 4);

    jbyte* code = env->GetByteArrayElements(codeArray, nullptr);
    if (code == nullptr) {
        LOGE("Failed to get byte array elements");
        return 0;
    }

    // 复制到对齐的缓冲区
    std::memcpy(alignedCode.data(), code, codeSize);

    // 立即释放原始数组
    env->ReleaseByteArrayElements(codeArray, code, JNI_ABORT);

    // 验证 SPIR-V magic number
    if (alignedCode[0] != 0x07230203) {
        LOGE("Invalid SPIR-V magic: 0x%08x (expected 0x07230203)", alignedCode[0]);
        return 0;
    }
    LOGI("SPIR-V magic verified: 0x%08x", alignedCode[0]);

    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = codeSize;
    createInfo.pCode = alignedCode.data();  // 使用对齐的指针

    VkShaderModule shaderModule;
    VkResult result = vkCreateShaderModule(device, &createInfo, nullptr, &shaderModule);

    if (result != VK_SUCCESS) {
        LOGE("vkCreateShaderModule failed: %d", result);
        return 0;
    }

    LOGI("Shader module created successfully: %p", shaderModule);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(shaderModule));
}

// ==================== Graphics Pipeline ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreateGraphicsPipeline(
        JNIEnv* env,
        jobject thiz,
        jlong deviceHandle,
        jlong renderPassHandle,
        jlong pipelineLayoutHandle,
        jlong vertShaderModuleHandle,
        jlong fragShaderModuleHandle
) {
    LOGI("=== Creating Graphics Pipeline ===");

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkRenderPass renderPass = reinterpret_cast<VkRenderPass>(renderPassHandle);
    VkPipelineLayout pipelineLayout = reinterpret_cast<VkPipelineLayout>(pipelineLayoutHandle);
    VkShaderModule vertShaderModule = reinterpret_cast<VkShaderModule>(vertShaderModuleHandle);
    VkShaderModule fragShaderModule = reinterpret_cast<VkShaderModule>(fragShaderModuleHandle);

    // Shader stages
    VkPipelineShaderStageCreateInfo vertShaderStageInfo{};
    vertShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    vertShaderStageInfo.stage = VK_SHADER_STAGE_VERTEX_BIT;
    vertShaderStageInfo.module = vertShaderModule;
    vertShaderStageInfo.pName = "main";

    VkPipelineShaderStageCreateInfo fragShaderStageInfo{};
    fragShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    fragShaderStageInfo.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    fragShaderStageInfo.module = fragShaderModule;
    fragShaderStageInfo.pName = "main";

    VkPipelineShaderStageCreateInfo shaderStages[] = {
            vertShaderStageInfo,
            fragShaderStageInfo
    };

    // Vertex input - 空的（顶点在shader中生成）
    VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
    vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInputInfo.vertexBindingDescriptionCount = 0;
    vertexInputInfo.pVertexBindingDescriptions = nullptr;
    vertexInputInfo.vertexAttributeDescriptionCount = 0;
    vertexInputInfo.pVertexAttributeDescriptions = nullptr;

    // Input assembly
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    inputAssembly.primitiveRestartEnable = VK_FALSE;

    // Viewport state - 使用动态状态
    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;
    // pViewports 和 pScissors 留空（动态设置）

    // Rasterization
    VkPipelineRasterizationStateCreateInfo rasterizer{};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.depthClampEnable = VK_FALSE;
    rasterizer.rasterizerDiscardEnable = VK_FALSE;  // 重要！必须 FALSE
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;  // 先禁用背面剔除测试
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterizer.depthBiasEnable = VK_FALSE;

    LOGI("Rasterizer: discard=%d, cull=%d, fill=%d",
         rasterizer.rasterizerDiscardEnable,
         rasterizer.cullMode,
         rasterizer.polygonMode);

    // Multisampling
    VkPipelineMultisampleStateCreateInfo multisampling{};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.sampleShadingEnable = VK_FALSE;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // Depth/Stencil - 禁用
    VkPipelineDepthStencilStateCreateInfo depthStencil{};
    depthStencil.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depthStencil.depthTestEnable = VK_FALSE;
    depthStencil.depthWriteEnable = VK_FALSE;
    depthStencil.stencilTestEnable = VK_FALSE;

    // Color blending
    VkPipelineColorBlendAttachmentState colorBlendAttachment{};
    colorBlendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                                          VK_COLOR_COMPONENT_G_BIT |
                                          VK_COLOR_COMPONENT_B_BIT |
                                          VK_COLOR_COMPONENT_A_BIT;
    colorBlendAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlending{};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.logicOpEnable = VK_FALSE;
    colorBlending.logicOp = VK_LOGIC_OP_COPY;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorBlendAttachment;
    colorBlending.blendConstants[0] = 0.0f;
    colorBlending.blendConstants[1] = 0.0f;
    colorBlending.blendConstants[2] = 0.0f;
    colorBlending.blendConstants[3] = 0.0f;

    LOGI("Color blend: enabled=%d, writeMask=0x%x",
         colorBlendAttachment.blendEnable,
         colorBlendAttachment.colorWriteMask);

    // Dynamic states
    VkDynamicState dynamicStates[] = {
            VK_DYNAMIC_STATE_VIEWPORT,
            VK_DYNAMIC_STATE_SCISSOR
    };

    VkPipelineDynamicStateCreateInfo dynamicState{};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamicStates;

    // Create pipeline
    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = shaderStages;
    pipelineInfo.pVertexInputState = &vertexInputInfo;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pDepthStencilState = &depthStencil;  // 重要！不要忘记
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = pipelineLayout;
    pipelineInfo.renderPass = renderPass;
    pipelineInfo.subpass = 0;
    pipelineInfo.basePipelineHandle = VK_NULL_HANDLE;

    VkPipeline graphicsPipeline;
    VkResult result = vkCreateGraphicsPipelines(
            device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            &graphicsPipeline
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create graphics pipeline: %d", result);
        return 0;
    }

    LOGI("✓ Graphics pipeline created: %p", (void*)graphicsPipeline);
    return reinterpret_cast<jlong>(graphicsPipeline);
}

// ==================== Descriptor Pool ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreateDescriptorPool(
        JNIEnv* env,
        jobject thiz,
        jlong deviceHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;

    VkDescriptorPoolSize poolSize = {};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    VkDescriptorPool descriptorPool;
    VkResult result = vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool);

    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool: VkResult=%d", result);
        return 0;
    }

    LOGD("✓ Descriptor pool created: %p", (void*)descriptorPool);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(descriptorPool));
}

// ==================== Sampler ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeCreateSampler(
        JNIEnv* env,
        jobject thiz,
        jlong deviceHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;

    VkSamplerCreateInfo samplerInfo = {};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.anisotropyEnable = VK_FALSE;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.borderColor = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    samplerInfo.compareEnable = VK_FALSE;
    samplerInfo.compareOp = VK_COMPARE_OP_ALWAYS;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.mipLodBias = 0.0f;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;

    VkSampler sampler;
    VkResult result = vkCreateSampler(device, &samplerInfo, nullptr, &sampler);

    if (result != VK_SUCCESS) {
        LOGE("Failed to create sampler: VkResult=%d", result);
        return 0;
    }

    LOGD("✓ Sampler created: %p", (void*)sampler);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(sampler));
}

// ==================== Allocate Descriptor Set ====================

JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeAllocateDescriptorSet(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong descriptorPoolHandle,
        jlong descriptorSetLayoutHandle) {

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkDescriptorPool descriptorPool = reinterpret_cast<VkDescriptorPool>(static_cast<uintptr_t>(descriptorPoolHandle));
    VkDescriptorSetLayout descriptorSetLayout = reinterpret_cast<VkDescriptorSetLayout>(static_cast<uintptr_t>(descriptorSetLayoutHandle));

    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout;

    VkDescriptorSet descriptorSet;
    VkResult result = vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet);

    if (result != VK_SUCCESS) {
        return 0;
    }

    return static_cast<jlong>(reinterpret_cast<uintptr_t>(descriptorSet));
}

// ==================== Update Descriptor Set ====================

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeUpdateDescriptorSet(
        JNIEnv* env, jobject /* this */,
        jlong deviceHandle,
        jlong descriptorSetHandle,
        jlong imageViewHandle,
        jlong samplerHandle) {

    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDescriptorSet descriptorSet = reinterpret_cast<VkDescriptorSet>(descriptorSetHandle);
    VkImageView imageView = reinterpret_cast<VkImageView>(imageViewHandle);
    VkSampler sampler = reinterpret_cast<VkSampler>(samplerHandle);

    LOGI("=== Updating Descriptor Set ===");
    LOGI("Device: %p", (void*)deviceInfo->device);
    LOGI("DescriptorSet: %p", (void*)descriptorSet);
    LOGI("ImageView: %p", (void*)imageView);
    LOGI("Sampler: %p", (void*)sampler);

    if (descriptorSet == VK_NULL_HANDLE) {
        LOGE("Invalid descriptor set!");
        return;
    }

    if (imageView == VK_NULL_HANDLE) {
        LOGE("Invalid image view!");
        return;
    }

    if (sampler == VK_NULL_HANDLE) {
        LOGE("Invalid sampler!");
        return;
    }

    // 配置图像描述符
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageInfo.imageView = imageView;
    imageInfo.sampler = sampler;

    LOGI("ImageInfo: layout=%d, view=%p, sampler=%p",
         imageInfo.imageLayout, (void*)imageInfo.imageView, (void*)imageInfo.sampler);

    VkWriteDescriptorSet descriptorWrite{};
    descriptorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrite.dstSet = descriptorSet;
    descriptorWrite.dstBinding = 0;  // binding = 0 in shader
    descriptorWrite.dstArrayElement = 0;
    descriptorWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptorWrite.descriptorCount = 1;
    descriptorWrite.pImageInfo = &imageInfo;

    vkUpdateDescriptorSets(deviceInfo->device, 1, &descriptorWrite, 0, nullptr);

    LOGI("✓ Descriptor set updated successfully");
}

// ==================== Command Buffer Operations ====================

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeBindPipeline(
        JNIEnv* env,
        jobject thiz,
        jlong commandBufferHandle,
        jlong pipelineHandle
) {
    VkCommandBuffer commandBuffer = reinterpret_cast<VkCommandBuffer>(commandBufferHandle);
    VkPipeline pipeline = reinterpret_cast<VkPipeline>(pipelineHandle);

    LOGI("Binding pipeline: cmd=%p, pipeline=%p", (void*)commandBuffer, (void*)pipeline);

    if (commandBuffer == VK_NULL_HANDLE) {
        LOGE("Invalid command buffer!");
        return;
    }

    if (pipeline == VK_NULL_HANDLE) {
        LOGE("Invalid pipeline!");
        return;
    }

    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    LOGI("✓ Pipeline bound successfully");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeBindDescriptorSets(
        JNIEnv* env, jobject /* this */,
        jlong commandBufferHandle,
        jlong pipelineLayoutHandle,
        jlong descriptorSetHandle) {

    VkCommandBuffer commandBuffer = reinterpret_cast<VkCommandBuffer>(commandBufferHandle);
    VkPipelineLayout pipelineLayout = reinterpret_cast<VkPipelineLayout>(pipelineLayoutHandle);
    VkDescriptorSet descriptorSet = reinterpret_cast<VkDescriptorSet>(descriptorSetHandle);

    LOGI("Binding descriptor sets:");
    LOGI("  CommandBuffer: %p", (void*)commandBuffer);
    LOGI("  PipelineLayout: %p", (void*)pipelineLayout);
    LOGI("  DescriptorSet: %p", (void*)descriptorSet);

    if (commandBuffer == VK_NULL_HANDLE) {
        LOGE("Invalid command buffer!");
        return;
    }

    if (pipelineLayout == VK_NULL_HANDLE) {
        LOGE("Invalid pipeline layout!");
        return;
    }

    if (descriptorSet == VK_NULL_HANDLE) {
        LOGE("Invalid descriptor set!");
        return;
    }

    vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelineLayout,
            0,  // firstSet
            1,  // descriptorSetCount
            &descriptorSet,
            0,  // dynamicOffsetCount
            nullptr
    );

    LOGI("✓ Descriptor sets bound successfully");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativePushConstants(
        JNIEnv* env, jobject thiz,
        jlong commandBufferHandle,
        jlong pipelineLayoutHandle,
        jfloatArray dataArray) {

    VkCommandBuffer commandBuffer = reinterpret_cast<VkCommandBuffer>(
            static_cast<uintptr_t>(commandBufferHandle));
    VkPipelineLayout pipelineLayout = reinterpret_cast<VkPipelineLayout>(static_cast<uintptr_t>(pipelineLayoutHandle));

    if (!commandBuffer || !pipelineLayout) {
        LOGE("Invalid handles: commandBuffer=%p, pipelineLayout=%p",
             commandBuffer, pipelineLayout);
        return;
    }

    if (!dataArray) {
        LOGE("dataArray is null");
        return;
    }

    jsize dataSize = env->GetArrayLength(dataArray);
    if (dataSize != 32) {  // 2 个 4x4 矩阵 = 32 floats
        LOGE("Invalid data size: %d (expected 32 for 2x mat4)", dataSize);
        return;
    }

    jfloat* data = env->GetFloatArrayElements(dataArray, nullptr);
    if (!data) {
        LOGE("Failed to get float array elements");
        return;
    }

    // 推送矩阵数据到 GPU (顶点着色器)
    vkCmdPushConstants(
            commandBuffer,
            pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,  // 关键：顶点着色器使用矩阵
            0,
            dataSize * sizeof(float),  // 128 bytes
            data
    );

    // 添加详细日志（可选，用于调试）
    static int logCounter = 0;
    if (logCounter++ % 300 == 0) {  // 每300帧打印一次
        LOGI("Pushed 2x mat4 constants:");
        LOGI("  tex_matrix[0]: [%.2f, %.2f, %.2f, %.2f]",
             data[0], data[1], data[2], data[3]);
        LOGI("  user_matrix[0]: [%.2f, %.2f, %.2f, %.2f]",
             data[16], data[17], data[18], data[19]);
    }

    env->ReleaseFloatArrayElements(dataArray, data, JNI_ABORT);  // 使用JNI_ABORT因为只读
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDraw(
        JNIEnv* env,
        jobject thiz,
        jlong commandBufferHandle,
        jint vertexCount,
        jint instanceCount,
        jint firstVertex,
        jint firstInstance
) {
    VkCommandBuffer commandBuffer = reinterpret_cast<VkCommandBuffer>(commandBufferHandle);

//    LOGI("Drawing: cmd=%p, vertices=%d, instances=%d",
//         (void*)commandBuffer, vertexCount, instanceCount);

    if (commandBuffer == VK_NULL_HANDLE) {
        LOGE("Invalid command buffer in draw!");
        return;
    }

    vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
//    LOGI("✓ Draw command recorded");
}

// ==================== Destruction Functions ====================

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroyDescriptorPool(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong descriptorPoolHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkDescriptorPool descriptorPool = reinterpret_cast<VkDescriptorPool>(static_cast<uintptr_t>(descriptorPoolHandle));
    vkDestroyDescriptorPool(device, descriptorPool, nullptr);
    LOGD("Descriptor pool destroyed");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroySampler(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong samplerHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkSampler sampler = reinterpret_cast<VkSampler>(static_cast<uintptr_t>(samplerHandle));
    vkDestroySampler(device, sampler, nullptr);
    LOGD("Sampler destroyed");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroyPipeline(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong pipelineHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkPipeline pipeline = reinterpret_cast<VkPipeline>(static_cast<uintptr_t>(pipelineHandle));
    vkDestroyPipeline(device, pipeline, nullptr);
    LOGD("Pipeline destroyed");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroyPipelineLayout(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong pipelineLayoutHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkPipelineLayout pipelineLayout = reinterpret_cast<VkPipelineLayout>(static_cast<uintptr_t>(pipelineLayoutHandle));
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    LOGD("Pipeline layout destroyed");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroyDescriptorSetLayout(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong descriptorSetLayoutHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkDescriptorSetLayout descriptorSetLayout = reinterpret_cast<VkDescriptorSetLayout>(static_cast<uintptr_t>(descriptorSetLayoutHandle));
    vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);
    LOGD("Descriptor set layout destroyed");
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_vulkan_AffineVulkanFilter_nativeDestroyShaderModule(
        JNIEnv* env, jobject thiz, jlong deviceHandle, jlong shaderModuleHandle
) {
    DeviceInfo* deviceInfo = reinterpret_cast<DeviceInfo*>(deviceHandle);
    VkDevice device = deviceInfo->device;
    VkShaderModule shaderModule = reinterpret_cast<VkShaderModule>(static_cast<uintptr_t>(shaderModuleHandle));
    vkDestroyShaderModule(device, shaderModule, nullptr);
    LOGD("Shader module destroyed");
}

} // extern "C"