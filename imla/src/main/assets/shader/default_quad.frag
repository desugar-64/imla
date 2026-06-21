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

    // Apply optional tint before alpha
    baseColor = mix(baseColor, data.tint, data.tint.a * data.tint.a);

    // Effect quads (mask > 0.5) get tint and override alpha
    if (data.mask > 0.5) {
        baseColor.a = data.alpha;
    } else {
        baseColor.a *= data.alpha;
    }
    color = baseColor;
}
