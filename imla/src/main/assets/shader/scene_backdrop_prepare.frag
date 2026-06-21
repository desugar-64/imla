#version 300 es
precision highp float;

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
    vec4 tint;
};

uniform sampler2D u_Textures[${MAX_TEXTURE_SLOTS}];
uniform vec2 u_InputSize;
uniform float u_InputFlipY;
uniform vec4 u_SampleInputRect;
uniform vec2 u_SourceTexelInputSize;
uniform float u_PrefilterScale;

in vec2 texCoord;
in VertexOutput data;

out vec4 color;

// Blur must average in linear light; sRGB-space averaging darkens transitions
// between saturated colours (blue<->green/red collapses toward black).
highp vec3 srgbToLinear(highp vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
highp vec3 linearToSrgb(highp vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

const vec2 PREFILTER_OFFSETS[8] = vec2[8](
    vec2(-0.75777, -0.75777),
    vec2(0.75777, -0.75777),
    vec2(0.75777, 0.75777),
    vec2(-0.75777, 0.75777),
    vec2(-2.907, 0.0),
    vec2(2.907, 0.0),
    vec2(0.0, -2.907),
    vec2(0.0, 2.907)
);

const float PREFILTER_WEIGHTS[8] = float[8](
    0.37487566,
    0.37487566,
    0.37487566,
    0.37487566,
    -0.12487566,
    -0.12487566,
    -0.12487566,
    -0.12487566
);

vec4 sampleInput(vec2 inputPoint) {
    vec2 uv = clamp(inputPoint / max(u_InputSize, vec2(1.0)), vec2(0.0), vec2(1.0));
    if (u_InputFlipY > 0.5) {
        uv.y = 1.0 - uv.y;
    }

    vec4 baseColor = vec4(1.0);
    switch (int(data.texIndex)) {
${TEXTURE_SWITCH_CASES}
    }
    baseColor.rgb = srgbToLinear(baseColor.rgb);
    return baseColor;
}

void main()
{
    vec2 inputPoint = u_SampleInputRect.xy + texCoord * u_SampleInputRect.zw;
    vec2 prefilterStep = u_SourceTexelInputSize * u_PrefilterScale;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 8; i++) {
        vec2 samplePoint = inputPoint + PREFILTER_OFFSETS[i] * prefilterStep;
        sum += sampleInput(samplePoint) * PREFILTER_WEIGHTS[i];
    }

    color = vec4(linearToSrgb(sum.rgb), sum.a);
}
