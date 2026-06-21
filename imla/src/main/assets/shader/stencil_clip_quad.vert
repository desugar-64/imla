#version 300 es
precision mediump float;

uniform mat4 u_ViewProjection;
uniform mat4 u_Transform;

layout (location = 0) in vec2 a_TexCoord;
layout (location = 1) in vec2 a_Position;

out vec2 texCoord;

void main() {
    texCoord = a_TexCoord;
    gl_Position = u_ViewProjection * u_Transform * vec4(a_Position, 0.0, 1.0);
}
