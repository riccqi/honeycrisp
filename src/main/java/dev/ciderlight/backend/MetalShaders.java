package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Ciderlight's built-in shader pipeline.
 *
 * <ul>
 *   <li>Terrain pipelines get Metal-native shaders with sunlight, shadows and (for translucent
 *       terrain) screen-space water reflections.</li>
 *   <li>Terrain and entity draws from the main pass are captured and replayed from the sun into a
 *       shadow map that the next frame samples.</li>
 *   <li>Before translucent terrain is drawn, the opaque scene is snapshotted for reflections.</li>
 *   <li>A composite pass shadows entities (deferred, from depth), adds light shafts and grades the
 *       image before the hand and HUD are drawn.</li>
 * </ul>
 * Off when the Shaders setting in Video Settings is Off (ShaderSetting), or with -Dciderlight.shaders=false.
 */
public final class MetalShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = System.getProperty("ciderlight.shaders") != null
        ? !"false".equals(System.getProperty("ciderlight.shaders"))
        : ShaderSetting.saved() != ShaderSetting.OFF;
    private static final boolean DEBUG = Boolean.getBoolean("ciderlight.shaderDebug");
    private static final boolean VL_HISTORY = !"false".equals(System.getProperty("ciderlight.vlHistory"));

    private static final boolean SHADOW_HISTORY = !"false".equals(System.getProperty("ciderlight.shadowHistory"));
    /** Leaves and plants sway in the wind (Waving Plants on the settings page, -Dciderlight.waving). */
    private static final boolean WAVING = ShaderToggle.WAVING.enabled();
    /** Water mirrors the scene around it (screen-space reflections); off: the sky only (-Dciderlight.waterReflections). */
    private static final boolean WATER_REFLECTIONS = ShaderToggle.WATER_REFLECTIONS.enabled();
    /** Waves move across the water (water_surface.metal); off: the water is calm (-Dciderlight.waterWaves). */
    private static final boolean WATER_WAVES = ShaderToggle.WATER_WAVES.enabled();

    static final int KIND_NONE = 0;
    static final int KIND_SOLID = 1;
    static final int KIND_CUTOUT = 2;
    static final int KIND_TRANSLUCENT = 3;

    /** Argument-table indices shared with the .metal sources. */
    private static final int FRAME_INDEX = 14;
    private static final int OPAQUE_COLOR_INDEX = 13;
    private static final int OPAQUE_DEPTH_INDEX = 12;
    private static final int SHADOW_COLOR_INDEX = 11;
    private static final int SHADOW_HISTORY_INDEX = 10;
    /** Translucent terrain's shadow history (terrain.metal, MC_REFLECT only). */
    private static final int TRANSLUCENT_SHADOW_HISTORY_INDEX = 15;
    /** Vertex-stage buffer holding the waving-foliage map (see foliage.metal). */
    private static final int FOLIAGE_INDEX = 13;
    private static final int AO_INDEX = 9;
    private static final int CLOUD_SHADOW_INDEX = 8;
    private static final int FRAME_CONSTANTS_INDEX = 7;
    /** The lowest texture slot bindFrame fills; a pipeline with more uniforms than this binds over it. */
    static final int FIRST_FRAME_SLOT = 6;
    /** Items whose sprites shine when held in first person (MetalRenderPipeline.handShine). */
    private static final String[] METAL_MATERIALS = {"iron", "golden", "diamond", "netherite", "copper", "chainmail"};
    private static final String[] METAL_SHAPES = {
        "sword", "pickaxe", "axe", "shovel", "hoe", "spear", "helmet", "chestplate", "leggings", "boots", "horse_armor", "nautilus_armor"
    };
    private static final String[] METAL_ITEMS = {
        "shears", "bucket", "water_bucket", "lava_bucket", "milk_bucket", "powder_snow_bucket", "flint_and_steel", "compass", "clock", "trident",
        "mace", "iron_ingot", "gold_ingot", "copper_ingot", "netherite_ingot", "iron_nugget", "gold_nugget", "copper_nugget", "diamond", "emerald",
        "minecart", "spyglass", "golden_apple", "enchanted_golden_apple", "golden_carrot", "iron_chain", "bell"
    };
    private static final int AO_PARAMS_OFFSET = 480;
    private static final int AIR_NEAR_OFFSET = 496;
    private static final int LIGHTNING_OFFSET = 512;
    private static final int LIGHTNING_EYE_OFFSET = 528;
    private static final int LIGHTNING_MATRIX_OFFSET = 544;
    /** Where it rains around the camera (FrameData.rainOrigin, rainMask): a grid of bits, one per 4x4-block column. */
    private static final int RAIN_ORIGIN_OFFSET = LIGHTNING_MATRIX_OFFSET + 64;
    private static final int RAIN_MASK_OFFSET = RAIN_ORIGIN_OFFSET + 16;
    /** Light sources in players' hands (FrameData.heldLights), in the first slots of the block once left unused. */
    private static final int HELD_LIGHT_OFFSET = RAIN_MASK_OFFSET + 512;
    private static final int HELD_LIGHTS = 8; // HELD_LIGHTS in frame.metal
    private static final int SURFACE_LIGHT_OFFSET = HELD_LIGHT_OFFSET + HELD_LIGHTS * 16; // surfaceSun, surfaceAmbient
    private static final int RAIN_GRID = 64;
    private static final int RAIN_CELL = 4;
    private static final int LIGHTNING_SHADOW_INDEX = 6;
    private static final int LIGHTNING_SHADOW_SIZE = 2048;
    /** The bolt's light is taken to come from this far up it. */
    private static final double LIGHTNING_HEIGHT = 28.0;
    /** Mist around the camera: the air there is (1 + FOG_NEAR) times as dense, fading over FOG_NEAR_RANGE blocks. */
    private static final float FOG_NEAR = Math.clamp(Float.parseFloat(System.getProperty("ciderlight.fogNear", "3")), 0.0F, 12.0F);
    private static final float FOG_NEAR_RANGE = Math.clamp(Float.parseFloat(System.getProperty("ciderlight.fogNearRange", "40")), 4.0F, 256.0F);
    /** Cloud shadows are soft: a small map over the near shadow map's area holds them (shadow.metal, cloud_shadow). */
    private static final int CLOUD_MAP_SIZE = 512;
    private static final int AIR_NOISE_TABLE = 256; // AIR_NOISE_TABLE in atmosphere.metal
    private static final float SHADOW_RADIUS = Float.parseFloat(System.getProperty("ciderlight.shadowDistance", "112"));
    /** An optional cap on how far fog and its light shafts reach; by default they reach as far as the render distance. */
    private static final float FOG_DISTANCE = Math.clamp(Float.parseFloat(System.getProperty("ciderlight.fogDistance", "1024")), 32.0F, 1024.0F);
    /** Above this half-width (blocks) the distant map doubles its resolution, so long render distances keep sharp shafts. */
    private static final float FAR_SHADOW_FINE_RADIUS = 320.0F;
    /** Frames the distant shadow map is drawn over, a vertical strip in each (renderFarShadowMap). */
    private static final int FAR_STRIPS = 4;
    private static final float[][] SHADOW_JITTER = {
        {-0.375F, -0.125F}, {0.125F, -0.375F}, {0.375F, 0.125F}, {-0.125F, 0.375F},
        {-0.375F, 0.375F}, {0.375F, -0.375F}, {0.125F, 0.125F}, {-0.125F, -0.125F}
    };
    /** Ice sprites: drawn nearly opaque so the water underneath does not show through (listed after the shiny ones). */
    private static final String[] ICE_SPRITES = {"ice", "frosted_ice_0", "frosted_ice_1", "frosted_ice_2", "frosted_ice_3"};
    /** Sprites that are all flame: drawn at full brightness like a light source (listed after the ice ones). */
    private static final String[] FLAME_SPRITES = {"fire_0", "fire_1", "soul_fire_0", "soul_fire_1", "campfire_fire", "soul_campfire_fire"};
    /** Sprites with a flame in them: only their bright texels are drawn at full brightness (listed after the flame ones). */
    private static final String[] GLOW_SPRITES = {
        "torch", "soul_torch", "redstone_torch", "copper_torch", "lantern", "soul_lantern", "copper_lantern", "exposed_copper_lantern",
        "weathered_copper_lantern", "oxidized_copper_lantern", "campfire_log_lit", "soul_campfire_log_lit"
    };
    /** Water's side against glass or leaves: a pool or tank seen through its wall (listed after the glow ones). */
    private static final String[] WATER_GLASS_SPRITES = {"water_overlay"};
    /** Particle sprites that are all flame or molten: drawn at full brightness like the flames of blocks. */
    private static final String[] FLAME_PARTICLES = {"flame", "soul_fire_flame", "copper_fire_flame", "lava"};
    /** The block of FrameData from airNear up to waterParams; its end (after `reserved` in frame.metal) fixes the later offsets. */
    private static final int RESERVED_END = AIR_NEAR_OFFSET + 72 * 16;
    private static final int WATER_OFFSET = RESERVED_END;
    private static final int FAR_MATRIX_OFFSET = WATER_OFFSET + 16;
    private static final int FAR_ANCHOR_OFFSET = FAR_MATRIX_OFFSET + 64;
    private static final int AIR_OFFSET = FAR_ANCHOR_OFFSET + 16;
    private static final int SOLAR_OFFSET = AIR_OFFSET + 16;
    private static final int FRAME_BYTES = SOLAR_OFFSET + 16;
    /** Metal and gem blocks: tinted highlight and a sky reflection (SPRITE_METAL in terrain.metal). */
    private static final String[] SHINY_SPRITES = {
        "gold_block", "iron_block", "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper", "cut_copper", "chiseled_copper",
        "diamond_block", "emerald_block", "netherite_block", "anvil", "anvil_top", "chain", "iron_bars", "cauldron_side",
        "cauldron_top", "cauldron_inner", "cauldron_bottom", "hopper_outside", "hopper_top", "hopper_inside", "iron_trapdoor", "iron_door_top",
        "iron_door_bottom", "lightning_rod", "lightning_rod_on", "copper_bulb", "copper_grate", "copper_trapdoor", "copper_door_top", "copper_door_bottom"
    };
    private static final int DEPTH_FORMAT = MetalConst.format(GpuFormat.D32_FLOAT);
    private static final int COLOR_FORMAT = MetalConst.format(GpuFormat.RGBA8_UNORM);
    private static final int TRANSMISSION_FORMAT = MetalConst.format(GpuFormat.RGBA16_UNORM);
    private static final int SHADOW_HISTORY_FORMAT = MetalConst.format(GpuFormat.RGBA16_FLOAT);
    private static final int VOLUMETRIC_FORMAT = MetalConst.format(GpuFormat.RGBA16_FLOAT);
    private static final int EXTINCTION_FORMAT = MetalConst.format(GpuFormat.R16_FLOAT);
    private static final int AO_FORMAT = MetalConst.format(GpuFormat.RGBA16_FLOAT);
    private static final int CLOUD_MAP_FORMAT = MetalConst.format(GpuFormat.R8_UNORM);
    private static final int BOTH = MetalConst.STAGE_VERTEX | MetalConst.STAGE_FRAGMENT;

    /** Set while the sun or moon casts shadows; read when Minecraft collects the chunk sections to draw. */
    private static final boolean CASTERS = !"false".equals(System.getProperty("ciderlight.shadowCasters"));
    /** Shadow passes draw only the chunk sections that can land in their map (-Dciderlight.shadowCull=false: draw them all). */
    private static final boolean SHADOW_CULL = !"false".equals(System.getProperty("ciderlight.shadowCull"));
    /** Blocks of slack around a section when culling it, for foliage swaying past its edge. */
    private static final float SHADOW_CULL_SLACK = 2.0F;
    private static volatile boolean castersWanted;
    /**
     * Shadow casters are the sections between the light and something in view (ShadowCasterMask);
     * -Dciderlight.casterMask=false takes every section around the camera instead, to compare against.
     */
    public static final boolean CASTER_MASK = !"false".equals(System.getProperty("ciderlight.casterMask"));
    /**
     * Of those, only the sections the light actually meets on its way to the view are taken: not what lies behind a
     * section it cannot pass through (ShadowCasterSweep). -Dciderlight.casterSweep=false takes them all.
     */
    public static final boolean CASTER_SWEEP = !"false".equals(System.getProperty("ciderlight.casterSweep"));
    /**
     * -Dciderlight.benchClock=<seconds> stops the clock that wind, drifting haze and caustics run on (it is the time of
     * day otherwise, so two runs minutes apart would not show the same haze): for comparing screenshots between runs.
     */
    private static final float BENCH_CLOCK = Float.parseFloat(System.getProperty("ciderlight.benchClock", "-1"));
    /** Height of the water surface over an underwater camera, NaN otherwise: the shader looks up shadows there too. */
    private static volatile double casterWaterSurface = Double.NaN;
    /** Half-width of the area whose chunk sections are shadow casters: the distant map's, plus a margin. */
    private static volatile double casterRadius = 288.0;
    private static boolean loggedCasters;

    /** The camera's view-projection (camera-relative) and the direction to the light, for choosing shadow casters. */
    record CasterView(Matrix4f viewProj, Vector3f light) {
    }

    private static volatile @Nullable CasterView casterView;
    /** Changes whenever the light has moved far enough for the set of shadow casters to need another look. */
    private static volatile int casterLightStamp;

    private final MetalDevice device;
    private final Quality quality;
    /** The near shadow map's resolution; the glass/water tint map has half of it (see shadow_fragment_translucent). */
    private final int shadowSize;
    private final int transmissionSize;
    private final String terrainSource;
    private final String entitySource;
    private final String particleSource;
    /** Whether opaque particles are drawn with particle.metal, which lights them itself (else vanilla's lightmap). */
    private volatile boolean particlesLit;
    private final String compositeSource;
    /**
     * Libraries and pipeline states by name, built on background threads ahead of the frame that first needs them
     * (Prebuild): those with fixed inputs as soon as the device exists (startPrebuild), the shadow and entity-light ones
     * as Minecraft's pipelines are compiled (prebuildFor). Owns everything it built (destroy).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Long>> built =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final MemorySegment sampleFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private final MemorySegment renderFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private final List<TerrainDraw> terrainDraws = new ArrayList<>();
    private final List<EntityDraw> entityDraws = new ArrayList<>();
    private final List<CloudDraw> cloudDraws = new ArrayList<>();
    private long cloudShadowState;
    /** Entity shadow and light pipelines by descriptor instance (one per Minecraft pipeline), so draws need not build the key. */
    private final Map<int[], Long> entityShadowStatesByDescriptor = new java.util.IdentityHashMap<>();
    private final Map<int[], Long> entityLightStatesByDescriptor = new java.util.IdentityHashMap<>();
    private long cloudMapTexture;
    private long cloudMapState;
    /** 1x1: values that depend on the frame alone, worked out on the GPU once per frame (frame_constants_fragment). */
    private long frameConstantsTexture;
    private long frameConstantsState;
    private int aoDivisor = 2;
    /** Marks the metal sprites of the item atlas for the hand pass (same layout as the block sprite map). */
    private long handSpriteBuffer;
    private byte @Nullable [] handSpriteBytes;
    private final MemorySegment handFrame = Arena.global().allocate(48, 16);
    private final MemorySegment cloudLight = Arena.global().allocate(48, 16);
    /** The UV rectangles of FLAME_PARTICLES in the particle atlas, for the block-light marker pass. */
    private final MemorySegment flameSprites = Arena.global().allocate(FLAME_PARTICLES.length * 16, 16);
    private final MemorySegment noFlameSprites = Arena.global().allocate(FLAME_PARTICLES.length * 16, 16);
    private final Matrix4f currentView = new Matrix4f();
    /** Block-light level (0-15) of what the nearest players hold, eased when they switch items, and where it is in the world. */
    private static final float[] heldLightLevel = new float[HELD_LIGHTS];
    private static final Vec3[] heldLightPos = new Vec3[HELD_LIGHTS];
    private static int heldLights;
    /** The eased level of every player with a light out, by entity id. */
    private Map<Integer, Float> heldLightEased = new HashMap<>();
    /** How brightly lightning lights the world right now: 1 while a bolt is out, fading quickly after. */
    private float lightningLight;
    private Vec3 lightningPos = Vec3.ZERO;
    private boolean lightningBolt;
    // The bolt's own shadow map: a perspective view from up the bolt, rendered while a bolt is out.
    private long lightningShadowTexture;
    private final MemorySegment lightningRenderFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private final Matrix4f lightningShadowMatrix = new Matrix4f();
    private Vec3 lightningShadowEye = Vec3.ZERO;
    private @Nullable Vec3 lightningShadowBolt;
    /** World height of the cloud base in the cloud map; NaN while there are no clouds in it. */
    private double cloudBase = Double.NaN;
    private final Matrix4f currentViewProj = new Matrix4f();
    private boolean viewValid;
    private final Vector3f casterLight = new Vector3f();

    private final MemorySegment farRenderFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private final long[] extinctionTextures = new long[2];
    private final MemorySegment skyFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private long skyState;
    /**
     * The distant map's depth and glass transmission textures, two of each: the fog samples the front pair while the
     * back pair is drawn a strip at a time (FAR_STRIPS frames), and then takes its place.
     */
    private final long[] farShadowTextures = new long[2];
    private final long[] farShadowColorTextures = new long[2];
    private int farFront;
    /** The strip of the back pair to draw next, or -1 when none is under way. */
    private int farStrip = -1;
    /** The matrix and anchor the back pair is drawn with, fixed for all its strips. */
    private final Matrix4f farPendingMatrix = new Matrix4f();
    private Vec3 farPendingAnchor = Vec3.ZERO;
    /** For culling a strip's chunk sections: the matrix of the strip alone, with farRenderFrame's offsets. */
    private final MemorySegment farCullFrame = Arena.global().allocate(FRAME_BYTES, 16);
    private boolean farShadowValid;
    private final Matrix4f lastFarShadowMatrix = new Matrix4f();
    private Vec3 lastFarShadowAnchor = Vec3.ZERO;
    private float previousFogRange;
    /** The distant fog shadow map covers the fog's whole range, which follows the render distance (updateFarShadowArea). */
    private float farShadowRadius;
    private int farShadowSize;
    private boolean previousAtmosphere;
    private long shadowTexture;
    private long shadowColorTexture;
    private long shadowSampler;
    private long shadowTranslucentState;
    private long mainReadDepthState;
    private long shadowSolidState;
    private long shadowCutoutState;
    private long shadowDepthState;
    private long compositeState;
    private long upscaleState;
    private long shadowHistoryState;
    /** Shadow history of what lies behind translucent terrain (the opaque depth): the ground under water and glass. */
    private final long[] shadowHistoryTextures = new long[2];
    /** Shadow history of the nearest surface (the full depth): water, glass and ice, and composite's deferred shadows. */
    private final long[] translucentShadowHistoryTextures = new long[2];
    private int shadowHistoryWidth;
    private int shadowHistoryHeight;
    private int shadowHistoryIndex;
    private boolean shadowHistoryValid;
    private @Nullable ClientLevel previousLevel;
    private final int[] rainMask = new int[RAIN_GRID * RAIN_GRID / 32];
    private boolean[] rainCells = new boolean[RAIN_GRID * RAIN_GRID];
    private boolean[] rainCellsSpare = new boolean[RAIN_GRID * RAIN_GRID];
    /** The level the mask was built for; cleared when the level changes so an old world is not kept alive. */
    private @Nullable ClientLevel rainMaskLevel;
    private int rainMaskX;
    private int rainMaskZ;
    private int rainMaskRow;
    private boolean previousUnderwater;
    private float waterSky;
    private double previousWaterSurface;
    private final Vector3f previousLight = new Vector3f();
    private final Matrix4f nextShadowMatrix = new Matrix4f();
    private Vec3 nextShadowAnchor = Vec3.ZERO;
    private long volumetricState;
    /** The fog's noise table (AirTableNoise in atmosphere.metal), drawn once. */
    private long airNoiseTexture;
    private final long[] volumetricTextures = new long[2];
    private int volumetricWidth;
    private int volumetricHeight;
    private int volumetricIndex;
    private boolean volumetricHistory;
    private long aoState;
    private long aoFilterState;
    private long aoRawTexture;
    private final long[] aoTextures = new long[2];
    private int aoWidth;
    private int aoHeight;
    private int aoIndex;
    private boolean aoHistoryValid;
    private boolean opaqueSnapshotThisFrame;
    private int frameIndex;
    private int shinyRefreshFrames;
    private final Matrix4f prevViewProj = new Matrix4f();
    private Vec3 prevCameraPos = Vec3.ZERO;
    private long opaqueColor;
    private long opaqueDepth;
    private int opaqueWidth;
    private int opaqueHeight;
    private int @Nullable [] uniformIndices;
    /** Grid over the block atlas marking which sprites wave and how; rebuilt when the block models are reloaded. */
    private long foliageBuffer;
    private byte @Nullable [] foliageBytes;
    private @Nullable Object foliageModels;
    private String vertexLayout = "";

    private boolean inMain;
    private long mainColor;
    private long mainDepth;
    private int mainWidth;
    private int mainHeight;
    private boolean shadowValid;
    private boolean sunActive;
    /**
     * Sun and moon shadow maps this frame (Shadows on the settings page, -Dciderlight.shadows; latchLiveToggles). Off,
     * they are never drawn and stay invalid, as at night when the sun is down: surfaces count as lit
     * (shadow_visibility) and the air gets ambient fog but no shafts (air_visibility).
     */
    private boolean shadows = ShaderToggle.SHADOWS.enabled();
    /** Screen-space ambient occlusion (GTAO) this frame (Ambient Occlusion on the settings page, -Dciderlight.ao). */
    private boolean ao = ShaderToggle.AMBIENT_OCCLUSION.enabled();
    private int debugFrames;
    private float eyeSky = 1.0F;
    private long lastFrameNanos;
    private final Matrix4f lastShadowMatrix = new Matrix4f();
    private Vec3 lastShadowAnchor = Vec3.ZERO;
    /** The camera position the captured draws are relative to, for culling their sections in the shadow passes. */
    private Vec3 shadowCullCamera = Vec3.ZERO;

    /** One captured terrain multidraw from the main pass. Contents addresses point at shared memory. */
    record TerrainDraw(
        int kind, int[] shadowDescriptor, long vertexBuffer, long vertexOffset, long instanceBuffer, long instanceOffset, long indexBuffer,
        int indexType, long commands, long commandsOffset, int drawCount, long globals, long globalsOffset, long projectionContents,
        long terrainContents, long fogContents, long atlas, long atlasSampler,
        // For culling sections in the shadow passes: CPU addresses of the draw arguments and of the per-section instance
        // data (0 when they cannot be read on the CPU), how many instances are readable there, and where a section's
        // block position lies in an instance.
        long commandsAddress, long sectionsAddress, int sectionCount, int sectionStride, int sectionPosOffset
    ) {
    }

    /** One captured cloud draw from the main pass: vertices are generated from the CloudFaces buffer, as in vanilla. */
    record CloudDraw(
        long indexBuffer, int indexType, int indexCount, int firstIndex, int vertexBase, long transforms, long transformsOffset,
        long info, long infoOffset, long faces
    ) {
    }

    /** One captured entity/item draw from the main pass. */
    record EntityDraw(
        int[] shadowDescriptor, long vertexBuffer, long vertexOffset, long indexBuffer, int indexType, int indexCount, int firstIndex,
        int vertexBase, int instanceCount, int firstInstance, long transforms, long transformsOffset, long atlas, long atlasSampler,
        boolean particle, boolean particleAtlas, boolean emissive
    ) {
    }

    MetalShaders(final MetalDevice device, final Quality quality) {
        this.device = device;
        this.quality = quality;
        this.shadowSize = quality.shadowSize;
        this.transmissionSize = Math.max(1, quality.shadowSize / 2);
        this.farShadowSize = quality.farShadowSize;
        this.terrainSource = quality.defines() + loadSource("terrain.metal");
        this.entitySource = quality.defines() + loadSource("entity.metal");
        this.particleSource = quality.defines() + loadSource("particle.metal");
        this.compositeSource = quality.defines() + (Boolean.getBoolean("ciderlight.aoDebug") ? "#define MC_DEBUG_AO 1\n" : "") + loadSource("composite.metal");
        if (ENABLED) {
            this.startPrebuild();
        }
    }

    /** Starts building `key` in the background (Prebuild), unless that has already been started or done. */
    private void prebuild(final String key, final java.util.function.LongSupplier build) {
        this.built.computeIfAbsent(key, k -> Prebuild.start(build));
    }

    /** What was built for `key`, waiting for its background build if one is under way; built right here if none was started. */
    private long built(final String key, final java.util.function.LongSupplier build) {
        java.util.concurrent.CompletableFuture<Long> future = this.built.get(key);
        if (future == null) {
            java.util.concurrent.CompletableFuture<Long> own = new java.util.concurrent.CompletableFuture<>();
            future = this.built.putIfAbsent(key, own);
            if (future == null) {
                future = own;
                try {
                    own.complete(build.getAsLong());
                } catch (RuntimeException e) {
                    own.completeExceptionally(e);
                }
            }
        }
        return Prebuild.take(future, key);
    }

    /** Everything whose inputs are fixed once the device exists: the libraries and the full-screen passes. */
    private void startPrebuild() {
        this.prebuild(COMPOSITE_LIBRARY, this::buildCompositeLibrary);
        this.prebuild(ENTITY_LIBRARY, this::buildEntityLibrary);
        this.prebuild(SKY_STATE, this::buildSkyState);
        this.prebuild(FRAME_CONSTANTS_STATE, this.fullscreen("frame_constants_fragment", VOLUMETRIC_FORMAT, FRAME_CONSTANTS_STATE));
        this.prebuild(SHADOW_HISTORY_STATE, this.fullscreen("shadow_history_fragment", SHADOW_HISTORY_FORMAT, SHADOW_HISTORY_STATE));
        this.prebuild(VOLUMETRIC_STATE, this::buildVolumetricState);
        this.prebuild(FOG_NOISE_STATE, this.fullscreen("air_noise_table_fragment", EXTINCTION_FORMAT, FOG_NOISE_STATE));
        // Built unless -Dciderlight.ao=false rules it out, so turning it on in the settings doesn't stall a frame.
        if (this.ao || !ShaderToggle.AMBIENT_OCCLUSION.forced()) {
            this.prebuild(AO_STATE, this.fullscreen("ao_fragment", AO_FORMAT, AO_STATE));
            this.prebuild(AO_FILTER_STATE, this.fullscreen("ao_filter_fragment", AO_FORMAT, AO_FILTER_STATE));
        }
        if (this.quality.renderScale < 1.0F) {
            this.prebuild(UPSCALE_STATE, this.fullscreen("upscale_fragment", COLOR_FORMAT, UPSCALE_STATE));
        }
        this.prebuild(COMPOSITE_STATE, this.fullscreen("composite_fragment", COLOR_FORMAT, COMPOSITE_STATE));
        this.prebuild(CLOUD_SHADOW_STATE, this::buildCloudShadowState);
        this.prebuild(CLOUD_MAP_STATE, this::buildCloudMapState);
    }

    /**
     * Starts building what draws with this pipeline will need from the shader pipeline: the shadow library and the
     * terrain shadow pass for a terrain pipeline, the shadow and block-light passes for one that draws mobs, the
     * block-light pass for the particle pipeline. Called on the thread that compiles the pipeline, before anything draws
     * with it.
     */
    void prebuildFor(final MetalRenderPipeline pipeline) {
        int[] descriptor = pipeline.shadowDescriptor();
        if (!ENABLED || descriptor == null) {
            return;
        }
        if (pipeline.isLitParticle()) {
            this.prebuild(entityLightKey(true, descriptor), () -> this.buildEntityLightState(true, descriptor));
            return;
        }
        int kind = pipeline.terrainKind();
        if (pipeline.isEmissiveLayer()) {
            this.prebuild(entityEmissiveKey(descriptor), () -> this.buildEntityEmissiveState(descriptor));
            return;
        }
        if (kind == KIND_NONE) {
            this.prebuild(entityShadowKey(descriptor), () -> this.buildEntityShadowState(descriptor));
            this.prebuild(entityLightKey(false, descriptor), () -> this.buildEntityLightState(false, descriptor));
            return;
        }
        this.prebuild(SHADOW_LIBRARY, this::buildShadowLibrary);
        if (kind == KIND_TRANSLUCENT) {
            this.prebuild(SHADOW_TRANSLUCENT_STATE, () -> this.buildTranslucentShadowState(descriptor));
        } else {
            boolean cutout = kind == KIND_CUTOUT;
            this.prebuild(cutout ? SHADOW_CUTOUT_STATE : SHADOW_SOLID_STATE, () -> this.buildTerrainShadowState(cutout, descriptor));
        }
    }

    private static final String COMPOSITE_LIBRARY = "composite library";
    private static final String ENTITY_LIBRARY = "entity library";
    private static final String SHADOW_LIBRARY = "shadow library";
    private static final String SKY_LIBRARY = "sky library";
    private static final String SKY_STATE = "Ciderlight atmospheric sky";
    private static final String FRAME_CONSTANTS_STATE = "Ciderlight frame constants";
    private static final String SHADOW_HISTORY_STATE = "Ciderlight surface shadow history";
    private static final String VOLUMETRIC_STATE = "Ciderlight volumetric";
    private static final String FOG_NOISE_STATE = "Ciderlight fog noise";
    private static final String AO_STATE = "Ciderlight ambient occlusion";
    private static final String AO_FILTER_STATE = "Ciderlight ambient occlusion filter";
    private static final String UPSCALE_STATE = "Ciderlight upscale";
    private static final String COMPOSITE_STATE = "Ciderlight composite";
    private static final String CLOUD_SHADOW_STATE = "Ciderlight cloud shadow";
    private static final String CLOUD_MAP_STATE = "Ciderlight cloud shadow map";
    private static final String SHADOW_SOLID_STATE = "Ciderlight shadow";
    private static final String SHADOW_CUTOUT_STATE = "Ciderlight shadow (cutout)";
    private static final String SHADOW_TRANSLUCENT_STATE = "Ciderlight shadow (translucent)";

    private static String entityShadowKey(final int[] descriptor) {
        return "Ciderlight entity shadow " + Arrays.toString(descriptor);
    }

    private static String entityLightKey(final boolean particle, final int[] descriptor) {
        return "Ciderlight " + (particle ? "particle" : "entity") + " light " + Arrays.toString(descriptor);
    }

    private static String entityEmissiveKey(final int[] descriptor) {
        return "Ciderlight emissive layer " + Arrays.toString(descriptor);
    }

    private long compositeLibrary() {
        return this.built(COMPOSITE_LIBRARY, this::buildCompositeLibrary);
    }

    private long buildCompositeLibrary() {
        return MetalNative.libraryCreate(this.device.context(), this.compositeSource);
    }

    private long entityLibrary() {
        return this.built(ENTITY_LIBRARY, this::buildEntityLibrary);
    }

    private long buildEntityLibrary() {
        return MetalNative.libraryCreate(this.device.context(), this.entitySource);
    }

    private long shadowLibrary() {
        return this.built(SHADOW_LIBRARY, this::buildShadowLibrary);
    }

    /** Needs the uniform layout of the terrain pipelines (compileTerrainLibrary) to be known. */
    private long buildShadowLibrary() {
        int[] idx;
        String vertexLayout;
        synchronized (this) {
            idx = this.uniformIndices;
            vertexLayout = this.vertexLayout;
        }
        if (idx == null) {
            throw new IllegalStateException("The shadow library needs a terrain pipeline first");
        }
        return MetalNative.libraryCreate(this.device.context(), vertexLayout + header(idx, KIND_SOLID) + this.terrainSource);
    }

    private long buildSkyState() {
        long library = this.built(SKY_LIBRARY, () -> MetalNative.libraryCreate(this.device.context(), loadSource("sky.metal")));
        int[] desc = {0, 0, 1, COLOR_FORMAT, 15, 0, 0, 0, 0, 0, 0, 0, DEPTH_FORMAT, MetalConst.PRIM_TRIANGLES};
        return MetalNative.pipelineCreate(this.device.context(), library, "sky_vertex", library, "sky_fragment", desc, SKY_STATE);
    }

    /** A full-screen pass of composite.metal drawing into a single target of `format`, without blending. */
    private java.util.function.LongSupplier fullscreen(final String fragment, final int format, final String label) {
        return () -> {
            long library = this.compositeLibrary();
            int[] desc = {0, 0, 1, format, 15, 0, 0, 0, 0, 0, 0, 0, -1, MetalConst.PRIM_TRIANGLES};
            return MetalNative.pipelineCreate(this.device.context(), library, "composite_vertex", library, fragment, desc, label);
        };
    }

    private long fullscreenState(final String fragment, final int format, final String label) {
        return this.built(label, this.fullscreen(fragment, format, label));
    }

    private long buildVolumetricState() {
        long library = this.compositeLibrary();
        int[] desc = {0, 0, 2, VOLUMETRIC_FORMAT, 15, 0, 0, 0, 0, 0, 0, 0,
            EXTINCTION_FORMAT, 1, 0, 0, 0, 0, 0, 0, 0, -1, MetalConst.PRIM_TRIANGLES};
        return MetalNative.pipelineCreate(this.device.context(), library, "composite_vertex", library, "volumetric_fragment", desc, VOLUMETRIC_STATE);
    }

    private long buildCloudShadowState() {
        long library = this.entityLibrary();
        return MetalNative.pipelineCreate(
            this.device.context(), library, "cloud_shadow_vertex", library, "cloud_shadow_fragment",
            translucentShadowDescriptor(new int[]{0, 0, 0, DEPTH_FORMAT, MetalConst.PRIM_TRIANGLES}), CLOUD_SHADOW_STATE
        );
    }

    private long buildCloudMapState() {
        long library = this.entityLibrary();
        int[] desc = {0, 0, 1, CLOUD_MAP_FORMAT, 15, 1, BlendFactor.DST_COLOR.ordinal(), BlendFactor.ZERO.ordinal(), BlendOp.ADD.ordinal(),
            BlendFactor.ONE.ordinal(), BlendFactor.ZERO.ordinal(), BlendOp.ADD.ordinal(), -1, MetalConst.PRIM_TRIANGLES};
        return MetalNative.pipelineCreate(this.device.context(), library, "cloud_map_vertex", library, "cloud_map_fragment", desc, CLOUD_MAP_STATE);
    }

    private long buildTerrainShadowState(final boolean cutout, final int[] descriptor) {
        long library = this.shadowLibrary();
        return MetalNative.pipelineCreate(
            this.device.context(), library, "shadow_vertex", cutout ? library : 0L, cutout ? "shadow_fragment_cutout" : "", descriptor,
            cutout ? SHADOW_CUTOUT_STATE : SHADOW_SOLID_STATE
        );
    }

    private long buildTranslucentShadowState(final int[] descriptor) {
        long library = this.shadowLibrary();
        return MetalNative.pipelineCreate(
            this.device.context(), library, "shadow_vertex", library, "shadow_fragment_translucent", translucentShadowDescriptor(descriptor),
            SHADOW_TRANSLUCENT_STATE
        );
    }

    private long buildEntityShadowState(final int[] descriptor) {
        long library = this.entityLibrary();
        return MetalNative.pipelineCreate(
            this.device.context(), library, "entity_shadow_vertex", library, "entity_shadow_fragment", descriptor, "Ciderlight entity shadow"
        );
    }

    private long buildEntityEmissiveState(final int[] descriptor) {
        long library = this.entityLibrary();
        return MetalNative.pipelineCreate(
            this.device.context(), library, "entity_emissive_vertex", library, "entity_emissive_fragment", alphaOnlyDescriptor(descriptor),
            "Ciderlight emissive layer"
        );
    }

    private long buildEntityLightState(final boolean particle, final int[] descriptor) {
        long library = this.entityLibrary();
        // Particles have their own vertex layout (UV0 and UV2 at other attribute locations), and so do moving blocks,
        // whose vertex format has no overlay UV1: UV2 is attribute 3 there instead of 4.
        String vertex = particle ? "particle_light_vertex" : hasAttribute(descriptor, 4) ? "entity_light_vertex" : "block_light_vertex";
        String fragment = particle ? (this.particlesLit ? "particle_lit_fragment" : "particle_light_fragment") : "entity_light_fragment";
        return MetalNative.pipelineCreate(this.device.context(), library, vertex, library, fragment, alphaOnlyDescriptor(descriptor), "Ciderlight entity light");
    }

    public static boolean needsShadowCasters() {
        return ENABLED && castersWanted && CASTERS;
    }

    /** How far below its real place the first-person player's body is drawn; entity.metal's OWN_BODY_DROP must match. */
    public static final double OWN_BODY_DROP = 10000.0;

    /** Whether the first-person player's own (otherwise undrawn) body should be extracted to cast a shadow. */
    public static boolean castsOwnShadow(final Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        return needsShadowCasters() && minecraft.player != null && camera.entity() == minecraft.player && !minecraft.player.isSpectator();
    }

    /** The player's own body in first person: extracted only because of castsOwnShadow, so it must not be seen. */
    public static boolean isHiddenOwnBody(final net.minecraft.client.renderer.entity.state.EntityRenderState state,
                                          final net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        return camera.isFirstPerson && minecraft.player != null && !minecraft.player.isSleeping()
            && state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar && avatar.id == minecraft.player.getId();
    }

    /**
     * Block light (0-15) reaching a place in the world from the light sources players hold. Terrain takes it in
     * its shader (held_light in frame.metal); mobs and items get it added to their own light (EntityRendererMixin).
     */
    public static int heldLightAt(final Vec3 pos) {
        float light = 0.0F;
        for (int i = 0; i < heldLights; i++) {
            light = Math.max(light, heldLightLevel[i] - (float)pos.distanceTo(heldLightPos[i]));
        }
        return Math.round(light);
    }

    /** The light a held item gives off: that of its block (torches, lanterns, glowstone), or of the lava in a bucket. */
    private static int lightEmission(final net.minecraft.world.item.ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            return blockItem.getBlock().defaultBlockState().getLightEmission();
        }
        return stack.is(net.minecraft.world.item.Items.LAVA_BUCKET) ? 15 : 0;
    }

    /**
     * The frustum Minecraft picks chunk sections with: widened, while shadows are on, to what a small turn would bring
     * into view (ShadowReceiverFrustum).
     */
    public static net.minecraft.client.renderer.culling.Frustum receiverFrustum(final net.minecraft.client.renderer.culling.Frustum camera) {
        if (!needsShadowCasters()) { // castersWanted is only ever set by the Metal backend
            return camera;
        }
        if ((DEBUG || MetalDebug.ENABLED) && !loggedCasters) {
            loggedCasters = true;
            LOGGER.info("Ciderlight shaders: widening chunk selection for shadow casters");
        }
        CasterView view = casterView;
        return new ShadowReceiverFrustum(camera, view != null ? view.viewProj() : null);
    }

    /** The direction to the light that casts the shadows; null while there is none, or before the first frame has found it. */
    public static org.joml.@Nullable Vector3fc casterLight() {
        CasterView view = casterView;
        return needsShadowCasters() && view != null ? view.light() : null;
    }

    /** See {@link #casterWaterSurface}. */
    public static double casterWaterSurface() {
        return casterWaterSurface;
    }

    /** Half-width, in blocks, of the area around the camera whose chunk sections can be in a shadow map. */
    public static double casterRadius() {
        return casterRadius;
    }

    /** Chunk sections Minecraft draws only because they cast shadows (by sectionKey); null or empty: none. */
    private static volatile it.unimi.dsi.fastutil.longs.@Nullable LongOpenHashSet casterOnlySections;

    public static void casterOnlySections(final it.unimi.dsi.fastutil.longs.@Nullable LongOpenHashSet sections) {
        casterOnlySections = sections;
    }

    public static it.unimi.dsi.fastutil.longs.@Nullable LongOpenHashSet casterOnlySections() {
        return casterOnlySections;
    }

    /** Identifies a chunk section by the block position of its lowest corner, as its draws carry it (ChunkPosition). */
    public static long sectionKey(final net.minecraft.world.phys.AABB box) {
        return sectionKey((int)Math.floor(box.minX), (int)Math.floor(box.minY), (int)Math.floor(box.minZ));
    }

    public static long sectionKey(final int x, final int y, final int z) {
        return net.minecraft.core.BlockPos.asLong(x, y, z);
    }

    /** See {@link #casterLightStamp}. */
    public static int casterLightStamp() {
        return casterLightStamp;
    }

    /** Keep vanilla fog for blindness/darkness and when another graphics backend is active. */
    public static boolean usesWaterScattering(final Camera camera) {
        if (!ENABLED || !"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()) || camera.getFluidInCamera() != FogType.WATER) {
            return false;
        }
        return !(camera.entity() instanceof LivingEntity living
            && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    /** Leave special dimensions, fluids and visibility-reducing effects to vanilla. */
    static boolean usesAtmosphere() {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!ENABLED || minecraft.level == null || !camera.isInitialized()
            || minecraft.level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD
            || camera.getFluidInCamera() != FogType.NONE) return false;
        return !(camera.entity() instanceof LivingEntity living
            && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    /** Called from the actual sky draw, before celestial bodies, using this frame's sky matrices. */
    boolean drawSky(final long enc, final long depthState, final boolean hasDepth, final int[] indices, final Object[] uniforms) {
        if (!hasDepth || !usesAtmosphere()) return false;
        GpuBufferSlice transform = (GpuBufferSlice)uniforms[indices[0]];
        GpuBufferSlice projection = (GpuBufferSlice)uniforms[indices[1]];
        long transformAddress = ((MetalBuffer)transform.buffer()).latestContents() + transform.offset();
        Matrix4f view = readMatrix(transformAddress);
        Matrix4f proj = readMatrix(((MetalBuffer)projection.buffer()).latestContents() + projection.offset());
        writeMatrix(this.skyFrame, 192, new Matrix4f(proj).mul(view).invert());
        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float angle = minecraft.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * (float)Math.PI / 180.0F;
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET, -(float)Math.sin(angle));
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 4, (float)Math.cos(angle));
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 8, 0.0F);
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 12, 1.0F);
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, 116, minecraft.level.getRainLevel(partialTick));
        this.skyFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET + 12, this.sampleFrame.get(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET + 12));
        if (this.skyState == 0L) {
            this.skyState = this.built(SKY_STATE, this::buildSkyState);
        }
        MetalNative.passSetPipeline(enc, this.skyState, depthState, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.skyFrame, FRAME_BYTES, BOTH);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        return true;
    }

    private static String loadSource(final String name) {
        try (InputStream in = MetalShaders.class.getResourceAsStream("/assets/ciderlight/shaders/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing shader " + name);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Metal runtime compilation has no resource include path; expand our shared headers here.
            for (String header : new String[]{"frame.metal", "shadow.metal", "water.metal", "reflection.metal", "water_surface.metal", "atmosphere.metal", "foliage.metal", "ao.metal"}) {
                String include = "#include \"" + header + "\"";
                if (source.contains(include)) {
                    source = source.replace(include, loadSource(header));
                }
            }
            return source;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- pipeline classification ----

    static int terrainKind(final String pipelineName) {
        if (!ENABLED) {
            return KIND_NONE;
        }
        return switch (pipelineName) {
            case "minecraft:pipeline/solid_terrain_multidraw" -> KIND_SOLID;
            case "minecraft:pipeline/cutout_terrain_multidraw" -> KIND_CUTOUT;
            case "minecraft:pipeline/translucent_terrain_multidraw" -> KIND_TRANSLUCENT;
            default -> KIND_NONE;
        };
    }

    /** Glowing eyes (endermen, spiders, phantoms): marked as light sources for the composite, and cast no shadow. */
    static boolean isEmissiveLayer(final String pipelineName) {
        return ENABLED && pipelineName.equals("minecraft:pipeline/eyes");
    }

    /**
     * Entity, armor and item pipelines whose draws should cast shadows. Players (and a few mobs) are drawn with
     * entity_translucent; the shadow fragment only drops their nearly clear texels. Emissive eyes cast nothing.
     * Falling sand and gravel and blocks pushed by pistons are drawn apart from the terrain with the *_block pipelines,
     * so they are lit like entities too: without this they cast no shadow and lost their torch light while moving.
     */
    static boolean castsEntityShadow(final String pipelineName) {
        if (!ENABLED || !pipelineName.startsWith("minecraft:pipeline/")) {
            return false;
        }
        String name = pipelineName.substring("minecraft:pipeline/".length());
        return name.startsWith("entity_cutout") || name.startsWith("entity_solid") || name.equals("item_cutout")
            || name.equals("entity_translucent") || name.equals("entity_translucent_cull") || name.equals("armor_cutout_no_cull")
            || name.equals("solid_block") || name.equals("cutout_block") || name.equals("translucent_block");
    }

    /**
     * Opaque particles (block-break debris among them): like entities they are lit in the composite pass, so they need
     * the block-light marker or torch-lit particles go dark. They cast no shadows.
     */
    static boolean isLitParticle(final String pipelineName) {
        return ENABLED && pipelineName.equals("minecraft:pipeline/opaque_particle");
    }

    /** Uniform slot of each named Minecraft uniform block, in the order the terrain shader expects. */
    static int[] indicesFor(final List<BindGroupLayout.UniformDescription> uniforms) {
        return indicesFor(uniforms, "Globals", "Projection", "TerrainUniform", "Fog", "Sampler2", "Sampler0");
    }

    static int[] indicesFor(final List<BindGroupLayout.UniformDescription> uniforms, final String... names) {
        int[] result = new int[names.length];
        for (int n = 0; n < names.length; n++) {
            result[n] = -1;
            for (int i = 0; i < uniforms.size(); i++) {
                if (uniforms.get(i).name().equals(names[n])) {
                    result[n] = i;
                }
            }
            if (result[n] < 0) {
                throw new IllegalStateException("Pipeline is missing uniform " + names[n]);
            }
        }
        return result;
    }

    private static String header(final int[] idx, final int kind) {
        StringBuilder b = new StringBuilder();
        b.append("#define IDX_GLOBALS ").append(idx[0]).append('\n');
        b.append("#define IDX_PROJECTION ").append(idx[1]).append('\n');
        b.append("#define IDX_TERRAIN ").append(idx[2]).append('\n');
        b.append("#define IDX_FOG ").append(idx[3]).append('\n');
        b.append("#define IDX_LIGHTMAP ").append(idx[4]).append('\n');
        b.append("#define IDX_ATLAS ").append(idx[5]).append('\n');
        if (kind == KIND_CUTOUT) {
            b.append("#define ALPHA_CUTOUT 0.5\n");
        }
        if (kind == KIND_TRANSLUCENT) {
            b.append("#define ALPHA_CUTOUT 0.1\n#define MC_REFLECT 1\n");
            if (!WATER_REFLECTIONS) {
                b.append("#define MC_NO_WATER_TRACE 1\n");
            }
            if (!WATER_WAVES) {
                b.append("#define MC_NO_WATER_WAVES 1\n");
            }
        }
        if (DEBUG) {
            b.append("#define MC_DEBUG_SHADOWS 1\n");
        }
        return b.toString();
    }

    /**
     * Compiles the replacement opaque-particle shader library (particle.metal), which lights particles like terrain;
     * returns its library handle. Throws if the pipeline's uniforms would overlap the slots bindFrame fills.
     */
    long compileParticleLibrary(final List<BindGroupLayout.UniformDescription> uniforms) {
        if (uniforms.size() > FIRST_FRAME_SLOT) {
            throw new IllegalStateException("too many uniforms (" + uniforms.size() + ")");
        }
        int[] idx = indicesFor(uniforms, "DynamicTransforms", "Projection", "Sampler0", "Sampler2");
        String header = "#define IDX_TRANSFORMS " + idx[0] + "\n#define IDX_PROJECTION " + idx[1] + "\n#define IDX_ATLAS " + idx[2]
            + "\n#define IDX_LIGHTMAP " + idx[3] + "\n";
        long library = MetalNative.libraryCreate(this.device.context(), header + this.particleSource);
        this.particlesLit = true;
        return library;
    }

    /** Compiles the replacement terrain shader library for a pipeline; returns its library handle. */
    long compileTerrainLibrary(final List<BindGroupLayout.UniformDescription> uniforms, final int kind, final String vertexLayout) {
        int[] idx = indicesFor(uniforms);
        synchronized (this) {
            if (this.uniformIndices == null) {
                this.uniformIndices = idx;
                this.vertexLayout = vertexLayout;
            }
        }
        return MetalNative.libraryCreate(this.device.context(), vertexLayout + header(idx, kind) + this.terrainSource);
    }

    /**
     * Where the waving-foliage code finds a vertex's position and UV when it reads a neighbouring vertex straight from
     * the chunk vertex buffer. Empty (no waving) unless the layout is the expected float3 position and float2 UV in
     * vertex buffer slot 0.
     */
    static String vertexLayoutDefines(final com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo info) {
        if (!WAVING) {
            return "";
        }
        int stride = -1;
        for (var vb : info.vertexBuffers()) {
            if (vb.bufferSlot() == 0 && vb.stepRate() == 0) {
                stride = vb.stride();
            }
        }
        int pos = -1;
        int uv = -1;
        for (var attrib : info.attribBindings()) {
            if (attrib.bufferSlot() != 0) {
                continue;
            }
            if (attrib.location() == 0 && attrib.format() == GpuFormat.RGB32_FLOAT) {
                pos = attrib.offset();
            } else if (attrib.location() == 2 && attrib.format() == GpuFormat.RG32_FLOAT) {
                uv = attrib.offset();
            }
        }
        if (stride <= 0 || pos < 0 || uv < 0) {
            LOGGER.warn("Ciderlight shaders: unexpected terrain vertex layout in {}, foliage will not wave", info.name());
            return "";
        }
        String defines = "#define MC_VERTEX_STRIDE " + stride + "u\n#define MC_UV_OFFSET " + uv + "\n#define MC_POS_OFFSET " + pos + "\n";
        if (MetalDebug.ENABLED) {
            LOGGER.info("Ciderlight shaders: waving foliage for {} ({})", info.name(), defines.replace('\n', ' '));
        }
        return defines + (Boolean.getBoolean("ciderlight.wavingDebug") ? "#define MC_WAVING_DEBUG 1\n" : "");
    }

    // ---- per-frame flow ----

    /** Called just before the "Main" world pass is opened: this frame's data, and the passes that must come before it. */
    void prepareMain(final long frame, final long colorTexture, final long depthTexture, final int width, final int height) {
        this.terrainDraws.clear();
        this.entityDraws.clear();
        this.cloudDraws.clear();
        this.inMain = true;
        this.mainColor = colorTexture;
        this.mainDepth = depthTexture;
        this.mainWidth = width;
        this.mainHeight = height;
        this.opaqueSnapshotThisFrame = false;
        this.latchLiveToggles();
        this.ensureShadowHistory();
        this.ensureAmbientOcclusion();
        this.updateFrameData();
        this.renderFrameConstants(frame);
    }

    /** Picks up Shadows and Ambient Occlusion switched on the settings page, once per frame so no frame mixes them. */
    private void latchLiveToggles() {
        boolean shadows = ShaderToggle.SHADOWS.enabled();
        if (shadows != this.shadows) {
            // The shadow and fog histories hold the other setting's light. Back on, the maps start over as at sunrise.
            this.invalidateHistory();
            this.invalidateShadowMaps();
            this.shadows = shadows;
        }
        boolean ao = ShaderToggle.AMBIENT_OCCLUSION.enabled();
        if (ao != this.ao) {
            this.aoHistoryValid = false;
            this.ao = ao;
        }
    }

    private void renderFrameConstants(final long frame) {
        if (this.frameConstantsState == 0L) {
            this.frameConstantsTexture = MetalNative.textureCreate(this.device.context(), VOLUMETRIC_FORMAT, 1, 1, 1, 1, 4 | 8);
            MetalNative.setLabel(this.frameConstantsTexture, "Ciderlight frame constants");
            this.frameConstantsState = this.fullscreenState("frame_constants_fragment", VOLUMETRIC_FORMAT, FRAME_CONSTANTS_STATE);
        }
        MetalNative.profileLabel("frame constants");
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{this.frameConstantsTexture}, 1, 1);
        MetalNative.passSetPipeline(enc, this.frameConstantsState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
    }

    /** Binds the per-frame data and shadow map; also used when the main pass is re-opened after a split. */
    void bindFrame(final long enc) {
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.sampleFrame, FRAME_BYTES, BOTH);
        // The sprite map: the vertex stage reads which quads wave, the fragment stage each sprite's material.
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.foliageBuffer(), 0L, BOTH);
        MetalNative.passSetTexture(enc, CLOUD_SHADOW_INDEX, this.cloudMapTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, FRAME_CONSTANTS_INDEX, this.frameConstantsTexture, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, LIGHTNING_SHADOW_INDEX, this.lightningShadowTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, FRAME_INDEX, this.shadowTexture(), this.shadowSampler, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, SHADOW_COLOR_INDEX, this.shadowColorTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, SHADOW_HISTORY_INDEX, this.shadowHistoryTextures[1 - this.shadowHistoryIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, TRANSLUCENT_SHADOW_HISTORY_INDEX, this.translucentShadowHistoryTextures[1 - this.shadowHistoryIndex], 0L,
            MetalConst.STAGE_FRAGMENT);
        if (this.ao) {
            MetalNative.passSetTexture(enc, AO_INDEX, this.aoTextures[1 - this.aoIndex], 0L, MetalConst.STAGE_FRAGMENT);
        }
        if (this.opaqueColor != 0L) {
            MetalNative.passSetTexture(enc, OPAQUE_COLOR_INDEX, this.opaqueColor, 0L, MetalConst.STAGE_FRAGMENT);
            MetalNative.passSetTexture(enc, OPAQUE_DEPTH_INDEX, this.opaqueDepth, 0L, MetalConst.STAGE_FRAGMENT);
        }
    }

    boolean inMain() {
        return this.inMain;
    }

    void capture(final TerrainDraw draw) {
        if (this.inMain) {
            this.terrainDraws.add(draw);
        }
    }

    void capture(final CloudDraw draw) {
        if (this.inMain) {
            this.cloudDraws.add(draw);
        }
    }

    void capture(final EntityDraw draw) {
        if (this.inMain) {
            this.entityDraws.add(draw);
        }
    }

    /** Snapshots the opaque scene (between encoders) so translucent terrain can reflect it. */
    void snapshotOpaque(final long frame) {
        if (this.opaqueColor == 0L || this.opaqueWidth != this.mainWidth || this.opaqueHeight != this.mainHeight) {
            if (this.opaqueColor != 0L) {
                long color = this.opaqueColor;
                long depth = this.opaqueDepth;
                this.device.encoder().queueForDestroy(() -> {
                    MetalNative.release(color);
                    MetalNative.release(depth);
                });
            }
            this.opaqueColor = MetalNative.textureCreate(this.device.context(), COLOR_FORMAT, this.mainWidth, this.mainHeight, 1, 1, 1 | 4);
            this.opaqueDepth = MetalNative.textureCreate(this.device.context(), DEPTH_FORMAT, this.mainWidth, this.mainHeight, 1, 1, 1 | 4);
            MetalNative.setLabel(this.opaqueColor, "Ciderlight opaque color");
            MetalNative.setLabel(this.opaqueDepth, "Ciderlight opaque depth");
            this.opaqueWidth = this.mainWidth;
            this.opaqueHeight = this.mainHeight;
        }
        MetalNative.profileLabel("opaque snapshot");
        MetalNative.blitTextureToTexture(frame, this.mainColor, this.opaqueColor, 0, 0, 0, 0, 0, this.mainWidth, this.mainHeight);
        MetalNative.blitTextureToTexture(frame, this.mainDepth, this.opaqueDepth, 0, 0, 0, 0, 0, this.mainWidth, this.mainHeight);
        this.opaqueSnapshotThisFrame = true;
    }

    /**
     * Called just before the main pass ends, with its encoder still open: works out this frame's camera matrices and
     * draws the entity block-light markers into the pass itself, which saves loading and storing the scene once more.
     */
    void finishMain(final long enc) {
        this.viewValid = !this.terrainDraws.isEmpty();
        if (!this.viewValid) {
            return;
        }
        TerrainDraw camera = this.terrainDraws.getFirst();
        Matrix4f view = readMatrix(camera.terrainContents());
        Matrix4f proj = readMatrix(camera.projectionContents());
        Matrix4f invView = new Matrix4f(view).invert();
        this.currentView.set(view);
        Matrix4f viewProj = this.currentViewProj.set(proj).mul(view);
        Matrix4f invViewProj = new Matrix4f(viewProj).invert();
        writeMatrix(this.sampleFrame, 192, invViewProj);
        writeMatrix(this.sampleFrame, 256, viewProj);
        writeMatrix(this.renderFrame, 128, invView);
        writeMatrix(this.sampleFrame, 128, invView);
        this.markEntityLight(enc);
    }

    /** Called after the main pass has ended: composite now, then render the shadow map for the next frame. */
    void endMain(final long frame) {
        this.inMain = false;
        if (!this.viewValid) {
            this.invalidateHistory();
            this.aoHistoryValid = false;
            casterView = null;
            return;
        }
        TerrainDraw camera = this.terrainDraws.getFirst();
        Matrix4f viewProj = this.currentViewProj;
        // Vanilla's fog colour (std140 offset 0) and render-distance fog end (offset 28) drive the haze in the composite pass.
        for (int i = 0; i < 4; i++) {
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 320 + i * 4, MemoryUtil.memGetFloat(camera.fogContents() + i * 4L));
        }
        float renderDistance = MemoryUtil.memGetFloat(camera.fogContents() + 28L);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 372, renderDistance);
        float fogRange = Math.min(FOG_DISTANCE, Math.max(renderDistance, 32.0F) * 0.98F);
        if (Math.abs(fogRange - this.previousFogRange) > 0.5F) {
            this.volumetricHistory = false;
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 452, 0.0F);
        }
        this.previousFogRange = fogRange;
        this.updateFarShadowArea(fogRange);
        Vec3 cameraPos = Minecraft.getInstance().gameRenderer.mainCamera().position();
        if (this.shinyRefreshFrames-- <= 0) {
            this.shinyRefreshFrames = 120; // sprites move when the atlas is restitched (resource packs), so refresh now and then
            this.updateSpriteMap();
            this.updateHandSprites();
        }
        this.renderShadowHistory(frame);
        this.volumetric(frame);
        if (this.ao) {
            this.ambientOcclusion(frame);
        }
        this.composite(frame);
        this.aoIndex = 1 - this.aoIndex;
        this.prevViewProj.set(viewProj);
        this.prevCameraPos = cameraPos;
        this.shadowHistoryIndex = 1 - this.shadowHistoryIndex;
        casterView = this.sunActive && this.shadows ? new CasterView(new Matrix4f(viewProj), new Vector3f(this.previousLight)) : null;
        if (this.sunActive && this.shadows) {
            this.renderShadowMap(frame);
            this.renderFarShadowMap(frame);
        } else {
            this.shadowValid = false;
            this.farShadowValid = false;
        }
        if (this.lightningBolt) {
            this.renderLightningShadow(frame);
        }
    }

    private long lightningShadowTexture() {
        if (this.lightningShadowTexture == 0L) {
            this.lightningShadowTexture = MetalNative.textureCreate(
                this.device.context(), DEPTH_FORMAT, LIGHTNING_SHADOW_SIZE, LIGHTNING_SHADOW_SIZE, 1, 1, 8 | 4
            );
            MetalNative.setLabel(this.lightningShadowTexture, "Ciderlight lightning shadow map");
        }
        return this.lightningShadowTexture;
    }

    /**
     * The captured draws seen from a point up the lightning bolt, looking at the camera's surroundings: the shadows
     * the bolt's light casts. Only rendered while a bolt is out; the next frame samples it (lightning_shadow).
     */
    private void renderLightningShadow(final long frame) {
        int[] idx = this.uniformIndices;
        if (idx == null) {
            return;
        }
        this.ensureShadowDepthState();
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        Vec3 eye = this.lightningPos.add(0.0, LIGHTNING_HEIGHT, 0.0);
        Vector3f look = new Vector3f((float)(camera.x - eye.x), (float)(camera.y - eye.y), (float)(camera.z - eye.z));
        float distance = look.length();
        if (distance < 1.0F) {
            look.set(0.0F, -1.0F, 0.0F);
            distance = 1.0F;
        }
        // Wide enough for about 112 blocks around the camera.
        float fov = Math.clamp(2.0F * (float)Math.atan(112.0 / distance), (float)Math.toRadians(50.0), (float)Math.toRadians(150.0));
        boolean vertical = Math.abs(look.y) > 0.98F * distance;
        Matrix4f matrix = new Matrix4f()
            .perspective(fov, 1.0F, 1.0F, distance + 220.0F, true)
            .lookAt(0.0F, 0.0F, 0.0F, look.x, look.y, look.z, vertical ? 1.0F : 0.0F, vertical ? 0.0F : 1.0F, 0.0F);
        this.lightningRenderFrame.copyFrom(this.renderFrame);
        writeMatrix(this.lightningRenderFrame, 0, matrix);
        this.lightningRenderFrame.set(ValueLayout.JAVA_FLOAT, 64, (float)(camera.x - eye.x));
        this.lightningRenderFrame.set(ValueLayout.JAVA_FLOAT, 68, (float)(camera.y - eye.y));
        this.lightningRenderFrame.set(ValueLayout.JAVA_FLOAT, 72, (float)(camera.z - eye.z));
        MetalNative.profileLabel("lightning shadow map");
        long enc = MetalNative.passBegin(frame, new long[0], new int[0], 0, new float[4], this.lightningShadowTexture(), 0, true, 1.0,
            LIGHTNING_SHADOW_SIZE, LIGHTNING_SHADOW_SIZE);
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.lightningRenderFrame, FRAME_BYTES, MetalConst.STAGE_VERTEX);
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.foliageBuffer(), 0L, MetalConst.STAGE_VERTEX);
        this.drawOpaqueShadowCasters(enc, idx, null); // a perspective map: every section is drawn
        MetalNative.passEnd(enc);
        this.lightningShadowMatrix.set(matrix);
        this.lightningShadowEye = eye;
        this.lightningShadowBolt = this.lightningPos;
    }

    private void ensureShadowDepthState() {
        if (this.shadowDepthState == 0L) {
            this.shadowDepthState = this.device.depthState(CompareOp.LESS_THAN_OR_EQUAL.ordinal(), true);
        }
    }

    private static Matrix4f readMatrix(final long address) {
        return new Matrix4f(MemoryUtil.memFloatBuffer(address, 16));
    }

    private static void writeMatrix(final MemorySegment seg, final int offset, final Matrix4f matrix) {
        ByteBuffer buf = seg.asSlice(offset, 64).asByteBuffer().order(ByteOrder.nativeOrder());
        matrix.get(0, buf);
    }

    /** Redraws the captured entity draws over the scene, writing their block light into the alpha channel. */
    private void markEntityLight(final long enc) {
        if (this.entityDraws.isEmpty()) {
            return;
        }
        if (this.mainReadDepthState == 0L) {
            // The main depth buffer is reverse-Z (vanilla's default compare is greater-or-equal), unlike the shadow map.
            this.mainReadDepthState = this.device.depthState(CompareOp.GREATER_THAN_OR_EQUAL.ordinal(), false);
        }
        MetalNative.passSetScissor(enc, 0, 0, this.mainWidth, this.mainHeight, this.mainWidth, this.mainHeight);
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.sampleFrame, FRAME_BYTES, MetalConst.STAGE_VERTEX);
        long currentState = 0L;
        boolean flameSpritesRead = false;
        // Glowing eyes last: they lie on the body's own faces, whose mark would otherwise replace theirs.
        for (boolean emissivePass : new boolean[]{false, true}) {
            for (EntityDraw draw : this.entityDraws) {
                if (draw.emissive() != emissivePass) {
                    continue;
                }
                Long cached = this.entityLightStatesByDescriptor.get(draw.shadowDescriptor());
                if (cached == null) {
                    boolean particle = draw.particle();
                    int[] descriptor = draw.shadowDescriptor();
                    cached = draw.emissive()
                        ? this.built(entityEmissiveKey(descriptor), () -> this.buildEntityEmissiveState(descriptor))
                        : this.built(entityLightKey(particle, descriptor), () -> this.buildEntityLightState(particle, descriptor));
                    this.entityLightStatesByDescriptor.put(descriptor, cached);
                }
                if (cached != currentState) {
                    currentState = cached;
                    // The redraw transforms vertices differently from vanilla's entity shader, so its depth can land a
                    // rounding error behind the mob's own, most on faces seen at a steep angle. Pulling it slightly towards
                    // the camera (reverse-Z: positive bias) keeps those pixels from losing their torch light.
                    // A particle faces the camera, so its depth is the same all over and the slope term gives nothing: without
                    // a much larger constant bias the whole quad fails the test on some frames and the particle flashes.
                    MetalNative.passSetPipeline(enc, cached, this.mainReadDepthState, false, false, draw.particle() ? 256.0F : 1.0F, 2.0F);
                }
                if (draw.particle()) {
                    // Block and item debris come through the same pipeline with other atlases: no flames among them.
                    if (draw.particleAtlas() && !flameSpritesRead) {
                        this.readFlameSprites();
                        flameSpritesRead = true;
                    }
                    MetalNative.passSetBytes(
                        enc, 0, draw.particleAtlas() ? this.flameSprites : this.noFlameSprites, FLAME_PARTICLES.length * 16, MetalConst.STAGE_FRAGMENT
                    );
                }
                MetalNative.passSetVertexBuffer(enc, 0, draw.vertexBuffer(), draw.vertexOffset());
                MetalNative.passSetBuffer(enc, 0, draw.transforms(), draw.transformsOffset(), MetalConst.STAGE_VERTEX);
                MetalNative.passSetTexture(enc, 0, draw.atlas(), draw.atlasSampler(), MetalConst.STAGE_FRAGMENT);
                MetalNative.passDrawIndexed(
                    enc, MetalConst.PRIM_TRIANGLES, draw.indexCount(), draw.indexType(), draw.indexBuffer(),
                    (long)draw.firstIndex() * (draw.indexType() == 1 ? 4 : 2), draw.instanceCount(), draw.vertexBase(), draw.firstInstance()
                );
            }
        }
    }

    /** Whether this is the texture of the particle atlas (flames, smoke...), not the block or item atlas debris uses. */
    boolean isParticleAtlas(final Object texture) {
        try {
            return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.PARTICLES).getTexture() == texture;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Fills flameSprites with where the flame particles' sprites lie in the particle atlas (u0, v0, u1, v1 each). */
    private void readFlameSprites() {
        this.flameSprites.fill((byte)0);
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.PARTICLES);
            TextureAtlasSprite missing = atlas.missingSprite();
            for (int i = 0; i < FLAME_PARTICLES.length; i++) {
                TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace(FLAME_PARTICLES[i]));
                if (sprite == null || sprite == missing || sprite.contents().name().equals(missing.contents().name())) {
                    continue;
                }
                this.flameSprites.set(ValueLayout.JAVA_FLOAT, i * 16, sprite.getU0());
                this.flameSprites.set(ValueLayout.JAVA_FLOAT, i * 16 + 4, sprite.getV0());
                this.flameSprites.set(ValueLayout.JAVA_FLOAT, i * 16 + 8, sprite.getU1());
                this.flameSprites.set(ValueLayout.JAVA_FLOAT, i * 16 + 12, sprite.getV1());
            }
        } catch (RuntimeException e) {
            // No particle atlas yet: no flames are marked this frame.
        }
    }

    /** A shadow descriptor with one colour target that only writes alpha, no blending. */
    private static int[] alphaOnlyDescriptor(final int[] shadowDesc) {
        int vertexPart = shadowDesc.length - 3;
        int[] desc = new int[vertexPart + 1 + 9 + 2];
        System.arraycopy(shadowDesc, 0, desc, 0, vertexPart);
        int i = vertexPart;
        desc[i++] = 1;
        desc[i++] = COLOR_FORMAT;
        desc[i++] = 8; // alpha only
        for (int k = 0; k < 7; k++) {
            desc[i++] = 0; // no blend
        }
        desc[i++] = DEPTH_FORMAT;
        desc[i] = MetalConst.PRIM_TRIANGLES;
        return desc;
    }

    private static final byte FOLIAGE_LEAVES = 1;
    private static final byte FOLIAGE_PLANT = 2;
    private static final byte FOLIAGE_LOWER = 3;
    private static final byte FOLIAGE_UPPER = 4;
    private static final byte FOLIAGE_HANGING = 5;
    /** Low-nibble flag beside the foliage kind: the sprite's quads are not shaded by their direction (foliage.metal). */
    private static final byte SPRITE_UNSHADED = 8;

    /** How a block's model waves (FOLIAGE_* in foliage.metal), or 0 if it stays still. */
    private static byte foliageKind(final BlockState state) {
        Block block = state.getBlock();
        if (state.is(BlockTags.LEAVES)) {
            return FOLIAGE_LEAVES;
        }
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock || block instanceof AzaleaBlock || block instanceof SeaPickleBlock) {
            return 0; // kelp belongs to the water; azalea bushes and sea pickles are solid little boxes
        }
        if (block instanceof DoublePlantBlock) {
            return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? FOLIAGE_UPPER : FOLIAGE_LOWER;
        }
        if (block instanceof VineBlock || block instanceof GrowingPlantBlock || block instanceof HangingRootsBlock
            || block instanceof HangingMossBlock || block instanceof SugarCaneBlock) {
            return FOLIAGE_HANGING; // stacked or wall-hugging: the whole block moves, so stacks never tear apart
        }
        if (block instanceof VegetationBlock) {
            return FOLIAGE_PLANT; // grass, ferns, flowers, saplings, crops, bushes, roots, petals (flat quads stay put)
        }
        return 0;
    }

    /** Sprite materials in the high four bits of a sprite-map byte (SPRITE_* in terrain.metal). */
    private static final String[][] MATERIAL_SPRITES = {SHINY_SPRITES, ICE_SPRITES, FLAME_SPRITES, GLOW_SPRITES, WATER_GLASS_SPRITES};

    /**
     * Builds the sprite map from the block registry and the loaded block models; cheap when nothing changed. Each
     * byte holds how the sprite's blocks wave (low four bits) and the sprite's material (high four bits).
     */
    private void updateSpriteMap() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
            TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
            TextureAtlasSprite missing = atlas.missingSprite();
            if (models == null || models == this.foliageModels) {
                return;
            }
            // Every sprite used by a waving block's models (all states, a few random variants). Built with waving off too:
            // terrain lighting tells foliage from other tinted blocks (grass blocks) by it.
            Map<TextureAtlasSprite, Byte> sprites = new HashMap<>();
            List<BlockStateModelPart> parts = new ArrayList<>();
            Direction[] sides = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    byte kind = foliageKind(state);
                    if (kind == 0) {
                        continue;
                    }
                    BlockStateModel model = models.get(state);
                    for (int seed = 0; seed < 4; seed++) {
                        parts.clear();
                        model.collectParts(RandomSource.create(seed), parts);
                        for (BlockStateModelPart part : parts) {
                            for (Direction side : sides) {
                                for (BakedQuad quad : part.getQuads(side)) {
                                    TextureAtlasSprite sprite = quad.materialInfo().sprite();
                                    if (sprite != null && sprite != missing) {
                                        // A sprite shared by blocks of different kinds deterministically takes the higher kind.
                                        sprites.merge(sprite, kind, (a, b) -> a.byteValue() == b.byteValue() ? a : (byte)Math.max(a, b));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Sprites drawn only on quads that vanilla lights as if they faced up (torches, lanterns, vines, ladders,
            // flower beds: "shade_direction_override"), whatever their real direction. Terrain divides vanilla's face
            // shading back out by the face's direction, which must not happen for these (SPRITE_UNSHADED).
            Set<TextureAtlasSprite> overridden = new HashSet<>();
            Set<TextureAtlasSprite> shaded = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    parts.clear();
                    models.get(state).collectParts(RandomSource.create(0), parts);
                    for (BlockStateModelPart part : parts) {
                        for (Direction side : sides) {
                            for (BakedQuad quad : part.getQuads(side)) {
                                TextureAtlasSprite sprite = quad.materialInfo().sprite();
                                if (sprite == null || sprite == missing) {
                                    continue;
                                }
                                Direction override = quad.materialInfo().shadeDirectionOverride();
                                (override != null && override != quad.direction() ? overridden : shaded).add(sprite);
                            }
                        }
                    }
                }
            }
            overridden.removeAll(shaded);
            for (TextureAtlasSprite sprite : overridden) {
                sprites.merge(sprite, SPRITE_UNSHADED, (a, b) -> (byte)(a | b));
            }
            for (int material = 0; material < MATERIAL_SPRITES.length; material++) {
                for (String name : MATERIAL_SPRITES[material]) {
                    TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace("block/" + name));
                    if (sprite == null || sprite == missing || sprite.contents().name().equals(missing.contents().name())) {
                        continue;
                    }
                    byte bits = (byte)((material + 1) << 4);
                    sprites.merge(sprite, bits, (a, b) -> (byte)((a & 15) | b));
                }
            }
            this.foliageModels = models;
            this.uploadSpriteMap(sprites);
        } catch (RuntimeException e) {
            LOGGER.warn("Ciderlight shaders: could not build the sprite map", e);
            this.foliageModels = null;
        }
    }

    /** Marks the item atlas sprites of metal tools, weapons and armour; cheap when nothing changed. */
    private void updateHandSprites() {
        Map<TextureAtlasSprite, Byte> sprites = new HashMap<>();
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.ITEMS);
            TextureAtlasSprite missing = atlas.missingSprite();
            List<String> names = new ArrayList<>(List.of(METAL_ITEMS));
            for (String material : METAL_MATERIALS) {
                for (String shape : METAL_SHAPES) {
                    names.add(material + "_" + shape);
                }
            }
            for (String name : names) {
                TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace("item/" + name));
                if (sprite != null && sprite != missing && !sprite.contents().name().equals(missing.contents().name())) {
                    sprites.put(sprite, (byte)1);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Ciderlight shaders: could not read the item atlas, held items will not shine", e);
        }
        byte[] bytes = spriteGrid(sprites);
        if (this.handSpriteBuffer != 0L && Arrays.equals(bytes, this.handSpriteBytes)) {
            return;
        }
        long buffer = MetalNative.bufferCreate(this.device.context(), bytes.length, false);
        MemorySegment.ofAddress(MetalNative.bufferContents(buffer)).reinterpret(bytes.length).copyFrom(MemorySegment.ofArray(bytes));
        MetalNative.setLabel(buffer, "Ciderlight held item sprite map");
        long old = this.handSpriteBuffer;
        if (old != 0L) {
            this.device.encoder().queueForDestroy(() -> MetalNative.release(old));
        }
        this.handSpriteBuffer = buffer;
        this.handSpriteBytes = bytes;
    }

    /**
     * The light the cloud vertex shader shades its faces with (MetalRenderPipeline.sunlitClouds): the sun's direction
     * and strength, and at golden hour a warm tint for the faces turned to it and a cool one for those turned away,
     * both taken from the surface light with their brightness evened out to 1 and softened.
     */
    void bindCloudLight(final long enc) {
        MemorySegment f = this.sampleFrame;
        MemorySegment c = this.cloudLight;
        for (int i = 0; i < 4; i++) {
            c.set(ValueLayout.JAVA_FLOAT, i * 4, f.get(ValueLayout.JAVA_FLOAT, 80 + i * 4));
        }
        boolean moon = f.get(ValueLayout.JAVA_FLOAT, 360) > 0.5F;
        float golden = moon ? 0.0F : f.get(ValueLayout.JAVA_FLOAT, AIR_NEAR_OFFSET + 8);
        for (int t = 0; t < 2; t++) {
            int at = SURFACE_LIGHT_OFFSET + t * 16;
            float r = f.get(ValueLayout.JAVA_FLOAT, at), g = f.get(ValueLayout.JAVA_FLOAT, at + 4), b = f.get(ValueLayout.JAVA_FLOAT, at + 8);
            float lum = Math.max(0.3F * r + 0.59F * g + 0.11F * b, 1.0e-3F);
            float soft = t == 0 ? 0.5F : 0.45F;
            float dim = t == 0 ? 1.0F : 0.95F; // the shaded side is a little darker as well as cooler
            c.set(ValueLayout.JAVA_FLOAT, 16 + t * 16, lerp(1.0F, r / lum, soft) * dim);
            c.set(ValueLayout.JAVA_FLOAT, 20 + t * 16, lerp(1.0F, g / lum, soft) * dim);
            c.set(ValueLayout.JAVA_FLOAT, 24 + t * 16, lerp(1.0F, b / lum, soft) * dim);
        }
        c.set(ValueLayout.JAVA_FLOAT, 28, golden);
        MetalNative.passSetBytes(enc, FRAME_INDEX, c, 48, MetalConst.STAGE_VERTEX);
    }

    /** Called when the first-person "Item in hand" pass opens: the data its item pipelines' shine needs. */
    void bindHand(final long enc, final int width, final int height) {
        if (this.handSpriteBuffer == 0L) {
            this.updateHandSprites();
        }
        // Where the sun or moon stands in the view decides where the highlight band lies across the screen: it
        // slides over the item as the player turns, and is strongest with the light ahead.
        Vector3f light = this.currentView.transformDirection(new Vector3f(this.previousLight));
        float strength = this.sampleFrame.get(ValueLayout.JAVA_FLOAT, 92) * this.eyeSky;
        boolean moon = this.sampleFrame.get(ValueLayout.JAVA_FLOAT, 360) > 0.5F;
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 0, 0.95F + 0.6F * light.x + 0.25F * light.y);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 4, 0.0F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 8, (moon ? 0.35F : 1.0F) * strength * (0.45F + 0.55F * Math.clamp(0.5F - light.z, 0.0F, 1.0F)));
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 12, 0.0F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 16, moon ? 0.75F : 1.0F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 20, moon ? 0.85F : 0.96F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 24, moon ? 1.0F : 0.86F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 28, 0.07F);
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 32, 1.0F / Math.max(width, 1));
        this.handFrame.set(ValueLayout.JAVA_FLOAT, 36, 1.0F / Math.max(height, 1));
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.handFrame, 48, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.handSpriteBuffer, 0L, MetalConst.STAGE_FRAGMENT);
    }

    /** Rasterises the sprite rectangles into a grid whose cells never straddle the edge of a marked sprite. */
    private void uploadSpriteMap(final Map<TextureAtlasSprite, Byte> sprites) {
        byte[] bytes = spriteGrid(sprites);
        if (DEBUG || MetalDebug.ENABLED) {
            LOGGER.info("Ciderlight shaders: {} waving or special sprites in the block sprite map", sprites.size());
        }
        if (this.foliageBuffer != 0L && Arrays.equals(bytes, this.foliageBytes)) {
            return;
        }
        // A fresh buffer each time: frames still in flight keep reading the old one until it is released.
        long buffer = MetalNative.bufferCreate(this.device.context(), bytes.length, false);
        MemorySegment.ofAddress(MetalNative.bufferContents(buffer)).reinterpret(bytes.length).copyFrom(MemorySegment.ofArray(bytes));
        MetalNative.setLabel(buffer, "Ciderlight sprite map");
        long old = this.foliageBuffer;
        if (old != 0L) {
            this.device.encoder().queueForDestroy(() -> MetalNative.release(old));
        }
        this.foliageBuffer = buffer;
        this.foliageBytes = bytes;
    }

    /** The sprite-map bytes for these sprites of one atlas: a header, then one byte per grid cell (foliage.metal). */
    private static byte[] spriteGrid(final Map<TextureAtlasSprite, Byte> sprites) {
        // Sprite rectangles in atlas texels, from their UVs (getX/getY exclude the stitcher's border padding).
        int cell = 0;
        int atlasWidth = 0;
        int atlasHeight = 0;
        for (TextureAtlasSprite sprite : sprites.keySet()) {
            atlasWidth = Math.max(atlasWidth, Math.round(sprite.contents().width() / (sprite.getU1() - sprite.getU0())));
            atlasHeight = Math.max(atlasHeight, Math.round(sprite.contents().height() / (sprite.getV1() - sprite.getV0())));
        }
        List<int[]> rects = new ArrayList<>();
        for (Map.Entry<TextureAtlasSprite, Byte> e : sprites.entrySet()) {
            TextureAtlasSprite sprite = e.getKey();
            int x0 = Math.round(sprite.getU0() * atlasWidth);
            int y0 = Math.round(sprite.getV0() * atlasHeight);
            int x1 = Math.round(sprite.getU1() * atlasWidth);
            int y1 = Math.round(sprite.getV1() * atlasHeight);
            cell = gcd(gcd(gcd(gcd(cell, x0), y0), x1), y1);
            rects.add(new int[]{x0, y0, x1, y1, e.getValue()});
        }
        byte[] bytes;
        if (sprites.isEmpty() || cell <= 0 || atlasWidth <= 0 || atlasHeight <= 0) {
            bytes = new byte[16];
        } else {
            int gridW = (atlasWidth + cell - 1) / cell;
            int gridH = (atlasHeight + cell - 1) / cell;
            if ((long)gridW * gridH > 16L << 20) {
                LOGGER.warn("Ciderlight shaders: atlas grid too fine ({}x{}), its sprites are left unmarked", gridW, gridH);
                bytes = new byte[16];
            } else {
                bytes = new byte[16 + gridW * gridH];
                ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                header.putFloat(0, (float)atlasWidth / cell);
                header.putFloat(4, (float)atlasHeight / cell);
                header.putInt(8, gridW);
                header.putInt(12, gridH);
                for (int[] r : rects) {
                    for (int y = r[1] / cell; y < Math.min(gridH, r[3] / cell); y++) {
                        for (int x = r[0] / cell; x < Math.min(gridW, r[2] / cell); x++) {
                            bytes[16 + y * gridW + x] = (byte)r[4];
                        }
                    }
                }
            }
        }
        return bytes;
    }

    private static int gcd(final int a, final int b) {
        return b == 0 ? Math.abs(a) : gcd(b, a % b);
    }

    /** The sprite map, or an empty one (nothing waves or shines) until the block models are available. */
    private long foliageBuffer() {
        if (this.foliageBuffer == 0L) {
            this.updateSpriteMap();
            if (this.foliageBuffer == 0L) {
                this.uploadSpriteMap(Map.of());
            }
        }
        return this.foliageBuffer;
    }

    /**
     * Drops the reprojected shadow and fog histories. The shadow maps are left alone: they hold where the light's
     * shadows fall in the world, which stays true when the camera enters water or a frame hitches, and discarding them
     * would draw the next frame with no shadows at all (a one-frame flash).
     */
    private void invalidateHistory() {
        this.shadowHistoryValid = false;
        this.volumetricHistory = false;
    }

    /** The shadow maps describe another world: don't sample them until they are rendered again. */
    private void invalidateShadowMaps() {
        this.shadowValid = false;
        this.farShadowValid = false;
        this.farStrip = -1;
    }

    private void ensureShadowHistory() {
        // Only the reusable visibility history is half resolution. Visible shading and its current PCF sample
        // stay at full resolution; depth-aware reconstruction rejects history across silhouettes.
        int width = Math.max(1, (this.mainWidth + 1) / 2);
        int height = Math.max(1, (this.mainHeight + 1) / 2);
        if (this.shadowHistoryTextures[0] != 0L && this.shadowHistoryWidth == width && this.shadowHistoryHeight == height) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            long old = this.shadowHistoryTextures[i];
            long oldTranslucent = this.translucentShadowHistoryTextures[i];
            if (old != 0L) {
                this.device.encoder().queueForDestroy(() -> {
                    MetalNative.release(old);
                    MetalNative.release(oldTranslucent);
                });
            }
            this.shadowHistoryTextures[i] = MetalNative.textureCreate(
                this.device.context(), SHADOW_HISTORY_FORMAT, width, height, 1, 1, 4 | 8
            );
            this.translucentShadowHistoryTextures[i] = MetalNative.textureCreate(
                this.device.context(), SHADOW_HISTORY_FORMAT, width, height, 1, 1, 4 | 8
            );
            MetalNative.setLabel(this.shadowHistoryTextures[i], "Ciderlight surface shadows " + i);
            MetalNative.setLabel(this.translucentShadowHistoryTextures[i], "Ciderlight translucent surface shadows " + i);
        }
        this.shadowHistoryWidth = width;
        this.shadowHistoryHeight = height;
        this.shadowHistoryValid = false;
    }

    private void renderShadowHistory(final long frame) {
        if (this.shadowHistoryState == 0L) {
            this.shadowHistoryState = this.fullscreenState("shadow_history_fragment", SHADOW_HISTORY_FORMAT, SHADOW_HISTORY_STATE);
        }
        MetalNative.profileLabel("shadow history");
        // Two layers wherever translucent terrain was drawn: the ground behind it (the depth from before translucent
        // terrain, like ambientOcclusion) and the translucent surface itself (the full depth). With only one, the other
        // layer's pixels never matched their history and showed the raw jittered shadow every frame.
        long behind = this.opaqueSnapshotThisFrame ? this.opaqueDepth : this.mainDepth;
        this.renderShadowHistoryLayer(frame, this.shadowHistoryTextures, behind);
        this.renderShadowHistoryLayer(frame, this.translucentShadowHistoryTextures, this.mainDepth);
        this.shadowHistoryValid = this.shadowValid;
    }

    private void renderShadowHistoryLayer(final long frame, final long[] textures, final long depth) {
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{textures[this.shadowHistoryIndex]},
            this.shadowHistoryWidth, this.shadowHistoryHeight);
        MetalNative.passSetPipeline(enc, this.shadowHistoryState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, depth, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 1, this.shadowTexture(), this.shadowSampler, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 2, textures[1 - this.shadowHistoryIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 3, this.shadowColorTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 7, this.cloudMapTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
    }

    /**
     * Marches the shadow map for volumetric light into a small texture, blended with last frame's reprojected result.
     * The texture is about 480 rows high whatever the window's size (half resolution in a 960-row window, a quarter
     * at 1900 rows): fog is soft, and the march is the most expensive thing on screen per pixel.
     */
    private void volumetric(final long frame) {
        int divisor = Math.max(2, Math.round(this.mainHeight / (float)this.quality.fogRows));
        int width = Math.max(1, (this.mainWidth + divisor - 1) / divisor);
        int height = Math.max(1, (this.mainHeight + divisor - 1) / divisor);
        if (this.volumetricTextures[0] == 0L || this.volumetricWidth != width || this.volumetricHeight != height) {
            for (int i = 0; i < 2; i++) {
                if (this.volumetricTextures[i] != 0L) {
                    long old = this.volumetricTextures[i];
                    long oldExtinction = this.extinctionTextures[i];
                    this.device.encoder().queueForDestroy(() -> { MetalNative.release(old); MetalNative.release(oldExtinction); });
                }
                this.volumetricTextures[i] = MetalNative.textureCreate(this.device.context(), VOLUMETRIC_FORMAT, width, height, 1, 1, 4 | 8);
                this.extinctionTextures[i] = MetalNative.textureCreate(this.device.context(), EXTINCTION_FORMAT, width, height, 1, 1, 4 | 8);
                MetalNative.setLabel(this.extinctionTextures[i], "Ciderlight fog transmittance " + i);
                MetalNative.setLabel(this.volumetricTextures[i], "Ciderlight volumetric " + i);
            }
            this.volumetricWidth = width;
            this.volumetricHeight = height;
            this.volumetricHistory = false;
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 452, 0.0F);
        }
        if (this.volumetricState == 0L) {
            this.volumetricState = this.built(VOLUMETRIC_STATE, this::buildVolumetricState);
        }
        if (this.airNoiseTexture == 0L) {
            this.renderAirNoiseTable(frame);
        }
        long target = this.volumetricTextures[this.volumetricIndex];
        long history = this.volumetricTextures[1 - this.volumetricIndex];
        MetalNative.profileLabel("volumetric");
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{target, this.extinctionTextures[this.volumetricIndex]}, width, height);
        MetalNative.passSetPipeline(enc, this.volumetricState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, this.mainDepth, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 1, this.shadowTexture(), this.shadowSampler, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 2, history, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 3, this.shadowColorTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 4, this.farShadowTexture(this.farFront), this.shadowSampler, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 5, this.extinctionTextures[1 - this.volumetricIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 6, this.farShadowColorTexture(this.farFront), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 7, this.cloudMapTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 8, this.lightningShadowTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 9, this.airNoiseTexture, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
        this.volumetricHistory = true;
    }

    /** Fills the fog's noise table with the noise hash: one filtered read per octave instead of four hashes in the march. */
    private void renderAirNoiseTable(final long frame) {
        this.airNoiseTexture = MetalNative.textureCreate(this.device.context(), EXTINCTION_FORMAT, AIR_NOISE_TABLE, AIR_NOISE_TABLE, 1, 1, 4 | 8);
        MetalNative.setLabel(this.airNoiseTexture, "Ciderlight fog noise");
        long state = this.fullscreenState("air_noise_table_fragment", EXTINCTION_FORMAT, FOG_NOISE_STATE);
        MetalNative.profileLabel("fog noise table");
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{this.airNoiseTexture}, AIR_NOISE_TABLE, AIR_NOISE_TABLE);
        MetalNative.passSetPipeline(enc, state, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
    }

    private void ensureAmbientOcclusion() {
        if (!this.ao) {
            return;
        }
        // Half resolution up to about 1600 rows, a third above (ao_scale in ao.metal); coarser at low quality.
        this.aoDivisor = Math.max(2, Math.round(this.mainHeight / (float)this.quality.aoRows));
        int width = Math.max(1, (this.mainWidth + this.aoDivisor - 1) / this.aoDivisor);
        int height = Math.max(1, (this.mainHeight + this.aoDivisor - 1) / this.aoDivisor);
        if (this.aoTextures[0] != 0L && this.aoWidth == width && this.aoHeight == height) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            long old = this.aoTextures[i];
            if (old != 0L) {
                this.device.encoder().queueForDestroy(() -> MetalNative.release(old));
            }
            this.aoTextures[i] = MetalNative.textureCreate(this.device.context(), AO_FORMAT, width, height, 1, 1, 4 | 8);
            MetalNative.setLabel(this.aoTextures[i], "Ciderlight ambient occlusion " + i);
        }
        long oldRaw = this.aoRawTexture;
        if (oldRaw != 0L) {
            this.device.encoder().queueForDestroy(() -> MetalNative.release(oldRaw));
        }
        this.aoRawTexture = MetalNative.textureCreate(this.device.context(), AO_FORMAT, width, height, 1, 1, 4 | 8);
        MetalNative.setLabel(this.aoRawTexture, "Ciderlight ambient occlusion (raw)");
        this.aoWidth = width;
        this.aoHeight = height;
        this.aoHistoryValid = false;
    }

    /**
     * Half-resolution GTAO: a noisy evaluation pass, then a 3x3 same-surface average blended with reprojected history.
     * Uses the depth snapshot taken before translucent terrain when there is one, so terrain seen through water and
     * glass still gets contact shadows.
     */
    private void ambientOcclusion(final long frame) {
        if (this.aoState == 0L) {
            this.aoState = this.fullscreenState("ao_fragment", AO_FORMAT, AO_STATE);
            this.aoFilterState = this.fullscreenState("ao_filter_fragment", AO_FORMAT, AO_FILTER_STATE);
        }
        long depth = this.opaqueSnapshotThisFrame ? this.opaqueDepth : this.mainDepth;
        MetalNative.profileLabel("ao");
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{this.aoRawTexture}, this.aoWidth, this.aoHeight);
        MetalNative.passSetPipeline(enc, this.aoState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, depth, 0L, MetalConst.STAGE_FRAGMENT);
        // Scene alpha marks thin plants, which are not occluders (matches the depth texture chosen above).
        MetalNative.passSetTexture(enc, 1, this.opaqueSnapshotThisFrame ? this.opaqueColor : this.mainColor, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);

        MetalNative.profileLabel("ao filter");
        enc = MetalNative.passBeginOverwrite(frame, new long[]{this.aoTextures[this.aoIndex]}, this.aoWidth, this.aoHeight);
        MetalNative.passSetPipeline(enc, this.aoFilterState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, depth, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 1, this.aoRawTexture, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 2, this.aoTextures[1 - this.aoIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
        this.aoHistoryValid = true;
    }

    float renderScale() {
        return this.quality.renderScale;
    }

    /**
     * Stretches the world, drawn at a fraction of the window's resolution (RenderScale), over the window-sized main
     * target, with a little sharpening (upscale_fragment); the hand was drawn with the world, the HUD comes after.
     */
    void upscale(final long frame, final long source, final long target, final int width, final int height) {
        if (this.upscaleState == 0L) {
            this.upscaleState = this.fullscreenState("upscale_fragment", COLOR_FORMAT, UPSCALE_STATE);
        }
        MetalNative.profileLabel("upscale");
        long enc = MetalNative.passBeginOverwrite(frame, new long[]{target}, width, height);
        MetalNative.passSetPipeline(enc, this.upscaleState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, source, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
    }

    private void composite(final long frame) {
        if (this.compositeState == 0L) {
            this.compositeState = this.fullscreenState("composite_fragment", COLOR_FORMAT, COMPOSITE_STATE);
        }
        MetalNative.profileLabel("composite");
        long enc = MetalNative.passBegin(frame, new long[]{this.mainColor}, new int[]{0}, 0, new float[4], 0L, 0, false, 0.0, this.mainWidth, this.mainHeight);
        MetalNative.passSetPipeline(enc, this.compositeState, 0L, false, false, 0.0F, 0.0F);
        MetalNative.passSetBytes(enc, 0, this.sampleFrame, FRAME_BYTES, BOTH);
        MetalNative.passSetTexture(enc, 0, this.mainDepth, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 1, this.shadowTexture(), this.shadowSampler, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 2, this.volumetricTextures[this.volumetricIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 3, this.shadowColorTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 4, this.translucentShadowHistoryTextures[1 - this.shadowHistoryIndex], 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 5, this.extinctionTextures[this.volumetricIndex], 0L, MetalConst.STAGE_FRAGMENT);
        if (this.ao) {
            MetalNative.passSetTexture(enc, 6, this.aoTextures[this.aoIndex], 0L, MetalConst.STAGE_FRAGMENT);
        }
        MetalNative.passSetTexture(enc, 7, this.cloudMapTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetTexture(enc, 8, this.lightningShadowTexture(), 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passDraw(enc, MetalConst.PRIM_TRIANGLES, 0, 3, 1, 0);
        MetalNative.passEnd(enc);
        this.volumetricIndex = 1 - this.volumetricIndex;
    }

    private void renderShadowMap(final long frame) {
        int[] idx = this.uniformIndices;
        if (idx == null) {
            return;
        }
        this.shadowCullCamera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        this.ensureShadowDepthState();
        MetalNative.profileLabel("shadow map");
        long enc = MetalNative.passBegin(frame, new long[0], new int[0], 0, new float[4], this.shadowTexture(), 0, true, 1.0, this.shadowSize, this.shadowSize);
        // Casters nearer the light than the map's near plane (hills toward a low sun) are kept at depth 0 rather than
        // clipped, so the near map is complete along the light and fog inside it need not consult the far map.
        MetalNative.passSetDepthClamp(enc, true);
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.renderFrame, FRAME_BYTES, MetalConst.STAGE_VERTEX);
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.foliageBuffer(), 0L, MetalConst.STAGE_VERTEX);

        this.drawOpaqueShadowCasters(enc, idx, this.renderFrame);
        MetalNative.passEnd(enc);

        // Clouds shade the near map's area through the cloud map; only the far map (fog) keeps them in its tint.
        this.renderShadowTransmission(frame, this.shadowTexture(), this.shadowColorTexture(), this.transmissionSize, this.renderFrame, this.renderFrame, idx, false, -1);
        this.renderCloudMap(frame);
        this.lastShadowMatrix.set(this.nextShadowMatrix);
        this.lastShadowAnchor = this.nextShadowAnchor;
        this.shadowValid = true;
    }

    /**
     * Colour and depth maps must use exactly the same captured light matrix and caster list. `depth` is the finished
     * depth map: it is sampled, not attached, as the colour map is smaller.
     */
    /**
     * @param uniforms the frame data the shadow vertex shader draws with
     * @param cullUniforms the same with the matrix its chunk sections are culled against
     * @param strip the strip of the map to draw (see renderFarShadowMap), or -1 for all of it
     */
    private void renderShadowTransmission(final long frame, final long depth, final long color, final int size, final MemorySegment uniforms,
                                          final MemorySegment cullUniforms, final int[] idx, final boolean clouds, final int strip) {
        MetalNative.profileLabel("shadow transmission");
        long enc = MetalNative.passBegin(frame, new long[]{color}, new int[]{0}, strip <= 0 ? 1 : 0,
            new float[]{1.0F, 1.0F, 1.0F, 1.0F}, 0L, 0, false, 1.0, size, size);
        if (strip >= 0) {
            MetalNative.passSetScissor(enc, strip * size / FAR_STRIPS, 0, size / FAR_STRIPS, size, size, size);
        }
        MetalNative.passSetDepthClamp(enc, true); // as for the depth map
        MetalNative.passSetBytes(enc, FRAME_INDEX, uniforms, FRAME_BYTES, MetalConst.STAGE_VERTEX);
        MetalNative.passSetTexture(enc, FRAME_INDEX, depth, 0L, MetalConst.STAGE_FRAGMENT);
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.foliageBuffer(), 0L, MetalConst.STAGE_VERTEX);
        boolean bound = false;
        for (TerrainDraw draw : this.terrainDraws) {
            if (draw.kind() != KIND_TRANSLUCENT) {
                continue;
            }
            if (!bound) {
                bound = true;
                if (this.shadowTranslucentState == 0L) {
                    int[] descriptor = draw.shadowDescriptor();
                    this.shadowTranslucentState = this.built(SHADOW_TRANSLUCENT_STATE, () -> this.buildTranslucentShadowState(descriptor));
                }
                MetalNative.passSetPipeline(enc, this.shadowTranslucentState, 0L, false, false, 0.0F, 0.0F);
            }
            MetalNative.passSetVertexBuffer(enc, 0, draw.vertexBuffer(), draw.vertexOffset());
            MetalNative.passSetVertexBuffer(enc, 1, draw.instanceBuffer(), draw.instanceOffset());
            MetalNative.passSetBuffer(enc, idx[0], draw.globals(), draw.globalsOffset(), MetalConst.STAGE_VERTEX);
            MetalNative.passSetTexture(enc, idx[5], draw.atlas(), draw.atlasSampler(), MetalConst.STAGE_FRAGMENT);
            this.drawShadowSections(enc, draw, cullUniforms);
        }
        // Clouds dim the light like a grey filter rather than blocking it: the ground and the fog under them are
        // shaded, sunlight through the gaps forms shafts, and the receiver can give their shadow a wide soft edge.
        if (clouds && !this.cloudDraws.isEmpty()) {
            if (this.cloudShadowState == 0L) {
                this.cloudShadowState = this.built(CLOUD_SHADOW_STATE, this::buildCloudShadowState);
            }
            MetalNative.passSetPipeline(enc, this.cloudShadowState, 0L, false, false, 0.0F, 0.0F);
            for (CloudDraw draw : this.cloudDraws) {
                MetalNative.passSetBuffer(enc, 0, draw.transforms(), draw.transformsOffset(), MetalConst.STAGE_VERTEX);
                MetalNative.passSetBuffer(enc, 1, draw.info(), draw.infoOffset(), MetalConst.STAGE_VERTEX);
                MetalNative.passSetTexture(enc, 0, draw.faces(), 0L, MetalConst.STAGE_VERTEX);
                MetalNative.passDrawIndexed(
                    enc, MetalConst.PRIM_TRIANGLES, draw.indexCount(), draw.indexType(), draw.indexBuffer(),
                    (long)draw.firstIndex() * (draw.indexType() == 1 ? 4 : 2), 1, draw.vertexBase(), 0
                );
            }
        }
        MetalNative.passEnd(enc);
    }

    private long cloudMapTexture() {
        if (this.cloudMapTexture == 0L) {
            this.cloudMapTexture = MetalNative.textureCreate(this.device.context(), CLOUD_MAP_FORMAT, CLOUD_MAP_SIZE, CLOUD_MAP_SIZE, 1, 1, 8 | 4);
            MetalNative.setLabel(this.cloudMapTexture, "Ciderlight cloud shadows");
        }
        return this.cloudMapTexture;
    }

    /** Draws the clouds from the light into the cloud map (white where nothing dims the light), with the near map's matrix. */
    private void renderCloudMap(final long frame) {
        MetalNative.profileLabel("cloud map");
        long enc = MetalNative.passBegin(frame, new long[]{this.cloudMapTexture()}, new int[]{0}, 1,
            new float[]{1.0F, 1.0F, 1.0F, 1.0F}, 0L, 0, false, 0.0, CLOUD_MAP_SIZE, CLOUD_MAP_SIZE);
        this.cloudBase = Double.NaN;
        if (!this.cloudDraws.isEmpty()) {
            if (this.cloudMapState == 0L) {
                this.cloudMapState = this.built(CLOUD_MAP_STATE, this::buildCloudMapState);
            }
            MetalNative.passSetPipeline(enc, this.cloudMapState, 0L, false, false, 0.0F, 0.0F);
            MetalNative.passSetBytes(enc, FRAME_INDEX, this.renderFrame, FRAME_BYTES, MetalConst.STAGE_VERTEX);
            for (CloudDraw draw : this.cloudDraws) {
                MetalNative.passSetBuffer(enc, 0, draw.transforms(), draw.transformsOffset(), MetalConst.STAGE_VERTEX);
                MetalNative.passSetBuffer(enc, 1, draw.info(), draw.infoOffset(), MetalConst.STAGE_VERTEX);
                MetalNative.passSetTexture(enc, 0, draw.faces(), 0L, MetalConst.STAGE_VERTEX);
                MetalNative.passDrawIndexed(
                    enc, MetalConst.PRIM_TRIANGLES, draw.indexCount(), draw.indexType(), draw.indexBuffer(),
                    (long)draw.firstIndex() * (draw.indexType() == 1 ? 4 : 2), 1, draw.vertexBase(), 0
                );
            }
            // The cloud base in the world, as the cloud renderer places it.
            Minecraft minecraft = Minecraft.getInstance();
            this.cloudBase = minecraft.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.CLOUD_HEIGHT, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }
        MetalNative.passEnd(enc);
    }

    /**
     * Draws a captured terrain multidraw into a shadow map. Most of the frame's sections lie outside the near map (it
     * covers SHADOW_RADIUS blocks, the draws reach to the render distance), so the sections are tested against the map
     * described by `uniforms` (null: no such test) and only those that can land in it are drawn.
     */
    private void drawShadowSections(final long enc, final TerrainDraw draw, @Nullable final MemorySegment uniforms) {
        int drawn = draw.drawCount();
        if (SHADOW_CULL && uniforms != null && draw.commandsAddress() != 0L) {
            // The shadow vertex shader places a vertex at (block position - camera) + camToAnchor before the matrix.
            Vec3 camera = this.shadowCullCamera;
            drawn = MetalNative.passDrawSectionsCulled(
                enc, MetalConst.PRIM_TRIANGLES, draw.indexType(), draw.indexBuffer(), draw.commands(), draw.commandsOffset(), draw.drawCount(), 20,
                draw.commandsAddress(), draw.sectionsAddress(), draw.sectionCount(), draw.sectionStride(), draw.sectionPosOffset(), uniforms,
                uniforms.get(ValueLayout.JAVA_FLOAT, 64) - camera.x, uniforms.get(ValueLayout.JAVA_FLOAT, 68) - camera.y,
                uniforms.get(ValueLayout.JAVA_FLOAT, 72) - camera.z, SHADOW_CULL_SLACK
            );
        } else {
            MetalNative.passDrawIndexedIndirect(
                enc, MetalConst.PRIM_TRIANGLES, draw.indexType(), draw.indexBuffer(), 0L, draw.commands(), draw.commandsOffset(), draw.drawCount(), 20
            );
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.shadowSections(draw.drawCount(), drawn);
        }
    }

    private void drawOpaqueShadowCasters(final long enc, final int[] idx, @Nullable final MemorySegment uniforms) {
        int currentKind = -1;
        for (TerrainDraw draw : this.terrainDraws) {
            if (draw.kind() == KIND_TRANSLUCENT) {
                continue;
            }
            if (draw.kind() != currentKind) {
                currentKind = draw.kind();
                MetalNative.passSetPipeline(enc, this.terrainShadowState(draw), this.shadowDepthState, false, false, 1.0F, 2.0F);
            }
            MetalNative.passSetVertexBuffer(enc, 0, draw.vertexBuffer(), draw.vertexOffset());
            MetalNative.passSetVertexBuffer(enc, 1, draw.instanceBuffer(), draw.instanceOffset());
            MetalNative.passSetBuffer(enc, idx[0], draw.globals(), draw.globalsOffset(), MetalConst.STAGE_VERTEX);
            if (draw.kind() == KIND_CUTOUT) {
                MetalNative.passSetTexture(enc, idx[5], draw.atlas(), draw.atlasSampler(), MetalConst.STAGE_FRAGMENT);
            }
            this.drawShadowSections(enc, draw, uniforms);
        }

        long currentState = 0L;
        for (EntityDraw draw : this.entityDraws) {
            if (draw.particle() || draw.emissive()) {
                continue;
            }
            long state = this.entityShadowState(draw.shadowDescriptor());
            if (state != currentState) {
                currentState = state;
                MetalNative.passSetPipeline(enc, state, this.shadowDepthState, false, false, 1.0F, 2.0F);
            }
            MetalNative.passSetVertexBuffer(enc, 0, draw.vertexBuffer(), draw.vertexOffset());
            MetalNative.passSetBuffer(enc, 0, draw.transforms(), draw.transformsOffset(), MetalConst.STAGE_VERTEX);
            MetalNative.passSetTexture(enc, 0, draw.atlas(), draw.atlasSampler(), MetalConst.STAGE_FRAGMENT);
            MetalNative.passDrawIndexed(
                enc, MetalConst.PRIM_TRIANGLES, draw.indexCount(), draw.indexType(), draw.indexBuffer(),
                (long)draw.firstIndex() * (draw.indexType() == 1 ? 4 : 2), draw.instanceCount(), draw.vertexBase(), draw.firstInstance()
            );
        }
    }

    private long farShadowTexture(final int pair) {
        if (this.farShadowTextures[pair] == 0L) {
            this.farShadowTextures[pair] = MetalNative.textureCreate(this.device.context(), DEPTH_FORMAT, this.farShadowSize, this.farShadowSize, 1, 1, 8 | 4);
            MetalNative.setLabel(this.farShadowTextures[pair], "Ciderlight distant fog shadow map");
        }
        return this.farShadowTextures[pair];
    }

    private long farShadowColorTexture(final int pair) {
        if (this.farShadowColorTextures[pair] == 0L) {
            this.farShadowColorTextures[pair] = MetalNative.textureCreate(this.device.context(), TRANSMISSION_FORMAT, this.farShadowSize / 2, this.farShadowSize / 2, 1, 1, 8 | 4);
            MetalNative.setLabel(this.farShadowColorTextures[pair], "Ciderlight distant glass transmission");
        }
        return this.farShadowColorTextures[pair];
    }

    /**
     * Sizes the distant fog shadow map to the fog's range (a little more, so fog at the edge is covered), which follows
     * the render distance: terrain the player can see shadows the fog in front of it. Its chunk sections are casters.
     */
    private void updateFarShadowArea(final float fogRange) {
        float radius = Math.max(SHADOW_RADIUS, fogRange + 16.0F);
        if (Math.abs(radius - this.farShadowRadius) <= 0.5F) {
            return;
        }
        int size = radius > FAR_SHADOW_FINE_RADIUS ? this.quality.farShadowFineSize : this.quality.farShadowSize;
        if (size != this.farShadowSize) {
            for (int pair = 0; pair < 2; pair++) {
                long depth = this.farShadowTextures[pair];
                long color = this.farShadowColorTextures[pair];
                this.device.encoder().queueForDestroy(() -> {
                    MetalNative.release(depth);
                    MetalNative.release(color);
                });
                this.farShadowTextures[pair] = 0L;
                this.farShadowColorTextures[pair] = 0L;
            }
        }
        this.farShadowRadius = radius;
        this.farShadowSize = size;
        this.farShadowValid = false;
        this.farStrip = -1;
        // This frame's fog was set up to sample the old map; it is not drawn until the end of the frame.
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, FAR_ANCHOR_OFFSET + 12, 0.0F);
        casterRadius = radius + 8.0; // a little beyond the texel-snapped map's half-width
    }

    /**
     * Distant fog needs lower spatial resolution than the near map, and its map covers the whole render distance: drawn
     * in one go, every so often, it made a frame of its own 10-20 ms slower. It is drawn instead one vertical strip per
     * frame into the back pair of textures, with a matrix and anchor fixed for the four strips, and the pair is sampled
     * once complete; each strip draws only the chunk sections in it. A light or camera that moves on meanwhile is taken
     * up by the next pair, four frames on. With nothing to sample yet, all of the map is drawn at once.
     */
    private void renderFarShadowMap(final long frame) {
        if (this.uniformIndices == null) return;
        if (!this.farShadowValid) {
            this.startFarShadowMap();
            for (int strip = 0; strip < FAR_STRIPS; strip++) {
                this.drawFarShadowStrip(frame, strip);
            }
            this.finishFarShadowMap();
            return;
        }
        if (this.farStrip < 0) {
            this.startFarShadowMap();
        }
        this.drawFarShadowStrip(frame, this.farStrip);
        if (++this.farStrip == FAR_STRIPS) {
            this.finishFarShadowMap();
        }
    }

    private void startFarShadowMap() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        Vector3f light = this.previousLight;
        float radius = this.farShadowRadius;
        this.farPendingAnchor = texelSnappedAnchor(camera, light, radius, this.farShadowSize);
        this.farPendingMatrix.setOrtho(-radius, radius, -radius, radius, 0.0F, radius * 4.0F, true)
            .lookAt(light.x * radius * 2, light.y * radius * 2, light.z * radius * 2, 0, 0, 0, 0, 0, 1);
        this.farStrip = 0;
    }

    private void drawFarShadowStrip(final long frame, final int strip) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        this.shadowCullCamera = camera;
        this.farRenderFrame.copyFrom(this.renderFrame);
        writeMatrix(this.farRenderFrame, 0, this.farPendingMatrix);
        // Chunk sections are placed relative to this frame's camera, so the offset to the fixed anchor follows it.
        Vec3 offset = camera.subtract(this.farPendingAnchor);
        this.farRenderFrame.set(ValueLayout.JAVA_FLOAT, 64, (float)offset.x);
        this.farRenderFrame.set(ValueLayout.JAVA_FLOAT, 68, (float)offset.y);
        this.farRenderFrame.set(ValueLayout.JAVA_FLOAT, 72, (float)offset.z);
        // The strip spans x from -1 + strip / 2 to -1 + (strip + 1) / 2 in clip space; this matrix stretches it to -1..1.
        this.farCullFrame.copyFrom(this.farRenderFrame);
        writeMatrix(this.farCullFrame, 0, new Matrix4f().translate(FAR_STRIPS - 1 - 2 * strip, 0.0F, 0.0F).scale(FAR_STRIPS, 1.0F, 1.0F)
            .mul(this.farPendingMatrix));
        int back = 1 - this.farFront;
        int size = this.farShadowSize;
        MetalNative.profileLabel("far shadow map");
        long enc = MetalNative.passBegin(frame, new long[0], new int[0], 0, new float[4], this.farShadowTexture(back), 0, strip == 0, 1.0, size, size);
        MetalNative.passSetScissor(enc, strip * size / FAR_STRIPS, 0, size / FAR_STRIPS, size, size, size);
        MetalNative.passSetDepthClamp(enc, true);
        MetalNative.passSetBytes(enc, FRAME_INDEX, this.farRenderFrame, FRAME_BYTES, MetalConst.STAGE_VERTEX);
        MetalNative.passSetBuffer(enc, FOLIAGE_INDEX, this.foliageBuffer(), 0L, MetalConst.STAGE_VERTEX);
        this.drawOpaqueShadowCasters(enc, this.uniformIndices, this.farCullFrame);
        MetalNative.passEnd(enc);
        this.renderShadowTransmission(frame, this.farShadowTexture(back), this.farShadowColorTexture(back), size / 2, this.farRenderFrame, this.farCullFrame,
            this.uniformIndices, true, strip);
    }

    private void finishFarShadowMap() {
        this.farFront = 1 - this.farFront;
        this.lastFarShadowMatrix.set(this.farPendingMatrix);
        this.lastFarShadowAnchor = this.farPendingAnchor;
        this.farShadowValid = true;
        this.farStrip = -1;
    }

    /** The shadow map's companion colour texture: per-channel light transmission through translucent casters. */
    private long shadowColorTexture() {
        if (this.shadowColorTexture == 0L) {
            this.shadowColorTexture = MetalNative.textureCreate(this.device.context(), TRANSMISSION_FORMAT, this.transmissionSize, this.transmissionSize, 1, 1, 8 | 4);
            MetalNative.setLabel(this.shadowColorTexture, "Ciderlight shadow transmission");
        }
        return this.shadowColorTexture;
    }

    /** A shadow descriptor with one colour target whose blend multiplies the new colour into what is already there. */
    private static int[] translucentShadowDescriptor(final int[] shadowDesc) {
        int vertexPart = shadowDesc.length - 3;
        int[] desc = new int[vertexPart + 1 + 9 + 2];
        System.arraycopy(shadowDesc, 0, desc, 0, vertexPart);
        int i = vertexPart;
        desc[i++] = 1; // one colour target
        desc[i++] = TRANSMISSION_FORMAT;
        desc[i++] = 15; // write mask
        desc[i++] = 1; // blending on: rgb = src * dst, alpha = nearest translucent depth
        desc[i++] = BlendFactor.DST_COLOR.ordinal();
        desc[i++] = BlendFactor.ZERO.ordinal();
        desc[i++] = BlendOp.ADD.ordinal();
        desc[i++] = BlendFactor.ONE.ordinal();
        desc[i++] = BlendFactor.ONE.ordinal();
        desc[i++] = BlendOp.MIN.ordinal();
        desc[i++] = -1; // no depth attachment
        desc[i] = MetalConst.PRIM_TRIANGLES;
        return desc;
    }

    private long terrainShadowState(final TerrainDraw draw) {
        boolean cutout = draw.kind() == KIND_CUTOUT;
        long state = cutout ? this.shadowCutoutState : this.shadowSolidState;
        if (state == 0L) {
            int[] descriptor = draw.shadowDescriptor();
            state = this.built(cutout ? SHADOW_CUTOUT_STATE : SHADOW_SOLID_STATE, () -> this.buildTerrainShadowState(cutout, descriptor));
            if (cutout) {
                this.shadowCutoutState = state;
            } else {
                this.shadowSolidState = state;
            }
        }
        return state;
    }

    private long entityShadowState(final int[] descriptor) {
        Long state = this.entityShadowStatesByDescriptor.get(descriptor);
        if (state == null) {
            state = this.built(entityShadowKey(descriptor), () -> this.buildEntityShadowState(descriptor));
            this.entityShadowStatesByDescriptor.put(descriptor, state);
        }
        return state;
    }

    private long shadowTexture() {
        if (this.shadowTexture == 0L) {
            this.shadowTexture = MetalNative.textureCreate(this.device.context(), DEPTH_FORMAT, this.shadowSize, this.shadowSize, 1, 1, 8 | 4);
            MetalNative.setLabel(this.shadowTexture, "Ciderlight shadow map");
            this.shadowSampler = MetalNative.samplerCreateCompare(this.device.context());
        }
        return this.shadowTexture;
    }

    /** Converts a pipeline's descriptor into a depth-only one for the shadow pass. */
    /** Whether a vertex descriptor (MetalRenderPipeline.describe, or shadowDescriptor of one) has an attribute at this location. */
    private static boolean hasAttribute(final int[] descriptor, final int location) {
        int attribStart = 1 + descriptor[0] * 3;
        for (int a = 0; a < descriptor[attribStart]; a++) {
            if (descriptor[attribStart + 1 + a * 4] == location) {
                return true;
            }
        }
        return false;
    }

    static int[] shadowDescriptor(final int[] base) {
        int nBuffers = base[0];
        int attribStart = 1 + nBuffers * 3;
        int nAttribs = base[attribStart];
        int vertexPart = attribStart + 1 + nAttribs * 4;
        int[] desc = new int[vertexPart + 3];
        System.arraycopy(base, 0, desc, 0, vertexPart);
        desc[vertexPart] = 0; // no color targets
        desc[vertexPart + 1] = DEPTH_FORMAT;
        desc[vertexPart + 2] = MetalConst.PRIM_TRIANGLES;
        return desc;
    }

    // ---- sun and shadow matrices ----

    /**
     * Marks which columns around the camera get rain from their biome, for the water's rain ripples: none fall in dry
     * biomes (deserts, savannas, the badlands) or where it snows. Each 4x4-block column is judged at its top block, like
     * vanilla's rain, since height decides between rain and snow. When the camera moves the grid is shifted and only the
     * columns that came into it are looked up; one row is also refreshed every frame for chunks that have loaded since.
     */
    private void updateRainMask(final ClientLevel level, final Vec3 cameraPos) {
        int originX = (Mth.floor(cameraPos.x / RAIN_CELL) - RAIN_GRID / 2) * RAIN_CELL;
        int originZ = (Mth.floor(cameraPos.z / RAIN_CELL) - RAIN_GRID / 2) * RAIN_CELL;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (level != this.rainMaskLevel || originX != this.rainMaskX || originZ != this.rainMaskZ) {
            boolean keep = level == this.rainMaskLevel;
            int dx = (originX - this.rainMaskX) / RAIN_CELL;
            int dz = (originZ - this.rainMaskZ) / RAIN_CELL;
            boolean[] next = this.rainCellsSpare;
            for (int cz = 0; cz < RAIN_GRID; cz++) {
                for (int cx = 0; cx < RAIN_GRID; cx++) {
                    int ox = cx + dx, oz = cz + dz;
                    next[cz * RAIN_GRID + cx] = keep && ox >= 0 && ox < RAIN_GRID && oz >= 0 && oz < RAIN_GRID
                        ? this.rainCells[oz * RAIN_GRID + ox]
                        : rainsAt(level, originX, originZ, cx, cz, pos);
                }
            }
            this.rainCellsSpare = this.rainCells;
            this.rainCells = next;
            this.rainMaskLevel = level;
            this.rainMaskX = originX;
            this.rainMaskZ = originZ;
        }
        int row = this.rainMaskRow;
        this.rainMaskRow = (row + 1) % RAIN_GRID;
        for (int cx = 0; cx < RAIN_GRID; cx++) {
            this.rainCells[row * RAIN_GRID + cx] = rainsAt(level, originX, originZ, cx, row, pos);
        }

        Arrays.fill(this.rainMask, 0);
        for (int bit = 0; bit < this.rainCells.length; bit++) {
            if (this.rainCells[bit]) {
                this.rainMask[bit >>> 5] |= 1 << (bit & 31);
            }
        }
        this.sampleFrame.set(ValueLayout.JAVA_INT, RAIN_ORIGIN_OFFSET, originX);
        this.sampleFrame.set(ValueLayout.JAVA_INT, RAIN_ORIGIN_OFFSET + 4, originZ);
        this.sampleFrame.set(ValueLayout.JAVA_INT, RAIN_ORIGIN_OFFSET + 8, this.rainCells[(RAIN_GRID / 2) * RAIN_GRID + RAIN_GRID / 2] ? 1 : 0);
        for (int i = 0; i < this.rainMask.length; i++) {
            this.sampleFrame.set(ValueLayout.JAVA_INT, RAIN_MASK_OFFSET + i * 4, this.rainMask[i]);
        }
    }

    /** Whether rain (not snow, not nothing) falls on the top block of cell (cx, cz) of a rain mask at the given corner. */
    private static boolean rainsAt(final ClientLevel level, final int originX, final int originZ, final int cx, final int cz,
                                   final BlockPos.MutableBlockPos pos) {
        int x = originX + cx * RAIN_CELL + RAIN_CELL / 2;
        int z = originZ + cz * RAIN_CELL + RAIN_CELL / 2;
        pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.RAIN;
    }

    private void updateFrameData() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vector3f sun = new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f light = new Vector3f(0.0F, 1.0F, 0.0F);
        float strength = 0.0F;
        float rain = 0.0F;
        float sunVis = 1.0F;
        float moonLight = 1.0F;
        float eyeSkyTarget = 1.0F;
        boolean moon = false;
        float golden = 0.0F; // 1 with the sun at the horizon, 0 with it high or well below
        float duskLift = 1.0F; // how much brighter than vanilla the sky light is kept around sunrise and sunset
        boolean atmosphere = usesAtmosphere();
        Vec3 cameraPos = camera.position();
        if (level != null && level.dimensionType().hasSkyLight() && camera.isInitialized()) {
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            EnvironmentAttributeProbe probe = camera.attributeProbe();
            float degToRad = (float)Math.PI / 180.0F;
            // Same rotation the sky renderer applies to the sun and moon quads: Y by -90 degrees, then X by the angle.
            float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * degToRad;
            sun.set(-(float)Math.sin(sunAngle), (float)Math.cos(sunAngle), 0.0F).normalize();
            float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick) * degToRad;
            Vector3f moonDir = new Vector3f(-(float)Math.sin(moonAngle), (float)Math.cos(moonAngle), 0.0F).normalize();
            MoonPhase phase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTick);
            moonLight = DimensionType.MOON_BRIGHTNESS_PER_PHASE[phase.index() % DimensionType.MOON_BRIGHTNESS_PER_PHASE.length];
            rain = level.getRainLevel(partialTick);

            // Day/night factor: 0 once the sun is a little below the horizon, 1 a little above it.
            sunVis = Math.clamp((sun.y + 0.0625F) / 0.125F, 0.0F, 1.0F);
            // The sun lights the world for as long as any of its disc shows: its centre is then up to seven degrees
            // below the horizon. A shadow map cannot hold shadows from down there, so the light's direction stops a
            // few degrees above the horizon while the sun sinks behind it, and its strength fades as the last of the
            // disc goes. The moon takes over from zero strength once it has risen as far.
            moon = sun.y <= -0.12F;
            if (moon && moonDir.y <= 0.0F) {
                moonDir.set(sun).negate();
            }
            if (moon) {
                light.set(moonDir);
                strength = smoothstep(0.12F, 0.20F, moonDir.y) * lerp(0.6F, 1.0F, moonLight);
            } else {
                light.set(sun);
                if (light.y < 0.06F) {
                    light.y = 0.06F;
                    light.normalize();
                }
                strength = smoothstep(-0.12F, -0.06F, sun.y);
                golden = (1.0F - smoothstep(0.05F, 0.45F, sun.y)) * strength;
                // Vanilla dims the sky light long before sunset: to about a third by the time the sun touches the
                // horizon. It is held near daylight instead for as long as the sun lights the world.
                float vanillaSky = Math.clamp(2.0F * sun.y + 0.2F, 0.0F, 1.0F) * 0.8F + 0.2F;
                duskLift = Math.max(vanillaSky, 0.85F * strength) / vanillaSky;
            }
            strength *= 1.0F - rain * 0.85F;
            // Sky light at the camera: no haze or light shafts deep inside caves.
            eyeSkyTarget = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(BlockPos.containing(cameraPos)) / 15.0F;
        }
        boolean underwater = level != null && usesWaterScattering(camera);
        double waterSurface = cameraPos.y;
        float surfaceSky = 0.0F;
        float waterDensity = 1.0F;
        if (underwater) {
            // The local connected water column supplies a surface plane. Limit the scan to the optical range;
            // deeper water is already opaque. Never search/load neighbouring chunks just to shade a frame.
            BlockPos.MutableBlockPos pos = BlockPos.containing(cameraPos).mutable();
            for (int i = 0; i < 64; i++) {
                var fluid = level.getFluidState(pos);
                if (!fluid.is(FluidTags.WATER)) break;
                waterSurface = pos.getY() + fluid.getHeight(level, pos);
                pos.move(0, 1, 0);
            }
            surfaceSky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos) / 15.0F;
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float waterRange = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, partialTick);
            waterDensity = Math.clamp(96.0F / Math.max(waterRange, 1.0F), 0.5F, 3.0F);
            if (camera.entity() instanceof LivingEntity living && living.hasEffect(MobEffects.NIGHT_VISION)) waterDensity *= 0.6F;
        }
        this.sunActive = strength > 0.0F;
        castersWanted = this.sunActive && this.shadows;
        casterWaterSurface = underwater ? waterSurface : Double.NaN;
        long now = System.nanoTime();
        float dt = this.lastFrameNanos == 0L ? 0.0F : Math.min((now - this.lastFrameNanos) / 1e9F, 0.25F);
        this.lastFrameNanos = now;
        // Keep ambient water brightness from jumping at block-light boundaries as the camera swims.
        if (underwater) {
            if (!this.previousUnderwater || level != this.previousLevel) this.waterSky = surfaceSky;
            else this.waterSky += (surfaceSky - this.waterSky) * (1.0F - (float)Math.exp(-dt));
        }
        // Smoothed over a few seconds (like Complementary's eyeBrightnessSmooth) so walking under leaves doesn't pulse the fog.
        this.eyeSky += (eyeSkyTarget - this.eyeSky) * Math.min(1.0F, dt / 2.5F);
        if ((DEBUG || System.getProperty("ciderlight.benchWater") != null) && (++this.debugFrames % 600) == 1) {
            LOGGER.info("Ciderlight shaders: light={} moon={} strength={} sunVis={} rain={} eyeSky={} camera={}", light, moon, strength, sunVis, rain,
                this.eyeSky, cameraPos);
            LOGGER.info("Ciderlight water: active={} surface={} surfaceSky={} density={}", underwater, waterSurface, surfaceSky, waterDensity);
        }

        float r;
        float g;
        float b;
        float darkness;
        if (!moon) {
            // Strong, saturated orange light low in the sky; near-white at midday.
            float noon = (float)Math.sqrt(Math.max(sun.y, 0.0F));
            r = lerp(1.22F, 1.16F, noon);
            g = lerp(0.70F, 1.10F, noon);
            b = lerp(0.30F, 1.00F, noon);
            darkness = 0.25F;
        } else {
            // Cool moonlight: moonlit ground about as bright as a vanilla night, moon shadows clearly darker.
            float m = lerp(0.75F, 1.0F, moonLight);
            r = 1.5F * m;
            g = 1.6F * m;
            b = 1.9F * m;
            darkness = 0.45F;
        }
        // Night sky light is pulled well below vanilla so block light stands out.
        float nightSky = lerp(0.9F, 1.0F, sunVis) * duskLift;

        // Light on surfaces: a warm direct sun on top of blue light from the open sky. The sun is about two and a half
        // times as bright as the sky light in shade, so sunlit faces stand out clearly from shaded ones; a low sun is
        // strongly orange and the shade around it more violet. At night the moon is a cool, dim key over a blue fill.
        float[] surfaceSun = new float[3];
        float[] surfaceAmbient = new float[3];
        if (!moon) {
            float noon = (float)Math.sqrt(Math.max(sun.y, 0.0F));
            surfaceSun[0] = lerp(1.62F, 1.20F, noon);
            surfaceSun[1] = lerp(0.88F, 1.02F, noon);
            surfaceSun[2] = lerp(0.42F, 0.70F, noon);
            surfaceAmbient[0] = lerp(0.62F, 0.50F, noon);
            surfaceAmbient[1] = lerp(0.58F, 0.63F, noon);
            surfaceAmbient[2] = lerp(0.70F, 0.86F, noon);
        } else {
            float m = lerp(0.75F, 1.0F, moonLight);
            surfaceSun[0] = 0.95F * m;
            surfaceSun[1] = 1.05F * m;
            surfaceSun[2] = 1.30F * m;
            surfaceAmbient[0] = 0.44F;
            surfaceAmbient[1] = 0.49F;
            surfaceAmbient[2] = 0.60F;
        }
        // Under rain clouds the light is flat and grey: the sun fades (strength) and the sky light loses its blue.
        for (int i = 0; i < 3; i++) {
            surfaceAmbient[i] = lerp(surfaceAmbient[i], 0.62F, rain);
        }

        // Shadow volume centred on the camera, snapped to whole shadow-map texels in the light's view so shadow edges
        // (on the ground and in the volumetric fog) stay put as the player walks instead of crawling.
        Vec3 anchor = texelSnappedAnchor(cameraPos, light, SHADOW_RADIUS, this.shadowSize);
        Matrix4f shadow = new Matrix4f()
            .setOrtho(-SHADOW_RADIUS, SHADOW_RADIUS, -SHADOW_RADIUS, SHADOW_RADIUS, 0.0F, SHADOW_RADIUS * 4.0F, true)
            .lookAt(light.x * SHADOW_RADIUS * 2.0F, light.y * SHADOW_RADIUS * 2.0F, light.z * SHADOW_RADIUS * 2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);

        // Jitter raster coverage by up to an eighth of a texel. Reprojected visibility history integrates the
        // sub-texel samples, so a slowly moving edge need not wait for a whole shadow texel to change. A wider
        // jitter (up to 3/8) left every shadow edge shimmering: the history cannot average out more than it holds.
        if (SHADOW_HISTORY) {
            float[] offset = SHADOW_JITTER[this.frameIndex & 7];
            shadow.m30(shadow.m30() + offset[0] * (2.0F / 3.0F) / this.shadowSize);
            shadow.m31(shadow.m31() + offset[1] * (2.0F / 3.0F) / this.shadowSize);
        }
        if (atmosphere != this.previousAtmosphere || underwater != this.previousUnderwater || (underwater && Math.abs(waterSurface - this.previousWaterSurface) > 0.25)
            || level != this.previousLevel || cameraPos.distanceToSqr(this.prevCameraPos) > 64.0
            || this.previousLight.dot(light) < 0.9999F || dt >= 0.25F || !this.sunActive) {
            this.invalidateHistory();
        }
        if (level != this.previousLevel) {
            this.invalidateShadowMaps();
        }
        // Ambient occlusion only depends on the camera and the scene, not on the light.
        if (level != this.previousLevel || cameraPos.distanceToSqr(this.prevCameraPos) > 64.0 || dt >= 0.25F) {
            this.aoHistoryValid = false;
        }
        this.previousLevel = level;
        this.previousAtmosphere = atmosphere;
        this.previousUnderwater = underwater;
        this.previousWaterSurface = waterSurface;
        this.previousLight.set(light);

        // Sampling this frame uses the shadow map rendered at the end of the previous frame.
        writeFrame(this.sampleFrame, this.lastShadowMatrix, cameraPos.subtract(this.lastShadowAnchor), light, strength, r, g, b, darkness, rain,
            this.shadowValid && this.sunActive);
        if (level != this.rainMaskLevel) {
            this.rainMaskLevel = null;
        }
        if (level != null && rain > 0.0F) {
            this.updateRainMask(level, cameraPos);
        }
        writeFrame(this.renderFrame, shadow, cameraPos.subtract(anchor), light, strength, r, g, b, darkness, rain, true);
        for (MemorySegment seg : new MemorySegment[]{this.sampleFrame, this.renderFrame}) {
            seg.set(ValueLayout.JAVA_FLOAT, 336, (float)cameraPos.x);
            seg.set(ValueLayout.JAVA_FLOAT, 340, (float)cameraPos.y);
            seg.set(ValueLayout.JAVA_FLOAT, 344, (float)cameraPos.z);
            // lightParams: day factor, night sky darkening, moon is the shadow light, moon phase brightness.
            seg.set(ValueLayout.JAVA_FLOAT, 352, sunVis);
            seg.set(ValueLayout.JAVA_FLOAT, 356, nightSky);
            seg.set(ValueLayout.JAVA_FLOAT, 360, moon ? 1.0F : 0.0F);
            seg.set(ValueLayout.JAVA_FLOAT, 364, moonLight);
            // fogParams: sky light at the camera, render distance (filled in at the end of the pass), haze density, unused.
            seg.set(ValueLayout.JAVA_FLOAT, 368, this.eyeSky);
            seg.set(ValueLayout.JAVA_FLOAT, 376, 1.0F + rain * 1.5F);
            seg.set(ValueLayout.JAVA_FLOAT, WATER_OFFSET, underwater ? 1.0F : 0.0F);
            seg.set(ValueLayout.JAVA_FLOAT, WATER_OFFSET + 4, (float)(waterSurface - cameraPos.y));
            seg.set(ValueLayout.JAVA_FLOAT, WATER_OFFSET + 8, this.waterSky);
            seg.set(ValueLayout.JAVA_FLOAT, WATER_OFFSET + 12, waterDensity);
            for (int i = 0; i < 3; i++) {
                seg.set(ValueLayout.JAVA_FLOAT, SURFACE_LIGHT_OFFSET + i * 4, surfaceSun[i]);
                seg.set(ValueLayout.JAVA_FLOAT, SURFACE_LIGHT_OFFSET + 16 + i * 4, surfaceAmbient[i]);
            }
        }
        // cameraPos.w: the cloud base above the camera; with no clouds in the map nothing is under them.
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 348, Double.isNaN(this.cloudBase) ? -1.0e9F : (float)(this.cloudBase - cameraPos.y));
        if (this.casterLight.distance(light) > 0.00175F) { // a tenth of a degree (ShadowCasterMask.LIGHT_LAG allows for it)
            this.casterLight.set(light);
            casterLightStamp++;
        }
        writeMatrix(this.sampleFrame, FAR_MATRIX_OFFSET, this.lastFarShadowMatrix);
        Vec3 farOffset = cameraPos.subtract(this.lastFarShadowAnchor);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, FAR_ANCHOR_OFFSET, (float)farOffset.x);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, FAR_ANCHOR_OFFSET + 4, (float)farOffset.y);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, FAR_ANCHOR_OFFSET + 8, (float)farOffset.z);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, FAR_ANCHOR_OFFSET + 12, this.farShadowValid ? 1.0F : 0.0F);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_OFFSET, FOG_DISTANCE);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_OFFSET + 4, (Boolean.getBoolean("ciderlight.benchFog") || Boolean.getBoolean("ciderlight.benchGlass")) ? 200.0F : 64.0F);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_OFFSET + 8, atmosphere ? 1.0F : 0.0F);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_OFFSET + 12, (float)Math.exp(-Math.max(dt, 0.001F) / 0.09F));
        for (MemorySegment seg : new MemorySegment[]{this.sampleFrame, this.renderFrame}) {
            seg.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET, sun.x);
            seg.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 4, sun.y);
            seg.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 8, sun.z);
            seg.set(ValueLayout.JAVA_FLOAT, SOLAR_OFFSET + 12, atmosphere ? 1.0F : 0.0F);
        }
        // These fields must be available before terrain is drawn, not just at composite time.
        writeMatrix(this.sampleFrame, 384, this.prevViewProj);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 448, (float)(this.frameIndex++ % 1024));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 452, this.volumetricHistory && VL_HISTORY ? 1.0F : 0.0F);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 456, this.shadowHistoryValid && SHADOW_HISTORY ? 1.0F : 0.0F);
        // fogParams.w: 0 no ambient occlusion, 1 enabled, 2 enabled with a valid previous frame to reproject.
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 380, this.ao ? (this.aoHistoryValid ? 2.0F : 1.0F) : 0.0F);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AO_PARAMS_OFFSET, (float)this.aoDivisor);
        // Light sources in players' hands light what is around them (held_light in frame.metal): the nearest
        // HELD_LIGHTS of them, each eased as its player switches items.
        heldLights = 0;
        Map<Integer, Float> eased = new HashMap<>();
        if (level != null && camera.isInitialized()) {
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float ease = 1.0F - (float)Math.exp(-Math.max(dt, 0.001F) / 0.08F);
            List<net.minecraft.client.player.AbstractClientPlayer> players = new ArrayList<>(level.players());
            players.sort(java.util.Comparator.comparingDouble(player -> player.distanceToSqr(cameraPos)));
            for (net.minecraft.client.player.AbstractClientPlayer player : players) {
                int held = player.isSpectator() ? 0 : Math.max(lightEmission(player.getMainHandItem()), lightEmission(player.getOffhandItem()));
                float level15 = this.heldLightEased.getOrDefault(player.getId(), 0.0F);
                level15 += (held - level15) * ease;
                if (held == 0 && level15 < 0.05F) {
                    continue;
                }
                eased.put(player.getId(), level15);
                if (heldLights < HELD_LIGHTS) {
                    heldLightLevel[heldLights] = level15;
                    // Held at chest height, a little below the eyes.
                    heldLightPos[heldLights++] = player.getEyePosition(partialTick).add(0.0, -0.3, 0.0);
                }
            }
        }
        this.heldLightEased = eased;
        for (int i = 0; i < HELD_LIGHTS; i++) {
            boolean lit = i < heldLights;
            int offset = HELD_LIGHT_OFFSET + i * 16;
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, offset, lit ? (float)(heldLightPos[i].x - cameraPos.x) : 0.0F);
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, offset + 4, lit ? (float)(heldLightPos[i].y - cameraPos.y) : 0.0F);
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, offset + 8, lit ? (float)(heldLightPos[i].z - cameraPos.z) : 0.0F);
            this.sampleFrame.set(ValueLayout.JAVA_FLOAT, offset + 12, lit ? heldLightLevel[i] / 15.0F : 0.0F);
        }
        // Lightning: the nearest bolt lights the terrain and mobs around it (lightning_light in frame.metal).
        boolean bolt = false;
        if (level != null && (level.isThundering() || this.lightningLight > 0.0F || rain > 0.0F)) {
            double nearest = Double.MAX_VALUE;
            for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.LightningBolt && entity.position().distanceToSqr(cameraPos) < nearest) {
                    nearest = entity.position().distanceToSqr(cameraPos);
                    this.lightningPos = entity.position();
                    bolt = true;
                }
            }
        }
        this.lightningLight = bolt ? 1.0F : this.lightningLight * (float)Math.exp(-dt / 0.08F);
        if (this.lightningLight < 0.01F) {
            this.lightningLight = 0.0F;
        }
        this.lightningBolt = bolt;
        // The bolt's light waits a frame for its shadow map, so the flash never shows without its shadows.
        boolean shadowed = this.lightningShadowBolt != null && this.lightningShadowBolt.distanceToSqr(this.lightningPos) < 0.01;
        writeMatrix(this.sampleFrame, LIGHTNING_MATRIX_OFFSET, this.lightningShadowMatrix);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_EYE_OFFSET, (float)(cameraPos.x - this.lightningShadowEye.x));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_EYE_OFFSET + 4, (float)(cameraPos.y - this.lightningShadowEye.y));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_EYE_OFFSET + 8, (float)(cameraPos.z - this.lightningShadowEye.z));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_EYE_OFFSET + 12, shadowed ? 1.0F : 0.0F);
        float shownLightning = shadowed ? this.lightningLight : 0.0F;
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET, (float)(this.lightningPos.x - cameraPos.x));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET + 4, (float)(this.lightningPos.y - cameraPos.y));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET + 8, (float)(this.lightningPos.z - cameraPos.z));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, LIGHTNING_OFFSET + 12, shownLightning);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_NEAR_OFFSET, FOG_NEAR);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_NEAR_OFFSET + 4, FOG_NEAR_RANGE);
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, AIR_NEAR_OFFSET + 8, golden);
        // Shadow history weight: a 0.2 s memory spans two jitter cycles (8 frames) at 120 fps; 0.06 s left a visible ripple.
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 460, (float)Math.exp(-Math.max(dt, 0.001F) / 0.2F));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 464, (float)(cameraPos.x - this.prevCameraPos.x));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 468, (float)(cameraPos.y - this.prevCameraPos.y));
        this.sampleFrame.set(ValueLayout.JAVA_FLOAT, 472, (float)(cameraPos.z - this.prevCameraPos.z));
        this.nextShadowMatrix.set(shadow);
        this.nextShadowAnchor = anchor;
    }

    private static Vec3 texelSnappedAnchor(final Vec3 cameraPos, final Vector3f light, final float radius, final int resolution) {
        // Light-space axes of the lookAt below (eye along the light, up = +Z).
        double fx = -light.x, fy = -light.y, fz = -light.z;
        double sx = fy * 1.0 - fz * 0.0, sy = fz * 0.0 - fx * 1.0, sz = fx * 0.0 - fy * 0.0; // cross(f, up)
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-6) {
            return cameraPos;
        }
        sx /= sl;
        sy /= sl;
        sz /= sl;
        double ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx; // cross(s, f)
        double texel = 2.0 * radius / resolution;
        double a = cameraPos.x * sx + cameraPos.y * sy + cameraPos.z * sz;
        double b = cameraPos.x * ux + cameraPos.y * uy + cameraPos.z * uz;
        double da = a - Math.rint(a / texel) * texel;
        double db = b - Math.rint(b / texel) * texel;
        return new Vec3(cameraPos.x - sx * da - ux * db, cameraPos.y - sy * da - uy * db, cameraPos.z - sz * da - uz * db);
    }

    private void writeFrame(
        final MemorySegment seg, final Matrix4f shadow, final Vec3 camToAnchor, final Vector3f sun, final float strength, final float r, final float g,
        final float b, final float darkness, final float rain, final boolean shadowsValid
    ) {
        writeMatrix(seg, 0, shadow);
        seg.set(ValueLayout.JAVA_FLOAT, 64, (float)camToAnchor.x);
        seg.set(ValueLayout.JAVA_FLOAT, 68, (float)camToAnchor.y);
        seg.set(ValueLayout.JAVA_FLOAT, 72, (float)camToAnchor.z);
        seg.set(ValueLayout.JAVA_FLOAT, 80, sun.x);
        seg.set(ValueLayout.JAVA_FLOAT, 84, sun.y);
        seg.set(ValueLayout.JAVA_FLOAT, 88, sun.z);
        seg.set(ValueLayout.JAVA_FLOAT, 92, strength);
        seg.set(ValueLayout.JAVA_FLOAT, 96, r);
        seg.set(ValueLayout.JAVA_FLOAT, 100, g);
        seg.set(ValueLayout.JAVA_FLOAT, 104, b);
        seg.set(ValueLayout.JAVA_FLOAT, 108, darkness);
        seg.set(ValueLayout.JAVA_FLOAT, 112, BENCH_CLOCK >= 0.0F ? BENCH_CLOCK : (float)(System.nanoTime() % 3_600_000_000_000L) / 1e9F);
        seg.set(ValueLayout.JAVA_FLOAT, 116, rain);
        seg.set(ValueLayout.JAVA_FLOAT, 120, 1.0F / this.shadowSize);
        seg.set(ValueLayout.JAVA_FLOAT, 124, shadowsValid ? 1.0F : 0.0F);
    }

    private static float smoothstep(final float a, final float b, final float x) {
        float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }

    void destroy() {
        castersWanted = false;
        casterView = null;
        // Libraries and pipeline states belong to the registry (built); the fields only cache them.
        for (java.util.concurrent.CompletableFuture<Long> build : this.built.values()) {
            Prebuild.releaseWhenDone(build, 0L);
        }
        List<Long> handles = new ArrayList<>(List.of(this.farShadowColorTextures[0], this.farShadowTextures[0], this.farShadowColorTextures[1],
            this.farShadowTextures[1], this.extinctionTextures[0], this.extinctionTextures[1], this.shadowTexture, this.shadowSampler, this.opaqueColor, this.opaqueDepth,
            this.volumetricTextures[0], this.volumetricTextures[1], this.shadowColorTexture, this.shadowHistoryTextures[0], this.shadowHistoryTextures[1],
            this.translucentShadowHistoryTextures[0], this.translucentShadowHistoryTextures[1],
            this.foliageBuffer, this.cloudMapTexture, this.frameConstantsTexture, this.lightningShadowTexture, this.handSpriteBuffer, this.airNoiseTexture,
            this.aoRawTexture, this.aoTextures[0], this.aoTextures[1]));
        for (long handle : handles) {
            if (handle != 0L) {
                MetalNative.release(handle);
            }
        }
    }
}
