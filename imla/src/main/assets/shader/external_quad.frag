#version 300 es
#extension GL_OES_EGL_image_external_essl3: enable
#extension GL_OES_EGL_image_external: require
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

uniform samplerExternalOES u_Textures[${MAX_TEXTURE_SLOTS}];

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

void main()
{
    vec4 baseColor = vec4(1.);
    vec2 texCoord = mix(texCoord, vec2(texCoord.x, 1.0 - texCoord.y), data.flipTexture);

    switch (int(data.texIndex)) {
${TEXTURE_SWITCH_CASES}
    }

    // Effect quads (mask > 0.5) override texture alpha with blurOpacity
    // to ensure consistent blur opacity regardless of texture alpha variations
    if (data.mask > 0.5) {
        baseColor.a = data.alpha;
    } else {
        baseColor.a *= data.alpha;
    }
    color = baseColor;
}
