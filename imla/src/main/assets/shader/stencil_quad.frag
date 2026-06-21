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

uniform sampler2D u_Textures[8];

in vec2 texCoord;
in VertexOutput data;

out vec4 fragColor;

void main() {
    vec2 tc = mix(texCoord, vec2(texCoord.x, 1.0 - texCoord.y), data.flipTexture);
    float maskValue = 0.0;

    switch (int(data.texIndex)) {
        case 0: maskValue = texture(u_Textures[0], tc).r; break;
        case 1: maskValue = texture(u_Textures[1], tc).r; break;
        case 2: maskValue = texture(u_Textures[2], tc).r; break;
        case 3: maskValue = texture(u_Textures[3], tc).r; break;
        case 4: maskValue = texture(u_Textures[4], tc).r; break;
        case 5: maskValue = texture(u_Textures[5], tc).r; break;
        case 6: maskValue = texture(u_Textures[6], tc).r; break;
        case 7: maskValue = texture(u_Textures[7], tc).r; break;
    }

    // Discard fragments outside shape (won't write to stencil)
    if (maskValue < 0.5) {
        discard;
    }

    // Fragment survives -> stencil write happens via glStencilOp
    fragColor = vec4(0.0);  // Color write is masked anyway
}
