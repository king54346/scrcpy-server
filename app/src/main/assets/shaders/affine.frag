#version 450

// 输入：从顶点着色器传来的纹理坐标
layout(location = 0) in vec2 fragTexCoord;

// 输出：最终颜色
layout(location = 0) out vec4 outColor;

// 采样器：绑定到描述符集
layout(set = 0, binding = 0) uniform sampler2D texSampler;

void main() {
    // 检查纹理坐标是否在有效范围内 [0, 1]
    // 超出范围的像素显示为透明黑色（实现裁剪效果）
    if (fragTexCoord.x < 0.0 || fragTexCoord.x > 1.0 ||
        fragTexCoord.y < 0.0 || fragTexCoord.y > 1.0) {
        outColor = vec4(0.0, 0.0, 0.0, 0.0);  // 透明
        return;
    }

    // 采样纹理
    outColor = texture(texSampler, fragTexCoord);
}