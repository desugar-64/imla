#version 300 es
precision mediump float;

#define GAMMA 2.2

uniform sampler2D u_Texture;
uniform vec2 u_Texel;
uniform float u_Offset;

in vec2 texCoord;

out vec4 color;

const vec2 s = vec2(-1, 1);
const float WEIGHT_SUM_INV = 1.0 / 3.125;

// Simple approximation
// Convert sRGB color to linear space
vec3 gammaDecode(vec3 rgb) {
    return rgb * rgb;
}
// Convert linear color to sRGB space
vec3 gammaEncode(vec3 rgb) {
    return sqrt(rgb);
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
    vec4 sum = vec4(gammaDecode(texture(u_Texture, uv).rgb) * 0.125, 0.0); // G
    // inner ring corners
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s).rgb) * 0.5; // D
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yy).rgb) * 0.5; // E
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yx).rgb) * 0.5; // I
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.xx).rgb) * 0.5; // J

    // outer ring corners
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s * 2.0).rgb) * 0.125; // A
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yy * 2.0).rgb) * 0.125; // C
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yx * 2.0).rgb) * 0.125; // M
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.xx * 2.0).rgb) * 0.125; // K

    // middle ring sides
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(s.x, 0.0) * 2.0).rgb) * 0.125; // F
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(0.0, s.y) * 2.0).rgb) * 0.125; // B
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(s.y, 0.0) * 2.0).rgb) * 0.125; // H
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(0.0, s.x) * 2.0).rgb) * 0.125; // L

    sum.a = 1.0;
    sum.rgb *= WEIGHT_SUM_INV;
    sum.rgb = gammaEncode(sum.rgb);

    color = sum;
}
