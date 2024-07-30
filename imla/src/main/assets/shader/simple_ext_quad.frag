#version 300 es
#extension GL_OES_EGL_image_external_essl3: enable
#extension GL_OES_EGL_image_external: require
precision mediump float;

uniform samplerExternalOES u_Texture;

in vec2 maskCoord;
in vec2 texCoord;
in vec2 texSize;
in float alpha;
in float flip;

out vec4 color;

void main()
{
    vec4 baseColor = texture(u_Texture, texCoord);
    baseColor.a = alpha;
    color = baseColor;
}
