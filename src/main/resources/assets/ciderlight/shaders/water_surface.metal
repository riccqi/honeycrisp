// Water surface seen from above: animated wave normals, sun glint, absorption and shoreline foam.
// The look was tuned after Complementary Unbound and Bliss (Chocapic13 edit); the code is an original Metal
// implementation of standard techniques (gradient value noise, GGX, Schlick Fresnel, Beer–Lambert).

static float water_hash(float2 p) {
    float3 q = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// Value noise with its analytic gradient: x value in [0, 1], yz d/dp.
static float3 water_noise(float2 p) {
    float2 i = floor(p), f = p - i;
    float2 u = f * f * (3.0 - 2.0 * f);
    float2 du = 6.0 * f * (1.0 - f);
    float a = water_hash(i), b = water_hash(i + float2(1.0, 0.0));
    float c = water_hash(i + float2(0.0, 1.0)), d = water_hash(i + float2(1.0, 1.0));
    float k = a - b - c + d;
    return float3(a + (b - a) * u.x + (c - a) * u.y + k * u.x * u.y,
                  du * (float2(b - a, c - a) + k * u.yx));
}

struct WaterWaves {
    float2 slope;     // world-space height gradient d(height)/d(xz)
    float variance;   // slope variance removed by distance filtering (added back as roughness)
};

constant int WATER_OCTAVES = 6;

// Layered, world-anchored waves. Each octave is smaller, faster and turned by the golden angle, so their
// interference never lines up into a visible grid. Octaves fade out before they are smaller than about
// four pixels (footprint = world size of one pixel), leaving distant water calm instead of aliased.
static WaterWaves water_waves(float2 world, float time, float footprint, float rain) {
    WaterWaves w = {float2(0.0), 0.0};
#ifdef MC_NO_WATER_WAVES
    // Water Waves off: a calm surface (water_face_waves still adds rain ripples). Calm water is still not a perfect
    // mirror: a little roughness keeps the sun's glint a soft patch instead of a hard, blinding point.
    w.variance = 0.0015;
    return w;
#endif
    const float2x2 turn = float2x2(float2(-0.737, 0.676), float2(-0.676, -0.737));
    float2x2 basis = float2x2(float2(1.0, 0.0), float2(0.0, 1.0));
    float frequency = 0.16;     // cycles per block: ~6 block swells down to ~0.25 block chop
    float weight = 0.115;       // slope contributed by this octave
    float speed = 0.22;
    for (int i = 0; i < WATER_OCTAVES; i++) {
        float wavelength = 1.0 / frequency;
        float keep = 1.0 - smoothstep(wavelength * 0.12, wavelength * 0.35, footprint);
        float octaveWeight = weight * (i >= 3 ? 1.0 + 1.6 * rain : 1.0 + 0.5 * rain);
        if (keep > 0.0) {
            float2 p = (basis * world) * frequency;
            p += float2(time * speed, time * speed * 0.37);
            float3 n = water_noise(p);
            // Second, counter-moving sample on a shifted lattice: waves evolve instead of sliding as a block.
            float3 m = water_noise(p * 1.07 + float2(17.3, -9.1) - float2(time * speed * 0.8, -time * speed * 0.55));
            float2 g = (n.yz + m.yz) * 0.5;
            // basis rotates world into noise space; its transpose brings the gradient back.
            w.slope += (transpose(basis) * g) * (octaveWeight * keep);
        }
        w.variance += octaveWeight * octaveWeight * (1.0 - keep * keep) * 0.35;
        basis = turn * basis;
        frequency *= 1.85;
        weight *= 0.8;
        speed *= 1.36;
    }
    return w;
}

// Rain drops: expanding rings in a jittered grid of cells, each cell on its own cycle.
static float2 water_rain_ripples(float2 world, float time, float footprint) {
    const float cell = 0.75;
    float fade = 1.0 - smoothstep(0.03, 0.09, footprint);
    if (fade <= 0.0) return float2(0.0);
    float2 base = floor(world / cell);
    float2 slope = float2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 id = base + float2(x, y);
            float h = water_hash(id * 1.37 + 3.1);
            float2 centre = (id + 0.2 + 0.6 * float2(h, water_hash(id + 11.7))) * cell;
            float age = fract(time * 0.85 + h * 7.0);
            float2 d = world - centre;
            float r = length(d);
            float ring = r - age * 0.55;
            float envelope = exp(-ring * ring * 220.0) * (1.0 - age) * (1.0 - age);
            slope += (r > 1e-4 ? d / r : float2(0.0)) * cos(ring * 34.0) * envelope;
        }
    }
    return slope * 0.32 * fade;
}

// GGX / Trowbridge–Reitz specular with Smith visibility, multiplied by N.L.
static float water_ggx(float3 n, float3 v, float3 l, float alpha, float f0) {
    float3 h = normalize(v + l);
    float ndoth = saturate(dot(n, h)), ndotl = saturate(dot(n, l)), ndotv = max(dot(n, v), 1e-3);
    float a2 = alpha * alpha;
    float denom = ndoth * ndoth * (a2 - 1.0) + 1.0;
    float d = a2 / (M_PI_F * denom * denom);
    float k = alpha * 0.5;
    float vis = 1.0 / ((ndotl * (1.0 - k) + k) * (ndotv * (1.0 - k) + k));
    float f = f0 + (1.0 - f0) * pow(1.0 - saturate(dot(h, v)), 5.0);
    return d * vis * f * ndotl * 0.25;
}

// Spectral absorption from the biome water colour: the dye's complementary wavelengths are absorbed first,
// so thin water stays clear and thick water turns towards the biome's blue-green.
static float3 water_absorption(float3 vertexColor) {
    float peak = max(vertexColor.r, max(vertexColor.g, vertexColor.b));
    float3 tint = clamp(vertexColor / max(peak, 1e-4), 0.05, 1.0);
    return (-log(tint) + float3(0.04, 0.03, 0.05)) * 0.2;
}

struct WaterFace {
    float3 tilt;      // world-space offset to add to the face normal (in the face's plane)
    float variance;   // as WaterWaves.variance
};

// Waves on a water face of any orientation. Each world plane the face leans towards contributes its own waves,
// weighted by how far the face leans, so a flowing slope or the side of a waterfall carries the same surface as
// the still water beside it without a seam. Waves on vertical sheets also run downwards, as falling water does.
// Rain ripples land on the level part only.
static WaterFace water_face_waves(float3 world, float3 n, float time, float footprint, float rain) {
    float3 w = pow(abs(n), float3(4.0));
    w /= w.x + w.y + w.z;
    WaterFace f = {float3(0.0), 0.0};
    if (w.y > 0.02) {
        WaterWaves top = water_waves(world.xz, time, footprint, rain);
        float2 g = top.slope;
        if (rain > 0.0) {
            g += water_rain_ripples(world.xz, time, footprint) * rain;
        }
        f.tilt += float3(-g.x, 0.0, -g.y) * w.y;
        f.variance += top.variance * w.y;
    }
    float fall = time * 1.6;
    if (w.x > 0.02) {
        WaterWaves side = water_waves(float2(world.z, world.y + fall), time, footprint, 0.0);
        f.tilt += float3(0.0, -side.slope.y, -side.slope.x) * w.x;
        f.variance += side.variance * w.x;
    }
    if (w.z > 0.02) {
        WaterWaves side = water_waves(float2(world.x, world.y + fall), time, footprint, 0.0);
        f.tilt += float3(-side.slope.x, -side.slope.y, 0.0) * w.z;
        f.variance += side.variance * w.z;
    }
    f.tilt -= n * dot(f.tilt, n);
    return f;
}
