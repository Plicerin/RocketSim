#version 120
// Simple metallic-roughness PBR approximation using compatibility builtins.
// - No normal or texture maps yet (uses gl_Normal)
// - Supports a directional light and simple ambient term
// - Uniforms provided for metallic, roughness, and a shadow factor placeholder

varying vec3 vNormal;
varying vec3 vPos;

uniform vec3 uLightDir;       // direction toward light
uniform vec3 uAmbient;        // ambient irradiance
uniform vec3 uBaseColor;      // albedo
uniform float uMetallic;      // 0..1
uniform float uRoughness;     // 0..1 (0 = smooth)
uniform float uShadowFactor;  // 0..1 (1 = lit, 0 = fully shadowed)

// Helper functions (approximations)
float saturate(float x) { return clamp(x, 0.0, 1.0); }

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (vec3(1.0) - F0) * pow(1.0 - cosTheta, 5.0);
}

float DistributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness*roughness;
    float a2 = a*a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH*NdotH;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    return a2 / (3.14159265 * denom * denom + 1e-6);
}

float GeometrySchlickGGX(float NdotV, float k) {
    return NdotV / (NdotV * (1.0 - k) + k);
}

float GeometrySmith(vec3 N, vec3 V, vec3 L, float k) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx1 = GeometrySchlickGGX(NdotV, k);
    float ggx2 = GeometrySchlickGGX(NdotL, k);
    return ggx1 * ggx2;
}

void main() {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vPos);
    vec3 L = normalize(uLightDir);
    vec3 H = normalize(V + L);

    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);

    // Material
    vec3 albedo = uBaseColor;
    float metallic = clamp(uMetallic, 0.0, 1.0);
    float roughness = clamp(uRoughness, 0.05, 1.0);

    // Fresnel reflectance at normal incidence
    vec3 F0 = mix(vec3(0.04), albedo, metallic);

    // Cook-Torrance BRDF
    float D = DistributionGGX(N, H, roughness);
    float k = (roughness + 1.0);
    k = (k*k) / 8.0; // UE4-inspired approximation
    float G = GeometrySmith(N, V, L, k);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 numerator = D * G * F;
    float denom = 4.0 * max(NdotV * NdotL, 0.001);
    vec3 specular = numerator / denom;

    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
    vec3 radiance = vec3(1.0) * uShadowFactor; // directional light color = white * shadow

    vec3 Lo = (kD * albedo / 3.14159265 + specular) * radiance * NdotL;

    // Ambient (approximate IBL diffuse)
    vec3 ambient = uAmbient * albedo;

    vec3 color = ambient + Lo;

    // Tone mapping / simple gamma
    color = color / (color + vec3(1.0));
    color = pow(color, vec3(1.0/2.2));

    gl_FragColor = vec4(color, 1.0);
}
