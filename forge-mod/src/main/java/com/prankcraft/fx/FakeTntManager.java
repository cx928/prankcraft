package com.prankcraft.fx;

import com.prankcraft.PrankCraftMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "fake TNT world" subsystem. Port of {@code com.prankcraft.fx.FakeTntManager}.
 *
 * <p><b>What the target sees:</b> primed TNT appears around them, hisses with the real fuse
 * sound, flashes, and detonates with the real explosion sound and particles.
 *
 * <p><b>What actually happens to the world:</b> nothing. Blocks are only ever changed in the
 * target's own client through {@link ClientboundBlockUpdatePacket} and are reverted the moment
 * the effect ends. The primed-TNT entities are real entities, so vanilla renders them perfectly,
 * but their fuse is pinned every tick, so vanilla never detonates them, and their explosion is
 * cancelled as a second line of defence. No block breaks, no item drops, no damage, no fire, no
 * lasting world change.
 *
 * <p>Three independent rails keep it that way - "fake TNT" that turns out to be real TNT is
 * exactly the bug that ends a friendship:
 * <ol>
 *   <li>fuse pinned to {@link #PINNED_FUSE} on spawn, every tick, and by a periodic sweep;</li>
 *   <li>{@code ExplosionEvent.Start} cancelled by {@code PrankSafetyListener} for every entity
 *       this manager owns;</li>
 *   <li>block changes are packets only; chunk data is never touched, and the real state is sent
 *       back when the trick ends.</li>
 * </ol>
 *
 * <p><b>Everything here is aimed at one connection.</b> That is why this class talks in packets
 * rather than using {@code ServerLevel} helpers: {@code levelEvent}, {@code sendParticles} and
 * {@code playSound} on a {@link ServerLevel} all broadcast to every player nearby, which would
 * tell the whole server that the prank happened. Each effect below builds the packet and hands it
 * to {@code target.connection.send(...)} and nobody else. The single exception is the primed-TNT
 * entity, which vanilla tracks normally - and it is only ever a TNT block that never lights.
 */
public final class FakeTntManager {

    /**
     * The fuse value every display entity is pinned to.
     *
     * <p>{@link Integer#MAX_VALUE} is what the Paper module uses, and it is the safest value:
     * vanilla's fuse comparison can never reach it.
     *
     * <p><b>Maintainer note.</b> This constant exists separately from its uses for a reason. In
     * 1.17 and later the primed-TNT fuse is a {@code short} rather than an {@code int}, so a port
     * that keeps passing {@code Integer.MAX_VALUE} to the setter silently truncates it to
     * {@code -1} and the TNT explodes on the next tick. Anyone forward-porting this class must
     * change this one value to {@code Short.MAX_VALUE} at the same time.
     */
    private static final int PINNED_FUSE = Integer.MAX_VALUE;

    private final PrankCraftMod mod;
    private final Random random = new Random();

    /** Player -> the trick currently running for them. */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /**
     * Every primed-TNT entity we spawned, so we can pin its fuse and cancel its explosion.
     *
     * <p>The entities are held directly rather than by id on purpose. An id-only registry needs a
     * level lookup to be useful, and a level lookup can fail for reasons that have nothing to do
     * with safety (a level mid-teardown, a chunk in the middle of loading). Holding the entity
     * means the fuse guard, the sweep and the explosion rails can all act immediately, with no
     * lookup that could come back empty at the wrong moment.
     */
    private final Set<Entity> ownedEntities = ConcurrentHashMap.newKeySet();

    private int guardCountdown;
    private int tickCountdown;

    public FakeTntManager(PrankCraftMod mod) {
        this.mod = mod;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called from the mod's server tick handler. Runs two independent timers off one hook: the
     * fuse guard (rail two) and the session ticker. Both periods are re-read from config on every
     * wrap, so {@code /prankcraft reload} takes effect without a restart.
     */
    public void onServerTick() {
        if (--guardCountdown <= 0) {
            guardCountdown = Math.max(1, mod.config().tntGuardPeriodTicks.get());
            guardFuses();
        }
        if (--tickCountdown <= 0) {
            tickCountdown = Math.max(1, mod.config().tntTickPeriodTicks.get());
            tickSessions(tickCountdown);
        }
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            clearFor(id);
        }
        // Belt and braces: drop any display TNT that somehow outlived its session.
        for (Entity entity : new ArrayList<>(ownedEntities)) {
            entity.remove();
        }
        ownedEntities.clear();
        sessions.clear();
    }

    /** Called when a level goes away; any trick running inside it is over by definition. */
    public void onLevelUnload(ServerLevel level) {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.level == level) {
                clearFor(session.playerId);
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** True while a fake-TNT trick is running for this player. */
    public boolean isActive(ServerPlayer target) {
        Session session = target == null ? null : sessions.get(target.getUUID());
        return session != null && !session.detonated;
    }

    public int activeSessions() {
        return sessions.size();
    }

    /** Cancels the running trick and reverts every faked block for this player. */
    public void clearFor(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        for (BlockPos pos : session.positions) {
            revert(session.playerId, session.level, pos);
        }
        session.positions.clear();
        for (Entity entity : new ArrayList<>(session.entities)) {
            ownedEntities.remove(entity);
            entity.remove();
        }
        session.entities.clear();
    }

    public void clearFor(ServerPlayer target) {
        if (target != null) {
            clearFor(target.getUUID());
        }
    }

    /**
     * Fakes primed TNT around a player.
     *
     * @param target        who should see it
     * @param explicitSpots exact block positions, or {@code null} to pick spots around the player
     * @return how many blocks were faked
     */
    public int fake(ServerPlayer target, List<BlockPos> explicitSpots) {
        if (target == null || !(target.level instanceof ServerLevel)) {
            return 0;
        }
        ServerLevel level = (ServerLevel) target.level;
        clearFor(target.getUUID());

        boolean prime = mod.config().tntPrimeEntity.get();
        int fuseTicks = mod.config().tntFuseTicks.get();
        List<BlockPos> spots = explicitSpots != null && !explicitSpots.isEmpty()
                ? explicitSpots
                : pickSpots(level, target.blockPosition());

        Session session = new Session(target.getUUID(), level, fuseTicks);
        int shown = 0;
        for (BlockPos raw : spots) {
            if (!level.isLoaded(raw)) {
                continue;
            }
            BlockPos pos = raw.immutable();
            // Rail three: the ONLY thing that changes is what this one client is told.
            sendFakeBlock(target, pos, Blocks.TNT.defaultBlockState());
            session.positions.add(pos);
            shown++;
            if (prime) {
                PrimedTnt tnt = spawnDisplayTnt(level, pos);
                if (tnt != null) {
                    session.entities.add(tnt);
                }
            }
        }

        if (shown == 0) {
            return 0;
        }

        sessions.put(target.getUUID(), session);
        playSound(target, target.getX(), target.getY(), target.getZ(),
                Fx.fuseSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
        return shown;
    }

    /**
     * Fires the display immediately: explosion particles + sound, then reverts everything.
     *
     * @return true when a running trick was detonated
     */
    public boolean detonate(ServerPlayer target) {
        Session session = target == null ? null : sessions.get(target.getUUID());
        if (session == null) {
            return false;
        }
        boom(target, session);
        return true;
    }

    /** Shows the TNT now and detonates it once the fuse elapses. Returns blocks faked. */
    public int runSequence(ServerPlayer target, List<BlockPos> spots) {
        int shown = fake(target, spots);
        if (shown > 0) {
            Session session = sessions.get(target.getUUID());
            if (session != null) {
                session.detonateWhenDone = true;
            }
        }
        return shown;
    }

    /** Every position currently faked for this player. */
    public List<BlockPos> fakedPositions(ServerPlayer target) {
        Session session = target == null ? null : sessions.get(target.getUUID());
        return session == null ? new ArrayList<>() : new ArrayList<>(session.positions);
    }

    /** True when this entity belongs to the trick and must never really explode. */
    public boolean isOwned(Entity entity) {
        return entity != null && ownedEntities.contains(entity);
    }

    /**
     * Drops our claim on an entity. Called by {@code PrankSafetyListener} the moment it cancels an
     * explosion, so a display entity can never be claimed twice and never keeps a stale reference.
     */
    public void release(Entity entity) {
        if (entity != null) {
            ownedEntities.remove(entity);
        }
    }

    /**
     * Removes a specific owned entity and forgets it. Used when a chunk unloads underneath a
     * running trick, so the display TNT cannot come back when the chunk reloads.
     */
    public void discard(Entity entity) {
        if (entity == null) {
            return;
        }
        ownedEntities.remove(entity);
        for (Session session : sessions.values()) {
            session.entities.remove(entity);
        }
        entity.remove();
    }

    /** A snapshot of the registry, for the safety sweep in {@code PrankSafetyListener}. */
    public Set<Entity> ownedEntities() {
        return new HashSet<>(ownedEntities);
    }

    /**
     * Forgets an owned entity that has already been removed from the world by something else.
     *
     * <p>The safety sweep uses this instead of {@link #discard(Entity)}: the entity is already
     * gone, and calling {@code remove()} on it again would be a second removal of the same entity
     * in the same tick, which is the kind of thing that turns a safety rail into a crash.
     */
    public void forget(Entity entity) {
        if (entity == null) {
            return;
        }
        ownedEntities.remove(entity);
        for (Session session : sessions.values()) {
            session.entities.remove(entity);
        }
    }

    /** How many display entities are alive right now. Zero is the normal resting state. */
    public int ownedEntityCount() {
        return ownedEntities.size();
    }

    /**
     * True when some live session is still pinning this entity's fuse.
     *
     * <p>This is the invariant the safety sweep checks. An owned entity with no session is
     * unreachable by {@link #guardFuses()} and therefore dangerous, no matter how it got that way.
     */
    public boolean hasLiveSession(Entity entity) {
        if (entity == null) {
            return false;
        }
        for (Session session : sessions.values()) {
            if (session.entities.contains(entity)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ internals

    private void tickSessions(int elapsedThisTick) {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            ServerPlayer target = session.server() == null
                    ? null
                    : session.server().getPlayerList().getPlayer(session.playerId);
            if (target == null) {
                clearFor(session.playerId);
                continue;
            }
            session.elapsed += elapsedThisTick;
            pinFuses(session.entities);

            int flashAt = Math.max(1, session.fuseTicks - 10);
            if (session.elapsed == flashAt && mod.config().tntThrottleFlash.get()) {
                playSound(target, target.getX(), target.getY(), target.getZ(),
                        Fx.clickSound(), SoundSource.BLOCKS, 0.6F, 1.6F);
            }

            if (session.elapsed >= session.fuseTicks) {
                boolean auto = mod.config().tntAutoDetonate.get();
                if (auto || session.detonateWhenDone) {
                    boom(target, session);
                } else {
                    session.elapsed = 0; // hold the trick open until an operator fires or clears it
                }
            }
        }
    }

    private void boom(ServerPlayer target, Session session) {
        double radius = mod.config().tntParticleRadius.get();
        int count = mod.config().tntParticleCount.get();
        for (BlockPos pos : session.positions) {
            showExplosion(target, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, radius, count);
        }
        for (Entity entity : new ArrayList<>(session.entities)) {
            ownedEntities.remove(entity);
            entity.remove();
        }
        session.entities.clear();
        sessions.remove(session.playerId);
        session.detonated = true;

        if (mod.config().tntRestoreBlocksOnDetonate.get()) {
            for (BlockPos pos : session.positions) {
                revert(session.playerId, session.level, pos);
            }
            session.positions.clear();
        } else {
            // The smoke of a real explosion hides the block change for a moment. Re-send the
            // real blocks a second and a half later, so a client that somehow missed the first
            // send is not left staring at a TNT block that is not there.
            final List<BlockPos> remaining = new ArrayList<>(session.positions);
            mod.schedule(30, () -> {
                for (BlockPos pos : remaining) {
                    revert(session.playerId, session.level, pos);
                }
                session.positions.clear();
            });
        }
    }

    /** Particles and sound for one player only. Nothing is broadcast to the level. */
    private void showExplosion(ServerPlayer viewer, double x, double y, double z, double radius, int count) {
        if (viewer == null) {
            return;
        }
        double radiusSquared = radius * radius;
        if (viewer.distanceToSqr(x, y, z) > radiusSquared) {
            return;
        }
        // The vanilla explosion smoke puff, delivered as the same level event a real blast uses.
        viewer.connection.send(new ClientboundLevelEventPacket(LevelEvent.PARTICLE_LARGE_SMOKE,
                new BlockPos(x, y, z), 0, false));
        viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.smoke(), true,
                x, y, z, 1.5F, 1.5F, 1.5F, 0.02F, count * 3));
        viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.explosionEmitter(), true,
                x, y, z, 0.4F, 0.4F, 0.4F, 0.0F, 2));
        viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.explosion(), true,
                x, y, z, 1.2F, 1.2F, 1.2F, 0.0F, count));
        playSound(viewer, x, y, z, Fx.explosionSound(), SoundSource.BLOCKS, 2.0F, 1.0F);
    }

    /** Sends a sound to exactly one player. */
    private void playSound(ServerPlayer viewer, double x, double y, double z,
                           SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (viewer == null || sound == null) {
            return;
        }
        viewer.connection.send(new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch));
    }

    /** Sends a block change to exactly one player. Never touches chunk data. */
    private void sendFakeBlock(ServerPlayer viewer, BlockPos pos, BlockState state) {
        try {
            viewer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        } catch (Throwable throwable) {
            mod.logger().warn("Could not send a fake block change: {}", throwable.getMessage());
        }
    }

    /** Puts the real block back in the target's client. */
    private void revert(java.util.UUID viewerId, ServerLevel level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return;
        }
        ServerPlayer viewer = level.getServer() == null
                ? null
                : level.getServer().getPlayerList().getPlayer(viewerId);
        if (viewer == null) {
            // Target logged off: their client is gone and the real world was never modified.
            return;
        }
        sendFakeBlock(viewer, pos, level.getBlockState(pos));
    }

    /**
     * Spawns the display entity. The fuse is set to the maximum immediately, and again every tick
     * by {@link #pinFuses(Set)} / {@link #guardFuses()}, so vanilla can never count it down.
     *
     * <p>Gravity is off and the velocity is zeroed: the entity is a TNT block that hangs in the
     * air exactly where the fake block is. It deliberately does NOT sync its fuse to the client -
     * tracked data only refreshes when the value changes, so the client keeps a primed TNT with a
     * maximum fuse, which vanilla renders as an ordinary TNT block. That is the intended look: a
     * block everyone agrees is about to explode, which never does.
     */
    private PrimedTnt spawnDisplayTnt(ServerLevel level, BlockPos pos) {
        try {
            Entity created = EntityType.TNT.create(level);
            if (!(created instanceof PrimedTnt)) {
                return null;
            }
            PrimedTnt tnt = (PrimedTnt) created;
            tnt.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
            tnt.setFuse(PINNED_FUSE);
            tnt.setNoGravity(true);
            tnt.setDeltaMovement(Vec3.ZERO);
            // Invulnerable, so a stray arrow or a player's sword cannot "set it off" and turn a
            // display entity into a real explosion. Cancelling the explosion is rail two's job,
            // but there is no reason to rely on the rail when the front door can be locked too.
            tnt.setInvulnerable(true);
            level.addFreshEntity(tnt);
            ownedEntities.add(tnt);
            return tnt;
        } catch (Throwable throwable) {
            mod.logger().warn("Could not spawn display TNT: {}", throwable.getMessage());
            return null;
        }
    }

    /** Re-pins the fuse on everything a session owns. Called once per session tick. */
    private void pinFuses(Set<Entity> entities) {
        if (entities.isEmpty()) {
            return;
        }
        for (Entity entity : new ArrayList<>(entities)) {
            pin(entity);
        }
    }

    /**
     * Pins one entity's fuse, or forgets it if it is gone.
     *
     * <p>Everything that touches a display entity goes through here, so there is exactly one place
     * that has to be correct for rail one to hold.
     */
    private void pin(Entity entity) {
        if (!(entity instanceof PrimedTnt)) {
            return;
        }
        PrimedTnt tnt = (PrimedTnt) entity;
        tnt.setFuse(PINNED_FUSE);
        tnt.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Second rail: the periodic sweep. Even if a tick were missed, the fuse is reset here long
     * before a vanilla fuse could expire. This is the single most important method in the file -
     * if it ever stops being called, the display entities eventually detonate for real.
     */
    private void guardFuses() {
        if (ownedEntities.isEmpty()) {
            return;
        }
        for (Entity entity : new ArrayList<>(ownedEntities)) {
            if (!entity.isAlive()) {
                ownedEntities.remove(entity);
                continue;
            }
            pin(entity);
        }
    }

    // ------------------------------------------------------------------ spot picking

    private List<BlockPos> pickSpots(ServerLevel level, BlockPos origin) {
        int wanted = mod.config().tntCount.get();
        int radius = mod.config().tntRadius.get();
        List<BlockPos> spots = new ArrayList<>();

        for (int ring = 1; ring <= radius && spots.size() < wanted * 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos candidate = findFloor(level, origin, dx, dz);
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
    private BlockPos findFloor(ServerLevel level, BlockPos origin, int dx, int dz) {
        int[] offsets = {0, -1, 1, -2};
        for (int dy : offsets) {
            BlockPos pos = origin.offset(dx, dy, dz);
            if (!level.isLoaded(pos)) {
                return null;
            }
            if (!isReplaceable(level.getBlockState(pos))) {
                continue;
            }
            BlockState below = level.getBlockState(pos.below());
            // A real player can stand on a solid block, or in the bottom of a pool. Anything
            // else - mid-air, inside a wall, on top of a fence - would give the trick away.
            if (below.getMaterial().isSolid() || isReplaceable(below)) {
                return pos;
            }
        }
        return null;
    }

    /** Minecraft's own block predicate: a real player cannot stand inside these. */
    private static boolean isReplaceable(BlockState state) {
        Material material = state.getMaterial();
        return material == Material.AIR
                || material == Material.WATER
                || material == Material.LAVA
                || material == Material.SNOW
                // Plants, torches, rails and the like: a TNT block would replace them, so they
                // are legal spots too.
                || material.isReplaceable();
    }

    private static final class Session {
        final UUID playerId;
        final ServerLevel level;
        final int fuseTicks;
        final Set<BlockPos> positions = new LinkedHashSet<>();
        final Set<Entity> entities = new HashSet<>();
        int elapsed;
        boolean detonated;
        boolean detonateWhenDone;

        Session(UUID playerId, ServerLevel level, int fuseTicks) {
            this.playerId = playerId;
            this.level = level;
            this.fuseTicks = Math.max(5, fuseTicks);
        }

        net.minecraft.server.MinecraftServer server() {
            return level.getServer();
        }
    }
}
