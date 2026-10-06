package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Footsteps that belong to nobody. Port of {@code com.prankcraft.effects.PhantomFootstepsEffect}.
 *
 * <p>The target hears a player walking up behind them, and if they look, sees only a puff of
 * smoke where the walker should have been. An empty room that breathes is far creepier than a
 * jumpscare, and it costs nothing.
 *
 * <p>Two details carried over from the Paper module because they are what sells it: the step is
 * placed at a random angle and distance around the target rather than on a fixed circle, and the
 * smoke puff is offset slightly upwards so it reads as a footfall rather than a particle sitting
 * in the floor.
 */
public final class PhantomFootstepsEffect implements PrankEffect {

    /** How far the trick carries, in blocks. */
    private static final double WITNESS_RADIUS = 32.0D;

    private final PrankCraftMod mod;
    private final Map<UUID, Long> running = new ConcurrentHashMap<>();

    public PhantomFootstepsEffect(PrankCraftMod mod) {
        this.mod = mod;
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
    public boolean enabled() {
        return mod.config().phantomEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 12;
    }

    @Override
    public int durationSeconds() {
        return mod.config().phantomDurationSeconds.get();
    }

    @Override
    public String fire(ServerPlayer target) {
        cancel(target);
        int interval = mod.config().phantomIntervalTicks.get();
        // A countdown rather than a timestamp: everything here runs on the server thread, and a
        // tick counter cannot drift or be confused by a system clock change.
        running.put(target.getUUID(), (long) interval);
        return "interval=" + interval + "t sound=" + mod.config().phantomSound.get();
    }

    /** Called once per server tick by the mod; drives every running footstep trail. */
    public void onServerTick() {
        if (running.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Long> entry : running.entrySet()) {
            long ticksLeft = entry.getValue() - 1L;
            if (ticksLeft > 0L) {
                entry.setValue(ticksLeft);
                continue;
            }
            ServerPlayer target = mod.player(entry.getKey());
            if (target == null) {
                running.remove(entry.getKey());
                continue;
            }
            entry.setValue((long) mod.config().phantomIntervalTicks.get());
            step(target);
        }
    }

    private void step(ServerPlayer target) {
        if (!(target.level instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel) target.level;
        SoundEvent sound = Fx.sound(mod.config().phantomSound.get(), Fx.footstepsDefault());
        float volume = mod.config().phantomVolume.get().floatValue();
        float pitch = mod.config().phantomPitch.get().floatValue();

        double angle = Math.random() * Math.PI * 2.0D;
        double distance = 3.0D + Math.random() * 3.0D;
        Vec3 origin = target.position();
        Vec3 step = new Vec3(origin.x + Math.cos(angle) * distance,
                origin.y,
                origin.z + Math.sin(angle) * distance);

        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(origin) > WITNESS_RADIUS * WITNESS_RADIUS) {
                continue;
            }
            if (sound != null) {
                viewer.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS,
                        step.x, step.y, step.z, volume, pitch));
            }
            viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.smokeNormal(), true,
                    step.x, step.y + 0.2D, step.z, 0.2F, 0.1F, 0.2F, 0.01F, 8));
        }
    }

    @Override
    public void cancel(ServerPlayer target) {
        if (target != null) {
            running.remove(target.getUUID());
        }
    }

    /** Called when a player disconnects: their client is gone, so just forget the state. */
    public void forget(UUID playerId) {
        running.remove(playerId);
    }
}
