#version 300 es
precision mediump float;

#define GAMMA 2.2

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
    vec2 pixel = floor(gl_FragCoord.xy);
    float val = rand(pixel);
    color = vec4(vec3(val, val, val), 1.0);
}
