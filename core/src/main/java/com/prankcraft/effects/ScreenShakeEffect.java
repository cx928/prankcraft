package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shakes the target's screen by abusing the title renderer: a run of empty titles, each nudged
 * a few pixels, reads as a camera shake without touching their view direction.
 *
 * <p>This is as far as a server-side plugin can honestly go. Actually spinning or tilting a
 * player's camera means overriding their movement packets - that is the line where a prank
 * turns into taking control of somebody's client, so this plugin does not cross it. The target
 * keeps full control of their own view at every moment; they are just being shown a wobble.
 */
public final class ScreenShakeEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();

    public ScreenShakeEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "screen-shake";
    }

    @Override
    public String description() {
        return "Wobbles the target's screen with empty offset titles. Camera control never leaves their hands.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.screenshake";
    }

    @Override
    public int defaultDurationSeconds() {
        return 2;
    }

    @Override
    public String fire(Player target) {
        cancel(target);
        int pulses = Math.max(1, plugin.getConfig().getInt("pranks.screen-shake.pulses", 6));
        long interval = Math.max(1L, plugin.getConfig().getLong("pranks.screen-shake.interval-ticks", 3L));
        int strength = Math.max(1, plugin.getConfig().getInt("pranks.screen-shake.strength", 12));
        boolean onlyTarget = plugin.getConfig().getBoolean("pranks.screen-shake.target-only", true);

        final int[] pulsesLeft = {pulses};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!target.isOnline() || pulsesLeft[0]-- <= 0) {
                cancel(target);
                return;
            }
            pulse(target, strength, onlyTarget);
        }, 0L, interval);

        tasks.put(target.getUniqueId(), task);
        return "pulses=" + pulses + " strength=" + strength;
    }

    private void pulse(Player target, int strength, boolean onlyTarget) {
        int offset = (int) (Math.random() * strength * 2) - strength;
        int fade = 1;
        int stay = 2;
        // fadeIn=0, stay, fadeOut=0 - a title that exists just long enough to be drawn once.
        target.sendTitle(pad(offset), "", fade, stay, fade);

        if (onlyTarget) {
            return;
        }
        for (Player viewer : target.getWorld().getPlayers()) {
            if (!viewer.equals(target) && viewer.getLocation().distanceSquared(target.getLocation()) <= 16 * 16) {
                viewer.sendTitle(pad(offset), "", fade, stay, fade);
            }
        }
    }

    /**
     * The title renderer strips leading spaces, so the vertical nudge is done with a
     * zero-width space followed by the blank run. The line stays visually empty.
     */
    private static String pad(int lines) {
        StringBuilder sb = new StringBuilder("\u200B");
        for (int i = 0; i < Math.abs(lines); i++) {
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public void cancel(Player target) {
        BukkitTask task = tasks.remove(target.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        if (target.isOnline()) {
            target.sendTitle("", "", 0, 1, 0); // clear whatever nudge is on screen
        }
    }
}
