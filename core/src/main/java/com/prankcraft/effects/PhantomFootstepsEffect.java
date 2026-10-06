package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Footsteps that belong to nobody. The target hears a player walking up behind them, and if
 * they look, sees only a puff of smoke where the walker should have been. An empty room that
 * breathes is far creepier than a jumpscare, and it costs nothing.
 */
public final class PhantomFootstepsEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();

    public PhantomFootstepsEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "phantom-footsteps";
    }

    @Override
    public String description() {
        return "Invisible footsteps circle the target, with a puff of smoke where the walker would be.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.phantom";
    }

    @Override
    public int defaultDurationSeconds() {
        return 12;
    }

    @Override
    public String fire(Player target) {
        cancel(target);
        long interval = Math.max(4L, plugin.getConfig().getLong("pranks.phantom-footsteps.interval-ticks", 18L));
        float volume = (float) plugin.getConfig().getDouble("pranks.phantom-footsteps.volume", 0.9);
        float pitch = (float) plugin.getConfig().getDouble("pranks.phantom-footsteps.pitch", 0.8);
        String soundName = plugin.getConfig().getString("pranks.phantom-footsteps.sound", "BLOCK_STONE_STEP");

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> step(target, soundName, volume, pitch), 5L, interval);
        tasks.put(target.getUniqueId(), task);
        return "interval=" + interval + "t sound=" + soundName;
    }

    private void step(Player target, String soundName, float volume, float pitch) {
        if (!target.isOnline()) {
            cancel(target);
            return;
        }
        Sound sound = soundName == null ? null : Fx.sound(soundName);
        if (sound == null) {
            sound = Fx.sound("BLOCK_STONE_STEP", "BLOCK_GRAVEL_STEP", "STEP_STONE");
        }
        Location origin = target.getLocation();
        double angle = Math.random() * Math.PI * 2;
        double distance = 3.0 + Math.random() * 3.0;
        Location step = origin.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);

        Particle smoke = Fx.smokeNormal();
        for (Player viewer : target.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(origin) > 32 * 32) {
                continue;
            }
            if (sound != null) {
                viewer.playSound(step, sound, volume, pitch);
            }
            if (smoke != null) {
                viewer.spawnParticle(smoke, step.clone().add(0, 0.2, 0), 8, 0.2, 0.1, 0.2, 0.01);
            }
        }
    }

    @Override
    public void cancel(Player target) {
        BukkitTask task = tasks.remove(target.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
