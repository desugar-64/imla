#version 300 es
precision mediump float;

uniform sampler2D u_Background;
uniform sampler2D u_Mask;
uniform sampler2D u_Textures[8];

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
};

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

void main() {
    vec4 maskColor = texture(u_Mask, maskCoord);

    bool flipTexture = int(data.flipTexture) > 0;
    vec2 texCoord = flipTexture ? vec2(texCoord.x, 1. - texCoord.y) : texCoord;

    vec4 backgroundColor = texture(u_Background, maskCoord);

    vec4 contentColor = vec4(1.0, 1.0, 1.0, 1.0);

    switch (int(data.texIndex)) {
        case 0:
            contentColor = texture(u_Textures[0], texCoord);break;
        case 1:
            contentColor = texture(u_Textures[1], texCoord);break;
        case 2:
            contentColor = texture(u_Textures[2], texCoord);break;
        case 3:
            contentColor = texture(u_Textures[3], texCoord);break;
        case 4:
            contentColor = texture(u_Textures[4], texCoord);break;
        case 5:
            contentColor = texture(u_Textures[5], texCoord);break;
        case 6:
            contentColor = texture(u_Textures[6], texCoord);break;
        case 7:
            contentColor = texture(u_Textures[7], texCoord);break;
    }

    vec4 finalColor = contentColor;

    finalColor.rgb = mix(backgroundColor.rgb, contentColor.rgb, maskColor.r);
    color = finalColor;
}