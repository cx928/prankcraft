package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * A short, loud, wholly cosmetic fright: a particle burst in front of the target and a
 * mob shriek. No entity is spawned, nothing can hurt them, and the effect is over in a
 * second or two - the point is the jump, not the torture.
 */
public final class JumpscareEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;

    public JumpscareEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "jumpscare";
    }

    @Override
    public String description() {
        return "Particle burst + mob scream right in front of the target (harmless).";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.jumpscare";
    }

    @Override
    public int defaultDurationSeconds() {
        return 2;
    }

    @Override
    public String fire(Player target) {
        String soundName = plugin.getConfig().getString("pranks.jumpscare.sound", "ENTITY_ENDERMAN_SCREAM");
        Sound sound = soundName == null ? null : Fx.sound(soundName);
        if (sound == null) {
            sound = Fx.sound("ENTITY_ENDERMAN_SCREAM", "ENTITY_GHAST_SCREAM", "ENTITY_CREEPER_PRIMED");
        }
        if (sound != null) {
            for (Player viewer : target.getWorld().getPlayers()) {
                if (viewer.getLocation().distanceSquared(target.getLocation()) <= 24 * 24) {
                    viewer.playSound(target.getLocation(), sound, 1.2f, 1.0f);
                }
            }
        }

        if (!plugin.getConfig().getBoolean("pranks.jumpscare.particles", true)) {
            return "sound=" + soundName;
        }

        // Place the burst along each viewer's own line of sight, so it works for someone
        // watching from across the room as well as for the target.
        Vector facing = target.getLocation().getDirection().clone().setY(0);
        if (facing.lengthSquared() < 1.0E-4) {
            facing = new Vector(0, 0, 1);
        }
        facing.normalize().multiply(1.6);
        Vector offset = facing.clone().setY(0.9);

        Particle smoke = Fx.smokeNormal();
        for (Player viewer : target.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(target.getLocation()) > 24 * 24) {
                continue;
            }
            Location burst = viewer.getLocation().clone().add(offset);
            if (smoke != null) {
                viewer.spawnParticle(smoke, burst, 30, 0.35, 0.35, 0.35, 0.02);
            }
            viewer.spawnParticle(Particle.FLAME, burst, 10, 0.25, 0.25, 0.25, 0.01);
        }
        return "sound=" + soundName;
    }
}
