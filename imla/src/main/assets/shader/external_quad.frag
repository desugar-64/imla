#version 300 es
#extension GL_OES_EGL_image_external: require
precision mediump float;

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
    float alpha;
};

uniform samplerExternalOES u_Texture;

in vec2 TexCoord;
in VertexOutput data;

out vec4 color;

void main()
{
    vec4 baseColor = vec4(1.);
    bool flipTexture = int(data.FlipTexture) > 0;
    vec2 texCoord = flipTexture ? vec2(TexCoord.x, 1. - TexCoord.y) : TexCoord;

    baseColor = texture(u_Texture, texCoord);
    baseColor.a = data.alpha;
    color = baseColor;
}
