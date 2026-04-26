#version 120
// Simple pass-through vertex shader for iterative development.
// Uses built-in attributes (compatibility profile) so it can be activated
// on a wide range of systems while the renderer matures.

varying vec3 vNormal;
varying vec3 vPos;

void main() {
    vNormal = normalize(gl_NormalMatrix * gl_Normal);
    vPos = vec3(gl_ModelViewMatrix * gl_Vertex);
    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
}
