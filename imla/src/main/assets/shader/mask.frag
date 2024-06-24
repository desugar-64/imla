#version 300 es
precision mediump float;

uniform sampler2D u_Mask;
uniform sampler2D u_Texture;

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
};

in vec2 maskCoord;
in vec2 texCoord;

out vec4 color;

void main() {
    vec4 maskColor = texture(u_Mask, maskCoord);
    vec4 contentColor = texture(u_Texture, texCoord);

    vec4 finalColor = vec4(1.0);

    finalColor.rgb = mix(contentColor.rgb, vec3(1.0, 0.0, 0.0), 1.0 - maskColor.r);
    color = finalColor;
}