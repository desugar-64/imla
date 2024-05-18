#version 300 es
#extension GL_OES_standard_derivatives: enable
precision mediump float;

#define GAMMA 2.0

in vec2 TexCoord;
out vec4 color;

highp float rand(vec2 co)
{
    highp float a = 12.9898;
    highp float b = 78.233;
    highp float c = 43758.5453;
    highp float dt = dot(co.xy, vec2(a, b));
    highp float sn = mod(dt, 3.14);
    return fract(sin(sn) * c);
}

void main()
{
    // color = vec4(1.0);
    float val = pow(clamp(rand(TexCoord), 0.6, 1.0), 1.0 / GAMMA);
    color = vec4(vec3(val, val, val), 1.0);
}