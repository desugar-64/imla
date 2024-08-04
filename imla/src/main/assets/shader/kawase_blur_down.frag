#version 300 es
precision mediump float;

#define GAMMA 2.2

uniform sampler2D u_Texture;
uniform vec2 u_Halfpixel;
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

void main() {
    vec2 uv = texCoord;

    vec4 sum = vec4(gammaDecode(texture(u_Texture, uv).rgb) * 4.0, 0.0);
    sum.rgb += gammaDecode(texture(u_Texture, uv - u_Halfpixel.xy * u_Offset).rgb);
    sum.rgb += gammaDecode(texture(u_Texture, uv + u_Halfpixel.xy * u_Offset).rgb);
    sum.rgb += gammaDecode(texture(u_Texture, uv + vec2(u_Halfpixel.x, -u_Halfpixel.y) * u_Offset).rgb);
    sum.rgb += gammaDecode(texture(u_Texture, uv - vec2(u_Halfpixel.x, -u_Halfpixel.y) * u_Offset).rgb);

    color = sum / 8.0;
    color.rgb = gammaEncode(color.rgb);
}