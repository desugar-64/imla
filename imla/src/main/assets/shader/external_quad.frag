#version 300 es
#extension GL_OES_EGL_image_external_essl3: enable
precision mediump float;

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
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
    if (baseColor.w == 0.0) { // debug mark transparency
                              color = vec4(1.0, 0.0, 1.0, 1.0);
    } else {
    }
    color = baseColor;
    //    color = vec4(1.0 - texCoord.y, 1.0, 0.0, 1.0);
}
