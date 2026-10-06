package com.prankcraft.listeners;

import com.prankcraft.PrankCraftMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The rails that make "harmless" a verifiable claim rather than a promise. Port of
 * {@code com.prankcraft.listeners.PrankSafetyListener}.
 *
 * <p>Every listener here exists to stop a cosmetic effect from turning into a real one: display
 * TNT cannot explode, and a display entity cannot outlive the trick it belongs to.
 *
 * <p>Note what this class does <em>not</em> do: it never touches a player's inventory, position,
 * health, gamemode or session, and it never cancels an event aimed at real gameplay. The only
 * events it reacts to are ones caused by entities this mod itself spawned.
 *
 * <p><b>On identifying the explosion's source.</b> 1.16.5's {@code Explosion} exposes only
 * {@code getSourceMob()}, which is a {@code LivingEntity} and is null for a primed TNT with no
 * owner. So the {@code Start} handler below cannot reliably name the entity that caused an
 * explosion, and it says so instead of pretending otherwise. What actually keeps the world safe is
 * the invariant the tick handler enforces every single tick - <em>no owned display TNT exists
 * without a live session pinning its fuse</em> - plus {@code Detonate} stripping owned entities
 * out of any explosion that does happen. See README.md, "safety rails".
 */
public final class PrankSafetyListener {

    private final PrankCraftMod mod;

    public PrankSafetyListener(PrankCraftMod mod) {
        this.mod = mod;
    }

    /**
     * Rail two of three, part one: the log of last resort.
     *
     * <p>The display TNT's fuse is pinned every tick, so this should never fire - which is exactly
     * why it is here. If a future Forge version changes how fuses work, this is the line that
     * appears in the server log, loudly, before anything else goes wrong. It deliberately does not
     * claim to be able to identify the source; see the class comment.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosionStart(ExplosionEvent.Start event) {
        if (event.getExplosion() == null || mod.tnt().ownedEntityCount() == 0) {
            return;
        }
        // Something is exploding while we hold display entities. Vanilla can only reach this
        // point for an entity whose fuse expired, so treat it as a failure of rail one.
        Entity source = event.getExplosion().getSourceMob();
        mod.logger().error("An explosion started while PrankCraft is holding {} display TNT entity/ies"
                        + " (source: {}). Rail one should have prevented this. Please report it.",
                mod.tnt().ownedEntityCount(),
                source == null ? "none reported by Forge 1.16.5" : source.toString());
    }

    /**
     * Rail two of three, part two: no display entity may take part in an explosion.
     *
     * <p>Owned entities are dropped from the affected lists unconditionally. If that leaves the
     * explosion with nothing to hit <em>and</em> this mod is holding display entities, the block
     * list is cleared too - there is no way to attribute the explosion to us with certainty on
     * 1.16.5, so the conservative choice is made and the log says why.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getExplosion() == null || mod.tnt().ownedEntityCount() == 0) {
            return;
        }
        boolean removedAny = event.getAffectedEntities().removeIf(entity -> mod.tnt().isOwned(entity));
        if (removedAny) {
            // Remove those entities from the world: an owned entity that reached this point is
            // broken by definition, and must not survive to be ticked again.
            for (Entity entity : new java.util.ArrayList<>(event.getAffectedEntities())) {
                if (entity instanceof PrimedTnt && mod.tnt().isOwned(entity)) {
                    mod.tnt().discard(entity);
                }
            }
            event.getAffectedBlocks().clear();
            mod.logger().warn("Dropped PrankCraft display TNT out of a real explosion and cleared the"
                    + " block list. Rail one should have prevented this - please report it.");
        }
    }

    /**
     * Rail two of three, part three: the invariant sweep.
     *
     * <p>Every tick, before anything else in this mod runs, walk the mod's own registry of display
     * entities and check each one still has a live session pinning its fuse. An owned entity with
     * no session is unreachable by the fuse guard and is removed on the spot. This is what makes
     * "no block may ever actually be destroyed" a property rather than a hope: there is no code
     * path that can leave an unpinned primed TNT in the world for more than one tick.
     *
     * <p>Deliberately NOT a {@code @SubscribeEvent}: it is called from the mod's own tick handler,
     * so its ordering against the fuse guard is fixed in one readable place instead of depending
     * on the registration order of two handlers for the same event.
     */
    public void sweepOrphans() {
        if (mod.tnt().ownedEntityCount() == 0) {
            return;
        }
        for (Entity entity : mod.tnt().ownedEntities()) {
            if (!mod.tnt().isOwned(entity)) {
                continue;
            }
            if (!entity.isAlive()) {
                // Something else removed it (a chunk unload, /kill, another mod). Just forget it:
                // a second removal in the same tick is not worth the risk of a rail misfiring.
                mod.tnt().forget(entity);
                continue;
            }
            if (!mod.tnt().hasLiveSession(entity)) {
                mod.logger().warn("Removing an orphaned PrankCraft display TNT entity (no live session).");
                mod.tnt().discard(entity);
            }
        }
    }

    /**
     * A trick that outlives its chunk is a bug waiting to happen: the display TNT would be
     * reloaded with a fuse that is no longer being pinned, because the session is long gone.
     * Removing it here means a chunk unload can never resurrect an unpinned primed TNT.
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getWorld() instanceof ServerLevel)) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof PrimedTnt && mod.tnt().isOwned(entity)) {
                mod.tnt().discard(entity);
            }
        }
    }

    /** Called from the mod when a level goes away; see {@code FakeTntManager#onLevelUnload}. */
    public void onLevelUnload(ServerLevel level) {
        mod.tnt().onLevelUnload(level);
    }
}
