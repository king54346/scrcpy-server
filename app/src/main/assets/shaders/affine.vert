#version 450

// Push Constants: 包含两个 4x4 矩阵
layout(push_constant) uniform PushConstants {
    mat4 tex_matrix;   // 纹理变换矩阵（来自 MediaCodec 或其他源）
    mat4 user_matrix;  // 用户定义的仿射变换矩阵
} pc;

// 输出到片段着色器
layout(location = 0) out vec2 fragTexCoord;

void main() {
    // 全屏三角形技巧：
    // 顶点ID 0 -> (-1, -1)，纹理坐标 (0, 0)
    // 顶点ID 1 -> ( 3, -1)，纹理坐标 (2, 0)
    // 顶点ID 2 -> (-1,  3)，纹理坐标 (0, 2)
    // 
    // 这样可以用3个顶点覆盖整个屏幕，比两个三角形（6个顶点）更高效
    vec2 positions[3] = vec2[3](
        vec2(-1.0, -1.0),
        vec2( 3.0, -1.0),
        vec2(-1.0,  3.0)
    );
    
    vec2 texCoords[3] = vec2[3](
        vec2(0.0, 0.0),
        vec2(2.0, 0.0),
        vec2(0.0, 2.0)
    );
    
    // 设置顶点位置（NDC坐标）
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    
    // 应用纹理变换和用户变换
    // 注意：变换顺序很重要！
    // tex_matrix 通常处理 Android 纹理的特殊转换（如旋转）
    // user_matrix 应用用户定义的仿射变换
    vec4 texCoord = vec4(texCoords[gl_VertexIndex], 0.0, 1.0);
    vec4 transformed = pc.tex_matrix * pc.user_matrix * texCoord;
    
    fragTexCoord = transformed.xy;
}
