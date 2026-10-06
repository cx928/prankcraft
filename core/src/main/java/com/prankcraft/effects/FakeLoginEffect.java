package com.prankcraft.effects;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import org.bukkit.entity.Player;

/**
 * Emits a join or leave line for somebody who is not actually connected.
 *
 * <p>When the target is online this only ever emits a <em>leave</em> line, and when they are
 * offline only a <em>join</em> line - so the fake message can never contradict the real one
 * sitting right above it. Used well, this is the setup for "wait, I thought you left?"; used
 * carelessly it just looks like a broken server.
 *
 * <p>The target themselves is always excluded from the broadcast, so nobody is ever told that
 * they personally left the game while sitting in front of it.
 */
public final class FakeLoginEffect implements PrankEffect {

    private final PrankCraftPlugin plugin;

    public FakeLoginEffect(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "fake-login";
    }

    @Override
    public String description() {
        return "Fakes a join/leave message for the target, never contradicting the real one.";
    }

    @Override
    public String permission() {
        return "prankcraft.effect.fakelogin";
    }

    @Override
    public int defaultDurationSeconds() {
        return 0;
    }

    @Override
    public String fire(Player target) {
        boolean online = target.isOnline();
        String template = online
                ? plugin.getConfig().getString("pranks.fake-login.logout-message", "&e{player} left the game")
                : plugin.getConfig().getString("pranks.fake-login.login-message", "&e{player} joined the game");

        String name = target.getName();
        String rendered = Text.color(template.replace("{player}", name));
        Text.broadcastExcluding(target.getUniqueId(), rendered);

        plugin.announce("&7Fake " + (online ? "leave" : "join") + " line emitted for &f" + name);
        return (online ? "fake-leave" : "fake-join") + " name=" + name;
    }
}
