#version 300 es
#extension GL_OES_EGL_image_external_essl3: enable
#extension GL_OES_EGL_image_external: require
precision mediump float;

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
    float alpha;
};

uniform samplerExternalOES u_Textures[8];

in vec2 TexCoord;
in VertexOutput data;

out vec4 color;

void main()
{
    vec4 baseColor = vec4(1.);
    bool flipTexture = int(data.FlipTexture) > 0;
    vec2 texCoord = flipTexture ? vec2(TexCoord.x, 1. - TexCoord.y) : TexCoord;

    switch (int(data.TexIndex)) {
        case 0:
            baseColor = texture(u_Textures[0], texCoord);break;
        case 1:
            baseColor = texture(u_Textures[1], texCoord);break;
        case 2:
            baseColor = texture(u_Textures[2], texCoord);break;
        case 3:
            baseColor = texture(u_Textures[3], texCoord);break;
        case 4:
            baseColor = texture(u_Textures[4], texCoord);break;
        case 5:
            baseColor = texture(u_Textures[5], texCoord);break;
        case 6:
            baseColor = texture(u_Textures[6], texCoord);break;
        case 7:
            baseColor = texture(u_Textures[7], texCoord);break;
    }

    baseColor.a = data.alpha;
    color = baseColor;
}
