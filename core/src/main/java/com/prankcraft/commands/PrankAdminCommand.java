package com.prankcraft.commands;

import com.prankcraft.PrankCraftPlugin;
import com.prankcraft.audit.AuditLog;
import com.prankcraft.prank.PrankEffect;
import com.prankcraft.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /prankcraft ...} - operator command.
 *
 * <p>Contains the manual control for the fake-TNT trick (show it, fire it, clear it) and the
 * audit viewer. The audit viewer is the important one: it is how you answer "did staff do
 * something to my account?" with facts instead of a shrug.
 */
public final class PrankAdminCommand implements CommandExecutor, org.bukkit.command.TabCompleter {

    private static final String PERMISSION = "prankcraft.admin";

    private final PrankCraftPlugin plugin;

    public PrankAdminCommand(PrankCraftPlugin plugin) {
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
            case "reload":
                reload(sender);
                break;
            case "tnt":
                tnt(sender, args);
                break;
            case "clear":
                clear(sender, args);
                break;
            case "audit":
                audit(sender, args);
                break;
            case "status":
                status(sender);
                break;
            default:
                usage(sender);
                break;
        }
        return true;
    }

    private void reload(CommandSender sender) {
        plugin.reloadEverything();
        Text.prefixed(sender, "&aReloaded config, consent data and the fake-TNT scheduler.");
    }

    private void tnt(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prankcraft tnt <show|fire|clear> <player>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3) {
            Text.prefixed(sender, "&cUsage: /prankcraft tnt " + action + " <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            Text.prefixed(sender, "&cNo online player named &f" + args[2] + "&c.");
            return;
        }

        // Multi-label cases fall through deliberately - the Java 8 equivalent of "case a, b ->".
        switch (action) {
            case "show":
            case "place": {
                int shown = plugin.tnt().fake(target, null);
                Text.prefixed(sender, shown > 0
                        ? "&aShowing &f" + shown + "&a fake TNT block(s) to &f" + target.getName() + "&a."
                        : "&cNo believable floor spots found near &f" + target.getName() + "&c.");
                break;
            }
            case "fire":
            case "boom":
            case "detonate": {
                boolean fired = plugin.tnt().detonate(target);
                Text.prefixed(sender, fired
                        ? "&aDetonated the fake TNT for &f" + target.getName() + "&a."
                        : "&cNo fake TNT is currently shown to &f" + target.getName() + "&c.");
                break;
            }
            case "clear":
            case "stop": {
                plugin.tnt().clearFor(target);
                Text.prefixed(sender, "&eCleared the fake-TNT illusion for &f" + target.getName() + "&e.");
                break;
            }
            default:
                Text.prefixed(sender, "&cUnknown action &f" + action + "&c. Use show, fire or clear.");
                break;
        }
        plugin.audit().record(senderId(sender), target.getUniqueId(), "fake-tnt:" + action,
                action + " issued from console/staff");
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.prefixed(sender, "&cUsage: /prankcraft clear <player|*>");
            return;
        }
        if (args[1].equals("*")) {
            int count = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.tnt().clearFor(p);
                count++;
            }
            Text.prefixed(sender, "&eCleared illusions for &f" + count + "&e player(s).");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.prefixed(sender, "&cNo online player named &f" + args[1] + "&c.");
            return;
        }
        plugin.tnt().clearFor(target);
        Text.prefixed(sender, "&eCleared illusions for &f" + target.getName() + "&e.");
    }

    private void audit(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Math.min(100, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                limit = 10;
            }
        }
        List<AuditLog.Entry> entries = plugin.audit().recent(limit);
        if (entries.isEmpty()) {
            Text.prefixed(sender, "&7No prank activity recorded yet.");
            return;
        }
        Text.msg(sender, "&8&m--------&r &dRecent prank activity &8&m--------");
        for (AuditLog.Entry entry : entries) {
            Text.msg(sender, "&8- " + entry.pretty());
        }
        Text.msg(sender, "&7Full history: &fplugins/PrankCraft/audit-*.log");
    }

    private void status(CommandSender sender) {
        Text.prefixed(sender, "&7Version: &f" + plugin.getDescription().getVersion());
        // Read from the cached snapshot, like every other hot path, so this cannot disagree with
        // what the engine is actually enforcing.
        Text.prefixed(sender, "&7Consent gate: &f" + plugin.config().requireConsent
                + " &7(per-target: " + plugin.config().requirePerTargetConsent + ")");
        Text.prefixed(sender, "&7Audit: &f" + plugin.config().auditEnabled);
        Text.prefixed(sender, "&7Active fake-TNT sessions: &f" + plugin.tnt().activeSessions());

        int total = 0;
        int enabled = 0;
        for (PrankEffect effect : plugin.pranks().effects()) {
            total++;
            if (plugin.pranks().enabled(effect)) {
                enabled++;
            }
        }
        Text.prefixed(sender, "&7Effects loaded: &f" + total + " &7enabled: &f" + enabled);
    }

    private void usage(CommandSender sender) {
        Text.msg(sender, "&8&m--------&r &dPrankCraft admin &8&m--------");
        Text.msg(sender, "&f/prankcraft status &7- version, gates, counters");
        Text.msg(sender, "&f/prankcraft tnt <show|fire|clear> <player> &7- manual fake-TNT control");
        Text.msg(sender, "&f/prankcraft clear <player|*> &7- drop every running illusion");
        Text.msg(sender, "&f/prankcraft audit [count] &7- recent prank activity");
        Text.msg(sender, "&f/prankcraft reload &7- reload config and consent data");
    }

    private static java.util.UUID senderId(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
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
            out.addAll(Arrays.asList("status", "tnt", "clear", "audit", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tnt")) {
            out.addAll(Arrays.asList("show", "fire", "clear"));
        } else if ((args.length == 3 && args[0].equalsIgnoreCase("tnt")) || (args.length == 2 && args[0].equalsIgnoreCase("clear"))) {
            out.add("*");
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
