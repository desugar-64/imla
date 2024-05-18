#version 300 es
precision mediump float;

uniform mat4 u_ViewProjection;
layout (location = 0) in vec2 a_TexCoord;
layout (location = 1) in vec4 a_Position;
layout (location = 2) in float a_TexIndex;
layout (location = 3) in float a_FlipTexture;
layout (location = 4) in float a_IsExternalTexture;
layout (location = 5) in float a_Alpha;

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
    float alpha;
};

out vec2 TexCoord;
out VertexOutput data;

void main() {
    TexCoord = a_TexCoord;
    data.TexIndex = a_TexIndex;
    data.FlipTexture = a_FlipTexture;
    data.isExternalTexture = a_IsExternalTexture;
    data.alpha = a_Alpha;
    gl_Position = u_ViewProjection * a_Position;
}