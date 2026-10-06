package com.prankcraft.commands;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.prank.PrankEngine;
import com.prankcraft.util.Compat;
import com.prankcraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /prankconsent ...} - the audit and override command for staff.
 *
 * <p>Trusted staff get exactly two powers here: look at the record, and fire an effect at
 * somebody who has not consented. Both are logged, and the force path is deliberately loud
 * about what it is doing, because "the admin did it" is not a defence a player can use.
 */
public final class ConsentCommand implements CommandExecutor, org.bukkit.command.TabCompleter {

    private static final String PERMISSION = "prankcraft.consent.admin";

    private final PrankCraftPlugin plugin;

    public ConsentCommand(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            Text.prefixed(sender, "&cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        // Classic switch statement rather than an arrow-form switch: Java 8 compatibility for the
        // legacy builds, which share this source file.
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info":
                info(sender, args);
                break;
            case "force":
                force(sender, args);
                break;
            case "grant":
                grant(sender, args, true);
                break;
            case "revoke":
                grant(sender, args, false);
                break;
            case "clear":
                clear(sender, args);
                break;
            default:
                usage(sender);
                break;
        }
        return true;
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prankconsent info <player>");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null || target.getUniqueId() == null) {
            Text.prefixed(sender, "&cUnknown player &f" + args[1] + "&c.");
            return;
        }
        boolean optedIn = plugin.consent().isOptedIn(target.getUniqueId());
        Text.prefixed(sender, "&7Player: &f" + name(target) + " &8(" + target.getUniqueId() + ")");
        Text.prefixed(sender, "&7Pranks: " + (optedIn ? "&aopted in" : "&cnot opted in"));
        List<String> allowed = new ArrayList<>();
        for (UUID id : plugin.consent().allowedFor(target.getUniqueId())) {
            allowed.add(id.equals(com.prankcraft.consent.ConsentManager.ALL) ? "*" : name(Bukkit.getOfflinePlayer(id)));
        }
        Text.prefixed(sender, "&7Allowed pranksters: &f" + (allowed.isEmpty() ? "none" : String.join(", ", allowed)));
        int pending = plugin.consent().requestsFor(target.getUniqueId()).size();
        Text.prefixed(sender, "&7Pending requests: &f" + pending);
    }

    private void force(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Text.prefixed(sender, "&cUsage: /prankconsent force <player> <effect>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.prefixed(sender, "&cNo online player named &f" + args[1] + "&c.");
            return;
        }
        // A named player is the normal case because the record should carry a real identity.
        // The console is also accepted - it is still attributed as "console" in the audit log,
        // and refusing it would make the force path impossible to script or test.
        Player actor = sender instanceof Player ? (Player) sender : null;
        if (actor != null) {
            Text.prefixed(sender, "&eForcing &f" + args[2] + " &eat &f" + target.getName()
                    + " &ewithout consent. This is logged.");
        } else {
            Text.prefixed(sender, "&eForcing &f" + args[2] + " &eat &f" + target.getName()
                    + " &efrom console, without consent. This is logged.");
        }
        PrankEngine.Result result = plugin.pranks().apply(actor, target, args[2], true);
        Text.prefixed(sender, plugin.pranks().explain(result, actor, target));
        if (result == PrankEngine.Result.OK) {
            Text.prefixed(target, "&7A staff member used a cosmetic effect on you. It could not have harmed you.");
        }
    }

    private void grant(CommandSender sender, String[] args, boolean allow) {
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prankconsent " + (allow ? "grant" : "revoke") + " <player> [prankster|*]");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null || target.getUniqueId() == null) {
            Text.prefixed(sender, "&cUnknown player &f" + args[1] + "&c.");
            return;
        }
        String scope = args.length >= 3 ? args[2] : "*";
        if (!allow) {
            if (scope.equals("*")) {
                plugin.consent().clear(target.getUniqueId());
                Text.prefixed(sender, "&eCleared all consent records for &f" + name(target) + "&e.");
                return;
            }
            OfflinePlayer prankster = resolve(scope);
            if (prankster != null && prankster.getUniqueId() != null) {
                plugin.consent().deny(target.getUniqueId(), prankster.getUniqueId());
            }
            Text.prefixed(sender, "&eRevoked &f" + scope + " &efor &f" + name(target) + "&e.");
            return;
        }
        if (scope.equals("*")) {
            plugin.consent().allowAll(target.getUniqueId());
        } else {
            OfflinePlayer prankster = resolve(scope);
            if (prankster == null || prankster.getUniqueId() == null) {
                Text.prefixed(sender, "&cUnknown prankster &f" + scope + "&c.");
                return;
            }
            plugin.consent().allow(target.getUniqueId(), prankster.getUniqueId());
        }
        Player online = target.getPlayer();
        Text.prefixed(sender, "&aGranted &f" + scope + " &afor &f" + name(target) + "&a.");
        if (online != null) {
            Text.prefixed(online, "&7Staff granted prank access: &f" + scope + "&7.");
        }
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prankconsent clear <player>");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null || target.getUniqueId() == null) {
            Text.prefixed(sender, "&cUnknown player &f" + args[1] + "&c.");
            return;
        }
        plugin.consent().clear(target.getUniqueId());
        Player online = target.getPlayer();
        if (online != null) {
            plugin.tnt().clearFor(online);
        }
        Text.prefixed(sender, "&eCleared consent records for &f" + name(target) + "&e.");
    }

    private void usage(CommandSender sender) {
        Text.msg(sender, "&8&m--------&r &dPrankCraft consent admin &8&m--------");
        Text.msg(sender, "&f/prankconsent info <player> &7- record for one player");
        Text.msg(sender, "&f/prankconsent grant <player> [prankster|*] &7- allow pranks");
        Text.msg(sender, "&f/prankconsent revoke <player> [prankster|*] &7- take that back");
        Text.msg(sender, "&f/prankconsent clear <player> &7- wipe and cancel their running effects");
        Text.msg(sender, "&f/prankconsent force <player> <effect> &7- fire without consent (logged, staff only)");
    }

    private static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        // Compat: Bukkit#getOfflinePlayerIfCached is Paper-only and absent on Spigot 1.16.5.
        return Compat.lookupOfflinePlayer(name);
    }

    private static String name(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? String.valueOf(player.getUniqueId()) : name;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            // Arrays.asList, not List.of: Java 9+ only, and this source is shared with the
            // legacy builds that run on Java 8 servers.
            out.addAll(Arrays.asList("info", "grant", "revoke", "clear", "force"));
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("force")) {
                for (PrankEffect effect : plugin.pranks().effects()) {
                    out.add(effect.id());
                }
            } else {
                out.add("*");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(p.getName());
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
