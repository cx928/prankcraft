package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitlesPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shakes the target's screen by abusing the title renderer: a run of empty titles, each nudged a
 * few lines, reads as a camera shake without touching their view direction. Port of
 * {@code com.prankcraft.effects.ScreenShakeEffect}.
 *
 * <p><b>This is as far as a server-side mod can honestly go, and it is a deliberate boundary.</b>
 * Actually spinning or tilting a player's camera means sending movement or look packets that the
 * client will obey - that is the line where a prank turns into taking control of somebody's
 * client, and this module does not cross it. The target keeps full control of their own view at
 * every moment; they are just being shown a wobble. See the hard rules in README.md.
 *
 * <p>Each pulse is a title with {@code fadeIn=0, stay=2, fadeOut=0} - a title that exists just
 * long enough to be drawn once - and the nudge is a run of newlines. One packet per pulse, to one
 * player.
 */
public final class ScreenShakeEffect implements PrankEffect {

    private final PrankCraftMod mod;
    private final Map<UUID, Shake> running = new ConcurrentHashMap<>();

    public ScreenShakeEffect(PrankCraftMod mod) {
        this.mod = mod;
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
    public boolean enabled() {
        return mod.config().screenShakeEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 2;
    }

    @Override
    public int durationSeconds() {
        return mod.config().screenShakeDurationSeconds.get();
    }

    @Override
    public String fire(ServerPlayer target) {
        cancel(target);

        int pulses = mod.config().screenShakePulses.get();
        int interval = mod.config().screenShakeIntervalTicks.get();
        int strength = mod.config().screenShakeStrength.get();

        Shake shake = new Shake(pulses, interval, strength);
        running.put(target.getUUID(), shake);
        // First pulse immediately, the rest from the tick handler. A title effect that only
        // starts after an interval feels like lag rather than a shake.
        pulse(target, strength);
        return "pulses=" + pulses + " strength=" + strength + " interval=" + interval + "t";
    }

    /** Called once per server tick by the mod; drives every running shake. */
    public void onServerTick() {
        if (running.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Shake> entry : running.entrySet()) {
            ServerPlayer target = mod.player(entry.getKey());
            Shake shake = entry.getValue();
            if (target == null || --shake.pulsesLeft <= 0) {
                running.remove(entry.getKey());
                if (target != null) {
                    clear(target);
                }
                continue;
            }
            if (++shake.ticksSincePulse < shake.interval) {
                continue;
            }
            shake.ticksSincePulse = 0;
            pulse(target, shake.strength);
        }
    }

    private void pulse(ServerPlayer target, int strength) {
        int offset = (int) (Math.random() * strength * 2) - strength;
        Component blank = Text.fromLegacy(pad(offset));
        target.connection.send(new ClientboundSetTitlesPacket(
                ClientboundSetTitlesPacket.Type.TITLE, blank));
        target.connection.send(new ClientboundSetTitlesPacket(
                ClientboundSetTitlesPacket.Type.TIMES, null, 0, 2, 0));
    }

    /**
     * The title renderer strips leading whitespace, so the vertical nudge is done with a
     * zero-width space followed by a run of newlines. The line stays visually empty.
     */
    private static String pad(int lines) {
        StringBuilder sb = new StringBuilder("\u200B");
        for (int i = 0; i < Math.abs(lines); i++) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Removes whatever nudge is on screen. Safe to call when nothing is running. */
    private void clear(ServerPlayer target) {
        target.connection.send(new ClientboundSetTitlesPacket(
                ClientboundSetTitlesPacket.Type.TITLE, Text.fromLegacy("")));
        target.connection.send(new ClientboundSetTitlesPacket(
                ClientboundSetTitlesPacket.Type.TIMES, null, 0, 1, 0));
    }

    @Override
    public void cancel(ServerPlayer target) {
        if (target == null) {
            return;
        }
        Shake shake = running.remove(target.getUUID());
        if (shake != null) {
            clear(target); // clear whatever nudge is on screen
        }
    }

    /** Called when a player disconnects: their client is gone, so just forget the state. */
    public void forget(UUID playerId) {
        running.remove(playerId);
    }

    private static final class Shake {
        int pulsesLeft;
        final int interval;
        final int strength;
        int ticksSincePulse;

        Shake(int pulsesLeft, int interval, int strength) {
            this.pulsesLeft = pulsesLeft;
            this.interval = Math.max(1, interval);
            this.strength = strength;
        }
    }
}
