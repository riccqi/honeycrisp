// Screen-space reflection intersections. A depth buffer describes a thin visible surface,
// not a solid extending all the way behind every foreground pixel.
struct ReflectionHit {
    float2 uv;
    float confidence;
    float distance;
    float hidden; // 1 when the ray left the screen or its range behind something in front of it: what it reflects is unknown
};

static float3 reflection_eye(float4x4 vp) {
    // Solve clip.x = clip.y = clip.w = 0, including bob translation inside the projection.
    float3 x = float3(vp[0].x, vp[1].x, vp[2].x);
    float3 y = float3(vp[0].y, vp[1].y, vp[2].y);
    float3 w = float3(vp[0].w, vp[1].w, vp[2].w);
    float determinant = dot(x, cross(y, w));
    if (abs(determinant) < 1e-8) return float3(0.0); // orthographic camera: no finite perspective eye
    return -(cross(y, w) * vp[3].x + cross(w, x) * vp[3].y + cross(x, y) * vp[3].w)
           / determinant;
}

static float2 reflection_depth_coefficients(float4x4 vp) {
    // Perspective depth = A + B / clip.w. This also holds for finite reverse-Z and P * bob * view.
    float3 z = float3(vp[0].z, vp[1].z, vp[2].z);
    float3 w = float3(vp[0].w, vp[1].w, vp[2].w);
    if (dot(w, w) < 1e-8) return float2(0.0);
    float a = dot(z, w) / dot(w, w);
    return float2(a, vp[3].z - a * vp[3].w);
}

// xy screen position, z signed linear-depth gap (positive behind visible geometry), w valid projection.
// `clip` is the probed point in clip space: along a ray it is linear in the distance, so the march projects the ray
// once instead of every point.
// `shift` moves the lookup sideways on screen (see reflection_sidestep); the returned position includes it.
static float4 reflection_probe(depth2d<float> depth, float2 coefficients, float4 clip, float2 shift = float2(0.0)) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    if (clip.w <= 0.0) return float4(0.0);
    float3 ndc = clip.xyz / clip.w;
    float2 uv = ndc.xy * 0.5 + 0.5 + shift;
    if (any(uv <= 0.0) || any(uv >= 1.0) || ndc.z < 0.0 || ndc.z > 1.0) return float4(0.0);
    float sampled = depth.sample(nearest, uv);
    if (sampled <= 0.0) return float4(uv, -1e6, 1.0); // sky is empty, not a reflector
    float surfaceW = coefficients.y / (sampled - coefficients.x);
    return float4(uv, clip.w - surfaceW, 1.0);
}

// The march's budget: every probe is a dependent texture read, and water at a grazing angle traces in every pixel.
// Steps grow by REFLECTION_STEP_GROWTH, so REFLECTION_STEPS still reach 128 blocks from the shortest first step; a ray re-examines
// at most REFLECTION_CROSSINGS places where it passes behind something, and looks up to 4 << (REFLECTION_SIDESTEPS - 1)
// pixels to either side of an object in front for the scene beside it.
// Low quality (Quality.LOW) takes half the steps, growing faster so they reach as far, and looks behind only the first
// thing a ray passes behind, without searching beside it: at most about a quarter of High's depth reads.
#ifdef MC_QUALITY_LOW
constant int REFLECTION_STEPS = 12;
constant float REFLECTION_STEP_GROWTH = 1.6;
constant int REFLECTION_CROSSINGS = 1;
constant int REFLECTION_SIDESTEPS = 0;
#else
constant int REFLECTION_STEPS = 24;
constant float REFLECTION_STEP_GROWTH = 1.25;
constant int REFLECTION_CROSSINGS = 3;
constant int REFLECTION_SIDESTEPS = 6;
#endif

// A ray that passes behind something much nearer the camera (a block or a tree in front of far water) cannot see what
// that hides, although the water does reflect it. The scene just beside the occluder, at the same height on screen,
// stands in for the hidden part: this returns how far sideways (in uv) the nearest pixel lies that the ray, at clip
// depth `rayW`, is in front of, or 0 when there is none within reach.
static float reflection_sidestep(depth2d<float> depth, float2 coefficients, float2 uv, float rayW) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    float pixel = 1.0 / float(depth.get_width());
    for (int i = 0; i < REFLECTION_SIDESTEPS; i++) {
        float reach = float(4 << i) * pixel;
        for (int side = 0; side < 2; side++) {
            float x = uv.x + (side == 0 ? -reach : reach);
            if (x <= 0.0 || x >= 1.0) continue;
            float sampled = depth.sample(nearest, float2(x, uv.y));
            if (sampled <= 0.0 || rayW < coefficients.y / (sampled - coefficients.x)) {
                return x - uv.x;
            }
        }
    }
    return 0.0;
}

// How clear of anything nearer the camera than the water (at clip depth `waterW`) a hit at `uv` lies on screen: 0 with
// such an occluder within 2 pixels, rising to 1 with none within 16. A leaf canopy in front of far water lets rays
// reach the scene beyond through its see-through texels and past its ragged edge, a different outcome in every column
// of pixels: drawn down the water, those hits are streaks. Faded out, the water there shows the sky evenly.
static float reflection_clearance(depth2d<float> depth, float2 coefficients, float2 uv, float waterW) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    float2 pixel = 1.0 / float2(depth.get_width(), depth.get_height());
    const float2 directions[4] = { float2(-1, 0), float2(1, 0), float2(0, -1), float2(0, 1) };
    for (int i = 0; i < 4; i++) {
        for (int d = 0; d < 4; d++) {
            float sampled = depth.sample(nearest, uv + directions[d] * float(2 << i) * pixel);
            if (sampled > 0.0 && coefficients.y / (sampled - coefficients.x) < waterW) {
                return float(i) * 0.25;
            }
        }
    }
    return 1.0;
}

// Whether a ray that ended at `previous` (at clip `clip`) is hidden behind something, so that what it reflects is unknown.
// Something nearer the camera than the water does not count: a ray heading away from the camera passes behind it only
// on screen, and guessing dark water there would paint the shape of a nearby tree's canopy into distant water.
static float reflection_hidden(float4 previous, float4 clip, float4 clipOrigin, float4 clipStep) {
    if (previous.z < 0.0) return 0.0;
    return clipStep.w > 0.0 && clip.w - previous.z < clipOrigin.w ? 0.0 : 1.0;
}

static ReflectionHit trace_reflection(depth2d<float> depth, float4x4 vp, float3 origin, float3 direction) {
    ReflectionHit miss = {float2(0.0), 0.0, 0.0};
    float2 coefficients = reflection_depth_coefficients(vp);
    if (abs(coefficients.y) < 1e-6) return miss; // this tracer requires perspective depth
    float4 clipOrigin = vp * float4(origin, 1.0);
    float4 clipStep = vp * float4(direction, 0.0);
    float previousT = 0.05;
    float4 previous = reflection_probe(depth, coefficients, clipOrigin + clipStep * previousT);
    // An already-occluded starting point cannot establish a front-to-back surface crossing.
    if (previous.w == 0.0 || previous.z >= 0.0) return miss;
    // The first step is about two pixels long, and at least 0.3 blocks: on distant water a shorter step would probe
    // the same pixel again and again.
    float2 shift = float2(0.0);
    float stride = 0.3;
    if (clipOrigin.w > 1e-4) {
        float2 screenSpeed = (clipStep.xy * clipOrigin.w - clipOrigin.xy * clipStep.w) / (clipOrigin.w * clipOrigin.w)
                           * 0.5 * float2(depth.get_width(), depth.get_height());
        stride = clamp(2.0 / max(length(screenSpeed), 1e-4), 0.3, 3.0);
    }
    int refinements = 0;
    // What to show if the ray never finds a clean hit: nothing at first, then the first thing it passed behind.
    ReflectionHit fallback = miss;
    for (int i = 0; i < REFLECTION_STEPS; i++) {
        float t = min(previousT + stride, 128.0);
        float4 sample = reflection_probe(depth, coefficients, clipOrigin + clipStep * t, shift);
        if (sample.w == 0.0) {
            fallback.hidden = reflection_hidden(previous, clipOrigin + clipStep * previousT, clipOrigin, clipStep);
            return fallback;
        }
        if (sample.z >= 0.0 && previous.z < 0.0) {
            float low = previousT, high = t;
            float4 refined = sample;
            // Refine the crossing, then test its metric residual. At a foreground silhouette the
            // residual stays large even as the bracket shrinks: that is an occlusion, not a hit.
            for (int j = 0; j < 9; j++) {
                float middle = (low + high) * 0.5;
                float4 probe = reflection_probe(depth, coefficients, clipOrigin + clipStep * middle, shift);
                if (probe.w == 0.0) return fallback;
                if (probe.z >= 0.0) { high = middle; refined = probe; }
                else low = middle;
            }
            float4 hitClip = clipOrigin + clipStep * high;
            float thickness = clamp(0.04 + hitClip.w * 0.001, 0.04, 0.2);
            if (high < 0.1) return fallback;
            // Fade towards the screen edges, where the next ray would leave the screen and find nothing. A mirror image
            // on level water sits straight below its source, so hits near the left and right edges are still good data:
            // only a thin margin is faded there, or the reflection would stop well short of the sides of the view.
            // Looking down at water, the rays run off the top of the screen: a wide margin there lets the mirrored
            // scene thin out into the sky colour instead of ending at a hard line.
            const float2 margin = float2(0.015, 0.2);
            float2 edge = smoothstep(float2(0.0), margin, refined.xy) * smoothstep(float2(0.0), margin, 1.0 - refined.xy);
            float confidence = edge.x * edge.y * (1.0 - smoothstep(96.0, 128.0, high));
            if (refined.z <= thickness) {
                if (clipStep.w > 0.0) confidence *= reflection_clearance(depth, coefficients, refined.xy, clipOrigin.w);
                return ReflectionHit{refined.xy, confidence, high};
            }
            bool foreground = clipStep.w > 0.0 && hitClip.w - refined.z < clipOrigin.w;
            // Behind something nearer the camera than the water (the leaves of a tree the camera stands under): what the
            // ray would find past it through gaps in the leaves differs from column to column, so it reflects the sky.
            // This is decided only after the refinement: a step that overshoots a surface the ray really meets (the
            // ceiling of an overhang, seen nearer the camera than the water under it) is a hit, not an occluder.
            if (foreground && sample.z > 1.0) return fallback;
            // The ray passed behind something (a leaf canopy, a trunk seen from its far side). Keep marching to what
            // lies beyond it: giving up here shows the sky colour through every tree as bright speckle. If nothing
            // else is found, an occluder only a few blocks in front of the ray is a better guess than the sky; one far
            // in front (a pillar between the camera and the water) is still rejected, and so is anything nearer the
            // camera than the water itself: a ray heading away from the camera cannot reach it, so a tree standing
            // between the camera and the water never shows in it.
            if (fallback.confidence == 0.0 && !foreground) {
                fallback = ReflectionHit{refined.xy, confidence * (1.0 - smoothstep(1.0, 8.0, refined.z)), high};
            }
            if (++refinements >= REFLECTION_CROSSINGS) return fallback;
            // More than a block behind what is visible there: the ray is passing behind an object nearer the camera,
            // not through it. Carry on in the scene beside it.
            if (refined.z > 1.0) {
                float side = reflection_sidestep(depth, coefficients, refined.xy, hitClip.w);
                if (side != 0.0) {
                    shift.x += side;
                    sample = reflection_probe(depth, coefficients, clipOrigin + clipStep * t, shift);
                    if (sample.w == 0.0) return fallback;
                }
            }
        }
        if (t >= 128.0) break;
        previous = sample;
        previousT = t;
        stride *= REFLECTION_STEP_GROWTH;
    }
    fallback.hidden = reflection_hidden(previous, clipOrigin + clipStep * previousT, clipOrigin, clipStep);
    return fallback;
}
