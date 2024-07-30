#version 300 es
precision mediump float;

layout (std140) uniform TextureDataUBO {
    vec2 uv[4];         // x, y,
    //    vec2 size;          // width, height
    float flipTexture;  // flip Y texture coordinate
    float alpha;        // alpha blending of the texture
} textureData;

layout (location = 0) in vec2 aPosition;

out vec2 maskCoord;
out vec2 texCoord;
//out vec2 texSize;
out float alpha;
out float flip;

void main() {
    vec2 ndcPos;

    // Set the final position of the vertex in clip space
    gl_Position = vec4(aPosition, 0.0, 1.0);

    alpha = textureData.alpha;

    maskCoord = aPosition * 0.5 + 0.5;
    maskCoord.y = abs(textureData.flipTexture - maskCoord.y);
    texCoord = textureData.uv[gl_VertexID % 4];
    texCoord.y = abs(textureData.flipTexture - texCoord.y);
    flip = textureData.flipTexture;
    //    texSize = textureData.size;
}