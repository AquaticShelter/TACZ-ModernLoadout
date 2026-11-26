#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 BlurDir;
uniform float Radius;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 sum = vec4(0.0);
    float total = 0.0;
    float r = max(1.0, Radius);
    // Sample every 2nd pixel: half the texture fetches for the same blur extent. The menu sits under a dim layer,
    // so the sparser kernel is not visible, but the two full-resolution passes cost roughly half as much per frame.
    float n = floor(r * 0.5);
    for (float i = -n; i <= n; i += 1.0) {
        float o = i * 2.0;   // always symmetric around the centre, even for an odd Radius
        float w = 1.0 - abs(o) / (r + 1.0);   // triangular weights (cheap gaussian)
        vec2 uv = texCoord + BlurDir * (o / InSize);
        sum += texture(DiffuseSampler, uv) * w;
        total += w;
    }
    fragColor = vec4(sum.rgb / total, 1.0);
}
