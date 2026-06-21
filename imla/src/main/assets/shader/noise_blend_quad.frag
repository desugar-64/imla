#version 300 es
precision mediump float;

// Screen-space blur sampling with quad-space noise/mask/base blending.
// u_SampleRect is the screen-space blur quad; u_SampleUvMin/Max map into the blur texture.

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
uniform vec4 u_SampleRect;
uniform vec2 u_SampleUvMin;
uniform vec2 u_SampleUvMax;
uniform float u_NoiseAlpha;
uniform float u_NoiseFlip;
uniform float u_NoiseEnabled;
uniform int u_NoiseTexIndex;
uniform float u_MaskEnabled;
uniform float u_MaskFlip;
uniform int u_MaskTexIndex;
uniform float u_BlurMaskPower;
uniform float u_BlurSigma;
uniform float u_BlurSigmaRange;
uniform float u_BaseEnabled;
uniform float u_BaseFlip;
uniform int u_BaseTexIndex;
uniform vec2 u_BaseUvMin;
uniform vec2 u_BaseUvMax;

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

vec4 sampleAt(vec2 uv) {
    vec4 baseColor = vec4(1.);
    switch (int(data.texIndex)) {
${TEXTURE_SWITCH_CASES}
    }
    return baseColor;
}

vec4 sampleNoiseAt(vec2 uv) {
    vec4 noiseColor = vec4(0.);
    switch (u_NoiseTexIndex) {
${NOISE_SWITCH_CASES}
    }
    return noiseColor;
}

vec4 sampleMaskAt(vec2 uv) {
    vec4 maskColor = vec4(1.);
    switch (u_MaskTexIndex) {
${MASK_SWITCH_CASES}
    }
    return maskColor;
}

vec4 sampleBaseAt(vec2 uv) {
    vec4 baseSource = vec4(1.);
    switch (u_BaseTexIndex) {
${BASE_SWITCH_CASES}
    }
    return baseSource;
}

void main()
{
    vec2 screenPos = vec2(gl_FragCoord.x, u_TargetSize.y - gl_FragCoord.y);
    vec2 denom = max(u_SampleRect.zw, vec2(1.0));
    vec2 sampleRatio = (screenPos - u_SampleRect.xy) / denom;
    sampleRatio = clamp(sampleRatio, vec2(0.0), vec2(1.0));
    vec2 blurUv = mix(u_SampleUvMin, u_SampleUvMax, sampleRatio);
    blurUv = mix(blurUv, vec2(blurUv.x, 1.0 - blurUv.y), data.flipTexture);
    vec4 blurColor = sampleAt(blurUv);

    float noiseValue = 0.5;
    if (u_NoiseEnabled > 0.5) {
        vec2 noiseUv = maskCoord;
        if (u_NoiseFlip > 0.5) {
            noiseUv.y = 1.0 - noiseUv.y;
        }
        noiseValue = sampleNoiseAt(noiseUv).r;
    }

    float maskValue = 1.0;
    if (u_MaskEnabled > 0.5) {
        vec2 maskUv = maskCoord;
        // Mask texture is TOP_LEFT (Android Canvas), no flip needed in shader
        // The u_MaskFlip uniform uses inverted logic: 0.0 for TOP_LEFT (no flip), 1.0 for BOTTOM_LEFT (flip)
        if (u_MaskFlip > 0.5) {
            maskUv.y = 1.0 - maskUv.y;
        }
        maskValue = sampleMaskAt(maskUv).r;
    }

    blurColor = mix(blurColor, data.tint, data.tint.a * data.tint.a);

    // Luma-weighted grain: strongest in midtones, weakest in highlights/shadows.
    if (u_BaseEnabled > 0.5) {
        vec2 baseUv = mix(u_BaseUvMin, u_BaseUvMax, sampleRatio);
        if (u_BaseFlip > 0.5) {
            baseUv.y = 1.0 - baseUv.y;
        }
        vec4 baseColor = sampleBaseAt(baseUv);
        float blurMask = pow(maskValue, u_BlurMaskPower);
        float effectiveSigma = u_BlurSigma * blurMask;
        float blurMix = data.alpha * clamp(effectiveSigma / max(u_BlurSigmaRange, 1e-5), 0.0, 1.0);
        // When baseColor is outside content bounds (transparent), use blur directly
        vec3 mixed;
        float outAlpha;
        if (baseColor.a < 0.01) {
            mixed = blurColor.rgb;
            outAlpha = blurColor.a; // Use blur's alpha when outside base bounds
        } else {
            mixed = mix(baseColor.rgb, blurColor.rgb, blurMix);
            outAlpha = max(baseColor.a, blurMix);
        }
        float luma = dot(mixed, vec3(0.2126, 0.7152, 0.0722));
        float weight = 1.0 - abs(luma * 2.0 - 1.0);
        weight = pow(weight, 1.5);
        vec3 grain = (vec3(noiseValue) - vec3(0.5)) * u_NoiseAlpha * weight * blurMix;
        vec3 finalColor = clamp(mixed + grain, 0.0, 1.0);
        color = vec4(finalColor, outAlpha);
    } else {
        float luma = dot(blurColor.rgb, vec3(0.2126, 0.7152, 0.0722));
        float weight = 1.0 - abs(luma * 2.0 - 1.0);
        weight = pow(weight, 1.5);
        vec3 grain = (vec3(noiseValue) - vec3(0.5)) * u_NoiseAlpha * weight;
        vec3 blended = clamp(blurColor.rgb + grain, 0.0, 1.0);
        float outAlpha = blurColor.a;
        if (data.mask > 0.5) {
            outAlpha = data.alpha;
        } else {
            outAlpha *= data.alpha;
        }
        outAlpha *= maskValue;
        color = vec4(blended, outAlpha);
    }
}
