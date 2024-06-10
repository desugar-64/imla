#version 300 es
precision mediump float;

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
    float alpha;
};

uniform sampler2D u_Mask;
uniform sampler2D u_Textures[8];

in vec2 TexCoord;
in VertexOutput data;

out vec4 color;

void main() {
    bool flipTexture = int(data.FlipTexture) > 0;
    vec2 texCoord = flipTexture ? vec2(TexCoord.x, 1. - TexCoord.y) : TexCoord;

    vec4 maskColor = texture(u_Mask, TexCoord);
    vec4 contentColor = texture(u_Textures[1], texCoord);

    color = vec4(1.0);

    color.rgb = mix(contentColor.rgb, vec3(1.0, 0.0, 0.0), 1.0 - maskColor.r);
    color = maskColor;
}