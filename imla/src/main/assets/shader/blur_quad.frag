#version 300 es
precision mediump float;

#define USE_GAMMA_CORRECTION 0
#define GAMMA 2.2

struct VertexOutput
{
    float TexIndex;
    float FlipTexture;
    float isExternalTexture;
    float alpha;
};

uniform float u_BlurDirection;
uniform vec2 u_TexelSize;
uniform float u_BlurSigma;
uniform vec4 u_BlurTint;

uniform sampler2D u_Textures[8];

in vec2 TexCoord;
in VertexOutput data;

out vec4 color;


//Classic gamma correction functions
vec3 linear_from_srgb(vec3 rgb)
{
    return pow(rgb, vec3(GAMMA));
}
vec3 srgb_from_linear(vec3 lin)
{
    return pow(lin, vec3(1.0 / GAMMA));
}

void main() {
    bool flipTexture = int(data.FlipTexture) > 0;
    vec2 texCoord = flipTexture ? vec2(TexCoord.x, 1. - TexCoord.y) : TexCoord;

    vec2 loc = texCoord;
    // horiz=(1.0, 0.0), vert=(0.0, 1.0)
    vec2 dir = u_BlurDirection < 1.0 ? vec2(1.0 / u_TexelSize.x, 0.0) : vec2(0.0, 1.0 / u_TexelSize.y);
    vec4 acc = vec4(0.0);
    float norm = 0.0;
    int support = int(u_BlurSigma);
    float sigma = u_BlurSigma;
    for (int i = -support; i <= support; i++) {
        float coeff = exp(-0.5 * float(i) * float(i) / (sigma * sigma));
        vec4 texColor = texture(u_Textures[1], loc + float(i) * dir); // todo: support batching
        #ifdef USE_GAMMA_CORRECTION
        texColor.rgb = linear_from_srgb(texColor.rgb);
        acc += texColor * coeff;
        #else
        acc += texColor * coeff;
        #endif
        norm += coeff;
    }
    #ifdef USE_GAMMA_CORRECTION
    acc = vec4(srgb_from_linear(acc.rgb * (1.0 / norm)), acc.a * (1.0 / norm));
    #else
    acc = acc * (1.0 / norm);
    #endif
    vec4 tintedColor = vec4(mix(acc.rgb, u_BlurTint.rgb, u_BlurTint.a * u_BlurTint.a), acc.a);
    color = tintedColor;
}