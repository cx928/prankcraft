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

/**
 * A short, loud, wholly cosmetic fright: a particle burst in front of the target and a mob
 * shriek. Port of {@code com.prankcraft.effects.JumpscareEffect}.
 *
 * <p>No entity is spawned, nothing can hurt them, and the effect is over in a second or two -
 * the point is the jump, not the torture. Nearby players get the same burst so the target is not
 * the only one who reacts, which is what makes the moment funny rather than creepy.
 */
public final class JumpscareEffect implements PrankEffect {

    /** How far the prank carries, in blocks. Same figure as the Paper version. */
    private static final double WITNESS_RADIUS = 24.0D;

    private final PrankCraftMod mod;

    public JumpscareEffect(PrankCraftMod mod) {
        this.mod = mod;
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
    public boolean enabled() {
        return mod.config().jumpscareEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 2;
    }

    @Override
    public int durationSeconds() {
        return mod.config().jumpscareDurationSeconds.get();
    }

    @Override
    public String fire(ServerPlayer target) {
        SoundEvent sound = Fx.sound(mod.config().jumpscareSound.get(), Fx.jumpscareDefault());
        boolean particles = mod.config().jumpscareParticles.get();

        // Place the burst along the target's own line of sight, so it works for someone watching
        // from across the room as well as for the target. Flattened to the horizontal plane so a
        // target looking at their feet does not get a smoke puff inside the floor.
        Vec3 facing = target.getLookAngle();
        Vec3 flat = new Vec3(facing.x, 0.0D, facing.z);
        if (flat.lengthSqr() < 1.0E-4D) {
            flat = new Vec3(0.0D, 0.0D, 1.0D);
        }
        flat = flat.normalize().scale(1.6D);

        if (!(target.level instanceof ServerLevel)) {
            return null;
        }
        ServerLevel level = (ServerLevel) target.level;

        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(target) > WITNESS_RADIUS * WITNESS_RADIUS) {
                continue;
            }
            // Each viewer sees the burst relative to their OWN position, so the fright lands in
            // front of whoever is looking, not in a fixed spot in the world.
            Vec3 burst = viewer.position().add(flat).add(0.0D, 0.9D, 0.0D);
            if (sound != null) {
                viewer.connection.send(new ClientboundSoundPacket(sound, SoundSource.HOSTILE,
                        burst.x, burst.y, burst.z, 1.2F, 1.0F));
            }
            if (particles) {
                viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.smokeNormal(), true,
                        burst.x, burst.y, burst.z, 0.35F, 0.35F, 0.35F, 0.02F, 30));
                viewer.connection.send(new ClientboundLevelParticlesPacket(Fx.flame(), true,
                        burst.x, burst.y, burst.z, 0.25F, 0.25F, 0.25F, 0.01F, 10));
            }
        }
        return "sound=" + (sound == null ? "none" : sound.getLocation()) + " particles=" + particles;
    }
}
