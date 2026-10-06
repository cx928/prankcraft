package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.fx.Fx;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Everyone else reads a death message; the target gets a red flash and a hurt sound.
 *
 * <p>What this deliberately does <em>not</em> do is open the "You Died" respawn screen. That
 * screen is driven by the player's own health, and producing it server-side means actually
 * damaging (or faking damage to) another player's character. Faking a death message is a joke;
 * faking somebody's death state is not, so this stops at the message.
 */
public final class FakeDeathEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;

    public FakeDeathEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "fake-death";
    }

    @Override
    public String description() {
        return "Broadcasts a fake death message for the target and flashes their screen red.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.fakedeath";
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public String fire(Player target) {
        String template = plugin.getConfig().getString("pranks.fake-death.broadcast", "&7{player} &7was blown up by a creeper");
        String rendered = Text.color(template.replace("{player}", target.getName()));

        Text.broadcastExcluding(target.getUniqueId(), rendered);

        if (plugin.getConfig().getBoolean("pranks.fake-death.red-screen", true)) {
            // Two empty red-ish titles read as a damage flash without touching their health.
            target.sendTitle("\u00A7c", "", 0, 6, 8);
        }
        String soundName = plugin.getConfig().getString("pranks.fake-death.sound", "ENTITY_PLAYER_HURT");
        Sound sound = soundName == null ? null : Fx.sound(soundName);
        if (sound == null) {
            sound = Fx.hurtSound();
        }
        if (sound != null) {
            target.playSound(target.getLocation(), sound, 1.0f, 1.0f);
        }

        Text.prefixed(target, "&7Everyone was just told you died. You did not.");
        return "message=\"" + Text.strip(rendered) + "\"";
    }
}
