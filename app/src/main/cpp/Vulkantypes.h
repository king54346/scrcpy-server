#ifndef VULKAN_TYPES_H
#define VULKAN_TYPES_H

#include <vulkan/vulkan.h>
#include <vector>

// 交换链信息
struct SwapchainInfo {
    VkSwapchainKHR swapchain;
    std::vector<VkImage> images;
    std::vector<VkImageView> imageViews;
    std::vector<VkFramebuffer> framebuffers;
    VkSurfaceFormatKHR format;
    VkExtent2D extent;
};

// 设备信息
struct DeviceInfo {
    VkDevice device;
    VkPhysicalDevice physicalDevice;
    VkQueue graphicsQueue;
    VkQueue presentQueue;
    uint32_t graphicsQueueFamily;
    uint32_t presentQueueFamily;
    VkSurfaceKHR surface;
};

// 纹理信息
struct TextureInfo {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView imageView;
    VkSampler sampler;
    uint32_t width;
    uint32_t height;
};

#endif // VULKAN_TYPES_H