package com.prankcraft.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Small chat/title helpers that only use APIs present in every version we target
 * (Bukkit 1.16 through current Paper). Deliberately avoids the Adventure API so the
 * same jar loads everywhere without shading anything.
 */
public final class Text {

    private Text() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static String strip(String input) {
        return ChatColor.stripColor(color(input));
    }

    public static void msg(CommandSender to, String raw) {
        if (to == null || raw == null || raw.isEmpty()) {
            return;
        }
        to.sendMessage(color(raw));
    }

    public static void prefixed(CommandSender to, String raw) {
        msg(to, "&8[&dPrank&8] &r" + raw);
    }

    public static void broadcast(String permission, String raw) {
        String rendered = color(raw);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (permission == null || p.hasPermission(permission)) {
                p.sendMessage(rendered);
            }
        }
        Bukkit.getConsoleSender().sendMessage(rendered);
    }

    /**
     * Messages everyone except one player.
     *
     * <p>Used for the fake-death and fake-login effects: the whole point is that the other
     * players believe something happened, while the player it "happened to" is never told a
     * falsehood about their own state.
     */
    public static void broadcastExcluding(java.util.UUID excluded, String raw) {
        String rendered = color(raw);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (excluded == null || !p.getUniqueId().equals(excluded)) {
                p.sendMessage(rendered);
            }
        }
        Bukkit.getConsoleSender().sendMessage(rendered);
    }

    public static void title(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        p.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
    }

    public static void actionBar(Player p, String raw) {
        p.sendActionBar(color(raw));
    }

    public static String joinNames(Collection<Player> players) {
        StringBuilder sb = new StringBuilder();
        for (Player p : players) {
            if (sb.length() > 0) {
                sb.append("&7, &f");
            }
            sb.append(p.getName());
        }
        return sb.length() == 0 ? "&7(none)" : sb.toString();
    }
}
