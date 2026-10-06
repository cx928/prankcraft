package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.prank.PrankEffect;
import net.minecraft.server.level.ServerPlayer;

/**
 * The headline trick: primed TNT appears around the target and detonates. Port of
 * {@code com.prankcraft.effects.FakeTntEffect}.
 *
 * <p>All of the interesting logic lives in {@code FakeTntManager}, including the three safety
 * rails that keep the display entities from ever really exploding. This class only exists so the
 * trick participates in the normal consent, duration and audit flow.
 */
public final class FakeTntEffect implements PrankEffect {

    private final PrankCraftMod mod;

    public FakeTntEffect(PrankCraftMod mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "fake-tnt";
    }

    @Override
    public String description() {
        return "Fake primed TNT surrounds the target and detonates. Reverts itself; never damages blocks.";
    }

    @Override
    public boolean enabled() {
        return mod.config().fakeTntEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public int durationSeconds() {
        return mod.config().fakeTntDurationSeconds.get();
    }

    @Override
    public String fire(ServerPlayer target) {
        int shown = mod.tnt().runSequence(target, null);
        if (shown <= 0) {
            // No believable floor spots: report a veto so the engine records nothing and the
            // operator gets "no believable floor spots" instead of a silent success.
            return null;
        }
        return "blocks=" + shown;
    }

    @Override
    public void cancel(ServerPlayer target) {
        mod.tnt().clearFor(target);
    }
}
