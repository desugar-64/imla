#version 300 es
precision mediump float;

#define USE_GAMMA_CORRECTION 0
#define GAMMA 2.2

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
};
// horiz=(1.0, 0.0), vert=(0.0, 1.0)
uniform vec2 u_BlurDirection;
uniform vec2 u_TexelSize;
uniform float u_BlurSigma;
uniform vec4 u_BlurTint;

uniform sampler2D u_Textures[8];

in vec2 maskCoord;
in vec2 texCoord;
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

float gaussianWeight(float x, float sigma) {
    return exp(-0.5 * (x * x) / (sigma * sigma));
}

void main() {
    bool flipTexture = int(data.flipTexture) > 0;
    vec2 loc = mix(texCoord, vec2(texCoord.x, 1.0 - texCoord.y), data.flipTexture);
    vec2 dir = u_BlurDirection / u_TexelSize;
    vec4 acc = vec4(0.0);
    float totalWeight = 0.0;
    float support = u_BlurSigma * 3.0;
    float sigma = u_BlurSigma;
    //    int step = 9; // creates an interesting effect of embossed glass
    float step = 1.0;

    for (float i = -support; i <= support; i += step) {
        float x = i;
        float weight = gaussianWeight(x, sigma);
        vec4 texColor = texture(u_Textures[1], loc + x * dir); // todo: support batching
        #ifdef USE_GAMMA_CORRECTION
        texColor.rgb = linear_from_srgb(texColor.rgb);
        acc += texColor * weight;
        #else
        acc += texColor * weight;
        #endif
        totalWeight += weight;
    }
    #ifdef USE_GAMMA_CORRECTION
    acc = vec4(srgb_from_linear(acc.rgb * (1.0 / totalWeight)), acc.a * (1.0 / totalWeight));
    #else
    acc = acc * (1.0 / totalWeight);
    #endif
    color = mix(acc, u_BlurTint, u_BlurTint.a * u_BlurTint.a);
}