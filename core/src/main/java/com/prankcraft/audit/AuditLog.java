package com.prankcraft.audit;

import com.prankcraft.PrankCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Append-only record of every effect that was fired, who fired it, and at whom.
 *
 * <p>This exists to protect players <em>and</em> the operator: if someone complains that
 * "the server faked my death message", there is a timestamped answer. Kept in memory for
 * the most recent {@value #MEMORY_LIMIT} entries so {@code /prank log} can show them, and
 * mirrored to {@code plugins/PrankCraft/audit-YYYY-MM-DD.log}.
 */
public final class AuditLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MEMORY_LIMIT = 200;

    /** One recorded action. */
    public record Entry(long millis, String actorName, UUID actorId, String targetName, UUID targetId,
                        String effect, String detail) {
        public String line() {
            return LocalDateTime.now().format(STAMP)
                    + " | actor=" + actorName + "(" + actorId + ")"
                    + " | target=" + targetName + "(" + targetId + ")"
                    + " | effect=" + effect
                    + " | " + detail;
        }

        public String pretty() {
            return "&7" + LocalDateTime.now().format(STAMP)
                    + " &f" + actorName + " &7-> &f" + targetName
                    + " &8[&d" + effect + "&8] &7" + detail;
        }
    }

    private final PrankCraftPlugin plugin;
    private final Deque<Entry> recent = new ArrayDeque<>();

    public AuditLog(PrankCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void record(UUID actorId, UUID targetId, String effect, String detail) {
        if (!plugin.config().auditEnabled) {
            return;
        }
        Entry entry = new Entry(System.currentTimeMillis(), nameOf(actorId), actorId,
                nameOf(targetId), targetId, effect, detail == null ? "" : detail);

        synchronized (recent) {
            recent.addLast(entry);
            while (recent.size() > MEMORY_LIMIT) {
                recent.removeFirst();
            }
        }

        if (plugin.config().auditConsole) {
            plugin.getLogger().info("[audit] " + entry.actorName() + " -> " + entry.targetName()
                    + " [" + effect + "] " + entry.detail());
        }
        if (plugin.config().auditFile) {
            appendToFile(entry);
        }
    }

    public List<Entry> recent(int limit) {
        synchronized (recent) {
            List<Entry> all = new ArrayList<>(recent);
            int from = Math.max(0, all.size() - Math.max(1, limit));
            List<Entry> tail = new ArrayList<>(all.subList(from, all.size()));
            java.util.Collections.reverse(tail);
            return tail;
        }
    }

    private void appendToFile(Entry entry) {
        Path path = plugin.getDataFolder().toPath().resolve("audit-" + LocalDateTime.now().format(DAY) + ".log");
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(entry.line());
                writer.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write audit log: " + e.getMessage());
        }
    }

    private static String nameOf(UUID id) {
        if (id == null) {
            return "console";
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String cached = Bukkit.getOfflinePlayer(id).getName();
        return cached == null ? id.toString().substring(0, 8) : cached;
    }

    public static String describe(CommandSender sender) {
        if (sender instanceof Player p) {
            return p.getName();
        }
        return "console";
    }
}
