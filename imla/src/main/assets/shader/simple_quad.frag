#version 300 es
#extension GL_OES_standard_derivatives: enable
precision mediump float;

uniform sampler2D u_Texture;

in vec2 maskCoord;
in vec2 texCoord;
in float alpha;

out vec4 color;

void main()
{
    vec4 baseColor = texture(u_Texture, texCoord);
    baseColor.a = alpha;
    color = baseColor;
}
