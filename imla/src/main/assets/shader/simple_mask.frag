#version 300 es
precision mediump float;

uniform sampler2D u_Background;
uniform sampler2D u_Mask;
uniform sampler2D u_Texture;

in vec2 maskCoord;
in vec2 texCoord;

out vec4 color;

void main() {
    vec4 maskColor = texture(u_Mask, maskCoord);
    vec4 backgroundColor = texture(u_Background, maskCoord);
    vec4 contentColor = texture(u_Texture, vec2(texCoord.x, texCoord.y));
    vec4 finalColor = contentColor;
    finalColor.rgb = mix(backgroundColor.rgb, contentColor.rgb, maskColor.r);
    color = finalColor;
}