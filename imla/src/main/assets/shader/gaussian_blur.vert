#version 300 es
precision mediump float;

layout (std140) uniform TextureDataUBO {
    vec2 uv[4];
    float flipTexture;
    float alpha;
} textureData;

uniform float u_FlipY;          // 1.0 to flip Y, 0.0 for normal

layout (location = 0) in vec2 aPosition;

out vec2 v_UV0;  // Center

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);

    vec2 texCoord = textureData.uv[gl_VertexID % 4];
    // Flip Y if requested (for root FBO content which is upside-down)
    texCoord.y = abs(u_FlipY - texCoord.y);

    v_UV0 = texCoord;
}
