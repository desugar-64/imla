#version 300 es
precision mediump float;

#define GAMMA 2.2

uniform sampler2D u_Texture;
uniform vec2 u_Texel;
uniform float u_Offset;

in vec2 texCoord;

out vec4 color;

// Simple approximation
// Convert sRGB color to linear space
vec3 gammaDecode(vec3 rgb) {
    return rgb * rgb;
}
// Convert linear color to sRGB space
vec3 gammaEncode(vec3 rgb) {
    return sqrt(rgb);
}

const vec2 s = vec2(-1, 1);
const float WEIGHT_SUM_INV = 1.0 / 16.0;

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
    vec4 sum = vec4(gammaDecode(texture(u_Texture, uv).rgb) * 4.0, 0.0);

    // corners
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s).rgb); // tl
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yy).rgb); // tr
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.yx).rgb); // br
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * s.xx).rgb); // bl

    // sides
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(s.x, 0.0)).rgb) * 2.0; // ml
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(0.0, s.y)).rgb) * 2.0; // mt
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(s.y, 0.0)).rgb) * 2.0; // mr
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Texel * vec2(0.0, s.x)).rgb) * 2.0; // mb

    sum.rgb *= WEIGHT_SUM_INV;
    sum.rgb = gammaEncode(sum.rgb);
    sum.a = 1.0;

    color = sum;
    //    color = texture(u_Texture, uv);
}