package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A rain of arrows that cannot hurt anybody.
 *
 * <p>Every arrow is spawned harmless: no damage, no knockback, no player pickup, and it is
 * removed when the effect ends. The drama is entirely in the sound and the arrows thudding into
 * the ground around the target, which is why the effect still lands even though the arrows are
 * incapable of doing anything.
 */
public final class ArrowRainEffect implements PrankEffect {

    /**
     * Every arrow this plugin has ever spawned that is still in the world.
     *
     * <p>Static on purpose: the safety listener has to be able to ask "is this arrow ours?"
     * without holding a reference to the effect instance, and it must stay correct across a
     * reload. Entries are removed by {@link #forget(UUID)} when the arrow is gone.
     */
    private static final java.util.Set<UUID> PRANK_ARROWS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True when this arrow was spawned by the arrow-rain effect. */
    public static boolean isPrankArrow(org.bukkit.entity.Entity entity) {
        return entity != null && PRANK_ARROWS.contains(entity.getUniqueId());
    }

    /** Drops an arrow from the tracking set once it is gone. */
    public static void forget(UUID entityId) {
        PRANK_ARROWS.remove(entityId);
    }

    /**
     * Forgets every tracked arrow. Called on disable so a reload cannot leave stale UUIDs in
     * the set, which would otherwise let a later arrow reuse the slot and be treated as ours.
     */
    public static void forgetAll() {
        PRANK_ARROWS.clear();
    }

    /** Read-only view for diagnostics and tests. */
    public static int tracked() {
        return PRANK_ARROWS.size();
    }

    private final PrankCraftPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> spawned = new ConcurrentHashMap<>();

    public ArrowRainEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "arrow-rain";
    }

    @Override
    public String description() {
        return "Arrows rain around the target. Zero damage, zero pickup, cleaned up afterwards.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.arrowrain";
    }

    @Override
    public int defaultDurationSeconds() {
        return 5;
    }

    @Override
    public String fire(Player target) {
        cancel(target);

        int perSecond = Math.max(1, plugin.getConfig().getInt("pranks.arrow-rain.per-second", 6));
        int perBurst = Math.max(1, perSecond / 4);
        boolean sticky = plugin.getConfig().getBoolean("pranks.arrow-rain.sticky", true);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> burst(target, perBurst, sticky), 0L, 5L);
        tasks.put(target.getUniqueId(), task);
        return "per-second=" + perSecond + " sticky=" + sticky;
    }

    private void burst(Player target, int count, boolean sticky) {
        if (!target.isOnline()) {
            cancel(target);
            return;
        }
        List<UUID> ids = spawned.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        for (int i = 0; i < count; i++) {
            double angle = Math.random() * Math.PI * 2;
            double distance = Math.random() * 3.0;
            Location spawnAt = target.getLocation().clone()
                    .add(Math.cos(angle) * distance, 9.0 + Math.random() * 3.0, Math.sin(angle) * distance);
            if (spawnAt.getWorld() == null || !spawnAt.getChunk().isLoaded()) {
                continue;
            }
            try {
                Arrow arrow = spawnAt.getWorld().spawn(spawnAt, Arrow.class);
                arrow.setVelocity(new Vector(0, -1.4, 0));
                arrow.setDamage(0.0);
                arrow.setKnockbackStrength(0);
                arrow.setCritical(false);
                arrow.setFireTicks(0);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                arrow.setPersistent(false);
                // Damage 0 is not enough on its own: a server or plugin can scale arrow damage,
                // and a falling arrow still counts as a projectile hit. Track it instead, so the
                // listener can cancel the event outright.
                PRANK_ARROWS.add(arrow.getUniqueId());
                ids.add(arrow.getUniqueId());
            } catch (Throwable throwable) {
                plugin.getLogger().fine("Arrow rain skipped a shot: " + throwable.getMessage());
            }
        }
        if (!sticky) {
            // Arrows that hit anything vanish immediately, so "sticky" is the only thing
            // keeping them around; nothing else to do here.
            return;
        }
    }

    @Override
    public void cancel(Player target) {
        BukkitTask task = tasks.remove(target.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        List<UUID> ids = spawned.remove(target.getUniqueId());
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
            forget(id);
        }
    }
}
