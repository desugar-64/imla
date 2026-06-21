#version 300 es
precision mediump float;

// Simple UV debug gradient:
// top-left  = black (0,0)
// top-right = red   (1,0)
// bottom-left = green (0,1)
// bottom-right = yellow (1,1)

struct VertexOutput
{
    float texIndex;
    float flipTexture;
    float isExternalTexture;
    float alpha;
    float mask;
    vec4 tint;
};

in vec2 maskCoord;
in vec2 texCoord;
in VertexOutput data;

out vec4 color;

void main() {
    color = vec4(texCoord, 0.0, 1.0);
}
