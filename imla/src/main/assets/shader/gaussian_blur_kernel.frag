#version 300 es
precision mediump float;

uniform sampler2D u_Texture;
uniform sampler2D u_Mask;
uniform vec2 u_UvMin;
uniform vec2 u_UvMax;
uniform vec2 u_MaskUvMin;
uniform vec2 u_MaskUvMax;
uniform float u_MaskEnabled;
uniform float u_MaskFlip;
uniform int u_SampleCount;
uniform vec4 u_KernelSamples[50];

in vec2 v_UV0;

out vec4 color;

float isValid(vec2 uv) {
    vec2 s = step(u_UvMin, uv) - step(u_UvMax, uv);
    return s.x * s.y;
}

vec4 gammaDecode(vec4 rgba) {
    return vec4(rgba.rgb * rgba.rgb, rgba.a);
}

vec4 gammaEncode(vec4 rgba) {
    return vec4(sqrt(rgba.rgb), rgba.a);
}

void main() {
    float maskValue = 1.0;
    if (u_MaskEnabled > 0.5) {
        vec2 maskSize = max(u_MaskUvMax - u_MaskUvMin, vec2(1e-6));
        vec2 local = (v_UV0 - u_MaskUvMin) / maskSize;
        vec2 inMin = step(vec2(0.0), local);
        vec2 inMax = step(local, vec2(1.0));
        float inBounds = inMin.x * inMin.y * inMax.x * inMax.y;
        vec2 maskUv = clamp(local, 0.0, 1.0);
        if (u_MaskFlip > 0.5) {
            maskUv.y = 1.0 - maskUv.y;
        }
        float sampled = texture(u_Mask, maskUv).r;
        maskValue = mix(1.0, sampled, inBounds);
    }

    vec4 sumColor = vec4(0.0);
    float sumWeight = 0.0;
    int count = min(u_SampleCount, 50);

    for (int i = 0; i < 50; i++) {
        if (i >= count) {
            break;
        }
        vec4 sampleData = u_KernelSamples[i];
        vec2 offset = sampleData.xy * maskValue;
        float weight = sampleData.z;
        vec2 uv = v_UV0 + offset;
        float valid = isValid(uv);
        vec4 sampled = texture(u_Texture, uv);
        sumColor += gammaDecode(sampled) * weight * valid;
        sumWeight += weight * valid;
    }

    if (sumWeight > 0.001) {
        color = gammaEncode(sumColor / sumWeight);
    } else {
        color = vec4(0.0);
    }
}
