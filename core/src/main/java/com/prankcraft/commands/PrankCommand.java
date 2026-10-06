package com.prankcraft.commands;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.prank.PrankEngine;
import com.prankcraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /prank ...} - the player-facing command.
 *
 * <p>Anyone can run the consent half ({@code allow}, {@code deny}, {@code status}); firing an
 * effect additionally needs that effect's permission, and the target still has to have agreed.
 */
public final class PrankCommand implements CommandExecutor, TabCompleter {

    private final PrankCraftPlugin plugin;

    public PrankCommand(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender, label);
            case "list" -> list(sender, label);
            case "status" -> status(sender);
            case "allow" -> allow(sender, args);
            case "deny" -> deny(sender, args);
            case "yes" -> respond(sender, true);
            case "no" -> respond(sender, false);
            case "stop", "clear" -> stopSelf(sender);
            case "reload" -> reload(sender);
            case "random" -> random(sender, args);
            default -> fire(sender, sub, args);
        }
        return true;
    }

    // ------------------------------------------------------------------ effect firing

    private void fire(CommandSender sender, String effectId, String[] args) {
        if (!(sender instanceof Player actor)) {
            Text.msg(sender, "&cOnly players can fire pranks. Console can use /prankcraft.");
            return;
        }
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prank " + effectId + " <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.prefixed(sender, "&cNo online player named &f" + args[1] + "&c.");
            return;
        }
        PrankEngine.Result result = plugin.pranks().apply(actor, target, effectId);
        Text.prefixed(sender, plugin.pranks().explain(result, actor, target));
    }

    private void random(CommandSender sender, String[] args) {
        if (!(sender instanceof Player actor)) {
            Text.msg(sender, "&cOnly players can fire pranks.");
            return;
        }
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prank random <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.prefixed(sender, "&cNo online player named &f" + args[1] + "&c.");
            return;
        }
        PrankEffect effect = plugin.pranks().randomEffect(actor);
        if (effect == null) {
            Text.prefixed(sender, "&cYou do not have permission for any enabled prank effect.");
            return;
        }
        PrankEngine.Result result = plugin.pranks().apply(actor, target, effect.id());
        Text.prefixed(sender, plugin.pranks().explain(result, actor, target));
    }

    // ------------------------------------------------------------------ consent

    private void allow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player self)) {
            Text.msg(sender, "&cConsent is per-player; run this in game.");
            return;
        }
        if (args.length < 2) {
            Text.prefixed(self, "&7Usage: &f/prank allow <player|*> &7- allow that player (or everyone) to prank you.");
            return;
        }
        if (!self.hasPermission("prankcraft.consent.manage")) {
            Text.prefixed(self, "&cYou may not change your consent settings on this server.");
            return;
        }
        if (args[1].equals("*") || args[1].equalsIgnoreCase("all")) {
            plugin.consent().allowAll(self.getUniqueId());
            Text.prefixed(self, "&aAnyone on the server may now prank you. &7Undo with &f/prank deny *&7.");
            return;
        }
        Player other = Bukkit.getPlayerExact(args[1]);
        if (other == null) {
            Text.prefixed(self, "&cNo online player named &f" + args[1] + "&c.");
            return;
        }
        plugin.consent().allow(self.getUniqueId(), other.getUniqueId());
        Text.prefixed(self, "&aYou will now accept pranks from &f" + other.getName() + "&a.");
        Text.prefixed(other, "&aYou may now prank &f" + self.getName() + "&a.");
    }

    private void deny(CommandSender sender, String[] args) {
        if (!(sender instanceof Player self)) {
            Text.msg(sender, "&cConsent is per-player; run this in game.");
            return;
        }
        if (args.length >= 2 && (args[1].equals("*") || args[1].equalsIgnoreCase("all"))) {
            plugin.consent().clear(self.getUniqueId());
            Text.prefixed(self, "&eYou will no longer be pranked by anyone.");
            return;
        }
        if (args.length >= 2) {
            Player other = Bukkit.getPlayerExact(args[1]);
            if (other != null) {
                plugin.consent().deny(self.getUniqueId(), other.getUniqueId());
                Text.prefixed(self, "&e" + other.getName() + " may no longer prank you.");
                return;
            }
        }
        plugin.consent().setOptIn(self.getUniqueId(), false);
        Text.prefixed(self, "&ePranks are now off for you. &7Re-enable with &f/prank allow <player>&7.");
    }

    private void respond(CommandSender sender, boolean approve) {
        if (!(sender instanceof Player self)) {
            return;
        }
        int count = approve ? plugin.consent().approveAll(self.getUniqueId()) : plugin.consent().denyAll(self.getUniqueId());
        if (count == 0) {
            Text.prefixed(self, "&7You have no pending prank requests.");
            return;
        }
        Text.prefixed(self, approve
                ? "&aApproved &f" + count + "&a prank request(s)."
                : "&eDeclined &f" + count + "&e prank request(s).");
    }

    private void status(CommandSender sender) {
        if (!(sender instanceof Player self)) {
            Text.msg(sender, "&7Consent data is per-player. Use /prankcraft consent <player> to inspect one.");
            return;
        }
        boolean optedIn = plugin.consent().isOptedIn(self.getUniqueId());
        Text.prefixed(self, "&7Pranks: " + (optedIn ? "&aenabled" : "&cdisabled"));
        StringBuilder allowed = new StringBuilder();
        for (java.util.UUID id : plugin.consent().allowedFor(self.getUniqueId())) {
            if (allowed.length() > 0) {
                allowed.append("&7, &f");
            }
            allowed.append(id.equals(com.prankcraft.consent.ConsentManager.ALL)
                    ? "everyone"
                    : Bukkit.getOfflinePlayer(id).getName());
        }
        Text.prefixed(self, "&7Allowed: " + (allowed.length() == 0 ? "&7nobody" : "&f" + allowed));
        List<java.util.UUID> pending = plugin.consent().requestsFor(self.getUniqueId());
        if (!pending.isEmpty()) {
            Text.prefixed(self, "&ePending requests from: &f" + pending.size() + "&e player(s). &7Use &f/prank yes&7 or &f/prank no&7.");
        }
    }

    private void stopSelf(CommandSender sender) {
        if (sender instanceof Player self) {
            plugin.tnt().clearFor(self);
            Text.prefixed(self, "&7Cleared any fake TNT illusion you were seeing.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("prankcraft.admin")) {
            Text.prefixed(sender, "&cYou do not have permission.");
            return;
        }
        // Same path as /prankcraft reload, so both commands refresh the cached settings too -
        // previously this one reloaded the file but left stale values in the hot paths.
        plugin.reloadEverything();
        Text.prefixed(sender, "&aConfiguration and consent data reloaded.");
    }

    private void list(CommandSender sender, String label) {
        Text.msg(sender, "&8&m--------&r &dPrank effects &8&m--------");
        for (PrankEffect effect : plugin.pranks().effects()) {
            if (!plugin.pranks().enabled(effect)) {
                continue;
            }
            boolean allowed = !(sender instanceof Player p) || p.hasPermission(effect.permission());
            Text.msg(sender, (allowed ? "&a" : "&8") + " /prank " + effect.id() + " <player> &7- " + effect.description());
        }
        Text.msg(sender, "&8&m--------------------------------");
    }

    private void help(CommandSender sender, String label) {
        Text.msg(sender, "&8&m--------&r &dPrankCraft &8&m--------");
        Text.msg(sender, "&f/prank list &7- show available effects");
        Text.msg(sender, "&f/prank <effect> <player> &7- prank somebody who agreed to it");
        Text.msg(sender, "&f/prank random <player> &7- surprise them with any effect you own");
        Text.msg(sender, "&f/prank allow <player|*> &7- let them prank you");
        Text.msg(sender, "&f/prank deny [player|*] &7- opt out (any time, takes effect immediately)");
        Text.msg(sender, "&f/prank status &7- see your current settings");
        Text.msg(sender, "&f/prank stop &7- clear an illusion you are stuck in");
        Text.msg(sender, "&7Pranks here are cosmetic only. Nothing can harm you, move you, or take your items.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("list", "status", "allow", "deny", "yes", "no", "random", "stop", "help"));
            if (sender.hasPermission("prankcraft.admin")) {
                subs.add("reload");
            }
            if (sender instanceof Player p) {
                subs.addAll(plugin.pranks().usableBy(p));
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("allow")) {
                out.add("*");
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
            return filter(out, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
