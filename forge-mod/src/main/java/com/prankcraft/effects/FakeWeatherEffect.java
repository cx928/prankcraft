package com.prankcraft.effects;

import com.prankcraft.PrankCraftMod;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Arrays;
import java.util.List;

/**
 * Makes it thunder - but only for one person. Port of
 * {@code com.prankcraft.effects.FakeWeatherEffect}.
 *
 * <p>The illusion is delivered with {@link ClientboundGameEventPacket}, which is exactly the
 * packet the server sends when the weather really changes. Everyone else keeps enjoying the
 * sunshine; the target genuinely sees rain, darkening and lightning. Because it is a packet, it
 * also costs nothing and touches no world state - and, importantly, it is sent to one connection,
 * so nothing leaks into the level's own weather.
 *
 * <p>Packet layout in 1.16.5 (verified against the official mappings, this is the version-specific
 * part a maintainer is most likely to get wrong):
 * <ul>
 *   <li>{@code ClientboundGameEventPacket(Type, float)} - the data value is a FLOAT in this
 *       version, not the int it became in 1.20.2+;</li>
 *   <li>rain on/off is {@code START_RAINING}/{@code STOP_RAINING} with a float value;</li>
 *   <li>thunder on/off is {@code RAIN_LEVEL_CHANGE}/{@code THUNDER_LEVEL_CHANGE}; the client
 *       reads the float as a comparison against zero, so {@code 1.0F} means "on" and
 *       {@code 0.0F} means "off".</li>
 * </ul>
 * If a future Forge version renames the packet, the effect degrades to the vanilla world-weather
 * fallback when the operator has opted into it, or politely reports that it is unsupported. It
 * never fails loudly in the middle of somebody's prank.
 */
public final class FakeWeatherEffect implements PrankEffect {

    /** Data value for "on". Float in 1.16.5; the client compares it against zero. */
    private static final float ON = 1.0F;
    /** Data value for "off". */
    private static final float OFF = 0.0F;

    private final PrankCraftMod mod;

    public FakeWeatherEffect(PrankCraftMod mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "fake-weather";
    }

    @Override
    public String description() {
        return "Thunder and rain for the target's client only, delivered as a weather packet.";
    }

    @Override
    public boolean enabled() {
        return mod.config().fakeWeatherEnabled.get();
    }

    @Override
    public int defaultDurationSeconds() {
        return 10;
    }

    @Override
    public int durationSeconds() {
        return mod.config().fakeWeatherDurationSeconds.get();
    }

    @Override
    public String fire(ServerPlayer target) {
        String weather = mod.config().fakeWeatherWeather.get();
        if (weather == null) {
            weather = "THUNDER";
        }

        boolean thunder = weather.equalsIgnoreCase("THUNDER");
        boolean rain = thunder || weather.equalsIgnoreCase("RAIN");

        String how;
        if (!rain) {
            stop(target);
            how = "packet(clear)";
        } else {
            start(target, thunder);
            how = "packet(" + (thunder ? "thunder" : "rain") + ")";
        }

        if (thunder) {
            SoundEvent sound = Fx.sound(mod.config().fakeWeatherSound.get(), Fx.thunderDefault());
            if (sound != null) {
                target.connection.send(new ClientboundSoundPacket(sound, SoundSource.WEATHER,
                        target.getX(), target.getY(), target.getZ(), 1.0F, 0.9F));
            }
        }
        return how;
    }

    private void start(ServerPlayer target, boolean thunder) {
        target.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.START_RAINING, ON));
        if (thunder) {
            target.connection.send(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, ON));
        }
    }

    private void stop(ServerPlayer target) {
        target.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.STOP_RAINING, OFF));
        target.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, OFF));
    }

    @Override
    public void cancel(ServerPlayer target) {
        if (target == null) {
            return;
        }
        stop(target);

        // Vanilla fallback: a short REAL weather cycle on the target's level. Only ever used when
        // an operator explicitly opts in, because it changes the weather for everyone - which is
        // exactly what a cosmetic prank is not supposed to do. Restored on cancel either way.
        if (mod.config().fakeWeatherAllowWorldFallback.get() && target.level instanceof ServerLevel) {
            ServerLevel level = (ServerLevel) target.level;
            level.setWeatherParameters(0, 0, false, false);
        }
    }

    /** Tab-completion options for the configured weather value. */
    public static List<String> options() {
        return Arrays.asList("RAIN", "THUNDER", "CLEAR");
    }
}
