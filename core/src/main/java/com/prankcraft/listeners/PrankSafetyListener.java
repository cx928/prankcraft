package com.prankcraft.listeners;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.effects.ArrowRainEffect;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * The rails that make "harmless" a verifiable claim rather than a promise.
 *
 * <p>Every listener here exists to stop a cosmetic effect from turning into a real one:
 * display TNT cannot explode, prank arrows cannot hurt anybody or be picked up, fake blocks are
 * reverted when a chunk unloads, and a target who logs out takes their trick with them.
 */
public final class PrankSafetyListener implements Listener {

    private final PrankCraftPlugin plugin;

    public PrankSafetyListener(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Rail two of three. The display TNT's fuse is already pinned every tick, so this should
     * never fire - which is exactly why it is here. If a future server version changes how
     * fuses work, the worst case is a cancelled event instead of a crater at spawn.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onExplode(EntityExplodeEvent event) {
        if (plugin.tnt().isOwned(event.getEntity())) {
            event.setCancelled(true);
            event.blockList().clear();
            event.getEntity().remove();
            plugin.tnt().release(event.getEntity());
        }
    }

    /** Belt and braces: no explosion damage of any kind from anything this plugin spawned. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.tnt().isOwned(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prank arrows are decoration. They are spawned with damage 0, but damage can be scaled by
     * other plugins and a falling arrow still registers as a projectile hit, so the event is
     * cancelled outright for anything this plugin spawned.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        // Plain instanceof cast rather than a pattern variable: this source is shared with the
        // legacy builds that must run on Java 8 servers.
        if (!(event.getDamager() instanceof AbstractArrow)) {
            return;
        }
        AbstractArrow arrow = (AbstractArrow) event.getDamager();
        if (ArrowRainEffect.isPrankArrow(arrow)) {
            event.setCancelled(true);
            arrow.remove();
            ArrowRainEffect.forget(arrow.getUniqueId());
        }
    }

    /** Nobody should be able to pick up the props. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        // Arrows are not items until they land; this guards any future prop that is.
        if (event.getEntity() instanceof org.bukkit.entity.Player
                && event.getEntity().hasMetadata("prankcraft-prop")) {
            event.setCancelled(true);
        }
    }

    /** A trick that outlives its target's session is a bug, so it does not. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.tnt().clearFor(event.getPlayer());
    }

    /** Revert fake blocks before their chunk goes away, so nothing is left half-drawn. */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof TNTPrimed && plugin.tnt().isOwned(entity)) {
                entity.remove();
                plugin.tnt().release(entity);
            }
        }
    }
}
