#version 300 es
precision mediump float;

#define GAMMA 2.2

// horiz=(1.0, 0.0), vert=(0.0, 1.0)
uniform vec2 u_BlurDirection;
uniform float u_BlurSigma;
uniform vec4 u_BlurTint;
uniform vec2 u_TexelSize;

uniform sampler2D u_Texture;

in vec2 maskCoord;
in vec2 texCoord;
//in vec2 texSize;
in float alpha;

out vec4 color;


//Classic gamma correction functions
vec3 linear_from_srgb(vec3 srgb) {
    return vec3(
        srgb.r <= 0.04045 ? srgb.r / 12.92 : pow((srgb.r + 0.055) / 1.055, GAMMA),
        srgb.g <= 0.04045 ? srgb.g / 12.92 : pow((srgb.g + 0.055) / 1.055, GAMMA),
        srgb.b <= 0.04045 ? srgb.b / 12.92 : pow((srgb.b + 0.055) / 1.055, GAMMA)
    );
}

vec3 srgb_from_linear(vec3 lin) {
    return vec3(
        lin.r <= 0.0031308 ? lin.r * 12.92 : 1.055 * pow(lin.r, 1.0 / GAMMA) - 0.055,
        lin.g <= 0.0031308 ? lin.g * 12.92 : 1.055 * pow(lin.g, 1.0 / GAMMA) - 0.055,
        lin.b <= 0.0031308 ? lin.b * 12.92 : 1.055 * pow(lin.b, 1.0 / GAMMA) - 0.055
    );
}

float gaussianWeight(float x, float sigma) {
    return exp(-0.5 * (x * x) / (sigma * sigma));
}

void main() {
    vec2 loc = texCoord;
    vec2 dir = u_BlurDirection / u_TexelSize;
    vec4 acc = vec4(0.0);
    float totalWeight = 0.0;
    int support = int(u_BlurSigma) * 3;
    float sigma = u_BlurSigma;
    //    int step = 9; // creates an interesting effect of embossed glass
    int step = 1;

    for (int i = -support; i <= support; i += step) {
        float fi = float(i);
        float x = fi;
        float weight = gaussianWeight(x, sigma);
        vec4 texColor = texture(u_Texture, loc + x * dir);
        texColor.rgb = linear_from_srgb(texColor.rgb);
        acc += texColor * weight;
        totalWeight += weight;
    }
    acc.rgb = srgb_from_linear(acc.rgb * (1.0 / totalWeight));
    acc.a *= (1.0 / totalWeight);
    color = mix(acc, u_BlurTint, u_BlurTint.a * u_BlurTint.a);
}