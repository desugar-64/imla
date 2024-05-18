#version 300 es
precision mediump float;

#define USE_GAMMA_CORRECTION 1
#define GAMMA 2.0

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
vec4 linear_from_srgb(vec4 rgb)
{
    return pow(rgb, vec4(GAMMA));
}
vec4 srgb_from_linear(vec4 lin)
{
    return pow(lin, vec4(1.0 / GAMMA));
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
        vec4 texColor = texture(u_Textures[1], loc + float(i) * dir);
        #ifdef USE_GAMMA_CORRECTION
        acc += linear_from_srgb(texColor) * coeff;
        #else
        acc += texColor * coeff;
        #endif
        norm += coeff;
    }
    #ifdef USE_GAMMA_CORRECTION
    acc = srgb_from_linear(acc * (1.0 / norm));
    #else
    acc = acc * 1.0 / norm;
    #endif
    //    acc.rgb = pow(acc.rgb, vec3(0.85));
    vec4 tintedColor = vec4(mix(acc.rgb, u_BlurTint.rgb, u_BlurTint.a * u_BlurTint.a), acc.a);
    tintedColor.a = mix(tintedColor.a, data.alpha, data.alpha);
    color = tintedColor;
    //    color = texture(u_Textures[1], texCoord);
    //    color = vec4(data.TexIndex, 0.0, 0.0, 1.0);
}