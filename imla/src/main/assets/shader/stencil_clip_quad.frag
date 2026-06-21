#version 300 es
precision mediump float;

uniform sampler2D u_Texture;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float maskValue = texture(u_Texture, texCoord).r;
    if (maskValue < 0.5) {
        discard;
    }
    fragColor = vec4(0.0);
}
