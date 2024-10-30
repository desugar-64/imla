#version 300 es
precision mediump float;

#define GAMMA 2.2

uniform sampler2D u_Texture;
uniform vec2 u_Texel;
uniform vec2 u_ContentOffset;

in vec2 texCoord;

out vec4 color;

const vec2 S = vec2(-1, 1);
const float WEIGHT_SUM_INV = 1.0 / 3.125;
const vec2 UV_MIN = vec2(0.0);
const vec2 UV_MAX = vec2(1.0);

// Simple approximation
// Convert sRGB color to linear space
vec4 gammaDecode(vec4 rgba) {
    vec3 rgb = rgba.rgb;
    return vec4(rgb * rgb, rgba.a);
}
// Convert linear color to sRGB space
vec4 gammaEncode(vec4 rgba) {
    vec3 rgb = rgba.rgb;
    return vec4(sqrt(rgb), rgba.a);
}

vec4 safeTexture(sampler2D tex, vec2 uv) {
//    return texture(tex, clamp(uv, UV_MIN + u_ContentOffset, UV_MAX - u_ContentOffset));
    return texture(tex, clamp(uv, UV_MIN, UV_MAX));
}

// Credits:
// Jorge Jimenez,
// NEXT GENERATION POST PROCESSING IN CALL OF DUTY: ADVANCED WARFARE, https://advances.realtimerendering.com/s2014/index.html
// . . . . . . .
// . A . B . C .
// . . D . E . .
// . F . G . H .
// . . I . J . .
// . K . L . M .
// . . . . . . .
// Temporally stable box filtering
void main() {
    vec2 uv = texCoord;

    // center point
    vec4 sum = gammaDecode(safeTexture(u_Texture, uv)) * 0.125; // G
    // inner ring corners
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S)) * 0.5; // D
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yy)) * 0.5; // E
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yx)) * 0.5; // I
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.xx)) * 0.5; // J

    // outer ring corners
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S * 2.0)) * 0.125; // A
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yy * 2.0)) * 0.125; // C
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yx * 2.0)) * 0.125; // M
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.xx * 2.0)) * 0.125; // K

    // middle ring sides
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(S.x, 0.0) * 2.0)) * 0.125; // F
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(0.0, S.y) * 2.0)) * 0.125; // B
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(S.y, 0.0) * 2.0)) * 0.125; // H
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(0.0, S.x) * 2.0)) * 0.125; // L

    sum *= WEIGHT_SUM_INV;
    color = gammaEncode(sum);
}
