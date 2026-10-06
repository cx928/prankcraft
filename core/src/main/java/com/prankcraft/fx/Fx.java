package com.prankcraft.fx;

import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves enum constants that were renamed between the Minecraft versions we target.
 *
 * <p>Nothing here is a compile-time reference to a version-specific constant: unknown or
 * renamed values are looked up once, cached, and silently skipped when absent. That is what
 * lets a single jar run on 1.16 and on current Paper without shading multi-version modules.
 */
public final class Fx {

    private static final Map<String, Particle> PARTICLE_CACHE = new HashMap<>();
    private static final Map<String, Sound> SOUND_CACHE = new HashMap<>();

    private Fx() {
    }

    /** First particle that exists in this server version, or {@code null}. */
    public static Particle particle(String... candidates) {
        String key = String.join("|", candidates);
        if (PARTICLE_CACHE.containsKey(key)) {
            return PARTICLE_CACHE.get(key);
        }
        Particle found = null;
        for (String name : candidates) {
            try {
                found = Particle.valueOf(name);
                break;
            } catch (IllegalArgumentException ignored) {
                // renamed or not present in this version - try the next candidate
            }
        }
        PARTICLE_CACHE.put(key, found);
        return found;
    }

    /** First sound that exists in this server version, or {@code null}. */
    public static Sound sound(String... candidates) {
        String key = String.join("|", candidates);
        if (SOUND_CACHE.containsKey(key)) {
            return SOUND_CACHE.get(key);
        }
        Sound found = null;
        for (String name : candidates) {
            try {
                found = Sound.valueOf(name);
                break;
            } catch (IllegalArgumentException ignored) {
                // renamed or not present in this version - try the next candidate
            }
        }
        SOUND_CACHE.put(key, found);
        return found;
    }

    // ------------------------------------------------------------ shorthands

    public static Particle explosion() {
        return particle("EXPLOSION", "EXPLOSION_LARGE", "EXPLOSION_HUGE");
    }

    public static Particle explosionEmitter() {
        return particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE", "EXPLOSION_LARGE");
    }

    public static Particle smoke() {
        return particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE");
    }

    /** Plain smoke. Renamed from {@code SMOKE_NORMAL} to {@code SMOKE}, hence the resolver. */
    public static Particle smokeNormal() {
        return particle("SMOKE", "SMOKE_NORMAL", "CLOUD");
    }

    public static Particle flame() {
        return particle("FLAME", "SOUL_FIRE_FLAME");
    }

    public static Particle cloud() {
        return particle("CLOUD", "SMOKE_NORMAL");
    }

    public static Sound explosionSound() {
        return sound("ENTITY_GENERIC_EXPLODE", "ENTITY_GENERIC_EXPLODE");
    }

    public static Sound fuseSound() {
        return sound("ENTITY_TNT_PRIMED", "ENTITY_TNT_PRIMED");
    }

    public static Sound clickSound() {
        return sound("UI_BUTTON_CLICK", "BLOCK_LEVER_CLICK", "CLICK");
    }

    public static Sound xpSound() {
        return sound("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
    }

    public static Sound hurtSound() {
        return sound("ENTITY_PLAYER_HURT", "ENTITY_PLAYER_HURT");
    }

    public static Sound levelUpSound() {
        return sound("ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
    }

    public static Sound portalSound() {
        return sound("BLOCK_PORTAL_TRIGGER", "PORTAL_TRIGGER");
    }

    // ------------------------------------------------------------ pre-resolved forms
    //
    // The varargs methods above build a String cache key from their arguments on every call,
    // which is pure overhead once the answer is known. These hold the result for the particles
    // and sounds the fake-TNT path uses, so a detonation resolves nothing at all.

    public static final Particle EXPLOSION_EMITTER = explosionEmitter();
    public static final Particle EXPLOSION_CORE = explosion();
    public static final Particle EXPLOSION_SMOKE = smoke();
    public static final Sound EXPLOSION_SOUND = explosionSound();
    public static final Sound FUSE_SOUND = fuseSound();
    public static final Sound CLICK_SOUND = clickSound();
}
