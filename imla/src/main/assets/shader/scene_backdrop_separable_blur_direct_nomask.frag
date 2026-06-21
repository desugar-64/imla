#version 300 es
precision mediump float;

uniform sampler2D u_SourceTexture;
uniform highp vec2 u_BlurDirection;
uniform highp vec2 u_SourceTexelStep;
uniform highp vec2 u_SourceUvMin;
uniform highp vec2 u_SourceUvMax;
uniform highp vec4 u_KernelSamples[${MAX_KERNEL_SAMPLE_COUNT}];

in vec2 texCoord;

out highp vec4 color;

const int MAX_KERNEL_SAMPLE_COUNT = ${MAX_KERNEL_SAMPLE_COUNT};

highp float isInsideBounds(highp vec2 uv, highp vec2 uvMin, highp vec2 uvMax) {
    highp vec2 inMin = step(uvMin, uv);
    highp vec2 inMax = step(uv, uvMax);
    return inMin.x * inMin.y * inMax.x * inMax.y;
}

void accumulateSample(
    highp vec4 sampleData,
    inout highp vec4 sum,
    inout highp float totalWeight
) {
    highp float offsetPx = sampleData.x;
    highp float weight = sampleData.y;
    highp vec2 sampleUv = texCoord + u_BlurDirection * u_SourceTexelStep * offsetPx;
    highp float valid = isInsideBounds(sampleUv, u_SourceUvMin, u_SourceUvMax);
    highp vec2 safeUv = clamp(sampleUv, u_SourceUvMin, u_SourceUvMax);
    sum += texture(u_SourceTexture, safeUv) * weight * valid;
    totalWeight += weight * valid;
}

void main()
{
    highp vec4 sum = vec4(0.0);
    highp float totalWeight = 0.0;

    for (int i = 0; i < MAX_KERNEL_SAMPLE_COUNT; i++) {
        accumulateSample(u_KernelSamples[i], sum, totalWeight);
    }

    if (totalWeight > 1e-6) {
        color = vec4((sum / totalWeight).rgb, 1.0);
    } else {
        highp vec2 safeUv = clamp(texCoord, u_SourceUvMin, u_SourceUvMax);
        color = vec4(texture(u_SourceTexture, safeUv).rgb, 1.0);
    }
}
