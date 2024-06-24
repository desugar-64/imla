#version 300 es
precision mediump float;

uniform mat4 u_ViewProjection;
layout (location = 0) in vec2 a_TexCoord;
layout (location = 1) in vec4 a_Position;
layout (location = 2) in float a_TexIndex;
layout (location = 3) in float a_FlipTexture;
layout (location = 4) in float a_IsExternalTexture;
layout (location = 5) in float a_Alpha;
layout (location = 6) in float a_Mask;
layout (location = 7) in vec2 a_MaskCoord;

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
};

out vec2 maskCoord;
out vec2 texCoord;
out VertexOutput data;

void main() {

    texCoord = a_TexCoord;
    data.texIndex = a_TexIndex;
    data.flipTexture = a_FlipTexture;
    data.isExternalTexture = a_IsExternalTexture;
    data.alpha = a_Alpha;
    data.mask = a_Mask;
    gl_Position = u_ViewProjection * a_Position;
}