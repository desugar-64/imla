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
uniform vec2 u_TargetSize;
uniform float u_FragCoordFlipY;
uniform vec4 u_SampleRect;
uniform vec2 u_SampleUvMin;
uniform vec2 u_SampleUvMax;
uniform float u_NoiseEnabled;
uniform float u_NoiseAlpha;
uniform int u_NoiseTexIndex;
uniform vec2 u_NoiseTextureSize;
uniform vec2 u_NoiseOffsetPx;
uniform mat4 u_RootToLocal;

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

vec3 applyTint(vec3 source, vec4 tint)
{
    float amount = 1.0 - pow(1.0 - clamp(tint.a, 0.0, 1.0), 1.6);
    return mix(source, tint.rgb, amount);
}

vec4 sampleNoiseAt(vec2 noiseUv)
{
    vec4 baseColor = vec4(0.5);
    switch (u_NoiseTexIndex) {
${NOISE_SWITCH_CASES}
    }
    return baseColor;
}

vec2 rootToLocalPx(vec2 screenPos)
{
    vec4 local = u_RootToLocal * vec4(screenPos, 0.0, 1.0);
    float w = abs(local.w) < 1e-5 ? 1e-5 : local.w;
    return local.xy / w;
}

vec3 applyNoise(vec3 source, float noiseValue)
{
    float luma = dot(source, vec3(0.2126, 0.7152, 0.0722));
    float weight = 1.0 - abs(luma * 2.0 - 1.0);
    weight = pow(weight, 1.5);
    vec3 grain = (vec3(noiseValue) - vec3(0.5)) * u_NoiseAlpha * weight;
    return clamp(source + grain, 0.0, 1.0);
}

void main()
{
    vec2 screenPos = vec2(
        gl_FragCoord.x,
        mix(gl_FragCoord.y, u_TargetSize.y - gl_FragCoord.y, u_FragCoordFlipY)
    );
    vec2 denom = max(u_SampleRect.zw, vec2(1.0));
    vec2 sampleRatio = (screenPos - u_SampleRect.xy) / denom;
    sampleRatio = clamp(sampleRatio, vec2(0.0), vec2(1.0));
    vec2 sampleUv = mix(u_SampleUvMin, u_SampleUvMax, sampleRatio);
    vec2 texCoordAdjusted = mix(sampleUv, vec2(sampleUv.x, 1.0 - sampleUv.y), data.flipTexture);

    vec4 baseColor = vec4(1.);
    switch (int(data.texIndex)) {
${TEXTURE_SWITCH_CASES}
    }

    baseColor.rgb = applyTint(baseColor.rgb, data.tint);

    if (u_NoiseEnabled > 0.5) {
        vec2 localPx = rootToLocalPx(screenPos);
        vec2 noiseUv = fract((localPx + u_NoiseOffsetPx) / max(u_NoiseTextureSize, vec2(1.0)));
        float noiseValue = sampleNoiseAt(noiseUv).r;
        baseColor.rgb = applyNoise(baseColor.rgb, noiseValue);
    }

    if (data.mask > 0.5) {
        baseColor.a = data.alpha;
    } else {
        baseColor.a *= data.alpha;
    }
    color = baseColor;
}
