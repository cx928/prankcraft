package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Makes it thunder - but only for one person.
 *
 * <p>The illusion is delivered with {@code ClientboundGameEventPacket}, which is exactly the
 * packet the server sends when the weather really changes. Everyone else keeps enjoying the
 * sunshine; the target genuinely sees rain, darkening and lightning. Because it is a packet,
 * it also costs nothing and touches no world state.
 *
 * <p>If a future server version renames the packet (this plugin spans 1.16 through current,
 * and that class has already been reshaped twice), the effect degrades to the vanilla API on
 * a temporary weather cycle, or politely reports that it is unsupported. It never fails loudly
 * in the middle of somebody's prank.
 */
public final class FakeWeatherEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;

    public FakeWeatherEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
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
    public String permission() {
        return "prankcraft.effect.weather";
    }

    @Override
    public int defaultDurationSeconds() {
        return 10;
    }

    @Override
    public String fire(Player target) {
        String weather = plugin.getConfig().getString("pranks.fake-weather.weather", "THUNDER");
        if (weather == null) {
            weather = "THUNDER";
        }

        boolean thunder = weather.equalsIgnoreCase("THUNDER");
        boolean rain = thunder || weather.equalsIgnoreCase("RAIN");

        boolean sent;
        String how;
        if (!rain) {
            sent = RainPackets.stop(target);
            how = "packet(clear)";
        } else {
            sent = RainPackets.start(target, thunder);
            how = "packet(" + (thunder ? "thunder" : "rain") + ")";
        }

        if (!sent) {
            how = useFallback(target, rain, thunder);
        }

        String soundName = plugin.getConfig().getString("pranks.fake-weather.sound", "ENTITY_LIGHTNING_BOLT_THUNDER");
        if (thunder && soundName != null) {
            Sound sound = Fx.sound(soundName, "ENTITY_LIGHTNING_BOLT_THUNDER", "AMBIENT_WEATHER_THUNDER");
            if (sound != null) {
                target.playSound(target.getLocation(), sound, 1.0f, 0.9f);
            }
        }
        return how;
    }

    /**
     * Vanilla fallback: a short real weather cycle on the target's world. Only ever used when
     * the packet route is unavailable, and the effect's {@link #cancel(Player)} restores the
     * previous weather so the world is not left changed.
     */
    private String useFallback(Player target, boolean rain, boolean thunder) {
        if (!plugin.getConfig().getBoolean("pranks.fake-weather.allow-world-fallback", false)) {
            Text.prefixed(target, "&8(this server's version has no fake-weather packet to send)");
            return "unsupported";
        }
        org.bukkit.World world = target.getWorld();
        world.setStorm(rain);
        world.setThundering(thunder);
        if (rain) {
            world.setWeatherDuration(20 * durationSeconds());
        }
        if (thunder) {
            world.setThunderDuration(20 * durationSeconds());
        }
        return "world-fallback(" + (thunder ? "thunder" : rain ? "rain" : "clear") + ")";
    }

    private int durationSeconds() {
        return Math.max(1, plugin.getConfig().getInt("pranks.fake-weather.duration-seconds", 10));
    }

    @Override
    public void cancel(Player target) {
        if (target == null || !target.isOnline()) {
            return;
        }
        RainPackets.stop(target);
        if (plugin.getConfig().getBoolean("pranks.fake-weather.allow-world-fallback", false)) {
            org.bukkit.World world = target.getWorld();
            world.setStorm(false);
            world.setThundering(false);
        }
    }

    /** Unused hook kept for tab-completion of the configured weather values. */
    public static List<String> options() {
        return List.of("RAIN", "THUNDER", "CLEAR");
    }
}
