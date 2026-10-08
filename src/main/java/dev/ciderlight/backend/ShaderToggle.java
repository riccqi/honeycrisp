package dev.ciderlight.backend;

import org.jspecify.annotations.Nullable;

/**
 * The on/off effects on the Ciderlight settings page (CiderlightSettingsScreen), all on by default. Each is saved to
 * config/ciderlight.properties under its key. Shadows and Ambient Occlusion are live: MetalShaders checks them every
 * frame. Waving Plants, Water Reflections and Water Waves are compiled into the pipelines, so like the Shaders choice
 * they take effect at the next start. -Dciderlight.KEY=true|false still overrides the saved value.
 */
public enum ShaderToggle {
    SHADOWS("shadows", "Shadows", true,
        "The sun and moon cast shadows, and light shafts form in the haze. Off skips the shadow maps, the biggest single "
            + "cost on the GPU, and lights everything as if it stood in the open."),
    WAVING("waving", "Waving Plants", false, "Leaves, grass, flowers and crops sway in the wind."),
    WATER_REFLECTIONS("waterReflections", "Water Reflections", false,
        "Water mirrors the hills, trees and buildings around it. Off: water reflects the sky only, which costs less."),
    WATER_WAVES("waterWaves", "Water Waves", false,
        "Waves roll across lakes and the sea and run down waterfalls. Off: the water lies calm (rain still ripples it)."),
    AMBIENT_OCCLUSION("ao", "Ambient Occlusion", true, "Soft shade in corners, under ledges and between blocks.");

    /** The property key, both in ciderlight.properties and as -Dciderlight.KEY. */
    public final String key;
    public final String label;
    public final String description;
    /** Applies as soon as it is chosen, not at the next start. */
    public final boolean live;
    /** What this session started with. */
    private final boolean running;
    /** Set by -Dciderlight.KEY, which wins over the settings page. */
    private final boolean forced;
    /** Chosen on the settings page since the game started: applies now if live, otherwise at the next start. */
    private volatile @Nullable Boolean chosen;

    ShaderToggle(final String key, final String label, final boolean live, final String description) {
        this.key = key;
        this.label = label;
        this.live = live;
        this.description = description;
        String override = System.getProperty("ciderlight." + key);
        this.forced = override != null;
        this.running = !"false".equals(override != null ? override : CiderlightConfig.saved(key));
    }

    /** Whether this session runs with the effect: the latest choice if live, otherwise what the game started with. */
    public boolean enabled() {
        return this.live ? this.current() : this.running;
    }

    /** What the next start will run with. */
    public boolean current() {
        return this.chosen != null ? this.chosen : this.running;
    }

    /** Whether -Dciderlight.KEY sets it, so a choice here changes nothing until that is removed. */
    public boolean forced() {
        return this.forced;
    }

    public boolean restartRequired() {
        return !this.live && !this.forced && this.chosen != null && this.chosen != this.running;
    }

    public void choose(final boolean on) {
        this.chosen = on;
        CiderlightConfig.set(this.key, Boolean.toString(on));
    }

    public static boolean anyRestartRequired() {
        for (ShaderToggle toggle : values()) {
            if (toggle.restartRequired()) {
                return true;
            }
        }
        return false;
    }
}
