# Ciderlight

A Fabric (and NeoForge, beta) mod for Minecraft Java **26.3** that adds a native **Apple Metal** rendering backend and a
built-in, Metal-native shader pipeline.

Download from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/ciderlight).

Minecraft 26.3 split its renderer into swappable backends (OpenGL and Vulkan). Ciderlight adds a third that talks to
Metal through a small Objective-C bridge (`src/main/native/ciderlight.m`) called via Java's FFM API. The game's GLSL
shaders are compiled to SPIR-V and translated to Metal Shading Language with SPIRV-Cross, so vanilla rendering,
resource packs and core-shader packs keep working.

## Performance

M1 Pro, 1708x960, render distance 16, vsync off, same jungle scene:

| Configuration                    | Median FPS | 1% low |
|----------------------------------|-----------:|-------:|
| Vanilla OpenGL                   |       95.4 |   26.5 |
| Ciderlight, shaders off          |      215.7 |  109.2 |
| Ciderlight, shaders on           |      191.9 |  126.5 |
| Ciderlight, shaders on, raining  |      160.9 |  103.1 |

Later optimisations raised shaders-on further (164–173 → 205–208 median FPS on another test save); the frame is
now CPU-limited. Reproduce with `./gradlew runClient -Pworld=<save> -Pbench=20` (add `-Pvanilla` or `-Pnoshaders`).

## Shaders

Written for this backend, not ported from an OptiFine/Iris pack, so they only run on Ciderlight.

- **Lighting**: sun-aware direct and sky light replacing vanilla's fixed face shading, warm torch falloff, full-bright flames.
- **Held light**: torches, lanterns and other light sources in hand light the area around the player (and other players).
- **Shadows**: real-time sun and moon shadows, including foliage, with coloured light through stained glass, ice and water.
- **Sky and atmosphere**: directional sky gradient, layered volumetric mist with light shafts and aerial perspective.
- **Ambient occlusion**: screen-space GTAO for soft contact shadows.
- **Waving foliage**: leaves, grass, crops and vines sway in a world-anchored wind, shadows included.
- **Clouds**: opaque, volume-shaded clouds lit by the sun's position.
- **Water**: animated waves, refraction, depth absorption, foam, sun glint, rain ripples and screen-space reflections.
- **Underwater**: volumetric light, absorption and caustics.
- **Composite**: sun rays, a gentle filmic grade and vignette.

Visual references: Complementary Shaders and Bliss (Chocapic13 edit). All Metal code is original.

### Settings

Open **Options > Video Settings > Ciderlight...** (under the graphics preset):

- **Shaders**: Off (Metal renderer, vanilla look), Low or High. A-series GPUs and the plain M1, M2, M3 and M4 default to
  Low (smaller shadow maps, fewer samples, lighter water reflections, world drawn at about two thirds resolution);
  everything else defaults to High.
- **Shadows**, **Waving Plants**, **Water Reflections**, **Water Waves**, **Ambient Occlusion**: each on by default. Shadows off also
  skips the shadow maps and light shafts, which is the biggest saving.
- **Render Scale**: the world's resolution, Auto (the quality's own) or 50 to 100%.

Shadows and Ambient Occlusion apply at once; everything else applies after a restart. All of it is saved in
`config/ciderlight.properties`.

JVM `-D` overrides: `ciderlight.quality=low|high`, `ciderlight.shaders=false`, `ciderlight.renderScale=<0.25–1>`,
`ciderlight.shadowSize`, `ciderlight.shadowDistance`, `ciderlight.fogDistance`, `ciderlight.waterReflections=false`, `ciderlight.waterWaves=false`,
`ciderlight.waving=false`, `ciderlight.ao=false`, `ciderlight.shadows=false`, `ciderlight.framePacing=true`, `ciderlight.debug=true`,
`ciderlight.disable=true` (fall back to vanilla backends). An override wins over the settings page, which greys that
option out.

## Installing

1. Install Fabric Loader for Minecraft 26.3 (https://fabricmc.net/use/installer/).
2. Download the latest Ciderlight JAR from [Releases](https://github.com/riccqi/ciderlight/releases) and copy it into `~/Library/Application Support/minecraft/mods/`.
3. Launch the Fabric profile. Ciderlight is client-side only, so it works on any server.

For NeoForge (beta), install NeoForge for 26.3 instead and use the `ciderlight-neoforge-<version>.jar`. Optionally add
`--enable-native-access=ciderlight` to the JVM arguments to silence Java's native-access warning, and leave NeoForge's
version check on: with it off, NeoForge 26.3's title screen crashes.

Don't install Sodium, Iris or Sodium Extra alongside it: Fabric won't launch with them (see [Known limitations](#known-limitations)).

Requires an Apple Silicon Mac on macOS 14 or later. On other machines the mod turns itself off and the game uses its default renderer.

## Building

Requires Xcode command-line tools and a Java 25 JDK (set `JAVA_HOME` to it).

```
./gradlew build                 # jar in build/libs/
./gradlew runClient             # dev client
./gradlew installMod            # build and copy into the launcher's mods folder
./gradlew -p neoforge build     # NeoForge jar in neoforge/build/libs/ (same sources, a build of its own)
./gradlew -p neoforge runClient # NeoForge dev client (its asset download needs a Java 21 runtime too)
python3 tests/check_shaders.py  # compile shader variants and run GPU regressions
```

[GitHub Actions](https://github.com/riccqi/ciderlight/actions/workflows/build.yml) builds the JAR and native library
from source; releases use that build's JAR unchanged.

## Known limitations

- Ambient occlusion and reflections only see what is on screen.
- Entities in caves can be darkened by the sun shadow test.
- No bloom/HDR.
- OptiFine/Iris shader packs (e.g. Complementary) are not supported.
- NeoForge (beta): stencil tests that NeoForge lets mods add to pipelines are not applied yet.
- Sodium (and add-ons that need it, such as Iris and Sodium Extra) can't run alongside Ciderlight: Sodium's
  chunk renderer uses a multi-draw call the Metal backend doesn't provide. Fabric will refuse to launch and list them as incompatible; this is intentional.
  Remove them from the mods folder.

## License

Copyright 2026 Richard Qi. [Apache License 2.0](LICENSE); keep the [NOTICE](NOTICE) file when redistributing.
