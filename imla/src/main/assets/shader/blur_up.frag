#version 300 es
precision mediump float;

#define GAMMA 2.2

uniform sampler2D u_Texture;
uniform vec2 u_Texel;
uniform vec4 u_Tint;

in vec2 texCoord;

out vec4 color;

const vec2 S = vec2(-1, 1);
const float WEIGHT_SUM_INV = 1.0 / 16.0;
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
    return texture(tex, clamp(uv, UV_MIN, UV_MAX));
}

// Credits:
// Jorge Jimenez,
// NEXT GENERATION POST PROCESSING IN CALL OF DUTY: ADVANCED WARFARE, https://advances.realtimerendering.com/s2014/index.html
// 9-tap bilinear upsampler (tent filter)
//      [ 1  2  1 ]
// 1/16 [ 2  4  2 ]
//      [ 1  2  1 ]
void main() {
    vec2 uv = texCoord;
    // center point
    vec4 sum = gammaDecode(safeTexture(u_Texture, uv)) * 4.0;

    // corners
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S)); // tl
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yy)); // tr
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.yx)); // br
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * S.xx)); // bl

    // sides
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(S.x, 0.0))) * 2.0; // ml
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(0.0, S.y))) * 2.0; // mt
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(S.y, 0.0))) * 2.0; // mr
    sum += gammaDecode(safeTexture(u_Texture, uv + u_Texel * vec2(0.0, S.x))) * 2.0; // mb

    sum *= WEIGHT_SUM_INV;
    color = gammaEncode(sum);
    color = mix(color, u_Tint, u_Tint.a * u_Tint.a);
}