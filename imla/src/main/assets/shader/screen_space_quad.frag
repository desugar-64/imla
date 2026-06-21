#version 300 es
precision highp float;

// Screen-space sampling for 3D-transformed quads.
// u_SampleRect is in top-left screen coordinates; u_SampleUvMin/Max map that
// rect into the blur texture without shearing the blur when the quad tilts.

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

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

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

    baseColor = mix(baseColor, data.tint, data.tint.a * data.tint.a);

    if (data.mask > 0.5) {
        baseColor.a = data.alpha;
    } else {
        baseColor.a *= data.alpha;
    }
    color = baseColor;
}
