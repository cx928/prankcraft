package com.prankcraft.fx;

import com.prankcraft.PrankCraftPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "fake TNT world" subsystem.
 *
 * <p><b>What the target sees:</b> primed TNT appears around them, hisses with the real fuse
 * sound, flashes, and detonates with the real explosion sound and particles.
 *
 * <p><b>What actually happens to the world:</b> nothing. Blocks are only ever changed in the
 * target's own client through {@code sendBlockChange} and are reverted the moment the effect
 * ends. The primed-TNT entities are real entities, so vanilla renders and animates them
 * perfectly, but their fuse is pinned every tick, so vanilla never detonates them, and their
 * explosion event is cancelled as a second line of defence. No block breaks, no item drops,
 * no damage, no fire, no lasting world change.
 *
 * <p>Three independent rails keep it that way - "fake TNT" that turns out to be real TNT is
 * exactly the bug that ends a friendship:
 * <ol>
 *   <li>fuse pinned to {@link Integer#MAX_VALUE} and re-pinned every tick while alive;</li>
 *   <li>{@code EntityExplodeEvent} cancelled for every entity this manager spawned;</li>
 *   <li>block changes are packets only; chunk data is never touched.</li>
 * </ol>
 */
public final class FakeTntManager {

    private final PrankCraftPlugin plugin;
    private final Random random = new Random();

    /** Player -> the trick currently running for them. */
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Every primed-TNT entity we spawned, so we can pin its fuse and cancel its explosion. */
    private final Set<UUID> ownedEntities = ConcurrentHashMap.newKeySet();

    private BukkitTask fuseGuard;
    private BukkitTask sessionTicker;

    public FakeTntManager(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        stopTasks();
        int guardPeriod = config().tntGuardPeriodTicks;
        int tickPeriod = config().tntTickPeriodTicks;
        fuseGuard = plugin.getServer().getScheduler().runTaskTimer(plugin, this::guardFuses, 20L, guardPeriod);
        sessionTicker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSessions, 1L, tickPeriod);
    }

    /** Cached settings; never read config.yml from the tick loop. */
    private com.prankcraft.config.Cfg config() {
        return plugin.config();
    }

    public void shutdown() {
        stopTasks();
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            clearFor(id);
        }
        // Belt and braces: drop any display TNT that somehow outlived its session.
        for (UUID entityId : new ArrayList<>(ownedEntities)) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        ownedEntities.clear();
        sessions.clear();
    }

    private void stopTasks() {
        if (fuseGuard != null) {
            fuseGuard.cancel();
            fuseGuard = null;
        }
        if (sessionTicker != null) {
            sessionTicker.cancel();
            sessionTicker = null;
        }
    }

    // ------------------------------------------------------------------ public API

    /** True while a fake-TNT trick is running for this player. */
    public boolean isActive(Player target) {
        Session session = sessions.get(target.getUniqueId());
        return session != null && !session.detonated;
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** Cancels the running trick and reverts every faked block for this player. */
    public void clearFor(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session != null) {
            for (Location location : session.positions) {
                revert(session.playerId, location);
            }
            for (UUID entityId : session.entities) {
                Entity entity = plugin.getServer().getEntity(entityId);
                if (entity != null) {
                    entity.remove();
                }
                ownedEntities.remove(entityId);
            }
        }
    }

    /** Cancels the running trick for this player and reverts every faked block. */
    public void clearFor(Player target) {
        if (target != null) {
            clearFor(target.getUniqueId());
        }
    }

    /**
     * Fakes primed TNT around a player.
     *
     * @param target        who should see it
     * @param explicitSpots exact block positions, or {@code null} to pick spots around the player
     * @return how many blocks were faked
     */
    public int fake(Player target, List<Location> explicitSpots) {
        if (target == null || !target.isOnline()) {
            return 0;
        }
        clearFor(target.getUniqueId());

        boolean prime = config().tntPrimeEntity;
        int fuseTicks = config().tntFuseTicks;
        List<Location> spots = explicitSpots != null && !explicitSpots.isEmpty()
                ? explicitSpots
                : pickSpots(target);

        Session session = new Session(target.getUniqueId(), fuseTicks);
        int shown = 0;
        for (Location raw : spots) {
            World world = raw.getWorld();
            if (world == null || !raw.getChunk().isLoaded()) {
                continue;
            }
            Location location = raw.getBlock().getLocation();
            sendFakeBlock(target, location);
            session.positions.add(location);
            shown++;
            if (prime) {
                TNTPrimed tnt = spawnDisplayTnt(location);
                if (tnt != null) {
                    session.entities.add(tnt.getUniqueId());
                }
            }
        }

        if (shown == 0) {
            return 0;
        }

        sessions.put(target.getUniqueId(), session);
        Sound fuse = config().fuseSound;
        if (fuse != null) {
            target.playSound(target.getLocation(), fuse, 1.0f, 1.0f);
        }
        return shown;
    }

    /**
     * Fires the display immediately: explosion particles + sound, then reverts everything.
     *
     * @return true when a running trick was detonated
     */
    public boolean detonate(Player target) {
        Session session = target == null ? null : sessions.get(target.getUniqueId());
        if (session == null) {
            return false;
        }
        boom(target, session);
        return true;
    }

    /** Shows the TNT now and detonates it once the fuse elapses. Returns blocks faked. */
    public int runSequence(Player target, List<Location> spots) {
        int shown = fake(target, spots);
        if (shown > 0) {
            Session session = sessions.get(target.getUniqueId());
            if (session != null) {
                session.detonateWhenDone = true;
            }
        }
        return shown;
    }

    /** Every position currently faked for this player. */
    public List<Location> fakedPositions(Player target) {
        Session session = target == null ? null : sessions.get(target.getUniqueId());
        // Collections.unmodifiableList, not List.copyOf: Java 10+, and this source is shared with
        // the legacy builds that must run on Java 8 servers.
        return session == null
                ? Collections.<Location>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(session.positions));
    }

    /** True when this entity belongs to the trick and must never really explode. */
    public boolean isOwned(Entity entity) {
        return entity != null && ownedEntities.contains(entity.getUniqueId());
    }

    /** Drops our claim on an entity once the trick is over. */
    public void release(Entity entity) {
        if (entity != null) {
            ownedEntities.remove(entity.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ internals

    private void tickSessions() {
        if (sessions.isEmpty()) {
            return;
        }
        int tickPeriod = config().tntTickPeriodTicks;
        for (Session session : new ArrayList<>(sessions.values())) {
            Player target = plugin.getServer().getPlayer(session.playerId);
            if (target == null || !target.isOnline()) {
                clearFor(session.playerId);
                continue;
            }
            session.elapsed += tickPeriod;
            pinFuses(session.entities);
            int flashAt = Math.max(1, session.fuseTicks - 10);
            if (session.elapsed == flashAt && config().tntThrottleFlash) {
                Sound click = config().clickSound;
                if (click != null) {
                    target.playSound(target.getLocation(), click, 0.6f, 1.6f);
                }
            }
            if (session.elapsed >= session.fuseTicks) {
                boolean auto = config().tntAutoDetonate;
                if (auto || session.detonateWhenDone) {
                    boom(target, session);
                } else {
                    session.elapsed = 0; // hold the trick open until an operator fires or clears it
                }
            }
        }
    }

    private void boom(Player target, Session session) {
        double radius = config().tntParticleRadius;
        int count = config().tntParticleCount;
        for (Location location : session.positions) {
            showExplosion(location.clone().add(0.5, 0.5, 0.5), radius, count);
        }
        for (UUID entityId : session.entities) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
            ownedEntities.remove(entityId);
        }
        session.entities.clear();
        sessions.remove(session.playerId);
        session.detonated = true;
        if (config().tntRestoreBlocks) {
            for (Location location : session.positions) {
                revert(session.playerId, location);
            }
            session.positions.clear();
        }
        if (target != null && target.isOnline()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (session.positions.isEmpty()) {
                    return;
                }
                for (Location location : session.positions) {
                    revert(session.playerId, location);
                }
                session.positions.clear();
            }, 30L);
        }
    }

    private void showExplosion(Location center, double radius, int count) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        // Read from the cached snapshot rather than re-resolving by name for every block.
        Particle emitter = Fx.EXPLOSION_EMITTER;
        Particle core = Fx.EXPLOSION_CORE;
        Particle smoke = Fx.EXPLOSION_SMOKE;
        Sound sound = Fx.EXPLOSION_SOUND;
        double radiusSquared = radius * radius;
        int smokeCount = count * 3;

        for (Player viewer : world.getPlayers()) {
            // Squared distance avoids a square root per viewer.
            if (viewer.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            if (emitter != null) {
                viewer.spawnParticle(emitter, center, 2, 0.4, 0.4, 0.4, 0.0);
            }
            if (core != null) {
                viewer.spawnParticle(core, center, count, 1.2, 1.2, 1.2, 0.0);
            }
            if (smoke != null) {
                viewer.spawnParticle(smoke, center, smokeCount, 1.5, 1.5, 1.5, 0.02);
            }
            if (sound != null) {
                viewer.playSound(center, sound, 2.0f, 1.0f);
            }
        }
    }

    /** Sends a block change to exactly one player. Never touches chunk data. */
    private void sendFakeBlock(Player viewer, Location location) {
        try {
            viewer.sendBlockChange(location, config().tntBlockData);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("sendBlockChange failed: " + throwable.getMessage());
        }
    }

    /** Puts the real block back in the target's client. */
    private void revert(UUID viewerId, Location location) {
        World world = location.getWorld();
        if (world == null || !location.getChunk().isLoaded()) {
            return;
        }
        Player viewer = plugin.getServer().getPlayer(viewerId);
        if (viewer == null) {
            // Target logged off: their client is gone and the real world was never modified.
            return;
        }
        try {
            viewer.sendBlockChange(location, location.getBlock().getBlockData());
        } catch (Throwable ignored) {
            // client disconnected mid-revert; harmless
        }
    }

    /**
     * Spawns the display entity. The fuse is set to the maximum immediately, and again every
     * tick by {@link #pinFuses(Set)} / {@link #guardFuses()}, so vanilla can never count it down.
     */
    private TNTPrimed spawnDisplayTnt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        try {
            TNTPrimed tnt = world.spawn(location.clone().add(0.5, 0.0, 0.5), TNTPrimed.class);
            tnt.setFuseTicks(Integer.MAX_VALUE);
            tnt.setYield(0.0f);
            tnt.setIsIncendiary(false);
            tnt.setInvulnerable(true);
            tnt.setGravity(false);
            tnt.setVelocity(new Vector(0, 0, 0));
            ownedEntities.add(tnt.getUniqueId());
            return tnt;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not spawn display TNT: " + throwable.getMessage());
            return null;
        }
    }

    private void pinFuses(Set<UUID> entityIds) {
        for (UUID entityId : entityIds) {
            Entity entity = plugin.getServer().getEntity(entityId);
            // Plain instanceof cast rather than a pattern variable: this source is shared with the
            // legacy builds that must run on Java 8 servers.
            if (entity instanceof TNTPrimed) {
                TNTPrimed tnt = (TNTPrimed) entity;
                tnt.setFuseTicks(Integer.MAX_VALUE);
                tnt.setVelocity(new Vector(0, 0, 0));
            }
        }
    }

    /**
     * Second rail: the periodic sweep. Even if a tick were missed, the fuse is reset here long
     * before a vanilla fuse could expire.
     */
    private void guardFuses() {
        if (ownedEntities.isEmpty()) {
            return;
        }
        for (UUID entityId : new ArrayList<>(ownedEntities)) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (entity == null || entity.isDead()) {
                ownedEntities.remove(entityId);
                continue;
            }
            if (entity instanceof TNTPrimed) {
                TNTPrimed tnt = (TNTPrimed) entity;
                tnt.setFuseTicks(Integer.MAX_VALUE);
                tnt.setVelocity(new Vector(0, 0, 0));
            }
        }
    }

    // ------------------------------------------------------------------ spot picking

    private List<Location> pickSpots(Player target) {
        int wanted = config().tntCount;
        int radius = config().tntRadius;
        List<Location> spots = new ArrayList<>();
        World world = target.getWorld();
        Location origin = target.getLocation();

        for (int ring = 1; ring <= radius && spots.size() < wanted * 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    Location candidate = findFloor(world, origin, dx, dz);
                    if (candidate != null) {
                        spots.add(candidate);
                    }
                }
            }
        }
        Collections.shuffle(spots, random);
        return spots.size() > wanted ? new ArrayList<>(spots.subList(0, wanted)) : spots;
    }

    /**
     * Finds a spot the target would believe: same floor level, or a block down/up. Returns
     * {@code null} when the column is solid, so TNT never appears inside a wall.
     */
    private Location findFloor(World world, Location origin, int dx, int dz) {
        int baseX = origin.getBlockX() + dx;
        int baseZ = origin.getBlockZ() + dz;
        int baseY = origin.getBlockY();
        for (int dy : new int[]{0, -1, 1, -2}) {
            Block block = world.getBlockAt(baseX, baseY + dy, baseZ);
            if (!block.getChunk().isLoaded()) {
                return null;
            }
            if (!isReplaceable(block)) {
                continue;
            }
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getType().isSolid() || isReplaceable(below)) {
                return block.getLocation();
            }
        }
        return null;
    }

    /** Minecraft's own block predicate: a real player cannot stand inside these. */
    private static boolean isReplaceable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type.isAir()
                || type == Material.WATER
                || type == Material.LAVA
                || type == Material.SNOW;
    }

    private static final class Session {
        final UUID playerId;
        final int fuseTicks;
        final Set<Location> positions = new java.util.LinkedHashSet<>();
        final Set<UUID> entities = new HashSet<>();
        int elapsed;
        boolean detonated;
        boolean detonateWhenDone;

        Session(UUID playerId, int fuseTicks) {
            this.playerId = playerId;
            this.fuseTicks = Math.max(5, fuseTicks);
        }
    }
}
