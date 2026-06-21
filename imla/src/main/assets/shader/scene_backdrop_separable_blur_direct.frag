#version 300 es
precision mediump float;

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
    vec4 tint;
};

uniform sampler2D u_SourceTexture;
uniform sampler2D u_MaskTexture;
uniform highp vec2 u_BlurDirection;
uniform highp vec2 u_SourceTexelStep;
uniform highp vec2 u_SourceUvMin;
uniform highp vec2 u_SourceUvMax;
uniform highp float u_MaskEnabled;
uniform highp float u_MaskFlipY;
uniform highp vec4 u_VisibleInSample;
uniform highp float u_FilterRadiusPx;
uniform highp float u_BlurSigmaPx;
uniform highp float u_DownsampleScale;

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out highp vec4 color;

const int MAX_BLUR_RADIUS_PX = ${MAX_BLUR_RADIUS_PX};

// Average in linear light, then re-encode; sRGB-space averaging darkens
// transitions between saturated colours (blue<->green/red toward black).
highp vec3 srgbToLinear(highp vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
highp vec3 linearToSrgb(highp vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

highp float gaussianWeight(highp float offsetPx, highp float sigmaPx) {
    sigmaPx = max(sigmaPx, 1e-6);
    highp float normalized = offsetPx / sigmaPx;
    return exp(-0.5 * normalized * normalized);
}

highp float isInsideBounds(highp vec2 uv, highp vec2 uvMin, highp vec2 uvMax) {
    highp vec2 inMin = step(uvMin, uv);
    highp vec2 inMax = step(uv, uvMax);
    return inMin.x * inMin.y * inMax.x * inMax.y;
}

highp float blurStrength() {
    if (u_MaskEnabled < 0.5) {
        return 1.0;
    }

    highp vec2 visibleSize = max(u_VisibleInSample.zw, vec2(1e-6));
    highp vec2 visibleCoord = (vec2(maskCoord) - u_VisibleInSample.xy) / visibleSize;
    highp vec2 maskUv = clamp(visibleCoord, vec2(0.0), vec2(1.0));
    if (u_MaskFlipY > 0.5) {
        maskUv.y = 1.0 - maskUv.y;
    }
    return clamp(texture(u_MaskTexture, maskUv).a, 0.0, 1.0);
}

void main()
{
    highp vec2 sourceUv = mix(vec2(texCoord), vec2(texCoord.x, 1.0 - texCoord.y), data.flipTexture);
    highp vec2 sourceUvA = mix(u_SourceUvMin, vec2(u_SourceUvMin.x, 1.0 - u_SourceUvMin.y), data.flipTexture);
    highp vec2 sourceUvB = mix(u_SourceUvMax, vec2(u_SourceUvMax.x, 1.0 - u_SourceUvMax.y), data.flipTexture);
    highp vec2 sourceUvMin = min(sourceUvA, sourceUvB);
    highp vec2 sourceUvMax = max(sourceUvA, sourceUvB);
    highp float sigmaPx = u_BlurSigmaPx * max(u_DownsampleScale, 1e-6) * blurStrength();
    // 3-sigma kernel half-width (matches GAUSSIAN_RADIUS_SIGMAS), capped by u_FilterRadiusPx.
    highp float filterRadiusPx = min(ceil(3.0 * sigmaPx), u_FilterRadiusPx);
    highp vec4 sum = vec4(0.0);
    highp float totalWeight = 0.0;

    for (int offsetIndex = -MAX_BLUR_RADIUS_PX; offsetIndex <= MAX_BLUR_RADIUS_PX; offsetIndex++) {
        highp float offsetPx = float(offsetIndex);
        if (abs(offsetPx) <= filterRadiusPx) {
            highp float weight = gaussianWeight(offsetPx, sigmaPx);
            highp vec2 sampleUv = sourceUv + u_BlurDirection * u_SourceTexelStep * offsetPx;
            highp float valid = isInsideBounds(sampleUv, sourceUvMin, sourceUvMax);
            highp vec2 safeUv = clamp(sampleUv, sourceUvMin, sourceUvMax);
            highp vec4 src = texture(u_SourceTexture, safeUv);
            sum += vec4(srgbToLinear(src.rgb), src.a) * weight * valid;
            totalWeight += weight * valid;
        }
    }

    if (totalWeight > 1e-6) {
        color = vec4(linearToSrgb((sum / totalWeight).rgb), 1.0);
    } else {
        highp vec2 safeUv = clamp(sourceUv, sourceUvMin, sourceUvMax);
        color = vec4(texture(u_SourceTexture, safeUv).rgb, 1.0);
    }
}
