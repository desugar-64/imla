#version 300 es
precision mediump float;

uniform sampler2D u_Texture;
uniform vec4 u_ClampUv;

in vec2 maskCoord;
in vec2 texCoord;
in float alpha;

out vec4 color;

void main()
{
    vec2 uv = clamp(texCoord, u_ClampUv.xy, u_ClampUv.zw);
    vec4 baseColor = texture(u_Texture, uv);
    baseColor.a *= alpha;
    color = baseColor;
}
