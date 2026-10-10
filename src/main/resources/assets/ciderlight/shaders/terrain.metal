// Ciderlight terrain shaders: vanilla terrain look plus directional sunlight and shadow mapping.
// Prepended by MetalShaders.java: IDX_GLOBALS, IDX_PROJECTION, IDX_TERRAIN, IDX_FOG, IDX_LIGHTMAP,
// IDX_ATLAS and, for cutout pipelines, ALPHA_CUTOUT.

#include <metal_stdlib>
using namespace metal;

#define IDX_FRAME 14
#define IDX_SHADOW 14
#define IDX_OPAQUE_COLOR 13
#define IDX_OPAQUE_DEPTH 12
#define IDX_SHADOW_COLOR 11
#define IDX_SHADOW_HISTORY 10
#define IDX_TRANSLUCENT_SHADOW_HISTORY 15
#define IDX_AO 9
#define IDX_CLOUD_SHADOW 8
#define IDX_FRAME_CONSTANTS 7
#define IDX_LIGHTNING_SHADOW 6

// std140 layouts of Minecraft's uniform blocks (packed vec3s keep the GLSL offsets).
struct Globals {
    packed_int3 CameraBlockPos;
    float GlintAlpha;
    packed_float3 CameraOffset;
    float GameTime;
    float2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

struct Projection {
    float4x4 ProjMat;
};

struct TerrainUniform {
    float4x4 ModelViewMat;
    int2 TextureSize;
};

struct Fog {
    float4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// Per-frame data written by MetalShaders.java.
#include "frame.metal"
#include "atmosphere.metal"
#include "ao.metal"
#ifdef MC_REFLECT
#include "reflection.metal"
#include "water_surface.metal"
#endif
#include "foliage.metal"

// Materials of special block sprites, from the sprite map (MetalShaders.updateSpriteMap).
#define SPRITE_METAL 1  // metal and gem blocks: a highlight in their own colour and a sky reflection
#define SPRITE_ICE 2
#define SPRITE_FLAME 3  // all flame: fire, campfire fire
#define SPRITE_GLOW 4   // has a flame in it: torches, lanterns, lit campfire logs
#define SPRITE_WATER_GLASS 5 // water's side against glass or leaves (vanilla's overlay texture)

// Which special sprite this atlas texel belongs to (SPRITE_*, 0 for none): one lookup in the sprite map.
static int sprite_kind(const device uchar *spriteMap, float2 uv) {
    return sprite_map_cell(spriteMap, uv) >> 4;
}

#ifdef ALPHA_CUTOUT
// How much of this texel is flame: all of a fire sprite, or the bright core of a torch, lantern or lit campfire log
// (their sticks, frames and bark stay below the threshold).
static float flame_emission(const device uchar *spriteMap, float2 uv, float3 texel) {
    int kind = sprite_kind(spriteMap, uv);
    if (kind == SPRITE_FLAME) {
        return 1.0;
    }
    return kind == SPRITE_GLOW ? smoothstep(0.78, 0.9, max(texel.r, max(texel.g, texel.b))) : 0.0;
}
#endif

struct VertexIn {
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float2 UV0 [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
    int3 ChunkPosition [[attribute(4)]];
    float ChunkVisibility [[attribute(5)]];
};

struct VertexOut {
    float4 position [[position]];
    float sphericalDistance;
    float cylindricalDistance;
    float4 color;
    float2 uv0;
    float2 light;      // block, sky lightmap coordinates (0..240)
    float chunkVisibility;
    float3 worldPos;   // camera-relative
};

static float3 camera_relative(VertexIn in, constant Globals &g) {
    return in.Position + float3(in.ChunkPosition - int3(g.CameraBlockPos)) + float3(g.CameraOffset);
}

// Camera-relative position including the wind sway of leaves and plants (identical in the main and shadow passes).
static float3 swayed_position(VertexIn in, constant Globals &g, constant FrameData &frame, uint vertexId, uint baseVertex,
                              const device uchar *raw, const device uchar *foliage) {
    float3 pos = camera_relative(in, g);
    return pos + foliage_offset(in.ChunkPosition, in.Position, length(pos), in.UV0, float(in.UV2.y), vertexId, baseVertex, raw,
                                foliage, frame);
}

vertex VertexOut terrain_vertex(VertexIn in [[stage_in]],
                                constant Globals &globals [[buffer(IDX_GLOBALS)]],
                                constant Projection &proj [[buffer(IDX_PROJECTION)]],
                                constant TerrainUniform &terrain [[buffer(IDX_TERRAIN)]],
                                constant FrameData &frame [[buffer(IDX_FRAME)]],
                                const device uchar *rawVertices [[buffer(IDX_RAW_VERTICES)]],
                                const device uchar *foliage [[buffer(IDX_FOLIAGE)]],
                                uint vertexId [[vertex_id]],
                                uint baseVertex [[base_vertex]]) {
    VertexOut out;
    float3 pos = swayed_position(in, globals, frame, vertexId, baseVertex, rawVertices, foliage);
    out.position = proj.ProjMat * terrain.ModelViewMat * float4(pos, 1.0);
    out.position.y = -out.position.y; // match the backend's GL-style render target layout
    out.sphericalDistance = length(pos);
    out.cylindricalDistance = max(length(pos.xz), abs(pos.y));
    out.color = in.Color;
#if defined(MC_WAVING_DEBUG) && defined(MC_VERTEX_STRIDE)
    out.color.rgb = foliage_debug_color(in.UV0, vertexId, baseVertex, rawVertices, foliage);
#endif
    out.uv0 = in.UV0;
    out.light = float2(in.UV2);
    float dist = length(pos);
    out.chunkVisibility = mix(1.0, in.ChunkVisibility, clamp((dist - 16.0) / 16.0, 0.0, 1.0));
    out.worldPos = pos;
    return out;
}

// --- texture sampling (same filtering behaviour as vanilla: crisp texels, RGSS when minified) ---

static float4 sample_nearest(texture2d<float> tex, sampler s, float2 uv, float2 pixelSize, float2 du, float2 dv, float2 texelScreenSize) {
    float2 texelCoords = uv / pixelSize;
    float2 center = round(texelCoords) - 0.5;
    float2 offset = texelCoords - center;
    offset = clamp((offset - 0.5) * pixelSize / texelScreenSize + 0.5, 0.0, 1.0);
    return tex.sample(s, (center + offset) * pixelSize, gradient2d(du, dv));
}

static float4 sample_albedo(texture2d<float> tex, sampler s, float2 uv, float2 pixelSize, bool rgss) {
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = sqrt(du * du + dv * dv);
    float4 nearest = sample_nearest(tex, s, uv, pixelSize, du, dv, texelScreenSize);
    if (!rgss) {
        return nearest;
    }
    float maxTexel = max(texelScreenSize.x, texelScreenSize.y);
    float minPixel = min(pixelSize.x, pixelSize.y);
    float blend = smoothstep(minPixel, minPixel * 2.0, maxTexel);
    if (blend <= 0.0) {
        return nearest;
    }
    float effective = sqrt(min(length(du), length(dv)) * max(length(du), length(dv)));
    float mip = max(0.0, log2(effective / minPixel));
    float lo = floor(mip);
    const float2 offsets[4] = {float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125)};
    float4 a = 0.0, b = 0.0;
    for (int i = 0; i < 4; i++) {
        float2 p = uv + offsets[i] * pixelSize;
        a += tex.sample(s, p, level(lo));
        b += tex.sample(s, p, level(lo + 1.0));
    }
    return mix(nearest, mix(a, b, fract(mip)) * 0.25, blend);
}

static float4 lightmap(texture2d<float> lm, sampler s, float2 uv) {
    return lm.sample(s, clamp(uv / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0)));
}

static float fog_value(float d, float start, float end) {
    return d <= start ? 0.0 : (d >= end ? 1.0 : (d - start) / (end - start));
}

// --- sunlight and shadows ---

#include "shadow.metal"

fragment float4 terrain_fragment(VertexOut in [[stage_in]],
                                 constant Globals &globals [[buffer(IDX_GLOBALS)]],
                                 constant Projection &proj [[buffer(IDX_PROJECTION)]],
                                 constant TerrainUniform &terrain [[buffer(IDX_TERRAIN)]],
                                 constant Fog &fog [[buffer(IDX_FOG)]],
                                 constant FrameData &frame [[buffer(IDX_FRAME)]],
                                 const device uchar *spriteMap [[buffer(IDX_FOLIAGE)]],
                                 texture2d<float> atlas [[texture(IDX_ATLAS)]],
                                 sampler atlasSampler [[sampler(IDX_ATLAS)]],
                                 texture2d<float> lightTex [[texture(IDX_LIGHTMAP)]],
                                 sampler lightSampler [[sampler(IDX_LIGHTMAP)]],
                                 depth2d<float> shadowMap [[texture(IDX_SHADOW)]],
                                 sampler shadowSampler [[sampler(IDX_SHADOW)]],
                                 texture2d<float> shadowColor [[texture(IDX_SHADOW_COLOR)]],
                                 texture2d<float> shadowHistory [[texture(IDX_SHADOW_HISTORY)]],
                                 texture2d<float> ambientOcclusion [[texture(IDX_AO)]],
                                 texture2d<float> cloudShadow [[texture(IDX_CLOUD_SHADOW)]],
                                 texture2d<float> frameConstants [[texture(IDX_FRAME_CONSTANTS)]],
                                 depth2d<float> lightningShadow [[texture(IDX_LIGHTNING_SHADOW)]],
                                 texture2d<float> opaqueColor [[texture(IDX_OPAQUE_COLOR)]],
                                 depth2d<float> opaqueDepth [[texture(IDX_OPAQUE_DEPTH)]]
#ifdef MC_REFLECT
                                 // Translucent terrain blends in the shader (Apple GPU framebuffer fetch), so
                                 // water can replace what is behind it with a refracted, absorbed view.
                                 , float4 behind [[color(0)]]
                                 // The shadow history of the translucent surface itself; shadowHistory holds the ground behind it.
                                 , texture2d<float> translucentShadowHistory [[texture(IDX_TRANSLUCENT_SHADOW_HISTORY)]]
#endif
                                 ) {
    // Derivatives must be taken in uniform control flow, before any discard or branch.
    float3 dpdx = dfdx(in.worldPos), dpdy = dfdy(in.worldPos);
    float3 faceNormal = normalize(cross(dpdx, dpdy));
    float footprint = max(length(dpdx), length(dpdy)); // world size of one pixel
    float2 distanceGradient = float2(dfdx(length(in.worldPos)), dfdy(length(in.worldPos)));
    float4 texel = sample_albedo(atlas, atlasSampler, in.uv0, 1.0 / float2(terrain.TextureSize), globals.UseRgss == 1);
    float4 albedo = texel * in.color;
#ifdef ALPHA_CUTOUT
    if (albedo.a < ALPHA_CUTOUT) {
        discard_fragment();
    }
#endif

    // --- lighting: rebuilt from the two lightmap coordinates rather than vanilla's blended texture ---
    // The darkest lightmap cell carries the brightness option, night vision and the darkness effect; the sky-only
    // column carries the time of day. Block light is shaped and coloured here, the way shader packs do it.
    float3 floorL = lightmap(lightTex, lightSampler, float2(0.0)).rgb;
    float3 skyOnly = lightmap(lightTex, lightSampler, float2(0.0, in.light.y)).rgb;
    float3 skyTerm = max(skyOnly - floorL, 0.0);
    float blockL = saturate(in.light.x / 240.0);
    float skyL = saturate(in.light.y / 240.0);

    float lightStrength = frame.sunDir.w;
    float3 direct = float3(0.0);
    float3 glisten = float3(0.0);
    float3 sunVis = float3(0.0);
    int kind = sprite_kind(spriteMap, in.uv0);
    bool metal = kind == SPRITE_METAL;
    // Metals reflect in their own colour (Schlick with F0 = albedo), turning white only at grazing angles.
    float3 V = normalize(in.worldPos);
    float3 viewN = dot(faceNormal, V) > 0.0 ? -faceNormal : faceNormal;
    // A torch or other light source in a player's hand lights the ground around them.
    blockL = max(blockL, held_light(frame, in.worldPos, viewN));

    // In the Overworld vanilla's fixed per-face shading (baked into the vertex colour) is replaced by light that knows
    // where the sun is: a face turned to the sun is lit by it whichever way it points, and only the sky light keeps a
    // gentle up-to-down falloff. Elsewhere vanilla's shading stays.
    float faceShade = 1.0;
    if (frame.solarDir.w > 0.5) {
        if (!sprite_unshaded(spriteMap, in.uv0)) {
            albedo.rgb /= vanilla_face_shade(viewN);
        }
        faceShade = sky_face_shade(viewN);
    }

    // Sky light: blue, from the whole sky, in the shade and in the sun alike. Sun (or moon) light: warm, direct, and
    // about two and a half times as bright, so sunlit faces stand clearly apart from shaded ones.
    float3 skyLight = float3(1.0);
    float3 sunLight = float3(0.0);
    if (lightStrength > 0.0 && in.light.y > 0.0) {
        float3 n = viewN;
        float3 lightDir = frame.sunDir.xyz;
        float ndotl = saturate(dot(n, lightDir));
        // A low sun grazes the ground. Lit strictly by the angle, everything flat would go dim well before sunset,
        // so around the golden hour surfaces take more of the light than their angle to it gives them.
        float facing = pow(ndotl, mix(1.0, 0.65, frame.airNear.z));
#ifdef ALPHA_CUTOUT
        // Foliage (biome-tinted cutouts: leaves, grass, vines) is thin, so sunlight passes through it: lit evenly
        // from any side, taking only the light that reaches it.
        // Crossed plants (flowers, saplings, dead bushes) are just as thin, whatever their tint.
        // A grass block is tinted too but solid: its top and the green fringe on its sides are lit like the dirt, at
        // any distance. (Lit evenly far away, the fringe drew a bright line along every block of a distant hill.)
        float tintSpread = max(in.color.r, max(in.color.g, in.color.b)) - min(in.color.r, min(in.color.g, in.color.b));
        bool foliage = tintSpread > 0.08 && foliage_kind(spriteMap, in.uv0) != FOLIAGE_NONE;
        bool crossed = abs(faceNormal.y) < 0.3 && abs(abs(faceNormal.x) - abs(faceNormal.z)) < 0.35;
        if (foliage || crossed) {
            facing = 0.85;
            ndotl = 1.0;
        }
#endif
        float3 vis = ndotl > 0.0 ? shadow_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudShadow, in.worldPos, n, ndotl) : float3(0.0);
        if (ndotl > 0.0 && frame.params.w > 0.5) {
#ifdef MC_REFLECT
            vis = shadow_temporal(frame, translucentShadowHistory, in.worldPos, vis, distanceGradient * 2.0); // half-resolution history
#else
            vis = shadow_temporal(frame, shadowHistory, in.worldPos, vis, distanceGradient * 2.0); // half-resolution history
#endif
        }
        direct = facing * vis;
        sunVis = vis;
        // Metals: a sharp sun highlight that flashes across a face as the view angle changes.
        if (metal) {
            float3 h = normalize(lightDir - V);
            float ndoth = saturate(dot(n, h));
            glisten = frame.sunColor.rgb * vis * (pow(ndoth, 64.0) * 1.0 + pow(ndoth, 8.0) * 0.12) * lightStrength;
            glisten *= mix(albedo.rgb, float3(1.0), pow(1.0 - saturate(dot(-V, h)), 5.0)) * 1.2;
        }
        // The sun crosses the sky from east to west, so east and west faces catch it most squarely of the walls:
        // a little extra keeps a lit wall from reading as merely grey.
        float sideCatch = 1.0 + 0.3 * n.x * n.x;
        // Sunlit ground throws warm light back up onto walls and overhangs, so a wall in its own shadow at noon is not
        // lit by the blue sky alone.
        float3 bounce = frame.surfaceSun.rgb * (0.26 * (0.5 - 0.5 * n.y) * saturate(lightDir.y * 2.0));
        skyLight = mix(float3(1.0), frame.surfaceAmbient.rgb + bounce, lightStrength);
        sunLight = frame.surfaceSun.rgb * direct * (lightStrength * sideCatch);
#ifdef MC_DEBUG_SHADOWS
        // Left half: N.L, right half: shadow visibility (red = facing away from the light).
        if (in.position.x < globals.ScreenSize.x * 0.5) {
            return float4(float3(ndotl), 1.0);
        }
        return ndotl > 0.0 ? float4(vis, 1.0) : float4(0.6, 0.0, 0.0, 1.0);
#endif
    }
    float3 skyScale = skyTerm * frame.lightParams.y;
    float3 skyAmbient = skyScale * skyLight * faceShade;
    float3 sky = skyAmbient + skyScale * sunLight;

    // Block light (torch_light), washed out under direct daylight.
    float3 blockTerm = float3(1.0, 0.66, 0.30) * torch_light(blockL) * faceShade;
    // Under open daylight block light mostly disappears, as it does outdoors in real life.
    float daylight = skyL * skyL * frame.lightParams.x;
    blockTerm *= 1.0 - 0.8 * daylight * (0.6 + 0.4 * dot(direct, float3(0.333)));

    // A little cool ambient so caves and night shadows never go fully black (scales with the brightness option).
    float3 ambient = floorL * float3(1.0, 1.05, 1.2) * faceShade;
    float3 light = ambient + sky + blockTerm;
    // A lightning bolt lights what is open to the sky around it.
    if (frame.lightning.w > 0.0 && in.light.y > 0.0) {
        float3 towardsCamera = dot(faceNormal, -in.worldPos) < 0.0 ? -faceNormal : faceNormal;
        float reached = lightning_shadow(frame, lightningShadow, shadowSampler, in.worldPos, towardsCamera);
        light += (lightning_light(frame, in.worldPos, towardsCamera) * reached + lightning_sky_light(frame)) * smoothstep(0.2, 0.9, skyL);
    }

    // Metal blocks mirror the sky (its smooth gradient stands in for a blurred reflection), taking some of the place
    // of their diffuse light, more so at grazing angles. Below the horizon, and where little sky is open above them,
    // they reflect a dim guess at their surroundings instead.
    float diffuseShare = 1.0;
    float3 reflected = float3(0.0);
    if (metal) {
        float3 R = reflect(V, viewN);
        float3 skyR = frame.solarDir.w > 0.5 ? atmosphere_sky(frame, R) : fog.FogColor.rgb;
        float3 env = mix(light * 0.3, skyR, smoothstep(-0.15, 0.15, R.y) * smoothstep(0.3, 0.95, skyL));
        float grazing = pow(1.0 - saturate(dot(-V, viewN)), 5.0);
        float strength = mix(0.3, 0.75, grazing);
        diffuseShare = 1.0 - strength;
        reflected = env * mix(albedo.rgb, float3(1.0), grazing) * strength;
    }
    float4 color = float4(albedo.rgb * light * diffuseShare + reflected + glisten, albedo.a);
    // Screen-space ambient occlusion is applied by the composite pass, from the same frame's depth. It darkens only
    // the ambient light: the sky light reaching shadowed surfaces and the cave floor, and torch light only slightly.
    // This is the share of the pixel's light it can remove; it is written to alpha at the end.
    const float3 luma = float3(0.2126, 0.7152, 0.0722);
    float3 occludable = ambient + skyAmbient + blockTerm * 0.3;
    // A reflection of the sky is ambient light too: corners and recesses see less of it.
    float aoShare = saturate(dot(albedo.rgb * occludable * diffuseShare + reflected, luma) / max(dot(color.rgb, luma), 1e-5));
    // Sunlit sand, snow and pale stone go above 1: roll them off instead of clipping them flat.
    color.rgb = highlight_rolloff(color.rgb);
#ifdef ALPHA_CUTOUT
    // Flames are light sources: drawn at full brightness, unshaded, whatever the light around them. Every emitter
    // sits in its own block light, so the sprite lookup is skipped everywhere else.
    if (in.light.x >= 112.0) {
        color.rgb = mix(color.rgb, texel.rgb * 1.25, flame_emission(spriteMap, in.uv0, texel.rgb));
    }
#endif

#ifdef MC_REFLECT
    // Water seen from above or from the side is shaded completely here: wave normals, refraction of the opaque scene
    // behind it, absorption by the water's thickness, shoreline foam, screen-space reflections and the sun's glint.
    // Top, sides and slopes of flowing water all take this path, so they match where they meet.
    // Ice and glass keep their vanilla look and are blended as before.
    bool waterSurface = false;
    {
        float3 n = faceNormal;
        if (dot(n, -in.worldPos) < 0.0) {
            n = -n;
        }
        // Water carries the biome water tint in its vertex colour; ice and glass are untinted (grey AO shading only).
        float tint = max(in.color.r, max(in.color.g, in.color.b)) - min(in.color.r, min(in.color.g, in.color.b));
        bool water = tint > 0.08;
        // Ice is drawn nearly opaque so the water underneath (and its reflections) hardly show through it.
        if (!water && n.y > 0.7 && sprite_kind(spriteMap, in.uv0) == SPRITE_ICE) {
            color.a = max(color.a, 0.94);
        }
        // Only the underside, seen from below, keeps the vanilla look.
        if (water && n.y > -0.3) {
            waterSurface = true;
            constexpr sampler nearestClamp(filter::nearest, address::clamp_to_edge);
            constexpr sampler linearClamp(filter::linear, address::clamp_to_edge);
            float3 absPos = in.worldPos - float3(globals.CameraOffset) + float3(globals.CameraBlockPos);
            float t = frame.params.x;
            // Overcast: the storm's clouds dim the sun's glint everywhere under them, dry biomes included.
            float overcast = saturate(frame.params.y) * smoothstep(0.7, 0.95, skyL);
            // Rain only roughens water that is open to the sky, in a biome where it rains rather than snows or stays dry.
            float rain = overcast > 0.0 ? overcast * rain_exposure(frame, absPos.xz) : 0.0;
            float4x4 vp = proj.ProjMat * terrain.ModelViewMat;
            float3 eye = reflection_eye(vp);
            float viewDist = max(length(in.worldPos - eye), 1e-3);
            float3 V = (in.worldPos - eye) / viewDist;
            float cosView = saturate(dot(-V, n));

            // --- wave normal ---
            WaterFace waves = water_face_waves(absPos, n, t, footprint, rain);
            // Flatter at grazing angles, where steep normals would point into the water.
            float3 nn = normalize(n + waves.tilt * (0.3 + 0.7 * cosView));

            // --- thickness of water behind this pixel, from the opaque depth snapshot ---
            float2 depthCoef = reflection_depth_coefficients(vp);
            bool perspective = abs(depthCoef.y) > 1e-6;
            float4 clip = vp * float4(in.worldPos, 1.0);
            float waterW = max(clip.w, 1e-4);
            float2 uv0 = clip.xy / waterW * 0.5 + 0.5;
            float sceneDepth = opaqueDepth.sample(nearestClamp, uv0);
            float behindW = sceneDepth <= 0.0 || !perspective ? 1e5 : depthCoef.y / (sceneDepth - depthCoef.x);
            float thickness0 = max(behindW - waterW, 0.0) * viewDist / waterW;

            // --- refraction: bend the view by the waves only (flat water stays undistorted) ---
            float reach = min(thickness0, 2.5);
            float3 bend = refract(V, nn, 0.75) - refract(V, n, 0.75);
            float3 target = in.worldPos + V * reach + bend * (reach * 3.0);
            float4 targetClip = vp * float4(target, 1.0);
            float2 uvR = targetClip.w > 1e-4 ? targetClip.xy / targetClip.w * 0.5 + 0.5 : uv0;
            float thickness = thickness0;
            if (perspective && all(uvR > 0.002) && all(uvR < 0.998)) {
                float d = opaqueDepth.sample(nearestClamp, uvR);
                float w = d <= 0.0 ? 1e5 : depthCoef.y / (d - depthCoef.x);
                // Something in front of the water (a pillar, a mob, the shore) must not be pulled into it.
                if (w > waterW + 0.05) {
                    thickness = (w - waterW) * viewDist / waterW;
                } else {
                    uvR = uv0;
                }
            } else {
                uvR = uv0;
            }
            float3 seen = opaqueColor.sample(linearClamp, uvR).rgb;
            // A translucent surface already drawn behind this one (glass, another water face) was not in the snapshot.
            float3 snapshot = opaqueColor.read(uint2(in.position.xy)).rgb;
            float layered = smoothstep(0.015, 0.06, length(behind.rgb - snapshot));
            seen = mix(seen, behind.rgb, layered);

            // --- absorption and scattering by the thickness of water (Beer–Lambert) ---
            float th = min(thickness, 96.0);
            // Behind a side face the depth snapshot shows where the view ends, which is where the water ends only if it
            // ends on something solid: the bed of a stream seen edge-on, the cliff behind a waterfall. Then that depth
            // counts, though never less than two blocks, or the foot of a stream's side, where the block it runs over
            // comes right up to the face, would go clear. If the water ends first, at the far side of a sheet (vanilla
            // draws it from within too; shaded water writes alpha 0), at anything else drawn over the snapshot, or before
            // the open sky, the snapshot overstates it (a waterfall's lip went dark navy): light through the side then
            // crosses a sheet's two blocks, and the face it leaves the water by adds none. A side against glass or leaves
            // looks into a pool or tank, whose depth always counts.
            float side = 1.0 - smoothstep(0.3, 0.7, n.y);
            bool leaving = false;
            if (abs(n.y) < 0.2) {
                // Vanilla insets each side face a thousandth of a block into its own block, so where the face lies in
                // the block grid tells which side of it the water is on.
                float3 grid = in.worldPos - float3(globals.CameraOffset);
                bool alongX = abs(n.x) > abs(n.z);
                float waterSide = fract(alongX ? grid.x : grid.z) < 0.5 ? 1.0 : -1.0;
                leaving = (alongX ? V.x : V.z) * waterSide < 0.0;
            }
            bool endsFirst = (behind.a < 0.002 || layered > 0.5 || behindW > 1e4) && kind != SPRITE_WATER_GLASS;
            th = mix(th, leaving ? 0.0 : (endsFirst ? 2.0 : max(th, 2.0)), side);
            float3 transmittance = exp(-water_absorption(in.color.rgb) * th);
            float murk = 1.0 - exp(-th * 0.075);
            // Scattered light from deep water: the biome hue pulled towards teal, with only a trace of the vanilla
            // texture's pattern so the surface reads as water rather than as a tiled sprite.
            float texDetail = mix(1.0, dot(texel.rgb, float3(0.3333)) / 0.62, 0.2);
            float3 deep = sqrt(saturate(in.color.rgb)) * float3(0.13, 0.31, 0.38) * texDetail * light;
            deep = mix(deep, float3(dot(deep, float3(0.2126, 0.7152, 0.0722))), 0.35); // a grey-blue, not a saturated teal
            float3 under = mix(seen * transmittance, deep, murk);

            // --- shoreline foam where the water is only a sliver deep ---
            float vertical = thickness0 * saturate(-V.y);
            // Level water only: a waterfall is always thin against the cliff it runs down.
            float edge = (1.0 - smoothstep(0.0, 0.3, vertical)) * (behindW < 1e4 ? 1.0 : 0.0) * smoothstep(0.6, 0.85, n.y);
            float foamNoise = water_noise(absPos.xz * 2.3 + float2(t * 0.21, -t * 0.13)).x * 0.6
                            + water_noise(absPos.xz * 5.7 - float2(t * 0.37, t * 0.29)).x * 0.4;
            float foam = edge * edge * smoothstep(0.4, 0.95, foamNoise + edge * 0.3) * 0.32;

            // --- reflection ---
            float3 R = reflect(V, nn);
            float rn = dot(R, n);
            if (rn < 0.03) {
                R = normalize(R + n * (0.03 - rn));
            }
            float3 sky = frame.solarDir.w > 0.5 ? atmosphere_sky(frame, R) : fog.FogColor.rgb;
            // Covered water (low sky light) must not mirror a bright sky it cannot see.
            float3 reflColor = mix(deep, sky, smoothstep(0.3, 0.95, skyL));
            // Schlick's curve with a softer exponent than 5: a little more mirror at the shallow angles water is
            // mostly seen at, so the banks and sky show in it as they do on a calm river.
            float mirror = min(0.02 + 0.98 * pow(1.0 - saturate(dot(-V, nn)), 3.5), 0.92);
            // Seen from steeply above, water reflects about 2% of what is over it: whether that is the traced scene
            // or the sky cannot be told apart, so the ray is only traced where the reflection shows. The hand-over is
            // spread over a wide range of angles: a narrow one shows as a line across the water when looking down.
            // Low quality traces only the shallower angles, where the reflection is brightest.
#if defined(MC_NO_WATER_TRACE)
            float traced = 0.0;
#elif defined(MC_QUALITY_LOW)
            float traced = smoothstep(0.04, 0.12, mirror);
#else
            float traced = smoothstep(0.025, 0.09, mirror);
#endif
            if (traced > 0.0) {
                ReflectionHit hit = trace_reflection(opaqueDepth, vp, in.worldPos + n * 0.03, R);
                // A ray lost behind something nearer the camera (a tree in front of far water) reflects what that
                // hides. Bright sky there stands out against the terrain mirrored around it, and a shallow ray most
                // often ends in terrain, so the guess for those is the dark water colour rather than the sky. Unless
                // the ray passed behind something on the way (too far behind it to count as its reflection): that
                // thing's colour is the better guess, being what the neighbouring rays meet. A lone ray that slips
                // behind a tree's trunk or leaves while its neighbours hit them was otherwise a black speck.
                float3 lostColor = hit.distance > 0.0 ? opaqueColor.sample(nearestClamp, hit.uv).rgb : deep;
                reflColor = mix(reflColor, lostColor, hit.hidden * traced * (1.0 - smoothstep(0.1, 0.5, R.y)));
                if (hit.confidence > 0.0) {
                    // Fetch the same texel whose depth was validated. Linear colour filtering could
                    // pull an unrelated foreground neighbour back into the reflection at silhouettes.
                    reflColor = mix(reflColor, opaqueColor.sample(nearestClamp, hit.uv).rgb, hit.confidence * traced);
                }
            }
            float fresnel = mirror * (1.0 - foam);

            // --- sun / moon glint, only where the surface is in direct light ---
            float3 glint = float3(0.0);
            if (lightStrength > 0.0) {
                float3 L = frame.sunDir.xyz;
                bool moon = frame.lightParams.z > 0.5;
                // Detail filtered out at a distance becomes roughness, so far glitter spreads instead of vanishing.
                float roughness = sqrt(0.0025 + 2.0 * waves.variance) + 0.12 * rain;
                glint = water_ggx(nn, -V, L, roughness, 0.02) * frame.sunColor.rgb * sunVis * lightStrength
                      * smoothstep(0.0, 0.06, L.y) * (moon ? 0.35 : 0.8) * (1.0 - 0.75 * overcast) * (1.0 - foam);
            }

            float3 foamColor = light * float3(0.86, 0.9, 0.92) + frame.sunColor.rgb * sunVis * lightStrength * 0.15;
            color.rgb = mix(mix(under, reflColor, fresnel), foamColor, foam) + glint;
            color.a = 1.0;
        }
    }
#endif

    float f = max(fog_value(in.sphericalDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd),
                  fog_value(in.cylindricalDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
    // Only terrain that is fading in or near the edge of the render distance takes the sky's colour.
    if (in.chunkVisibility < 1.0 || f > 0.0) {
        float3 distantFog = frame.solarDir.w > 0.5 ? atmosphere_sky(frame, normalize(in.worldPos)) : fog.FogColor.rgb;
        color = mix(float4(distantFog, fog.FogColor.a * color.a), color, in.chunkVisibility);
        color.rgb = mix(color.rgb, distantFog, f * fog.FogColor.a);
    }
    aoShare *= in.chunkVisibility * (1.0 - f * fog.FogColor.a); // fog is not occluded
#ifdef MC_REFLECT
    // Programmable blending: identical to vanilla's SRC_ALPHA / ONE_MINUS_SRC_ALPHA for glass and ice, whose alpha
    // stays the composite's marker. Shaded water already contains what lies behind it and is marked as lit (0).
    color = saturate(color);
    return float4(mix(behind.rgb, color.rgb, color.a), waterSurface ? 0.0 : color.a);
#else
    // Alpha below 0.5 marks terrain as already lit for the composite pass.
    color.a = aoShare * TERRAIN_AO_ALPHA;
#ifdef ALPHA_CUTOUT
    // Crossed quads (grass, flowers, ferns) have diagonal faces. They take no ambient occlusion and cast none.
    if (abs(faceNormal.y) < 0.3 && abs(abs(faceNormal.x) - abs(faceNormal.z)) < 0.35) {
        color.a = AO_THIN_PLANT_ALPHA;
    }
#endif
    return color;
#endif
}

// --- shadow map pass ---

struct ShadowOut {
    float4 position [[position]];
    float2 mapUV;      // where this point falls in the shadow map
    float2 uv0;
    float3 color;      // vertex colour (biome tint)
    float tintSpread;  // > 0.08 for biome-tinted blocks: foliage, water
};

vertex ShadowOut shadow_vertex(VertexIn in [[stage_in]],
                               constant Globals &globals [[buffer(IDX_GLOBALS)]],
                               constant FrameData &frame [[buffer(IDX_FRAME)]],
                               const device uchar *rawVertices [[buffer(IDX_RAW_VERTICES)]],
                               const device uchar *foliage [[buffer(IDX_FOLIAGE)]],
                               uint vertexId [[vertex_id]],
                               uint baseVertex [[base_vertex]]) {
    ShadowOut out;
    float3 pos = swayed_position(in, globals, frame, vertexId, baseVertex, rawVertices, foliage) + frame.camToAnchor.xyz;
    out.position = frame.shadowMat * float4(pos, 1.0);
    out.mapUV = float2(out.position.x * 0.5 + 0.5, 0.5 - out.position.y * 0.5);
    out.uv0 = in.UV0;
    out.color = in.Color.rgb;
    out.tintSpread = max(in.Color.r, max(in.Color.g, in.Color.b)) - min(in.Color.r, min(in.Color.g, in.Color.b));
    return out;
}

fragment void shadow_fragment_cutout(ShadowOut in [[stage_in]],
                                     texture2d<float> atlas [[texture(IDX_ATLAS)]],
                                     sampler atlasSampler [[sampler(IDX_ATLAS)]]) {
    if (atlas.sample(atlasSampler, in.uv0, level(0)).a < 0.5) {
        discard_fragment();
    }
    // Leaves and grass cast full shadows (their texture's holes already let light through), so the shadow of every
    // tuft and canopy shows crisply on the ground.
}

// Translucent blocks are drawn into a colour map multiplied together (blend dst * src): what fraction of the
// light, per channel, gets through stained glass, ice or water at that point. The colour map is smaller than the
// shadow map (a tint needs less detail than a shadow edge, and an ocean fills all of it every frame), so the pass has
// no depth attachment: translucent blocks behind an opaque caster are rejected against the shadow map here.
fragment float4 shadow_fragment_translucent(ShadowOut in [[stage_in]],
                                            texture2d<float> atlas [[texture(IDX_ATLAS)]],
                                            sampler atlasSampler [[sampler(IDX_ATLAS)]],
                                            depth2d<float> shadowMap [[texture(IDX_SHADOW)]]) {
    constexpr sampler nearestClamp(filter::nearest, address::clamp_to_edge);
    // This texel covers several of the shadow map's: it is hidden only if it is behind the caster in all of them.
    float4 casters = shadowMap.gather(nearestClamp, in.mapUV);
    if (in.position.z > max(max(casters.x, casters.y), max(casters.z, casters.w)) + 1e-4) {
        discard_fragment();
    }
    float4 albedo = atlas.sample(atlasSampler, in.uv0, level(0));
    if (albedo.a < 0.1) {
        discard_fragment();
    }
    bool water = in.tintSpread > 0.08;
    float3 transmission = material_transmission(albedo, in.color, water);
    // RGB multiplies transmission; MIN blending in alpha records the first translucent surface.
    return float4(transmission, in.position.z);
}
