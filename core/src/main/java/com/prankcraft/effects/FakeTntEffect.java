package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import org.bukkit.entity.Player;

/**
 * The headline trick: primed TNT appears around the target and detonates.
 *
 * <p>All of the interesting logic lives in {@code FakeTntManager}, including the three safety
 * rails that keep the display entities from ever really exploding. This class only exists so
 * the trick participates in the normal permission, consent, duration and audit flow.
 */
public final class FakeTntEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;

    public FakeTntEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
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
    public String permission() {
        return "prankcraft.effect.faketnt";
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public String fire(Player target) {
        int shown = plugin.tnt().runSequence(target, null);
        if (shown <= 0) {
            return null;
        }
        return "blocks=" + shown;
    }

    @Override
    public void cancel(Player target) {
        plugin.tnt().clearFor(target);
    }
}
