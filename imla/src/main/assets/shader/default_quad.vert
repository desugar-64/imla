#version 300 es
precision highp float;

uniform mat4 u_ViewProjection;
layout (location = 0) in vec2 a_TexCoord;
layout (location = 1) in vec4 a_Position;
layout (location = 2) in float a_TexIndex;
layout (location = 3) in float a_Flags;
layout (location = 4) in float a_Alpha;
layout (location = 5) in vec2 a_MaskCoord;
layout (location = 6) in highp float a_TintPacked;

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
    vec4 tint;
};

out vec2 maskCoord;
out vec2 texCoord;
out VertexOutput data;

// Manual ABGR unpacking with highp for device compatibility
vec4 unpackABGR(highp float packed) {
    highp uint bits = floatBitsToUint(packed);
    return vec4(
        float(bits & 0xFFu) / 255.0,
        float((bits >> 8u) & 0xFFu) / 255.0,
        float((bits >> 16u) & 0xFFu) / 255.0,
        float((bits >> 24u) & 0xFFu) / 255.0
    );
}

void main() {
    maskCoord = a_MaskCoord;
    texCoord = a_TexCoord;
    data.texIndex = a_TexIndex;
    data.alpha = a_Alpha;
    int flags = int(a_Flags + 0.5);
    data.flipTexture = (flags & 1) != 0 ? 1.0 : 0.0;
    data.isExternalTexture = (flags & 2) != 0 ? 1.0 : 0.0;
    data.mask = (flags & 4) != 0 ? 1.0 : 0.0;
    data.tint = unpackABGR(a_TintPacked);
    gl_Position = u_ViewProjection * a_Position;
}
