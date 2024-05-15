#version 300 es
#extension GL_OES_standard_derivatives: enable
precision mediump float;

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

    //    color.rgb = hash42(uvec2(1000, 1000)).rgb;
    color = vec4(1.0);
    color = vec4(vec3(rand(TexCoord)), 1.0);
}