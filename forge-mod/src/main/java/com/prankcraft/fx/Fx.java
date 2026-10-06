package com.prankcraft.fx;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves sounds and particles from config strings, with version-stable defaults.
 *
 * <p>Port of {@code com.prankcraft.fx.Fx} from the Paper module. The Paper version used enum
 * {@code valueOf} lookups because the Bukkit enums were renamed between 1.16 and current. On
 * Forge 1.16.5 the equivalent problem is that a config file outlives a mod version: somebody
 * will paste a sound id from a newer wiki page, or from a resource pack, and the mod must not
 * throw inside a server tick because of it. So lookups are by registry name (the same
 * {@code minecraft:entity.tnt.primed} string the vanilla {@code /playsound} command takes),
 * failures fall back to a hard-coded default, and every outcome is cached so the log is not
 * spammed once per footstep.
 */
public final class Fx {

    private static final Map<String, SoundEvent> SOUND_CACHE = new HashMap<>();
    private static final Map<String, ParticleOptions> PARTICLE_CACHE = new HashMap<>();

    private Fx() {
    }

    // ------------------------------------------------------------------ lookups

    /**
     * A sound by registry name, accepting {@code entity.tnt.primed}, {@code minecraft:entity.tnt.primed}
     * or a bare {@code ENTITY_TNT_PRIMED} constant name. Returns {@code fallback} when nothing matches.
     */
    public static SoundEvent sound(String configured, SoundEvent fallback) {
        if (configured == null || configured.trim().isEmpty()) {
            return fallback;
        }
        String key = configured.trim();
        SoundEvent cached = SOUND_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        SoundEvent resolved = resolveSound(key);
        // Cache the fallback as well: a typo should cost one lookup, not one per footstep.
        SOUND_CACHE.put(key, resolved == null ? fallback : resolved);
        return resolved == null ? fallback : resolved;
    }

    private static SoundEvent resolveSound(String key) {
        if (key.indexOf(':') < 0) {
            // A bare name like ENTITY_TNT_PRIMED - turn it into the registry path shape.
            String lowered = key.toLowerCase(java.util.Locale.ROOT);
            if (lowered.startsWith("minecraft.") || lowered.startsWith("block.") || lowered.startsWith("entity.")
                    || lowered.startsWith("ui.") || lowered.startsWith("ambient.") || lowered.startsWith("item.")) {
                return lookupSound(lowered);
            }
            return lookupSound(lowered.replace('_', '.'));
        }
        return lookupSound(key);
    }

    private static SoundEvent lookupSound(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        // getOptional, never get(): a missing id must not throw a NullPointerException mid-tick.
        return Registry.SOUND_EVENT.getOptional(location).orElse(null);
    }

    /** A particle by registry name. Same rules as {@link #sound(String, SoundEvent)}. */
    public static ParticleOptions particle(String configured, ParticleOptions fallback) {
        if (configured == null || configured.trim().isEmpty()) {
            return fallback;
        }
        String key = configured.trim();
        ParticleOptions cached = PARTICLE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String id = key.indexOf(':') < 0 ? "minecraft:" + key.toLowerCase(java.util.Locale.ROOT) : key;
        ResourceLocation location = ResourceLocation.tryParse(id);
        ParticleOptions resolved = location == null
                ? null
                : Registry.PARTICLE_TYPE.getOptional(location).orElse(null);
        PARTICLE_CACHE.put(key, resolved == null ? fallback : resolved);
        return resolved == null ? fallback : resolved;
    }

    // ------------------------------------------------------------------ defaults

    public static SoundEvent fuseSound() {
        return SoundEvents.TNT_PRIMED;
    }

    public static SoundEvent explosionSound() {
        return SoundEvents.GENERIC_EXPLODE;
    }

    public static SoundEvent clickSound() {
        return SoundEvents.UI_BUTTON_CLICK;
    }

    public static SoundEvent jumpscareDefault() {
        return SoundEvents.ENDERMAN_SCREAM;
    }

    public static SoundEvent footstepsDefault() {
        return SoundEvents.STONE_STEP;
    }

    public static SoundEvent thunderDefault() {
        return SoundEvents.LIGHTNING_BOLT_THUNDER;
    }

    public static ParticleOptions explosion() {
        return ParticleTypes.EXPLOSION;
    }

    public static ParticleOptions explosionEmitter() {
        return ParticleTypes.EXPLOSION_EMITTER;
    }

    /** The big smoke puff. {@code SMOKE} is the plain one; {@code LARGE_SMOKE} is the thick one. */
    public static ParticleOptions smoke() {
        return ParticleTypes.LARGE_SMOKE;
    }

    public static ParticleOptions smokeNormal() {
        return ParticleTypes.SMOKE;
    }

    public static ParticleOptions flame() {
        return ParticleTypes.FLAME;
    }
}
